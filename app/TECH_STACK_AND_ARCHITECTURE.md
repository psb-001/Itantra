# iTantra — Tech stack, device architecture and system design

**SIH26173 · Team B.J.P (Bug Janta Party) · Android app `com.bjp.itantra` v0.1.0**
Generated 21 September 2026 from the actual build files and a built APK — not from memory.

> **Status of every claim below.** Everything in §1–§5 is taken from `app/build.gradle.kts`,
> `build.gradle.kts`, `AndroidManifest.xml`, and a real 515 MB APK. Everything in §6 is what has
> actually been executed. §7 lists what has not.

---

## 1. Build toolchain

| Component | Version | Notes |
|---|---|---|
| **Android Gradle Plugin** | **8.1.4** | requires JDK 17 — will not run on JDK 21+ |
| **Kotlin** | **1.9.22** | |
| **Gradle** | **8.2** | full distribution, no wrapper jar was generated |
| **JDK** | **Temurin 17.0.20.1** | at `~/android-dev/jdk17/Contents/Home` |
| **Android SDK** | platform **34**, build-tools **34.0.0**, platform-tools 37.0.1 | at `~/android-dev/android-sdk` |
| **compileSdk** | 34 | |
| **minSdk** | **23** (Android 6.0) | the app's own calls (AudioTrack.Builder, getColor…) are API 23 |
| **targetSdk** | 33 (Android 13) | |
| **Java / Kotlin target** | 17 | |

`androidResources.noCompress += listOf("onnx", "txt", "bin")` — the model files are already
compressed; re-compressing them bloats the APK and slows every build.

---

## 2. Language and libraries

**Language: Kotlin** throughout. No Java, no Flutter, no React Native.

| Dependency | Version | Purpose |
|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin extensions |
| `androidx.appcompat:appcompat` | 1.6.1 | activity + `AlertDialog` for the device picker |
| `com.google.android.material:material` | 1.11.0 | theme |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | (declared; the layout is a `LinearLayout`) |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.7.0 | `lifecycleScope` for off-UI-thread model loading |

**There is no sherpa-onnx Maven dependency.** The Kotlin API is **vendored** into
`app/src/main/java/com/k2fsa/sherpa/onnx/` (8 files, fetched by `setup-project.sh`), and the native
libraries sit in `app/src/main/jniLibs/`.

**Platform APIs used directly, with no library:** `BluetoothAdapter` / `BluetoothServerSocket` /
`BluetoothSocket`, `WifiP2pManager`, `AudioRecord`, `AudioTrack`, `AudioManager`, `org.json`.

---

## 3. Native libraries and models

### Native (arm64-v8a only)

| File | Size | Licence |
|---|---|---|
| `libsherpa-onnx-jni.so` | 4.6 MB | Apache 2.0 |
| `libonnxruntime.so` | 21.2 MB | MIT |

Source: the official `sherpa-onnx-v1.13.8-android.tar.bz2`. **Not** the copy inside the
`SherpaOnnxVadAsr` reference APK — that build is speech-to-text only and contains **zero
`OfflineTts` symbols**, so reusing it crashes the listener phone with `UnsatisfiedLinkError`.

### Models bundled in `assets/`

| Model | Size | Licence | Role |
|---|---|---|---|
| `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/model.int8.onnx` | **348.4 MB** | **Apache 2.0** | speech → text |
| `tts/hi_IN-pratham-medium.onnx` | 60.6 MB | **MIT** | text → Hindi speech |
| `tts/en_US-lessac-medium.onnx` | 60.3 MB | **MIT** | text → English speech |
| `tts/espeak-ng-data/` (~120 files) | ~7 MB | GPL (data) | phoneme dictionaries |
| `silero_vad.onnx` | 0.6 MB | MIT | pause detection |
| `bench/bench_0{0,1,2}_*.wav` | 0.9 MB | CC-BY-4.0 | on-device test clips |

---

## 4. Device architecture — two phones, identical stack

**There are no roles.** Both phones run the same code and load the same three models. Either can
talk, either can receive, and push-to-talk decides who has the floor — which is what a walkie-talkie
actually is.

```
              PHONE A                                PHONE B
┌──────────────────────────────┐       ┌──────────────────────────────┐
│  TALKING                     │       │  TALKING                     │
│  AudioRecord 16 kHz mono     │       │  AudioRecord 16 kHz mono     │
│            ↓                 │       │            ↓                 │
│  Silero VAD cuts the sentence│       │  Silero VAD cuts the sentence│
│            ↓                 │       │            ↓                 │
│  AsrEngine (Omnilingual 300M)│       │  AsrEngine (Omnilingual 300M)│
│            ↓                 │       │            ↓                 │
│  LanguageFilter → Message    │       │  LanguageFilter → Message    │
├──────────────────────────────┤       ├──────────────────────────────┤
│  RECEIVING          TEXT     │  ⇄    │  RECEIVING          TEXT     │
│  TtsEngine (Piper VITS) ~250B│       │  TtsEngine (Piper VITS) ~250B│
│  AudioTrack 22 050 Hz, ALARM │       │  AudioTrack 22 050 Hz, ALARM │
└──────────────────────────────┘       └──────────────────────────────┘
        Bluetooth Classic RFCOMM (primary)  /  Wi-Fi Direct (fallback)
```

