# MuScriptor Score v1.0 (Android, private non-commercial project)

An **offline-only** Android music-to-sheet application based on MuScriptor Small.

**Status: source-level integration prototype, NOT a validated ready-to-install MuScriptor application.**
The Android UI, local MP3 decode, native C ABI adapter, event parser, simple score preview, editing,
MIDI writer and MusicXML writer are implemented. The heavyweight third-party native engine and gated
weights are **not bundled**. The NDK build, end-to-end inference, and APK installation have **not been
verified on an Android device**. Running the Gradle build without preparing the native engine produces
an APK whose UI works but whose AI transcription cannot run.

## Architecture

- Android Java app, `compileSdk 36`, `targetSdk 36`, `minSdk 26`, `versionName 1.0`
- Android MediaExtractor + MediaCodec decodes local MP3 and other supported audio to mono 16 kHz
- JNI shim `muscriptorscore_bridge` loads the local `libaudiocpp.so` C ABI **in-process** using `dlopen`
- `audio.cpp` implements actual MuScriptor Small inference with ggml, CPU backend (no Python, no API)
- `.gguf` Small model, copied by user into private app storage through Android Storage Access Framework
- MuScriptor event stream (`start` / `end`) -> note list -> basic preview / MIDI / MusicXML
- **No `android.permission.INTERNET` in AndroidManifest.xml**

No analysis server is used. The preparation/build steps use a development computer and the internet
one time to obtain third-party source and authorized model weights; these are *build-time tasks*, not
a phone-to-server inference workflow. The installed app is offline after the model has been imported.

## Why isn't the GGUF model already in the ZIP?

Official MuScriptor model weights require accepting **CC BY-NC 4.0** at Hugging Face and are hundreds of
megabytes. We cannot obtain or redistribute them through this session. Similarly the audio.cpp native
runtime must be compiled for Android ARM64; it is not a normal Android Maven library. Neither is included.

## Requirements on Windows

1. Android Studio SDK Platform 36 and Android NDK, CMake, Ninja, Git, host C++ compiler.
2. JDK 21, Gradle **8.13** and Android Gradle Plugin **8.13.0**.
3. Python 3, `numpy` and `safetensors` for GGUF model conversion.
4. A **64-bit Android (ARM64)** phone with enough free storage and memory.

### Prepare native engine (development computer)

Open PowerShell inside the extracted `MuScriptorScore/` directory:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Native.ps1
```

The script clones `0xShug0/audio.cpp` at commit `e74e0aaea1a9ed0d3dde4752c73670068f9c1ea4`,
including submodules, attempts a native CPU-only Android NDK build and copies `libaudiocpp.so` into
`app/src/main/jniLibs/arm64-v8a/`. This route has **not been tested here**; Android-related build
patches could be necessary in that upstream repository. It does not build a server.

Check binary dependencies with `llvm-readelf -d libaudiocpp.so`; any non-system shared libraries need
to be included alongside it. If the model build fails, no working transcription can be claimed.

### Prepare your model (development computer)

First accept the MuScriptor model license and download `config.json` and `model.safetensors` from:
https://huggingface.co/MuScriptor/muscriptor-small

Ensure both files are in one folder. Then run:

```powershell
python -m pip install numpy safetensors
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Model.ps1 -CheckpointDir "C:\path\to\checkpoint"
```

This builds the audio.cpp *host* GGUF converter, and writes
`native-build/weights/muscriptor-small-f32.gguf`.
Copy the GGUF to your Android device (USB/MTP or ADB). In the app select **モデルを追加** once to store it
locally. The app does not download the file itself and never sends the MP3 anywhere.

### Build and install the Android app

Use Android Studio: **Open** the `MuScriptorScore/` folder and build/install `app`.
Alternatively, from the project root in PowerShell:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
```

The project contains Gradle Wrapper **8.13** (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`).
The Wrapper JAR was obtained from the official Gradle `v8.13.0` tag and verified against
the published SHA-256
`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
The distribution ZIP checksum is pinned to
`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
The first build downloads Gradle and Android build dependencies; offline operation refers to
**on-device music transcription after setup**, not the development build.
The APK appears in `app/build/outputs/apk/debug/` when the Android build succeeds.

## App workflow

1. Tap **MP3を選択** and select a local audio file via SAF.
2. Tap **モデルを追加** to import an authorized MuScriptor Small F32 GGUF (once).
3. Tap **この曲を採譜**. AI computation runs synchronously in a single app background worker.
4. Review part selections, score previews, and the note list. Tap a note to adjust pitch/start/end.
5. Correct BPM as needed (default **120 BPM**). Export MIDI, MusicXML or JSON with Android SAF.

**Limits:** MP3 decoding capped at 10 minutes; one-job-at-a-time; native MuScriptor inference is
CPU only and may take a very long time on low-end smartphones, or fail due to memory pressure.
The score preview is not a professional engraver. MusicXML output is **4/4 with fixed user-selected
BPM and 1/16-note quantization**; it has no automatic tempo, meter, key, lyrics or reliable drum
notation. The XML supports separate voices and tied notes but not ideal engraving in every case.
No PDF export in this initial source. Export MusicXML for MuseScore or similar notation editors.

## Verification completed

`tests/run-export-tests.sh` compiles the pure Java MIDI/MusicXML writers and verifies correct MIDI
header and XML parsing, escaping, multiple instrument parts and overlapping voices. Run with JDK 21:

```bash
bash tests/run-export-tests.sh
```

**Not verified:** Android Gradle build, NDK cross-build, linking/binary dependencies, MuScriptor
actual output on ARM64, device performance, real MP3/music quality, GUI lifecycle on actual phone.

## Licenses and attribution

- MuScriptor by Kyutai/Mirelo: **MIT code**, weights **CC BY-NC 4.0**.
- audio.cpp by ShugoAI LLC: **Apache 2.0** with additional third-party dependencies.
- The model weight file is not included. **Personal noncommercial use only**. Do not distribute
  weights or package an app commercially without obtaining the relevant rights.
- Android app glue and original code here may be modified for personal use. See `THIRD_PARTY_NOTICES.md`.

Source references:
- https://github.com/muscriptor/muscriptor
- https://huggingface.co/MuScriptor/muscriptor-small
- https://github.com/0xShug0/audio.cpp/blob/main/docs/models/muscriptor.md
- https://github.com/0xShug0/audio.cpp/blob/main/docs/c_api.md
