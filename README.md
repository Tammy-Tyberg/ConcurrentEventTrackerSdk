# ConcurrentEventTracker SDK

A thread-safe Android/Kotlin SDK component for tracking custom events, buffering them in memory, persisting them to Room, and uploading them as a compressed JSON payload.

The project demonstrates a common analytics/telemetry SDK flow:

```text
track events → buffer in memory → flush to Room → JSON file → GZIP → multipart upload
```

---

## Public API

```kotlin
fun trackEvent(event: Event)

suspend fun uploadFlushedEvents()

fun shutdown()
```

```kotlin
@Serializable
data class Event(
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)
```

---

## Features

- Thread-safe event tracking from multiple callers
- In-memory buffering
- Automatic flush when:
  - 5 events are buffered
  - 10 seconds pass since the last flush
- Room persistence
- Ordered upload using SDK-assigned sequence numbers
- JSON array payload generation
- Stream-based GZIP compression
- Multipart upload using OkHttp
- Snapshot-safe deletion after successful upload
- Unit tests covering concurrency, flushing, upload, JSON, compression, and networking

---

## Architecture

The SDK is organized into clear layers:

```text
sdk/
├── ConcurrentEventTracker.kt
├── ConcurrentEventTrackerImpl.kt
├── TrackerCommand.kt
│
├── domain/
│   ├── model/
│   ├── repository/
│   └── upload/
│
├── data/
│   ├── db/
│   ├── repository/
│   └── upload/
│
└── di/
```

### Core Flow

```text
trackEvent()
    → Channel<TrackerCommand>
    → single worker coroutine
    → in-memory buffer
    → flush to Room
    → upload pipeline
```

### Concurrency Model

- Uses a Channel + single worker coroutine, similar to an actor pattern
- Only one coroutine owns the mutable buffer, so no locks are required
- `trackEvent()` is non-blocking and uses `trySend`
- Count-based flushes, timer flushes, manual flushes, and shutdown flushes are serialized through the same worker

This model avoids shared mutable state and makes flush, shutdown, and upload coordination deterministic and easy to reason about.

### Flush Triggers

| Trigger     | Condition                 |
|------------|---------------------------|
| Count      | 5 buffered events          |
| Timer      | 10 seconds                 |
| Manual     | `uploadFlushedEvents()`    |
| Shutdown   | `shutdown()`               |

---

## Upload Flow

`uploadFlushedEvents()` performs the following steps:

1. Flush the in-memory buffer and wait for completion
2. Read persisted events from Room in order
3. Write the events as a JSON array to a temporary file
4. Compress the JSON file using GZIP
5. Upload the `.gz` file using multipart/form-data
6. Delete only the uploaded rows after a successful upload
7. Clean up temporary files in `finally`

The forced flush before upload is important because events may still be buffered in memory below the automatic flush threshold.

```text
uploadFlushedEvents()
    → flushAndAwait()
    → read DB snapshot
    → write JSON
    → compress GZIP
    → upload file
    → delete uploaded snapshot
```

Upload endpoint: `POST https://api.escuelajs.co/api/v1/files/upload`  
Multipart field name: `file`  
This is the endpoint provided with the assignment spec.

---

## Event Ordering

Events are assigned a monotonic sequence number when they are accepted by the SDK.

Room reads persisted events using `ORDER BY sequence ASC`, so uploads preserve SDK acceptance order rather than relying on timestamps.

This matters because multiple events may share the same timestamp when they are created quickly.

---

## Snapshot-Safe Deletion

The upload flow captures a snapshot of the persisted events before uploading.

After a successful upload, the SDK deletes only the sequence numbers from that snapshot.

This avoids a common bug where newer events, flushed while an upload is already in progress, could be accidentally deleted.

```text
Correct:
read snapshot [1, 2, 3]
upload [1, 2, 3]
delete only [1, 2, 3]

Avoided:
deleteAllEvents()
```

---

## Error Handling

| Failure Step        | DB Rows Deleted? | Temp Files Cleaned? |
|--------------------|------------------|---------------------|
| JSON write fails   | No               | Yes                 |
| GZIP fails         | No               | Yes                 |
| Upload fails       | No               | Yes                 |
| DB delete fails    | No               | Yes                 |

If JSON writing, compression, or upload fails, events remain in Room and can be retried later.

If upload succeeds but DB deletion fails, those rows may be uploaded again on a later retry. A production SDK would solve this with upload state tracking or server-side idempotency.

---

## Memory and Lifecycle Safety

