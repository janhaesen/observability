package sidecarclient

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestOutboxRecoversQueuedEventsAcrossReopen(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"submissionId":"33333333-3333-4333-8333-333333333333","acceptedCount":1}`))
	}))
	defer server.Close()

	dir := testDir(t)
	client, err := NewOutboxClient(OutboxClientConfig{
		Client: Config{BaseURL: server.URL},
		Outbox: OutboxConfig{Directory: dir, CapacityBytes: 64 << 10},
	})
	if err != nil {
		t.Fatalf("NewOutboxClient() error = %v", err)
	}

	queued, err := client.Submit(context.Background(), sampleEvent("queued.once"))
	if err != nil {
		t.Fatalf("Submit() error = %v", err)
	}
	if queued.Status.PendingSubmissions != 1 {
		t.Fatalf("pending submissions = %d, want 1", queued.Status.PendingSubmissions)
	}
	if err := client.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}

	reopened, err := NewOutboxClient(OutboxClientConfig{
		Client: Config{BaseURL: server.URL},
		Outbox: OutboxConfig{Directory: dir, CapacityBytes: 64 << 10},
	})
	if err != nil {
		t.Fatalf("reopen outbox: %v", err)
	}
	defer reopened.Close()

	status, err := reopened.Status()
	if err != nil {
		t.Fatalf("Status() error = %v", err)
	}
	if status.PendingSubmissions != 1 || status.PendingEvents != 1 {
		t.Fatalf("status = %#v, want one pending submission/event", status)
	}

	result, err := reopened.Flush(context.Background())
	if err != nil {
		t.Fatalf("Flush() error = %v", err)
	}
	if result.DeliveredSubmissions != 1 || result.DeliveredEvents != 1 {
		t.Fatalf("flush result = %#v, want one delivered submission/event", result)
	}
	if result.Status.PendingSubmissions != 0 || result.Status.PendingEvents != 0 {
		t.Fatalf("post-flush status = %#v, want empty queue", result.Status)
	}
}

func TestOutboxMovesPermanent4xxToDLQAndContinues(t *testing.T) {
	t.Parallel()

	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		call := calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		if call == 1 {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"type":"https://example.invalid/problem","title":"invalid-request","status":400,"detail":"bad payload"}`))
			return
		}
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"submissionId":"44444444-4444-4444-8444-444444444444","acceptedCount":1}`))
	}))
	defer server.Close()

	dir := testDir(t)
	client, err := NewOutboxClient(OutboxClientConfig{
		Client: Config{BaseURL: server.URL},
		Outbox: OutboxConfig{Directory: dir, CapacityBytes: 64 << 10},
	})
	if err != nil {
		t.Fatalf("NewOutboxClient() error = %v", err)
	}
	defer client.Close()

	if _, err := client.Submit(context.Background(), sampleEvent("drop.first")); err != nil {
		t.Fatalf("Submit(first) error = %v", err)
	}
	if _, err := client.Submit(context.Background(), sampleEvent("keep.second")); err != nil {
		t.Fatalf("Submit(second) error = %v", err)
	}

	result, err := client.Flush(context.Background())
	if err != nil {
		t.Fatalf("Flush() error = %v", err)
	}
	if result.DeadLetteredSubmissions != 1 || result.DeliveredSubmissions != 1 {
		t.Fatalf("flush result = %#v, want one DLQ and one delivered submission", result)
	}
	if result.Status.PendingSubmissions != 0 {
		t.Fatalf("pending submissions after flush = %d, want 0", result.Status.PendingSubmissions)
	}
	if result.Status.DeadLetterSubmissions != 1 || result.Status.DeadLetterEvents != 1 {
		t.Fatalf("DLQ status = %#v, want one dead-lettered submission/event", result.Status)
	}
	assertDLQContainsOneRecord(t, filepath.Join(dir, dlqFileName))
}

func TestOutboxRetriesTransientFailuresWithoutDropping(t *testing.T) {
	t.Parallel()

	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		call := calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		if call == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"type":"https://example.invalid/problem","title":"unavailable","status":503,"detail":"retry later"}`))
			return
		}
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"submissionId":"55555555-5555-4555-8555-555555555555","acceptedCount":1}`))
	}))
	defer server.Close()

	client, err := NewOutboxClient(OutboxClientConfig{
		Client: Config{BaseURL: server.URL},
		Outbox: OutboxConfig{Directory: testDir(t), CapacityBytes: 64 << 10},
	})
	if err != nil {
		t.Fatalf("NewOutboxClient() error = %v", err)
	}
	defer client.Close()

	if _, err := client.Submit(context.Background(), sampleEvent("retry.me")); err != nil {
		t.Fatalf("Submit() error = %v", err)
	}

	firstResult, err := client.Flush(context.Background())
	if err == nil {
		t.Fatal("first Flush() error = nil, want transient failure")
	}
	var httpErr *HTTPError
	if !errors.As(err, &httpErr) || httpErr.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("first Flush() error = %v, want HTTP 503", err)
	}
	if firstResult.Status.PendingSubmissions != 1 || firstResult.Status.PendingEvents != 1 {
		t.Fatalf("first flush status = %#v, want one pending submission/event", firstResult.Status)
	}

	secondResult, err := client.Flush(context.Background())
	if err != nil {
		t.Fatalf("second Flush() error = %v", err)
	}
	if secondResult.DeliveredSubmissions != 1 || secondResult.Status.PendingSubmissions != 0 {
		t.Fatalf("second flush result = %#v, want drained queue", secondResult)
	}
}

func TestOutboxCapacityErrorsExplicitly(t *testing.T) {
	t.Parallel()

	dir := testDir(t)
	client, err := NewOutboxClient(OutboxClientConfig{
		Client: Config{BaseURL: "http://127.0.0.1:1"},
		Outbox: OutboxConfig{Directory: dir, CapacityBytes: 1},
	})
	if err != nil {
		t.Fatalf("NewOutboxClient() error = %v", err)
	}
	defer client.Close()

	_, err = client.Submit(context.Background(), sampleEvent("too.big"))
	if err == nil {
		t.Fatal("Submit() error = nil, want OutboxFullError")
	}
	var fullErr *OutboxFullError
	if !errors.As(err, &fullErr) {
		t.Fatalf("Submit() error = %v, want OutboxFullError", err)
	}
	status, statusErr := client.Status()
	if statusErr != nil {
		t.Fatalf("Status() error = %v", statusErr)
	}
	if status.PendingSubmissions != 0 || status.PendingBytes != 0 {
		t.Fatalf("status after rejected submit = %#v, want empty queue", status)
	}
}

func sampleEvent(name string) Event {
	message := "queued"
	return Event{
		Name:      name,
		Level:     LevelInfo,
		Timestamp: time.Date(2026, 7, 18, 20, 30, 0, 0, time.UTC),
		Message:   &message,
		Context:   map[string]any{"attempt": 1},
	}
}

func testDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp(".", ".client-go-test-")
	if err != nil {
		t.Fatalf("MkdirTemp() error = %v", err)
	}
	t.Cleanup(func() {
		_ = os.RemoveAll(dir)
	})
	return dir
}

func assertDLQContainsOneRecord(t *testing.T, path string) {
	t.Helper()
	file, err := os.Open(path)
	if err != nil {
		t.Fatalf("Open(%q) error = %v", path, err)
	}
	defer file.Close()

	count := 0
	_, err = scanAnyRecord(file, 0, func(_ int64, _ int64, payload []byte) error {
		var record dlqRecord
		if err := json.Unmarshal(payload, &record); err != nil {
			return err
		}
		count++
		if record.StatusCode != http.StatusBadRequest {
			t.Fatalf("record statusCode = %d, want 400", record.StatusCode)
		}
		if len(record.Events) != 1 {
			t.Fatalf("record events = %d, want 1", len(record.Events))
		}
		return nil
	})
	if err != nil {
		t.Fatalf("scanAnyRecord() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("DLQ record count = %d, want 1", count)
	}
}
