package sidecarclient

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	defaultCompactThresholdBytes int64 = 1 << 20
	journalHeaderBytes                 = 8
	journalFileName                    = "outbox.journal"
	stateFileName                      = "outbox.state.json"
	dlqFileName                        = "outbox.dlq"
)

// OutboxConfig configures the optional single-process append-only file outbox.
type OutboxConfig struct {
	Directory             string
	CapacityBytes         int64
	CompactThresholdBytes int64
}

// OutboxClientConfig combines HTTP and outbox configuration.
type OutboxClientConfig struct {
	Client Config
	Outbox OutboxConfig
}

// OutboxClient appends submissions locally, then flushes them to the sidecar later.
type OutboxClient struct {
	client *Client
	store  *fileOutbox
}

// NewOutboxClient creates an outbox-backed sidecar client.
func NewOutboxClient(cfg OutboxClientConfig) (*OutboxClient, error) {
	client, err := NewClient(cfg.Client)
	if err != nil {
		return nil, err
	}
	store, err := newFileOutbox(cfg.Outbox)
	if err != nil {
		return nil, err
	}
	return &OutboxClient{client: client, store: store}, nil
}

// Submit appends one event to the durable local outbox.
func (c *OutboxClient) Submit(ctx context.Context, event Event) (QueuedSubmission, error) {
	normalized, preparedWireEvent, err := prepareEvent(event)
	if err != nil {
		return QueuedSubmission{}, err
	}
	status, err := c.store.enqueue(ctx, journalRecord{Version: 1, Events: []wireEvent{preparedWireEvent}})
	if err != nil {
		return QueuedSubmission{}, err
	}
	return QueuedSubmission{Events: []Event{normalized}, Status: status}, nil
}

// SubmitBatch appends a batch to the durable local outbox.
func (c *OutboxClient) SubmitBatch(ctx context.Context, events []Event) (QueuedSubmission, error) {
	normalized, wireEvents, err := prepareEvents(events)
	if err != nil {
		return QueuedSubmission{}, err
	}
	status, err := c.store.enqueue(ctx, journalRecord{Version: 1, Events: wireEvents})
	if err != nil {
		return QueuedSubmission{}, err
	}
	return QueuedSubmission{Events: normalized, Status: status}, nil
}

// Status reports local queue and DLQ counts.
func (c *OutboxClient) Status() (OutboxStatus, error) {
	return c.store.status()
}

// Flush replays pending submissions in order until drained or a transient failure stops progress.
func (c *OutboxClient) Flush(ctx context.Context) (FlushResult, error) {
	return c.store.flush(ctx, c.client)
}

// Close releases local outbox file handles.
func (c *OutboxClient) Close() error {
	if c == nil || c.store == nil {
		return nil
	}
	return c.store.close()
}

type fileOutbox struct {
	directory             string
	journalPath           string
	statePath             string
	dlqPath               string
	capacityBytes         int64
	compactThresholdBytes int64

	mu        sync.Mutex
	journal   *os.File
	ackOffset int64
}

type outboxState struct {
	AckOffset int64 `json:"ackOffset"`
}

type journalRecord struct {
	Version int         `json:"version"`
	Events  []wireEvent `json:"events"`
}

type dlqRecord struct {
	Version    int         `json:"version"`
	FailedAt   string      `json:"failedAt"`
	StatusCode int         `json:"statusCode"`
	Problem    *Problem    `json:"problem,omitempty"`
	Body       string      `json:"body,omitempty"`
	Events     []wireEvent `json:"events"`
}

type scannedRecord struct {
	Offset int64
	Next   int64
	Record journalRecord
}

func newFileOutbox(cfg OutboxConfig) (*fileOutbox, error) {
	if cfg.CapacityBytes <= 0 {
		return nil, fmt.Errorf("outbox capacityBytes must be greater than 0")
	}
	if cfg.Directory == "" {
		return nil, fmt.Errorf("outbox directory is required")
	}
	compactThreshold := cfg.CompactThresholdBytes
	if compactThreshold <= 0 {
		compactThreshold = minInt64(defaultCompactThresholdBytes, cfg.CapacityBytes)
	}
	if err := os.MkdirAll(cfg.Directory, 0o755); err != nil {
		return nil, fmt.Errorf("create outbox directory: %w", err)
	}
	journalPath := filepath.Join(cfg.Directory, journalFileName)
	journal, err := os.OpenFile(journalPath, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0o644)
	if err != nil {
		return nil, fmt.Errorf("open outbox journal: %w", err)
	}
	outbox := &fileOutbox{
		directory:             cfg.Directory,
		journalPath:           journalPath,
		statePath:             filepath.Join(cfg.Directory, stateFileName),
		dlqPath:               filepath.Join(cfg.Directory, dlqFileName),
		capacityBytes:         cfg.CapacityBytes,
		compactThresholdBytes: compactThreshold,
		journal:               journal,
	}
	if err := outbox.recoverLocked(); err != nil {
		_ = journal.Close()
		return nil, err
	}
	return outbox, nil
}