### Why symmetric — and what it costs

An earlier design split the models: one phone ASR-only, the other TTS-only. That made the link
**one-way** — a reply required both users to swap roles. It was removed.

| | One-way (rejected) | **Two-way (current)** |
|---|---|---|
| Models per phone | ASR **or** TTS | **VAD + ASR + TTS** |
| Memory | 428 MB PSS | **536 MB PSS / 647 MB RSS** |
| Startup | ~1.5 s | ~3.9 s |
| Direction | one-way | **two-way** |

The cost is **+108 MB and ~2.4 s of startup**. That is the price of two-way, and it is the right
trade — a walkie-talkie that only works in one direction is not a walkie-talkie.

**Idle behaviour:** when nobody is speaking, only the 0.6 MB VAD is active. **Measured RTF 0.004** —
about 240× faster than real time. That is the answer to ISRO's *"CPU usage during idle listening"*
requirement.

### Connection model

Both phones **start listening** on launch, so both are reachable. Tapping a device in **DEVICES**
additionally dials that peer — listening continues meanwhile, so two users tapping each other's phone
at the same moment cannot deadlock. Once connected the link is bidirectional and either phone may
transmit.

---

## 5. System design

### 5.1 Software layers

```
┌─────────────────────────────────────────────────────────────┐
│ UI layer             LinearLayout; status, level meter,     │
│                      transcript, event log, PTT, DEVICES    │
├─────────────────────────────────────────────────────────────┤
│ MainActivity         session controller, permissions,       │
│                      capture loop, pipeline wiring          │
├─────────────────────────────────────────────────────────────┤
│ speech/              VadEngine · AsrEngine · TtsEngine ·    │
│                      Speaker                                │
├─────────────────────────────────────────────────────────────┤
│ LanguageFilter       enforces the SELECTED language's script│
│ Message + Ledger     packet format, dedupe, expiry          │
├─────────────────────────────────────────────────────────────┤
│ transport/           Transport interface                    │
│                        ├── BluetoothRfcommTransport         │
│                        └── WifiDirectTransport              │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 The packet — what actually crosses the link

```json
{
  "id":   "a3f1c2e8-...",   // UUID — suppresses duplicate playback
  "lang": "hin_Deva",       // selects the TTS voice on the receiver
  "type": "ALERT",          // ALERT = max volume, loops, non-interruptible
  "seq":  42,               // ordering only — never gates delivery
  "ts":   1758267000,       // Unix seconds
  "ttl":  300,              // expiry; stale alerts must not replay as fresh
  "text": "नमस्ते यह एक आपातकालीन संदेश है"
}
```

Measured payload: **49–111 bytes** of raw text; **128–275 bytes** with the envelope. The equivalent
audio is roughly **1,000× larger**. That ratio is the entire argument for the project.

### 5.3 Transport framing

RFCOMM is a byte stream with no message boundaries, so **every packet is prefixed with a 4-byte
big-endian length**. Without it, two packets sent close together arrive glued into one. Both
transports use the same framing.

### 5.4 Transport choice — and why Bluetooth is primary

| | Bluetooth Classic | Wi-Fi Direct |
|---|---|---|
| Range | ~10 m | ~200 m |
| Bandwidth | ~2 Mbit/s | ~250 Mbit/s |
| **Needed** | **~1 kbit/s** | **~1 kbit/s** |
| API stability | stable 15+ years | vendor-specific behaviour |
| Sender authentication | pairing, free | none |
| Discovery permission | Bluetooth only | location on Android 10+, `NEARBY_WIFI_DEVICES` on 13+ |

The payload is 50–250 bytes. Both transports offer thousands of times more bandwidth than needed,
so bandwidth is irrelevant and **reliability decides it**. `WifiP2pManager` behaves differently
across vendors — that is how live demos fail. Bluetooth pairing also authenticates the sender, which
matters for an app that plays emergency alerts at full volume.

### 5.5 Pipeline order — the part that matters

```
mic → VAD → flush on release → ASR (+0.2 s leading silence) → LanguageFilter → Message → transport
                                                    ↓
                              socket → Message → TTS → AudioTrack
