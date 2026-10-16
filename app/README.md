# iTantra — Android app (SIH26173)

Offline voice transceiver. Speech in on phone A, **text** over Bluetooth or Wi-Fi Direct,
speech out on phone B. No network call is made at any point.

> **Status: BUILDS AND RUNS.** The tree builds a signed 993 MB release APK (v1.0.0) and a debug
> APK. The release APK launched on an Android 34 emulator, ran every screen and passed the three
> on-device self-tests (16 Oct 2026). The two-phone Bluetooth loop ran on two physical phones on
> 27 Sep 2026, on the earlier build. The v1.0.0 pass is pending (`VERIFICATION_STATUS.md`).
> Everything marked ✅ is real. Everything else is written-but-untested.

---

## What this project is

A fork of the design behind `k2-fsa/sherpa-onnx/android/SherpaOnnxVadAsr`, extended with the three
things that reference app does not have:

1. **A transport** — Bluetooth Classic RFCOMM (primary) and Wi-Fi Direct (fallback)
2. **TTS playback** — the reference app is speech-to-text only
3. **A language filter** — the ASR model is a 1,600-language model with no language parameter, so
   output must be filtered to the ten required scripts

---

## Architecture in one screen

```
Phone A (both roles)                 Phone B (both roles)
┌────────────────────────┐           ┌────────────────────────┐
│ Microphone 16 kHz mono │           │ Text + language tag    │
│         ↓              │  TEXT     │         ↓              │
│ Silero VAD             │ ────────▶ │ Piper TTS              │
│ finds the pause        │ 50–250 B  │         ↓              │
│         ↓              │           │ Speaker (max volume)   │
│ Omnilingual ASR 348 MB │           │                        │
└────────────────────────┘           └────────────────────────┘
        Bluetooth Classic (primary) / Wi-Fi Direct (fallback)
```

**Neither phone runs both heavy models.** The speaker pays the ASR cost (~710 MB resident),
the listener pays the TTS cost (~100 MB). That asymmetry is the core design decision — it is what
makes this fit on a low-end phone, and it is the direct answer to the Efficiency criterion.

---

## Source layout

```
app/src/main/java/com/bjp/itantra/
├── MainActivity.kt              push-to-talk, permissions, the pipeline
├── LanguageFilter.kt            restricts output to the ten required scripts
├── Message.kt                   packet format, delivery states, duplicate/expiry handling
├── speech/SpeechEngine.kt       VadEngine, AsrEngine, TtsEngine, Speaker
└── transport/
    ├── Transport.kt             the interface + runtime picker
    ├── BluetoothRfcommTransport.kt
    └── WifiDirectTransport.kt

app/src/main/java/com/k2fsa/sherpa/onnx/   ← vendored API (created by setup-project.sh)
app/src/main/jniLibs/arm64-v8a/            ← native .so (created by setup-project.sh)
app/src/main/assets/                       ← models (created by setup-project.sh)
```

---

## Build — it works

**This project compiles.** First successful build: 20 September 2026, `BUILD SUCCESSFUL in 19s`.

### Toolchain — installed at `~/android-dev/`

Homebrew is not installed on this machine, so everything was fetched directly (no sudo needed):

| Component | Location | Size |
|---|---|---|
| **JDK 17** (Temurin 17.0.20.1) | `~/android-dev/jdk17/Contents/Home` | 177 MB |
| **Android SDK** (platform 34, build-tools 34.0.0, platform-tools) | `~/android-dev/android-sdk` | 1.4 GB |
| **Gradle 8.2** | `~/android-dev/gradle-8.2` | 130 MB |
| cmdline-tools 13114758 | `~/android-dev/android-sdk/cmdline-tools/latest` | 137 MB |

**JDK 17 is required and is NOT the system JDK.** This machine has JDK 26 and JDK 11. AGP 8.1.4
works with neither. `build.sh` sets `JAVA_HOME` correctly, so you never have to think about it.

### Build

```bash
cd output/option_a_stt/android/iTantraApp
./setup-project.sh          # vendors API + native libs + models (already run; copies only)
./build.sh                  # defaults to assembleDebug

# -> app/build/outputs/apk/debug/app-debug.apk   (521 MB)
```

`build.sh` accepts any Gradle task: `./build.sh installDebug`, `./build.sh clean`, etc.

### Install on a phone

```bash
~/android-dev/android-sdk/platform-tools/adb install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

Or just copy the APK to the phone and tap it (enable "install unknown apps" first).

### Verified APK contents

```
package        com.bjp.itantra   v0.1.0
minSdk 23 · targetSdk 33 · compileSdk 34
permissions    RECORD_AUDIO · BLUETOOTH_CONNECT · BLUETOOTH_SCAN
               ACCESS_FINE_LOCATION · NEARBY_WIFI_DEVICES · MODIFY_AUDIO_SETTINGS