func (o *fileOutbox) close() error {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.journal == nil {
		return nil
	}
	err := o.journal.Close()
	o.journal = nil
	return err
}

func (o *fileOutbox) enqueue(_ context.Context, record journalRecord) (OutboxStatus, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if err := o.maybeCompactLocked(false); err != nil {
		return OutboxStatus{}, err
	}
	encoded, err := encodeRecord(record)
	if err != nil {
		return OutboxStatus{}, err
	}
	journalSize, err := o.sizeLocked()
	if err != nil {
		return OutboxStatus{}, err
	}
	used := journalSize - o.ackOffset
	if used+int64(len(encoded)) > o.capacityBytes {
		if err := o.maybeCompactLocked(true); err != nil {
			return OutboxStatus{}, err
		}
		journalSize, err = o.sizeLocked()
		if err != nil {
			return OutboxStatus{}, err
		}
		used = journalSize - o.ackOffset
		if used+int64(len(encoded)) > o.capacityBytes {
			return OutboxStatus{}, &OutboxFullError{
				CapacityBytes:  o.capacityBytes,
				UsedBytes:      used,
				AttemptedBytes: int64(len(encoded)),
			}
		}
	}
	if _, err := o.journal.Write(encoded); err != nil {
		return OutboxStatus{}, fmt.Errorf("append outbox journal: %w", err)
	}
	if err := o.journal.Sync(); err != nil {
		return OutboxStatus{}, fmt.Errorf("sync outbox journal: %w", err)
	}
	return o.statusLocked()
}

func (o *fileOutbox) status() (OutboxStatus, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.statusLocked()
}

func (o *fileOutbox) flush(ctx context.Context, client *Client) (FlushResult, error) {
	o.mu.Lock()
	defer o.mu.Unlock()

	var result FlushResult
	for {
		if err := ctx.Err(); err != nil {
			status, statusErr := o.statusLocked()
			if statusErr == nil {
				result.Status = status
			}
			if statusErr != nil {
				return result, errors.Join(err, statusErr)
			}
			return result, err
		}
		entry, err := o.nextPendingLocked()
		if err != nil {
			return result, err
		}
		if entry == nil {
			break
		}
		_, submitErr := client.submitPrepared(ctx, entry.Record.Events)
		if submitErr == nil {
			if err := o.ackLocked(entry.Next); err != nil {
				return result, err
			}
			result.DeliveredSubmissions++
			result.DeliveredEvents += len(entry.Record.Events)
			continue
		}
		httpErr := new(HTTPError)
		if errors.As(submitErr, &httpErr) && isPermanentOutboxFailure(httpErr.StatusCode) {
			if err := o.appendDLQLocked(entry.Record, httpErr); err != nil {
				return result, err
			}
			if err := o.ackLocked(entry.Next); err != nil {
				return result, err
			}
			result.DeadLetteredSubmissions++
			result.DeadLetteredEvents += len(entry.Record.Events)
			continue
		}
		status, statusErr := o.statusLocked()
		if statusErr == nil {
			result.Status = status
		}
		if statusErr != nil {
			return result, errors.Join(submitErr, statusErr)
		}
		return result, submitErr
	}
	status, err := o.statusLocked()
	if err != nil {
		return result, err
	}
	result.Status = status
	return result, nil
}

func (o *fileOutbox) recoverLocked() error {
	truncatedSize, err := scanJournal(o.journal, 0, nil)
	if err != nil {
		return err
	}
	if err := o.journal.Truncate(truncatedSize); err != nil {
		return fmt.Errorf("truncate recovered journal: %w", err)
	}
	ackOffset, err := loadAckOffset(o.statePath)
	if err != nil {
		return err
	}
	if ackOffset < 0 || ackOffset > truncatedSize {
		ackOffset = 0
	}
	o.ackOffset = ackOffset
	return o.persistAckOffsetLocked()
}

