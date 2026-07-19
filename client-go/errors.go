package sidecarclient

import "fmt"

// HTTPError captures a non-202 sidecar response.
type HTTPError struct {
	StatusCode int
	Problem    *Problem
	Body       string
}

func (e *HTTPError) Error() string {
	if e == nil {
		return "<nil>"
	}
	if e.Problem != nil {
		if e.Problem.Detail != "" {
			return fmt.Sprintf("sidecar returned %d %s: %s", e.StatusCode, e.Problem.Title, e.Problem.Detail)
		}
		return fmt.Sprintf("sidecar returned %d %s", e.StatusCode, e.Problem.Title)
	}
	if e.Body != "" {
		return fmt.Sprintf("sidecar returned %d: %s", e.StatusCode, e.Body)
	}
	return fmt.Sprintf("sidecar returned %d", e.StatusCode)
}

// OutboxFullError reports explicit bounded-capacity exhaustion.
type OutboxFullError struct {
	CapacityBytes  int64
	UsedBytes      int64
	AttemptedBytes int64
}

func (e *OutboxFullError) Error() string {
	if e == nil {
		return "<nil>"
	}
	return fmt.Sprintf(
		"outbox capacity exceeded: capacity=%d used=%d attempted=%d",
		e.CapacityBytes,
		e.UsedBytes,
		e.AttemptedBytes,
	)
}
