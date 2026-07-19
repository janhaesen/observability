package sidecarclient

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	defaultBaseURL            = "http://127.0.0.1:8080"
	defaultUserAgent          = "observability-client-go"
	singleSubmissionPath      = "/v1/events"
	batchSubmissionPath       = "/v1/events:batch"
	maxContractBatchSize      = 100
	maxErrorResponseBodyBytes = 1 << 20
)

// Config configures direct sidecar submission.
type Config struct {
	BaseURL       string
	HTTPClient    *http.Client
	TokenProvider TokenProvider
	UserAgent     string
}

// Client submits events directly to the sidecar HTTP API.
type Client struct {
	baseURL       *url.URL
	httpClient    *http.Client
	tokenProvider TokenProvider
	userAgent     string
}

// NewClient builds a direct HTTP sidecar client.
func NewClient(cfg Config) (*Client, error) {
	baseURL := cfg.BaseURL
	if strings.TrimSpace(baseURL) == "" {
		baseURL = defaultBaseURL
	}
	parsed, err := url.Parse(baseURL)
	if err != nil {
		return nil, fmt.Errorf("parse base url: %w", err)
	}
	if parsed.Scheme == "" || parsed.Host == "" {
		return nil, fmt.Errorf("base url must include scheme and host")
	}
	userAgent := cfg.UserAgent
	if strings.TrimSpace(userAgent) == "" {
		userAgent = defaultUserAgent
	}
	httpClient := cfg.HTTPClient
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	return &Client{
		baseURL:       parsed,
		httpClient:    httpClient,
		tokenProvider: cfg.TokenProvider,
		userAgent:     userAgent,
	}, nil
}

// Submit sends one event to POST /v1/events.
func (c *Client) Submit(ctx context.Context, event Event) (SubmissionResult, error) {
	normalized, preparedWireEvent, err := prepareEvent(event)
	if err != nil {
		return SubmissionResult{}, err
	}
	receipt, err := c.submitPrepared(ctx, []wireEvent{preparedWireEvent})
	if err != nil {
		return SubmissionResult{}, err
	}
	return SubmissionResult{Receipt: receipt, Events: []Event{normalized}}, nil
}

// SubmitBatch sends up to 100 events to POST /v1/events:batch.
func (c *Client) SubmitBatch(ctx context.Context, events []Event) (SubmissionResult, error) {
	normalized, wireEvents, err := prepareEvents(events)
	if err != nil {
		return SubmissionResult{}, err
	}
	receipt, err := c.submitPrepared(ctx, wireEvents)
	if err != nil {
		return SubmissionResult{}, err
	}
	return SubmissionResult{Receipt: receipt, Events: normalized}, nil
}

func (c *Client) submitPrepared(ctx context.Context, wireEvents []wireEvent) (SubmissionReceipt, error) {
	var path string
	var payload any
	if len(wireEvents) == 1 {
		path = singleSubmissionPath
		payload = wireEvents[0]
	} else {
		path = batchSubmissionPath
		payload = wireBatch{Events: wireEvents}
	}
	return c.doSubmit(ctx, path, payload, len(wireEvents))
}