func (o *fileOutbox) nextPendingLocked() (*scannedRecord, error) {
	var entry *scannedRecord
	_, err := scanJournal(o.journal, o.ackOffset, func(offset, next int64, record journalRecord) error {
		entry = &scannedRecord{Offset: offset, Next: next, Record: record}
		return io.EOF
	})
	if err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}
	return entry, nil
}

func (o *fileOutbox) statusLocked() (OutboxStatus, error) {
	status := OutboxStatus{}
	size, err := o.sizeLocked()
	if err != nil {
		return status, err
	}
	status.PendingBytes = maxInt64(size-o.ackOffset, 0)
	if _, err := scanJournal(o.journal, o.ackOffset, func(_, _ int64, record journalRecord) error {
		status.PendingSubmissions++
		status.PendingEvents += len(record.Events)
		return nil
	}); err != nil {
		return status, err
	}
	dlqFile, err := os.OpenFile(o.dlqPath, os.O_CREATE|os.O_RDONLY, 0o644)
	if err != nil {
		return status, fmt.Errorf("open dlq journal: %w", err)
	}
	defer dlqFile.Close()
	if _, err := scanAnyRecord(dlqFile, 0, func(_ int64, _ int64, payload []byte) error {
		var record dlqRecord
		if err := json.Unmarshal(payload, &record); err != nil {
			return fmt.Errorf("decode dlq record: %w", err)
		}
		status.DeadLetterSubmissions++
		status.DeadLetterEvents += len(record.Events)
		return nil
	}); err != nil {
		return status, err
	}
	return status, nil
}

func (o *fileOutbox) ackLocked(nextOffset int64) error {
	o.ackOffset = nextOffset
	if err := o.persistAckOffsetLocked(); err != nil {
		return err
	}
	return o.maybeCompactLocked(false)
}

func (o *fileOutbox) persistAckOffsetLocked() error {
	state := outboxState{AckOffset: o.ackOffset}
	payload, err := json.Marshal(state)
	if err != nil {
		return fmt.Errorf("marshal outbox state: %w", err)
	}
	pendingPath := o.statePath + ".next"
	if err := os.WriteFile(pendingPath, payload, 0o644); err != nil {
		return fmt.Errorf("write outbox state: %w", err)
	}
	stateFile, err := os.OpenFile(pendingPath, os.O_RDWR, 0o644)
	if err != nil {
		return fmt.Errorf("open outbox state: %w", err)
	}
	if err := stateFile.Sync(); err != nil {
		stateFile.Close()
		return fmt.Errorf("sync outbox state: %w", err)
	}
	if err := stateFile.Close(); err != nil {
		return fmt.Errorf("close outbox state: %w", err)
	}
	if err := os.Rename(pendingPath, o.statePath); err != nil {
		return fmt.Errorf("replace outbox state: %w", err)
	}
	return nil
}

func (o *fileOutbox) appendDLQLocked(record journalRecord, httpErr *HTTPError) error {
	dlq, err := os.OpenFile(o.dlqPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return fmt.Errorf("open dlq journal: %w", err)
	}
	defer dlq.Close()
	encoded, err := encodeRecord(dlqRecord{
		Version:    1,
		FailedAt:   time.Now().UTC().Format(time.RFC3339Nano),
		StatusCode: httpErr.StatusCode,
		Problem:    httpErr.Problem,
		Body:       httpErr.Body,
		Events:     record.Events,
	})
	if err != nil {
		return err
	}
	if _, err := dlq.Write(encoded); err != nil {
		return fmt.Errorf("append dlq journal: %w", err)
	}
	if err := dlq.Sync(); err != nil {
		return fmt.Errorf("sync dlq journal: %w", err)
	}
	return nil
}

