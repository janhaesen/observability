package sidecarclient

import "time"

// Level matches the sidecar OpenAPI event level enum.
type Level string

const (
	LevelTrace Level = "TRACE"
	LevelDebug Level = "DEBUG"
	LevelInfo  Level = "INFO"
	LevelWarn  Level = "WARN"
	LevelError Level = "ERROR"
)

// Event matches the sidecar OpenAPI event submission schema.
type Event struct {
	ClientEventID string
	Name          string
	Level         Level
	Timestamp     time.Time
	Message       *string
	Context       map[string]any
	Payload       []byte
	Error         *RemoteError
}

// RemoteError matches the sidecar OpenAPI remote error schema.
type RemoteError struct {
	Type       string
	Message    *string
	Stacktrace string
}

// Problem mirrors RFC 7807 responses returned by the sidecar.
type Problem struct {
	Type   string `json:"type"`
	Title  string `json:"title"`
	Status int    `json:"status"`
	Detail string `json:"detail,omitempty"`
}

// SubmissionReceipt matches the OpenAPI 202 response schema.
type SubmissionReceipt struct {
	SubmissionID  string `json:"submissionId"`
	AcceptedCount int    `json:"acceptedCount"`
}

// SubmissionResult returns the accepted sidecar receipt and the normalized events.
type SubmissionResult struct {
	Receipt SubmissionReceipt
	Events  []Event
}

// QueuedSubmission reports the normalized events and resulting outbox status.
type QueuedSubmission struct {
	Events []Event
	Status OutboxStatus
}

// OutboxStatus summarizes the local outbox and DLQ state.
type OutboxStatus struct {
	PendingSubmissions    int
	PendingEvents         int
	PendingBytes          int64
	DeadLetterSubmissions int
	DeadLetterEvents      int
}

// FlushResult reports what a flush delivered or moved to the DLQ.
type FlushResult struct {
	DeliveredSubmissions    int
	DeliveredEvents         int
	DeadLetteredSubmissions int
	DeadLetteredEvents      int
	Status                  OutboxStatus
}
