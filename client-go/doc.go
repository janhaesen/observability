// Package sidecarclient implements a Go client for the observability sidecar API.
//
// The direct client posts events to the sidecar over HTTP. The optional outbox
// client durably appends submissions to a single-process local journal before
// delivery, then replays them in order with at-least-once semantics.
package sidecarclient