func (o *fileOutbox) maybeCompactLocked(force bool) error {
	if o.ackOffset == 0 {
		return nil
	}
	size, err := o.sizeLocked()
	if err != nil {
		return err
	}
	if !force && o.ackOffset < o.compactThresholdBytes && o.ackOffset != size {
		return nil
	}
	pendingBytes := maxInt64(size-o.ackOffset, 0)
	pending := make([]byte, pendingBytes)
	if pendingBytes > 0 {
		if _, err := o.journal.ReadAt(pending, o.ackOffset); err != nil && !errors.Is(err, io.EOF) {
			return fmt.Errorf("read pending journal bytes: %w", err)
		}
	}
	compactPath := o.journalPath + ".compact"
	compactFile, err := os.OpenFile(compactPath, os.O_CREATE|os.O_RDWR|os.O_TRUNC, 0o644)
	if err != nil {
		return fmt.Errorf("open compact journal: %w", err)
	}
	if len(pending) > 0 {
		if _, err := compactFile.Write(pending); err != nil {
			compactFile.Close()
			return fmt.Errorf("write compact journal: %w", err)
		}
	}
	if err := compactFile.Sync(); err != nil {
		compactFile.Close()
		return fmt.Errorf("sync compact journal: %w", err)
	}
	if err := compactFile.Close(); err != nil {
		return fmt.Errorf("close compact journal: %w", err)
	}
	if err := o.journal.Close(); err != nil {
		return fmt.Errorf("close journal for compaction: %w", err)
	}
	if err := os.Rename(compactPath, o.journalPath); err != nil {
		return fmt.Errorf("replace compact journal: %w", err)
	}
	reopened, err := os.OpenFile(o.journalPath, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0o644)
	if err != nil {
		return fmt.Errorf("reopen compacted journal: %w", err)
	}
	o.journal = reopened
	o.ackOffset = 0
	return o.persistAckOffsetLocked()
}

func (o *fileOutbox) sizeLocked() (int64, error) {
	info, err := o.journal.Stat()
	if err != nil {
		return 0, fmt.Errorf("stat outbox journal: %w", err)
	}
	return info.Size(), nil
}

func loadAckOffset(path string) (int64, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, fmt.Errorf("read outbox state: %w", err)
	}
	if len(payload) == 0 {
		return 0, nil
	}
	var state outboxState
	if err := json.Unmarshal(payload, &state); err != nil {
		return 0, fmt.Errorf("decode outbox state: %w", err)
	}
	return state.AckOffset, nil
}

func scanJournal(file *os.File, offset int64, visitor func(offset, next int64, record journalRecord) error) (int64, error) {
	return scanAnyRecord(file, offset, func(recordOffset, next int64, payload []byte) error {
		if visitor == nil {
			return nil
		}
		var record journalRecord
		if err := json.Unmarshal(payload, &record); err != nil {
			return fmt.Errorf("decode journal record at %d: %w", recordOffset, err)
		}
		return visitor(recordOffset, next, record)
	})
}

func scanAnyRecord(file *os.File, offset int64, visitor func(offset, next int64, payload []byte) error) (int64, error) {
	info, err := file.Stat()
	if err != nil {
		return 0, fmt.Errorf("stat journal: %w", err)
	}
	size := info.Size()
	position := offset
	var header [journalHeaderBytes]byte
	for position < size {
		if size-position < journalHeaderBytes {
			return position, nil
		}
		if _, err := file.ReadAt(header[:], position); err != nil {
			return position, fmt.Errorf("read journal header at %d: %w", position, err)
		}
		payloadSize := int64(binary.BigEndian.Uint64(header[:]))
		next := position + journalHeaderBytes + payloadSize
		if payloadSize < 0 || next < position {
			return position, fmt.Errorf("invalid journal record size at %d", position)
		}
		if next > size {
			return position, nil
		}
		payload := make([]byte, payloadSize)
		if _, err := file.ReadAt(payload, position+journalHeaderBytes); err != nil {
			return position, fmt.Errorf("read journal payload at %d: %w", position, err)
		}
		if visitor != nil {
			if err := visitor(position, next, payload); err != nil {
				return next, err
			}
		}
		position = next
	}
	return position, nil
}

func encodeRecord(value any) ([]byte, error) {
	payload, err := json.Marshal(value)
	if err != nil {
		return nil, fmt.Errorf("marshal journal record: %w", err)
	}
	encoded := make([]byte, journalHeaderBytes+len(payload))
	binary.BigEndian.PutUint64(encoded[:journalHeaderBytes], uint64(len(payload)))
	copy(encoded[journalHeaderBytes:], payload)
	return encoded, nil
}

func isPermanentOutboxFailure(statusCode int) bool {
	if statusCode < http.StatusBadRequest || statusCode >= http.StatusInternalServerError {
		return false
	}
	switch statusCode {
	case http.StatusUnauthorized, http.StatusRequestTimeout, http.StatusConflict, http.StatusTooManyRequests:
		return false
	default:
		return true
	}
}

func minInt64(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

func maxInt64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