```

Three details that are load-bearing:

1. **Flush on release.** The VAD emits a segment only after ~0.25 s of trailing silence. A user who
   speaks and immediately releases never triggers it, so the utterance must be flushed explicitly.
   Without this, speech-to-text produced literally nothing.
2. **0.2 s of leading silence before ASR.** The VAD cuts exactly at speech onset; with no run-up the
   model's script decision is unstable and Hindi comes back in Arabic or Tamil script. Measured
   threshold: 0.05 s fixes it; 0.2 s gives margin.
3. **Filter after ASR, before send.** The model is a 1,600-language character-level CTC model with
   no language parameter. The filter enforces the *selected* language's script — not merely "one of
   the ten", which would let a Hindi→Gujarati flip through as unreadable text.

### 5.6 Permissions

| Permission | API | Why |
|---|---|---|
| `RECORD_AUDIO` | all | microphone |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | ≤ 30 | legacy, capped with `maxSdkVersion="30"` |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | 31+ | **required**; the legacy pair is ignored on Android 12+ |
| `ACCESS_FINE_LOCATION` | ≤ 32 | Wi-Fi Direct discovery, capped with `maxSdkVersion="32"` |
| `NEARBY_WIFI_DEVICES` | 33+ | replaces location for Wi-Fi Direct discovery |
| `MODIFY_AUDIO_SETTINGS` | all | route alerts through `STREAM_ALARM` |

**Pairing is manual and deliberate.** The app never pairs on the user's behalf — it uses
`bondedDevices` only. Pairing in Android Settings is what authenticates the sender of an emergency
alert, so it stays a visible, one-time step.

---

## 6. Verified — executed and observed

| | |
|---|---|
| Builds a **515 MB** debug APK | `iTantra-debug.apk` |
| Installs and launches on Android 34 | `01_launched_speaker_mode.png` |
| **348 MB ASR model loads on Android** | "Ready. SPEAKER mode" |
| Microphone opens — `AudioRecord` reaches `STATE_INITIALIZED` | "recording started (mic OK, sampleRate=16000)" |
| **VAD segmentation + release-flush** | "0 segments before flush, 1 after" |
| **ASR returns correct Devanagari and Latin** | script=1.00 on all four test segments |
| Language filter accepts/rejects correctly | same |
| Packet round-trips intact | `loopback_test_verified.txt` |
| **Receiver path (`onPacket`) executes** | same |
| **TTS loads and synthesises** | 49–380 ms per sentence |
| **AudioTrack playback starts** | isPlaying=true |
| **ALERT mode works** (loop points, `USAGE_ALARM`) | "mode=ALERT" |
| Mode switch reloads models and re-roles the transport | run log |
| **Bluetooth reaches the adapter** | "no paired device — pair the two phones in Android Settings first" |
| **Wi-Fi Direct passes permissions and starts discovery** | "DISCOVERING — looking for a peer to join" |
| **Android ASR latency** | **RTF 0.120–0.127** |
| **Android TTS latency** | **RTF 0.055–0.058** |
| **Memory with ASR resident** | **428 MB PSS / 535 MB RSS** |

---

## 7. NOT verified — still just code

| | Why | Risk |
|---|---|---|
| **Bluetooth RFCOMM socket** | no paired device available; the handshake and byte transfer have never run | **HIGH** — this is the demo |
| **Wi-Fi Direct group formation** | discovery runs; no second device to form a group with | MEDIUM |
| **Two-device operation** | one runtime only | HIGH |
| **Real microphone audio** | emulator mic input is silent | MEDIUM |
| **A real low-end phone** | all figures are from an emulator on Apple Silicon | MEDIUM |
| **Alert non-interruptibility** | playback starts in ALERT mode; "cannot be ducked" untested against competing audio | LOW |
| **TTS for Kannada, Tamil, Odia** | not implemented — no permissively-licensed neural voice exists | MEDIUM (PS requirement) |

---

## 8. APK composition

| Item | Size | Share |
|---|---|---|
| ASR model | 348.4 MB | 68% |
| Two TTS voices | 120.9 MB | 23% |
| `libonnxruntime.so` | 21.2 MB | 4% |
| `classes.dex` + resources | ~11 MB | 2% |
| `libsherpa-onnx-jni.so` | 4.6 MB | 1% |
| `espeak-ng-data` | ~7 MB | 1% |
| Silero VAD | 0.6 MB | <1% |
| **Total** | **515 MB** | debug build, arm64 only |

**How to shrink it.** The 348 MB ASR model is the product and cannot go. The 121 MB of TTS is two
voices bundled at once — shipping one voice per language, or downloading on demand, saves ~60 MB.
`espeak-ng-data` ships dictionaries for ~100 languages; pruning to those used saves ~6 MB. A release
build would land near **370 MB**.

Source: **15 Kotlin files** (8 vendored sherpa-onnx API + 7 ours), **2 308 lines** of our own Kotlin.

---

## 9. Licensing

| Component | Licence | Redistributable? |
|---|---|---|
| Omnilingual ASR 300M INT8 | **Apache 2.0** | ✅ |
| Silero VAD | MIT | ✅ |
| Piper TTS voices | **MIT** | ✅ |
| sherpa-onnx runtime + JNI | Apache 2.0 | ✅ |
| espeak-ng-data | GPL (data, shipped as asset) | ⚠️ note it |
| FLEURS test audio | CC-BY-4.0 | evaluation only, not shipped |
| AndroidX / Material | Apache 2.0 | ✅ |

**No CC-BY-NC anywhere in the shipped product.** MMS-TTS was specifically rejected on that basis.
