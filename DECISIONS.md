# MuScriptor - Decision Log

## 2026-11-28: Incremental transcription / checkpoint recovery (v1.3.0)

- **Windowing:** 30-second PCM windows, 5 seconds overlap, 25-second hop. Keep original 16 kHz mono decoder and 10-minute input limit.
- **Merging:** Convert model-relative notes to song-relative timestamps; deduplicate within overlap by instrument, pitch and substantial temporal intersection. Preserve repeated notes and one-to-one matching. This is an approximate heuristic pending listening tests.
- **Durability:** Save merged notes and the next chunk index together to an atomic app-private JSON checkpoint after each successful chunk. On restart, re-decode and compare PCM SHA-256, model identity, URI, size and settings before resuming.
- **Lifetimes:** Use an app-process-level single-worker executor and reconnect Activity callbacks across recreation. Share a warm audio.cpp C API model/session across chunk requests, with native cleanup in finally.
- **User controls:** Automatically resume matching checkpoints after model initialization; pause only after a finished chunk; require confirmation before replacing the source or discarding progress.
- **Scope limit:** Not a Foreground Service. Android may kill background work and the in-flight chunk is then retried. Do not present background continuity as guaranteed.
- **Verification gate:** Run JVM merger tests; Android JNI linking, true model output, listening quality and persistence under OS process death require device testing.

See [docs/TRANSCRIPTION_CHECKPOINTS.md](docs/TRANSCRIPTION_CHECKPOINTS.md).
