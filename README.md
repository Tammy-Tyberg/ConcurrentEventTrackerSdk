# ConcurrentEventTracker SDK

A thread-safe, lifecycle-aware Android SDK for buffering analytics events in memory, persisting them to Room, and uploading them as a compressed JSON payload over HTTP.

---

## Public API

```kotlin
fun trackEvent(event: Event)        // Thread-safe. Call from any thread.
suspend fun uploadFlushedEvents()   // Flush → JSON → GZIP → HTTP POST → delete on success.
fun shutdown()                      // Graceful, non-blocking. Best-effort flush.
```

**`Event`** is the only public model:

```kotlin
@Serializable
data class Event(
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)
```

---

## Architecture

The SDK is organized into three layers:

```
sdk/
├── ConcurrentEventTracker.kt          public interface
├── ConcurrentEventTrackerImpl.kt      @Singleton Channel/actor implementation
├── TrackerCommand.kt                  sealed interface: Track | Flush | FlushAndAck | Shutdown
│
├── domain/
│   ├── model/
│   │   ├── Event.kt                   public @Serializable data class
│   │   └── TrackedEvent.kt            internal — adds sequence:Long for ordering
│   ├── repository/
│   │   └── EventRepository.kt         internal interface (insertAll, getAll, deleteBySequences)
│   └── upload/
│       ├── EventUploader.kt           internal interface (upload(File): UploadResponse)
│       ├── UploadFlushedEventsUseCase.kt  orchestrates the 6-step pipeline
│       └── UploadResponse.kt          @Serializable — location field from API
│
├── data/
│   ├── db/
│   │   ├── EventEntity.kt             Room entity (id auto, name, timestamp, sequence, metadata)
│   │   ├── EventDao.kt                insertAll, getAllOrdered, deleteBySequences
│   │   ├── EventDatabase.kt
│   │   ├── Mappers.kt                 TrackedEvent ↔ EventEntity
│   │   └── MetadataConverter.kt       Map<String,String> ↔ JSON string for Room column
│   ├── repository/
│   │   └── EventRepositoryImpl.kt
│   └── upload/
│       ├── EventPayloadWriter.kt      List<TrackedEvent> → JSON array written to File
│       ├── GzipCompressor.kt          streaming GZIP compress (inputFile → outputFile)
│       ├── OkHttpEventUploader.kt     multipart/form-data POST; logs URL to Logcat
│       └── TempFileManager.kt         creates/deletes temp files in context.cacheDir
│
└── di/
    ├── DatabaseModule.kt
    ├── IoDispatcher.kt / TrackerScope.kt   @Qualifier annotations
    ├── RepositoryModule.kt
    ├── ScopeModule.kt                 provisions CoroutineScope + IoDispatcher
    ├── TrackerModule.kt               @Binds impl + @Named("flushInterval")
    └── UploadModule.kt                OkHttpClient, Json (encodeDefaults=true), TempFileManager
```

The demo app lives in the top-level package alongside `MainActivity`:

```
MainViewModel.kt        @HiltViewModel wrapping ConcurrentEventTracker
TrackerDemoScreen.kt    Compose UI: 4 buttons + status card
```

---

## Concurrency Model

```
trackEvent()  ──► Channel<TrackerCommand>(UNLIMITED) ──► single worker coroutine
                                                              │
                  ┌───────────────────────────────────────────┤
                  │ Track(event)     → append to in-memory buffer
                  │ Flush            → insertAll to Room, clear buffer
                  │ FlushAndAck(ack) → insertAll, clear buffer, ack.complete(Unit)
                  │ Shutdown         → final flush, cancel scope
                  └───────────────────────────────────────────┘
```

**Key invariants:**

- The mutable buffer is owned exclusively by the worker coroutine — no locks needed.
- `trackEvent()` uses `channel.trySend()`, which never blocks. `Channel.UNLIMITED` means it never returns `false` until after shutdown.
- Sequence numbers are stamped at `trackEvent()` time via `AtomicLong` — before the event enters the channel — preserving call-site ordering.
- The shutdown guard (`AtomicBoolean`) prevents `trySend` from sending after the channel closes.

**Flush triggers:**

