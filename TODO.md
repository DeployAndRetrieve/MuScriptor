# MuScriptor - Implementation Backlog

- [ ] P0: Verify v1.3.0 Android Gradle build (Java, JNI symbols, NDK), install and perform first real multi-chunk MuScriptor inference.
- [ ] P0: Verify offline warm session reuse across 2+ windows, memory peak, model load time and crash handling. Inspect if requests retain any state.
- [ ] P0: Confirm exact MuScriptor event JSON schema and whether every chunk's timestamps are relative; adjust parser if needed.
- [ ] P0: Confirm checkpoint resume after force-stopping the app during a window; ensure prior completed windows are not rerun.
- [ ] P1: Benchmark and listen to 5-second seam matching, long sustained notes, same-pitch retriggers, mixed percussion and cross-window instrument changes.
- [ ] P1: Add Foreground Service and restricted WakeLock so long running transcription can continue with screen off, respecting recent Android restrictions.
- [ ] P1: Add robust source-permission and missing-model recovery UI; protect backups against accidental checkpoint deletion.
- [ ] P1: Add app-level instrumentation tests for source fingerprint mismatch, atomic write interruption and completed checkpoint recovery.
- [ ] P2: Optimize model inference CPU/RAM use and consider configurable window/overlap after empirical quality testing.
- [ ] P2: Improve MIDI/MusicXML timing and voicing using stitched note data.

Functionality before documentation polish. Unverified items must remain marked as unverified.