- Room uses the application context
- Temporary files are created in `context.cacheDir`
- The SDK does not hold Activity, Fragment, View, or Lifecycle references
- GZIP compression is stream-based to avoid loading the compressed file into memory
- `shutdown()` is terminal for the current tracker instance

Because `shutdown()` is non-suspending, the demo treats it as a terminal action and disables tracking/upload controls afterward. To create a new tracker instance, restart the demo app.

---

## Demo App

The demo UI includes:

- Track Single Event
- Track 5 Events
- Upload Flushed Events
- Shutdown Tracker
- Status card showing current state

The UI is intentionally minimal and exists only as a manual smoke-test harness. Core correctness is covered by unit tests.

---

## How to Run

Open the project in Android Studio and run the app on an emulator or device.

---

## How to Run Tests

```bash
./gradlew :app:testDebugUnitTest
```

Useful individual test commands:

```bash
./gradlew :app:testDebugUnitTest --tests "*.ConcurrentEventTrackerImplTest"
./gradlew :app:testDebugUnitTest --tests "*.ConcurrentEventTrackerUploadTest"
./gradlew :app:testDebugUnitTest --tests "*.UploadFlushedEventsUseCaseTest"
./gradlew :app:testDebugUnitTest --tests "*.EventPayloadWriterTest"
./gradlew :app:testDebugUnitTest --tests "*.GzipCompressorTest"
./gradlew :app:testDebugUnitTest --tests "*.OkHttpEventUploaderTest"
```

---

## Manual Upload Verification

To manually verify a generated upload:

1. Run the app
2. Track several events
3. Tap Upload Flushed Events
4. Copy the uploaded `.gz` URL from Logcat (tag: `ConcurrentEventTracker`)
5. Run:

```bash
curl -L -o events.gz "PASTE_UPLOADED_GZ_URL_HERE"

file events.gz

gzip -t events.gz

gunzip -c events.gz > events.json

cat events.json | python3 -m json.tool
```

Expected payload shape:

```json
[
  {
    "name": "button_tap",
    "timestamp": 123456789,
    "metadata": {
      "source": "demo_ui"
    }
  }
]
```

The uploaded payload should include only event data:

- `name`
- `timestamp`
- `metadata`

It should not include internal persistence fields such as database IDs or sequence numbers.

---

## AI Usage

I used AI assistance as a development aid during this assignment, mainly for:

- brainstorming architecture and edge cases
- generating initial boilerplate for tests and DI setup
- reviewing failure paths around flushing, upload, cleanup, and retry behavior
- helping refine documentation and test coverage

All implementation decisions were reviewed and validated manually. In particular, I focused on understanding and verifying:

- the channel-based concurrency model
- the flush-before-upload synchronization
- ordered persistence using sequence numbers
- snapshot-safe deletion
- JSON, GZIP, and multipart upload behavior
- failure behavior across the upload pipeline

The final code, tests, and design tradeoffs reflect my own review and debugging process.

---

## Known Limitations

### Non-suspending shutdown

`shutdown()` is non-suspending because that is the assignment API. It performs a best-effort graceful shutdown for the current tracker instance.

A production SDK might expose a suspending close API or return a completion signal so callers can deterministically wait for the final flush.

### Manual upload trigger

Uploads are triggered manually through `uploadFlushedEvents()`.

A production analytics SDK would likely use WorkManager for scheduled and retryable background uploads.

### Delete-after-upload failure

If upload succeeds but deleting rows from Room fails, those rows may be uploaded again on retry.

A production system should use server-side idempotency keys, batch IDs, or an upload state column.

### DB read failure at startup

If the initial sequence initialization query throws (for example, due to DB corruption), the worker coroutine fails silently. Events continue to be accepted by `trackEvent()` and queued in the channel, but nothing processes them. A production SDK should catch this failure and fall back to a safe default sequence value.

### Process death before flush

Events already persisted in Room survive process death. Events still buffered in memory may be lost if the process is killed before a flush occurs.

A production SDK could persist events immediately or use a more durable queue depending on requirements.

---

## If I Had More Time

- WorkManager integration for guaranteed background upload
- Retry with exponential backoff
- Upload state column such as `PENDING`, `UPLOADING`, and `UPLOADED`
- Server-side idempotency key or batch ID
- Streaming JSON generation for very large event queues
- Chunked uploads for very large payloads
- Metadata redaction or encryption for sensitive analytics data
- Observable callbacks or listener interface for upload and flush results
- Separate SDK Gradle module for clearer app/SDK boundaries
- Builder or factory initialization API for apps that do not use Hilt