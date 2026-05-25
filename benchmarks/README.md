# Benchmarks

This module provides a lightweight comparative benchmark harness for the observability sink decorators and representative in-process sink behaviors.

It is designed for:

- relative trend comparison between scenarios
- backpressure visibility
- decorator trade-off exploration during development and release prep

It is **not** intended to be a universal performance claim for all JVMs, workloads, or deployment environments.

## What the suite covers

The current scenario set includes:

| Scenario | Purpose |
| --- | --- |
| `direct-noop` | direct baseline with minimal sink overhead |
| `async-noop-cap-128` | async overhead with a small queue |
| `async-noop-cap-2048` | async overhead with a larger queue |
| `direct-delay-250us` | representative direct sink behavior with deterministic per-event cost |
| `async-backpressure-queue-32` | async backpressure under queue saturation and drop pressure |
| `batching-delay-direct` | batching over a delegate that still pays per-event cost |
| `batching-delay-batch-capable` | batching over a batch-capable delegate that pays once per batch |
| `retry-once-every-5th` | retry path overhead when intermittent failures succeed on retry |

## Run all scenarios

```bash
./gradlew :benchmarks:run
```

## Run selected scenarios

The runner executes every scenario by default, but you can filter by name with Gradle's `--args`:

```bash
./gradlew :benchmarks:run --args "async-noop-cap-2048"
./gradlew :benchmarks:run --args "async-noop-cap-2048 retry-once-every-5th"
./gradlew :benchmarks:run --args "async-noop-cap-2048,retry-once-every-5th"
```

## What the report means

The runner prints a single row per scenario with:

- `producerMs` / `producerEvS`: enqueue or direct handle cost before close and drain
- `endToEndMs` / `endToEndEvS`: full run including close and drain
- `avgProducerUs` / `avgEndUs`: coarse averages derived from total elapsed time, not per-event timers
- `drops` / `queueMax`: async backpressure signals from `InMemoryOperationalDiagnostics`
- `batchAvgMs`: average batch flush time from diagnostics
- `retryExh`: retry exhaustion count from diagnostics

Important interpretation notes:

- every scenario performs a warmup pass before measured output
- retry scenarios inject zero-cost backoff so they measure decorator overhead, not `Thread.sleep`
- async scenarios can show a large gap between producer-side and end-to-end numbers
- sub-millisecond batch flushes may display as `0.00` because diagnostics record elapsed time in whole milliseconds

## Example comparative results

Example output from a local run of this suite:

| Scenario | Events | producerMs | endToEndMs | producerEvS | endToEndEvS | drops | queueMax |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `direct-noop` | 100000 | 49.66 | 49.67 | 2013804.63 | 2013375.50 | 0 | 0 |
| `async-noop-cap-128` | 100000 | 70.03 | 175.20 | 1427981.53 | 570785.21 | 0 | 128 |
| `async-noop-cap-2048` | 100000 | 39.83 | 141.22 | 2510557.46 | 708116.05 | 0 | 855 |
| `direct-delay-250us` | 5000 | 1252.51 | 1252.54 | 3991.97 | 3991.90 | 0 | 0 |
| `async-backpressure-queue-32` | 5000 | 1.11 | 17.20 | 4497078.25 | 290768.12 | 4966 | 32 |
| `batching-delay-direct` | 5000 | 1255.46 | 1255.54 | 3982.61 | 3982.36 | 0 | 0 |
| `batching-delay-batch-capable` | 5000 | 26.26 | 26.31 | 190375.26 | 190039.70 | 0 | 0 |
| `retry-once-every-5th` | 25000 | 8.30 | 8.31 | 3011322.57 | 3008966.72 | 0 | 0 |

## What the results do **not** represent

Do not treat these numbers as:

- guarantees for production systems
- cross-machine performance constants
- a substitute for measuring your own sink, payload size, and workload mix

The most useful reading is comparative:

- how much overhead a decorator introduces relative to a direct baseline
- whether batching helps only when the delegate is batch-capable
- how small async queues behave under stress
- how retry bookkeeping changes throughput even when backoff sleeps are removed