| Trigger | Condition |
|---|---|
| Count-based | Every 5 events tracked |
| Timer-based | Every 10 seconds (injectable via `@Named("flushInterval")`) |
| Manual | `uploadFlushedEvents()` → `flushAndAwait()` |
| Shutdown | `shutdown()` → final Flush before cancel |

---

## Upload Flow

`uploadFlushedEvents()` executes 6 steps in order:

```
1. flushAndAwait()
   Send FlushAndAck to channel → suspend on CompletableDeferred until worker ACKs
   ↓
2. repository.getAllEvents()
   Read current DB snapshot; capture sequence numbers (snapshot)
   ↓
3. EventPayloadWriter.write(events, jsonFile)
   Serialize List<TrackedEvent.event> as a JSON array to a temp file
   ↓
4. GzipCompressor.compress(jsonFile, gzipFile)
   Stream-compress jsonFile → gzipFile (avoids loading all bytes into memory)
   ↓
5. OkHttpEventUploader.upload(gzipFile)
   POST multipart/form-data to https://api.escuelajs.co/api/v1/files/upload
   Parse UploadResponse; log URL to Logcat with tag "EventTracker"
   ↓
6. repository.deleteEventsBySequences(snapshot)    ← only on success
   fileManager.deleteQuietly(jsonFile, gzipFile)   ← always in finally
```

Step 1 is critical: without `FlushAndAck`, events tracked below the auto-flush threshold
(still in memory) would be invisible to step 2 and silently missed.

---

## Error Handling

| Step that fails | DB rows deleted? | Temp files cleaned? |
|---|---|---|
| JSON write | No | Yes (finally) |
| GZIP compress | No | Yes (finally) |
| HTTP upload | No | Yes (finally) |
| DB delete (after success) | Partial/None | Yes (finally) |

All failures throw — the caller (`uploadFlushedEvents()`) propagates the exception to
the coroutine that called it. No events are silently dropped.

If the upload succeeds but the DB delete throws, those rows will be re-uploaded on the next
call. A production SDK should use an idempotency key or an `uploadState` column to prevent
duplicate delivery.

---

## Event Ordering

Events are stamped with a monotonically increasing `sequence: Long` at `trackEvent()` time
using `AtomicLong.getAndIncrement()`. Room reads use `ORDER BY sequence ASC`. This gives
deterministic upload order regardless of system clock behavior or batching.

---

## Memory Safety

Room and `TempFileManager` receive `applicationContext` and `context.cacheDir` respectively
— never an Activity reference. The `@TrackerScope` `CoroutineScope` is Hilt-singleton so it
survives configuration changes. The SDK holds no references to Activity, Fragment, or View.

---

## How to Run

1. Open the project in Android Studio.
2. Connect a device or start an emulator.
3. Run the `app` configuration.
4. The demo screen appears immediately.

---

## How to Run Tests

```bash
./gradlew :app:testDebugUnitTest
```

Individual suites:

```bash
./gradlew :app:testDebugUnitTest --tests "*.ConcurrentEventTrackerImplTest"
./gradlew :app:testDebugUnitTest --tests "*.ConcurrentEventTrackerUploadTest"
./gradlew :app:testDebugUnitTest --tests "*.UploadFlushedEventsUseCaseTest"
./gradlew :app:testDebugUnitTest --tests "*.EventPayloadWriterTest"
./gradlew :app:testDebugUnitTest --tests "*.GzipCompressorTest"
./gradlew :app:testDebugUnitTest --tests "*.OkHttpEventUploaderTest"
```

86 tests, all passing.

| Suite | Count | Focus |
|---|---|---|
| ConcurrentEventTrackerImplTest | 32 | trackEvent, count/timer flush, concurrency, ordering, shutdown |
| ConcurrentEventTrackerUploadTest | 8 | End-to-end upload, buffer-before-DB proof, snapshot safety |
| UploadFlushedEventsUseCaseTest | 20 | All failure paths, snapshot safety, payload shape |
| EventPayloadWriterTest | 8 | JSON shape, special chars, empty metadata, no internal fields |
| GzipCompressorTest | 6 | Round-trip, empty input, missing input, streaming |
| OkHttpEventUploaderTest | 11 | MockWebServer: POST, multipart shape, response parsing, error cases |

