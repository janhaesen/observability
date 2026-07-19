package sidecarclient

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestClientSubmitGeneratesClientEventIDAndUsesTokenProvider(t *testing.T) {
	t.Parallel()

	var tokenCalls atomic.Int32
	var seenAuthorization string
	var seenPath string
	var seenPayload wireEvent
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenAuthorization = r.Header.Get("Authorization")
		seenPath = r.URL.Path
		defer r.Body.Close()
		if err := json.NewDecoder(r.Body).Decode(&seenPayload); err != nil {
			t.Fatalf("decode request: %v", err)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"submissionId":"11111111-1111-4111-8111-111111111111","acceptedCount":1}`))
	}))
	defer server.Close()

	client, err := NewClient(Config{
		BaseURL: server.URL,
		TokenProvider: func(context.Context) (string, error) {
			tokenCalls.Add(1)
			return "secret-token", nil
		},
	})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}

	message := "hello"
	result, err := client.Submit(context.Background(), Event{
		Name:      "app.started",
		Level:     LevelInfo,
		Timestamp: time.Date(2026, 7, 18, 20, 30, 0, 0, time.UTC),
		Message:   &message,
		Context: map[string]any{
			"attempt": 1,
			"healthy": true,
		},
	})
	if err != nil {
		t.Fatalf("Submit() error = %v", err)
	}

	if tokenCalls.Load() != 1 {
		t.Fatalf("token provider calls = %d, want 1", tokenCalls.Load())
	}
	if seenAuthorization != "Bearer secret-token" {
		t.Fatalf("Authorization = %q, want Bearer token", seenAuthorization)
	}
	if seenPath != singleSubmissionPath {
		t.Fatalf("path = %q, want %q", seenPath, singleSubmissionPath)
	}
	if !isUUID(result.Events[0].ClientEventID) {
		t.Fatalf("generated client event id %q is not a UUID", result.Events[0].ClientEventID)
	}
	if seenPayload.ClientEventID != result.Events[0].ClientEventID {
		t.Fatalf("request clientEventId = %q, want %q", seenPayload.ClientEventID, result.Events[0].ClientEventID)
	}
	if seenPayload.Message == nil || *seenPayload.Message != message {
		t.Fatalf("message = %#v, want %q", seenPayload.Message, message)
	}
	if result.Receipt.AcceptedCount != 1 {
		t.Fatalf("acceptedCount = %d, want 1", result.Receipt.AcceptedCount)
	}
}

func TestClientSubmitBatchUsesBatchEndpointAndFixedToken(t *testing.T) {
	t.Parallel()

	var seenAuthorization string
	var seenPayload wireBatch
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenAuthorization = r.Header.Get("Authorization")
		defer r.Body.Close()
		if err := json.NewDecoder(r.Body).Decode(&seenPayload); err != nil {
			t.Fatalf("decode batch request: %v", err)
		}
		if r.URL.Path != batchSubmissionPath {
			t.Fatalf("path = %q, want %q", r.URL.Path, batchSubmissionPath)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"submissionId":"22222222-2222-4222-8222-222222222222","acceptedCount":2}`))
	}))
	defer server.Close()

	client, err := NewClient(Config{BaseURL: server.URL, TokenProvider: FixedToken("fixed-token")})
	if err != nil {
		t.Fatalf("NewClient() error = %v", err)
	}

	explicitID := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	remoteMessage := "boom"
	result, err := client.SubmitBatch(context.Background(), []Event{
		{
			ClientEventID: explicitID,
			Name:          "app.started",
			Level:         LevelInfo,
			Timestamp:     time.Date(2026, 7, 18, 20, 31, 0, 0, time.UTC),
			Context:       map[string]any{"host": "api-1"},
			Payload:       []byte("payload"),
		},
		{
			Name:      "app.failed",
			Level:     LevelError,
			Timestamp: time.Date(2026, 7, 18, 20, 32, 0, 0, time.UTC),
			Context:   map[string]any{"retry": false},
			Error: &RemoteError{
				Type:       "java.lang.IllegalStateException",
				Message:    &remoteMessage,
				Stacktrace: "stack",
			},
		},
	})
	if err != nil {
		t.Fatalf("SubmitBatch() error = %v", err)
	}

	if seenAuthorization != "Bearer fixed-token" {
		t.Fatalf("Authorization = %q, want fixed token", seenAuthorization)
	}
	if len(seenPayload.Events) != 2 {
		t.Fatalf("encoded events = %d, want 2", len(seenPayload.Events))
	}
	if seenPayload.Events[0].ClientEventID != explicitID {
		t.Fatalf("explicit clientEventId = %q, want %q", seenPayload.Events[0].ClientEventID, explicitID)
	}
	if got := base64.StdEncoding.EncodeToString(seenPayload.Events[0].PayloadBase64); got != "cGF5bG9hZA==" {
		t.Fatalf("payloadBase64 = %q, want cGF5bG9hZA==", got)
	}
	if seenPayload.Events[1].Error == nil || seenPayload.Events[1].Error.Type != "java.lang.IllegalStateException" {
		t.Fatalf("error payload = %#v, want remote error", seenPayload.Events[1].Error)
	}
	if result.Receipt.AcceptedCount != 2 {
		t.Fatalf("acceptedCount = %d, want 2", result.Receipt.AcceptedCount)
	}
	if len(result.Events) != 2 || !isUUID(result.Events[1].ClientEventID) {
		t.Fatalf("normalized events = %#v, want generated second client event id", result.Events)
	}
}