func (c *Client) doSubmit(ctx context.Context, path string, payload any, expectedAccepted int) (SubmissionReceipt, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return SubmissionReceipt{}, fmt.Errorf("marshal request: %w", err)
	}
	endpoint := c.baseURL.ResolveReference(&url.URL{Path: path})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), bytes.NewReader(body))
	if err != nil {
		return SubmissionReceipt{}, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, application/problem+json")
	if c.userAgent != "" {
		req.Header.Set("User-Agent", c.userAgent)
	}
	if c.tokenProvider != nil {
		token, err := c.tokenProvider.Token(ctx)
		if err != nil {
			return SubmissionReceipt{}, fmt.Errorf("resolve bearer token: %w", err)
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return SubmissionReceipt{}, err
	}
	defer resp.Body.Close()

	responseBody, err := io.ReadAll(io.LimitReader(resp.Body, maxErrorResponseBodyBytes))
	if err != nil {
		return SubmissionReceipt{}, fmt.Errorf("read response: %w", err)
	}
	if resp.StatusCode != http.StatusAccepted {
		return SubmissionReceipt{}, parseHTTPError(resp.StatusCode, responseBody)
	}
	var receipt SubmissionReceipt
	if err := json.Unmarshal(responseBody, &receipt); err != nil {
		return SubmissionReceipt{}, fmt.Errorf("decode receipt: %w", err)
	}
	if !isUUID(receipt.SubmissionID) {
		return SubmissionReceipt{}, fmt.Errorf("decode receipt: submissionId must be a UUID")
	}
	if receipt.AcceptedCount != expectedAccepted {
		return SubmissionReceipt{}, fmt.Errorf(
			"decode receipt: acceptedCount=%d, want %d",
			receipt.AcceptedCount,
			expectedAccepted,
		)
	}
	return receipt, nil
}

func parseHTTPError(statusCode int, body []byte) error {
	httpErr := &HTTPError{StatusCode: statusCode, Body: strings.TrimSpace(string(body))}
	var problem Problem
	if len(body) > 0 && json.Unmarshal(body, &problem) == nil && problem.Title != "" {
		httpErr.Problem = &problem
	}
	return httpErr
}

func prepareEvents(events []Event) ([]Event, []wireEvent, error) {
	if len(events) == 0 {
		return nil, nil, fmt.Errorf("at least one event is required")
	}
	if len(events) > maxContractBatchSize {
		return nil, nil, fmt.Errorf("batch size %d exceeds contract limit %d", len(events), maxContractBatchSize)
	}
	normalized := make([]Event, len(events))
	wireEvents := make([]wireEvent, len(events))
	for i, event := range events {
		prepared, wire, err := prepareEvent(event)
		if err != nil {
			return nil, nil, fmt.Errorf("event %d: %w", i, err)
		}
		normalized[i] = prepared
		wireEvents[i] = wire
	}
	return normalized, wireEvents, nil
}

func prepareEvent(event Event) (Event, wireEvent, error) {
	id, err := normalizeClientEventID(event.ClientEventID)
	if err != nil {
		return Event{}, wireEvent{}, err
	}
	if strings.TrimSpace(event.Name) == "" {
		return Event{}, wireEvent{}, fmt.Errorf("name is required")
	}
	if !event.Level.valid() {
		return Event{}, wireEvent{}, fmt.Errorf("level must be one of TRACE, DEBUG, INFO, WARN, ERROR")
	}
	if event.Timestamp.IsZero() {
		return Event{}, wireEvent{}, fmt.Errorf("timestamp is required")
	}
	contextMap, err := normalizeContext(event.Context)
	if err != nil {
		return Event{}, wireEvent{}, err
	}
	prepared := Event{
		ClientEventID: id,
		Name:          event.Name,
		Level:         event.Level,
		Timestamp:     event.Timestamp.UTC(),
		Message:       cloneString(event.Message),
		Context:       contextMap,
		Payload:       bytes.Clone(event.Payload),
	}
	wire := wireEvent{
		ClientEventID: id,
		Name:          event.Name,
		Level:         string(event.Level),
		Timestamp:     event.Timestamp.UTC().Format(time.RFC3339Nano),
		Message:       cloneString(event.Message),
		Context:       contextMap,
	}
	if len(event.Payload) > 0 {
		wire.PayloadBase64 = event.Payload
	}
	if event.Error != nil {
		remoteError, err := normalizeRemoteError(event.Error)
		if err != nil {
			return Event{}, wireEvent{}, err
		}
		prepared.Error = remoteError.toPublic()
		wire.Error = remoteError
	}
	return prepared, wire, nil
}

func normalizeRemoteError(remoteError *RemoteError) (*wireRemoteError, error) {
	if remoteError == nil {
		return nil, nil
	}
	if strings.TrimSpace(remoteError.Type) == "" {
		return nil, fmt.Errorf("error.type is required")
	}
	if strings.TrimSpace(remoteError.Stacktrace) == "" {
		return nil, fmt.Errorf("error.stacktrace is required")
	}
	return &wireRemoteError{
		Type:       remoteError.Type,
		Message:    cloneString(remoteError.Message),
		Stacktrace: remoteError.Stacktrace,
	}, nil
}

func normalizeContext(contextMap map[string]any) (map[string]any, error) {
	if contextMap == nil {
		return map[string]any{}, nil
	}
	normalized := make(map[string]any, len(contextMap))
	for key, value := range contextMap {
		normalizedValue, ok := normalizeContextValue(value)
		if !ok {
			return nil, fmt.Errorf("context.%s must be a string, bool, or finite number", key)
		}
		normalized[key] = normalizedValue
	}
	return normalized, nil
}

func normalizeContextValue(value any) (any, bool) {
	switch typed := value.(type) {
	case string:
		return typed, true
	case bool:
		return typed, true
	case int:
		return typed, true
	case int8:
		return typed, true
	case int16:
		return typed, true
	case int32:
		return typed, true
	case int64:
		return typed, true
	case uint:
		return typed, true
	case uint8:
		return typed, true
	case uint16:
		return typed, true
	case uint32:
		return typed, true
	case uint64:
		return typed, true
	case float32:
		return normalizeFloat(float64(typed))
	case float64:
		return normalizeFloat(typed)
	case json.Number:
		if floatValue, err := typed.Float64(); err == nil {
			return normalizeFloat(floatValue)
		}
		return nil, false
	default:
		return nil, false
	}
}

func normalizeFloat(value float64) (any, bool) {
	if math.IsNaN(value) || math.IsInf(value, 0) {
		return nil, false
	}
	return value, true
}

func normalizeClientEventID(id string) (string, error) {
	if strings.TrimSpace(id) == "" {
		return newUUID()
	}
	if !isUUID(id) {
		return "", fmt.Errorf("clientEventId must be a UUID")
	}
	return strings.ToLower(id), nil
}

func newUUID() (string, error) {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", fmt.Errorf("generate clientEventId: %w", err)
	}
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	return fmt.Sprintf(
		"%08x-%04x-%04x-%04x-%012x",
		raw[0:4],
		raw[4:6],
		raw[6:8],
		raw[8:10],
		raw[10:16],
	), nil
}

func isUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i, r := range value {
		switch i {
		case 8, 13, 18, 23:
			if r != '-' {
				return false
			}
		default:
			if !isHex(r) {
				return false
			}
		}
	}
	return true
}

func isHex(r rune) bool {
	return ('0' <= r && r <= '9') || ('a' <= r && r <= 'f') || ('A' <= r && r <= 'F')
}

func cloneString(value *string) *string {
	if value == nil {
		return nil
	}
	cloned := *value
	return &cloned
}

func (l Level) valid() bool {
	switch l {
	case LevelTrace, LevelDebug, LevelInfo, LevelWarn, LevelError:
		return true
	default:
		return false
	}
}

type wireEvent struct {
	ClientEventID string           `json:"clientEventId"`
	Name          string           `json:"name"`
	Level         string           `json:"level"`
	Timestamp     string           `json:"timestamp"`
	Message       *string          `json:"message,omitempty"`
	Context       map[string]any   `json:"context"`
	PayloadBase64 []byte           `json:"payloadBase64,omitempty"`
	Error         *wireRemoteError `json:"error,omitempty"`
}

type wireBatch struct {
	Events []wireEvent `json:"events"`
}

type wireRemoteError struct {
	Type       string  `json:"type"`
	Message    *string `json:"message,omitempty"`
	Stacktrace string  `json:"stacktrace"`
}

func (w *wireRemoteError) toPublic() *RemoteError {
	if w == nil {
		return nil
	}
	return &RemoteError{
		Type:       w.Type,
		Message:    cloneString(w.Message),
		Stacktrace: w.Stacktrace,
	}
}