---

## How to Manually Verify Upload

1. Run the app on a device or emulator.
2. Open Logcat and filter by tag: **`EventTracker`**
3. Tap **Track Single Event** three times (3 events — below the 5-event auto-flush threshold, stays in memory).
4. Tap **Upload Flushed Events**.
5. The status card shows "Uploading…" then "Upload complete — GZIP URL logged".
6. Logcat shows: `Upload complete: https://api.escuelajs.co/…`
7. Tap **Track 5 Events** — this hits the count-based auto-flush (events go to Room automatically).
8. Tap **Upload Flushed Events** again — verifies DB-persisted events also upload correctly.

---

## AI Usage

This project was developed with AI assistance (Claude Sonnet via Claude Code CLI).

**What AI did:**

- Generated boilerplate for DI modules, Room entity/DAO, and Hilt annotations
- Suggested the `Channel<TrackerCommand>` actor pattern for thread safety
- Drafted the `FlushAndAck(ack: CompletableDeferred<Unit>)` mechanism for synchronizing upload with the in-memory buffer
- Wrote test scaffolding: `FakeEventRepository`, `FakeEventUploader`, `ThrowingEventPayloadWriter`, `ThrowingGzipCompressor`
- Discovered MockWebServer 5.1.0 API changes via `javap` decompilation (setter methods, `SocketPolicy` enum, `RecordedRequest.body` as `okio.Buffer`)
- Wrote the full test suite (86 tests across 6 suites)
- Drafted this README

**What I reviewed and decided:**

- `deleteEventsBySequences(snapshot)` not `deleteAll()` — snapshot safety is a deliberate design choice
- `try/finally` not `runCatching` — to avoid swallowing `CancellationException`
- `Channel.UNLIMITED` not bounded — bounded channels back-pressure `trackEvent()` on the UI thread, which is unacceptable in an SDK
- `encodeDefaults = true` in Json — required so `metadata: emptyMap()` serializes as `{}` not omitted
- `@param:IoDispatcher` annotation target — resolves a Kotlin/Hilt ambiguity warning on qualifier annotations
- Non-blocking `shutdown()` — matches the assignment spec; limitation is documented

---

## Design Tradeoffs

**Channel vs Mutex**: A `Mutex` requires every accessor to acquire it. With a channel, only one coroutine ever touches the buffer — no lock contention, naturally composable, cancellation-aware.

**`deleteEventsBySequences` vs `deleteAll`**: Events flushed to Room during an in-flight upload get new sequence numbers outside the snapshot. `deleteAll` would drop them silently. The targeted delete is always safe.

**No Retrofit**: There is one upload endpoint. A full service interface adds indirection without value. OkHttp is hidden behind `EventUploader` so it is replaceable without touching the use case.

---

## Known Limitations / TODOs

- **Delete-after-upload failure**: If upload succeeds but the DB delete throws, events will be re-uploaded on the next call. Fix: server-side idempotency key or `uploadState` column (`PENDING → UPLOADING → UPLOADED`).
- **`shutdown()` is best-effort**: Non-suspending by design. The caller cannot await the final flush. Fix: expose `suspend fun shutdown()` or return `Job`.
- **No WorkManager**: Upload is manually triggered. If the process dies during upload, the next call retries from DB. Fix: `PeriodicWorkRequest` with network/battery constraints.
- **No DAO/Room unit tests**: `EventDao` tests require Robolectric or instrumented tests — not included. Candidates: `dao_getAllOrdered_returnsAscendingSequence`, `dao_deleteBySequences_deletesOnlyMatchingRows`.
- **No retry/backoff**: Failed uploads propagate immediately. Fix: exponential backoff loop in `UploadFlushedEventsUseCase`.

---

## If I Had More Time

- Exponential backoff on upload failure
- WorkManager integration for guaranteed background delivery
- Upload state column (`PENDING → UPLOADING → UPLOADED`) to handle retry safely
- Server-side idempotency key (batch UUID in the multipart form field)
- Chunked / streaming JSON for large event backlogs (10k+ events)
- Encryption / metadata redaction filter
- Per-event immediate persistence option for full process-death resilience
- Metrics/callback interface so the host app can observe upload success/failure