assets/sherpa-onnx-omnilingual-asr-.../model.int8.onnx   348.4 MB
assets/tts/hi_IN-pratham-medium.onnx                      60.6 MB
assets/tts/en_US-lessac-medium.onnx                       60.3 MB
assets/silero_vad.onnx                                     0.6 MB
lib/arm64-v8a/libonnxruntime.so                           21.2 MB
lib/arm64-v8a/libsherpa-onnx-jni.so                        3.4 MB
```

### Why 521 MB, and how to shrink it

| Item | Size | Can it go? |
|---|---|---|
| ASR model | 348 MB | **No** — it is the product |
| Two TTS voices | 121 MB | **Yes** — ship one per language, or download on demand |
| `libonnxruntime.so` | 21 MB | No |
| `espeak-ng-data` | 8 MB | Partly — it ships all ~100 languages' dictionaries; prune to the ones used |
| `classes.dex` | 9.6 MB | Some — this is a debug build with no minification |

A release build with one voice would land around **370 MB**. Still large, but the PS explicitly
targets low-power phones rather than small downloads, and sideloading has no store limit.

### The two compile errors that had to be fixed

Recorded so they don't get re-introduced:
1. `loadModels()` — the `if (isSpeaker)` branch returned `Unit`, making the whole expression infer
   `Any` instead of `Boolean`. Fixed by adding an explicit `true`.
2. `LanguageFilter.Result` has fields `send`, `text`, `fraction`, `removed` — there is no `verdict`
   field. The log line referenced one that did not exist.

### If you don't want to build at all

There is a **prebuilt reference APK** that already does mic → VAD → ASR on-device:

```
output/option_a_stt/android/sherpa-onnx-1.13.8-arm64-v8a-vad_asr-1600-omnilingual_asr_300M_ctc_int8.apk
```

297,969,404 bytes, verified. Install it to prove the model runs on a phone. It has no TTS and no
transport, but it answers the one question that matters most: *does the model run on Android at all?*
**It does** — confirmed on a 6 GB phone.

---

## What is verified vs written

| Component | Status |
|---|---|
| ASR accuracy, 10 languages, 200 clips each | ✅ measured (`SUMMARY_full_v2.json`) |
| VAD detects speech, ignores silence, RTF 0.004 | ✅ measured (`vad_test.py`) |
| Language filter, 21/21 test cases | ✅ tested (`language_filter.py`) |
| Packet format, payload 49–111 B raw | ✅ measured (`end_to_end.json`) |
| Model runs on a real Android phone | ✅ confirmed on a 6 GB device |
| **This app compiling** | ✅ **BUILDS** — 521 MB APK produced 20 Sep |
| **Bluetooth transport** | ✅ ran on two physical phones, 27 Sep 2026 (`VERIFICATION_STATUS.md`) |
| **Wi-Fi Direct transport** | ⬜ still unproven on hardware |
| **Two-phone demo** | ✅ 27 Sep 2026, two physical phones; the v1.0.0 build still needs its pass |
| **TTS on device** | ✅ 7 of 10 languages speak on device |

---

## Known gaps

**1. TTS voices for 3 languages are missing.** Piper ships Hindi, Marathi, Malayalam, Telugu,
Bengali and English. Gujarati is available via Mimic3. **Kannada, Tamil and Odia have no neural
voice** — only MMS-TTS (CC-BY-NC, non-commercial) or espeak-ng (robotic). See
`../MODEL_AND_TTS_FINDINGS.md`. `TtsEngine.voiceFor()` returns `null` for those three.

**2. Bluetooth pairing is manual.** The phones must be paired in Android Settings before the app
can connect. That is deliberate — pairing is what provides sender authentication for emergency
alerts — but it means the demo needs a one-time setup step.

**3. `numThreads` is set to 2 and untuned.** More threads finish sooner but heat the SoC. Tune on
the actual demo device.

**4. No foreground service.** A long session may be killed by the OS. Fine for a demo, not for
field use.

---

## The things that will bite you

These are the mistakes this project is written to avoid. They are all silent failures.

**Android 12+ ignores the old Bluetooth permissions.** You need `BLUETOOTH_CONNECT` and
`BLUETOOTH_SCAN`. Declaring only `BLUETOOTH` produces no crash, no dialog, no connection.

**Wi-Fi Direct needs `ACCESS_FINE_LOCATION` to discover peers** on Android 10+. Documented, and
frequently missed.

**RFCOMM is a stream, not a message bus.** Two packets sent close together arrive glued into one
unless you frame them. This project length-prefixes every packet with a 4-byte big-endian length.

**The ASR model has no language parameter.** It is a 1,600-language character-level CTC model and
will emit Chinese or Arabic on noisy audio. `LanguageFilter` is not optional.

**JDK 26 will not build this.** Install JDK 17.
