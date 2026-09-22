# SIH26173 — iTantra: Architecture and System Design

**Team B.J.P (Bug Janta Party)** · Prepared 19 September 2026
Status labels: **[BUILT]** = exists and tested · **[DESIGNED]** = specified, not written · **[MISSING]** = not started

---

## 1. The one-sentence system design

Two phones, one app. Phone A turns speech into text and sends only the text. Phone B turns that text
back into speech. **Neither phone runs both heavy models at once.**

---

## 2. System design — who runs what

**Both phones run the identical stack.** There are no roles. Either phone can talk, either can
receive, and push-to-talk decides who has the floor at any moment — which is what a walkie-talkie is.

```
              PHONE A                                PHONE B
┌──────────────────────────────┐       ┌──────────────────────────────┐
│  TALKING                     │       │  TALKING                     │
│  Microphone 16 kHz mono      │       │  Microphone 16 kHz mono      │
│            ↓                 │       │            ↓                 │
│  Silero VAD cuts the sentence│       │  Silero VAD cuts the sentence│
│            ↓                 │       │            ↓                 │
│  Omnilingual ASR (348 MB)    │       │  Omnilingual ASR (348 MB)    │
│            ↓                 │       │            ↓                 │
│  Language filter → packet    │       │  Language filter → packet    │
├──────────────────────────────┤       ├──────────────────────────────┤
│  RECEIVING          TEXT     │  ⇄    │  RECEIVING          TEXT     │
│  Piper TTS (60 MB)  ~50–250  │       │  Piper TTS (60 MB)  ~50–250  │
│  Speaker, max volume, looped │       │  Speaker, max volume, looped │
└──────────────────────────────┘       └──────────────────────────────┘
        Bluetooth RFCOMM (primary)  /  Wi-Fi Direct (fallback)
```

### Why symmetric, and what it costs

An earlier design split the models — one phone speech-to-text, the other text-to-speech. That made
the link **one-way**: a reply required both users to swap roles, which is not how a walkie-talkie
works. It was removed.

| | One-way (rejected) | **Two-way (current)** |
|---|---|---|
| Models per phone | ASR **or** TTS | **VAD + ASR + TTS** |
| Memory | ~430 MB | **536 MB PSS / 647 MB RSS** |
| Startup | ~1.5 s | ~3.9 s |
| Direction | one-way | **two-way** |

The cost is real: **+106 MB and ~2.4 s of startup**. That is the price of two-way, and it is the
right trade — a walkie-talkie that only works in one direction is not a walkie-talkie.

**Idle behaviour:** when nobody is speaking, only the 0.6 MB VAD is active. Its measured cost is
**RTF 0.004** — about 240× faster than real time. That is the answer to ISRO's *"CPU usage during
idle listening"* requirement.

---

## 3. Software architecture — the layers

```
┌─────────────────────────────────────────────────────────────┐
│ UI layer                                                    │
│ Push-to-talk button · transcript view · mode + language     │
├─────────────────────────────────────────────────────────────┤
│ Session controller                                          │
│ The state machine. Owns which mode the app is in.           │
├─────────────────────────────────────────────────────────────┤
│ Speech engines                                              │
│ VadEngine (always) · AsrEngine (speaker) · TtsEngine (list.)│
├─────────────────────────────────────────────────────────────┤
│ Message layer                                               │
│ Packet build/parse · dedupe by ID · queue · expiry policy   │
├─────────────────────────────────────────────────────────────┤
│ Transport interface  ◀── one contract, two implementations  │
│   ├── BluetoothRfcommTransport                              │
│   └── WifiDirectTransport                                   │
└─────────────────────────────────────────────────────────────┘
```

**Why the transport is an interface:** the PS allows either Wi-Fi or Bluetooth. Making it swappable
means we can build Bluetooth first (reliable, stable API) and add Wi-Fi Direct later without touching
the speech code. It also lets us claim "Wi-Fi Direct with Bluetooth fallback" honestly.

---

## 4. The packet — what actually crosses the link

This is the entire point of the project: **send text, not audio.**

```json
{
  "id":   "a3f1c2e8-...",     // UUID — used to suppress duplicate playback
  "lang": "hin_Deva",         // selects the TTS voice on the receiving phone
  "type": "alert",            // "alert" = max volume, non-interruptible; "msg" = normal
  "seq":  42,                 // ordering
  "ts":   1758267000,         // creation time (Unix seconds)
  "ttl":  300,                // expiry — stale alerts must not replay as fresh
  "text": "नमस्ते यह एक आपातकालीन संदेश है"
}
```

**Measured size:** the raw text is **49–111 bytes** (4 test utterances, `end_to_end.json`). With the
JSON envelope the packet lands around **150–250 bytes**.

**Comparison:** the equivalent audio is roughly **1,000× larger**. That ratio is the whole argument.

**Note on the envelope:** JSON is used for clarity during development. It is not the minimum possible
size — a compact binary encoding would be smaller. For a ~200-byte packet on a link that carries
kilobytes per second, this is not a bottleneck, and readability during debugging is worth more.

---

## 5. Modes and state machine

### States

```
IDLE ──hold PTT──▶ RECORDING ──pause detected──▶ TRANSCRIBING ──▶ SENDING ──▶ IDLE
                                                                              │
IDLE ◀──playback done── SPEAKING ◀──text received ◀── RECEIVING ◀─────────────┘
```

### Three operating modes

| Mode | Behaviour |
|---|---|
| **Push-to-talk** | Hold the button: mic + VAD active, sentence segments, ASR runs, text sends. Release returns to idle. |
| **Phone mode** | When PTT is not held, the app behaves as a normal phone. This is the PS requirement *"if turned off, it should work like a phone."* |
| **Alert mode** | Received `type: "alert"` messages play at maximum volume, loop until explicitly acknowledged, and cannot be ducked or interrupted. |

### Message lifecycle — keep these distinct

```
Recorded → Recognised → Queued → Transmitted → Received → Played → Acknowledged
```

**"Received" is not the same as "a human heard it."** A packet checksum proves the bytes arrived; it
does not prove comprehension. The UI must never collapse these into one tick mark.

---

## 6. Transport choice — Bluetooth vs Wi-Fi Direct

**Recommendation: Bluetooth Classic (RFCOMM) primary, Wi-Fi Direct secondary.**

| | Bluetooth Classic | Wi-Fi Direct |
|---|---|---|
| Range | ~10 m | ~200 m line of sight |
| Bandwidth | ~2 Mbit/s | ~250 Mbit/s |
| **Needed** | **~1 kbit/s** | **~1 kbit/s** |
| API stability | Stable 15+ years | Vendor-specific behaviour |
| Sender authentication | Pairing gives it free | No equivalent |
| Permissions | Bluetooth only | Location required to scan (Android 10+) |

**Reasoning:** the payload is 50–250 bytes. We need roughly 1 kbit/s. Bluetooth gives us 2,000×
headroom, so Wi-Fi Direct's bandwidth advantage is worthless here. That reduces the decision to
reliability — and `WifiP2pManager` behaves differently across Android vendors, which is how live demos
fail. Bluetooth pairing also provides sender authentication, which matters for an app that plays
emergency alerts: you do not want an unpaired device injecting fake ones.

---

## 7. Build status — what exists and what does not

### Built and tested (on macOS, not a phone)
| Component | Evidence |
|---|---|
| Omnilingual ASR, 10 languages | `SUMMARY_full.json` — 2,000 clips, CER 0.052–0.149 |
| Piper TTS (Hindi, English) | `tts_result.json`, `tts_intelligibility.json` |
| Full text→speech→text loop | `end_to_end.json` — 49–111 B payloads |
| Offline proof | `prove_local_run.py` — reproduces stored results live |
| **Silero VAD (pause detection)** | `vad_test.py` — 0 segments on silence, 1–3 on real speech, **RTF 0.004** |
| **Model runs on a real Android phone** | confirmed on a 6 GB device with the prebuilt APK |
| **Language filter** | `language_filter.py` — 21/21 cases; `android/.../LanguageFilter.kt` |
| **Android ASR latency** | `android/evidence/benchmark_onddevice.txt` — **RTF 0.120–0.127** |
| **Android TTS latency** | same — **RTF 0.055–0.058** |
| **Android memory** | `dumpsys meminfo` — **428 MB PSS / 535 MB RSS** with the ASR model |

### Android — BUILDS, INSTALLS AND RUNS. Measured.
Project: `android/iTantraApp/`. Run `./setup-project.sh` then `./build.sh`.
Toolchain at `~/android-dev/` (JDK 17, Android SDK 34, Gradle 8.2). Emulator AVD `itantra34`.
**521 MB debug APK built, installed on an Android 34 runtime, launched, and the 348 MB model
loaded successfully.** Memory with the model resident: **428 MB PSS / 535 MB RSS**.
Screenshots in `android/evidence/`. The PTT capture path runs and stops cleanly.
**Phone-class CPU latency is still unmeasured** — that is the remaining gap.

| Component | Status |
|---|---|
| Android project, Gradle, manifest | **[WRITTEN]** |
| Microphone capture | **[WRITTEN]** |
| Speaker playback + alert mode | **[WRITTEN]** |
| Bluetooth RFCOMM transport | **[WRITTEN]** |
| Wi-Fi Direct transport | **[WRITTEN]** |
| Push-to-talk UI | **[WRITTEN]** |
| Language filter | **[WRITTEN + tested in Python]** |
| **Compiling** | ✅ **[BUILT]** — 521 MB APK, 20 Sep |
| **Running on an Android runtime** | ✅ **[RUNS]** — 428 MB PSS measured |
| **Phone-class CPU latency** | **[MISSING]** |
| **Two-phone test** | **[MISSING]** |

> **Prebuilt reference APK available.** `android/sherpa-onnx-1.13.8-arm64-v8a-vad_asr-1600-omnilingual_asr_300M_ctc_int8.apk`
> (297,969,404 bytes, verified) already bundles Silero VAD + our exact ASR model + the native arm64
> libraries. It is the fastest route to proving the model runs on a phone, and it is the reference
> to fork when building our own app.

### Known coverage gap — resolved 20 Sep
**7 of 10 languages have a neural voice** (Piper for Hindi, Marathi, Malayalam, Telugu, Bengali,
English; Mimic3 for Gujarati). **Kannada, Tamil and Odia do not** — the only options are MMS-TTS
(`cc-by-nc-4.0`) or espeak-ng (GPL, robotic). **Decision: ship espeak-ng and declare it.**
See `MODEL_AND_TTS_FINDINGS.md`.

---

## 8. What to put on slide 3

The template asks for **Technologies to be used** and **Methodology and process (flowchart)**.

- **Top half:** the two-phone diagram from §2 — this is your flowchart.
- **Bottom half:** the technology list — Kotlin, sherpa-onnx, Silero VAD, Omnilingual ASR 300M INT8,
  Piper VITS, Bluetooth RFCOMM / Wi-Fi Direct.
- **One line of measured evidence:** ASR RTF 0.038–0.042, full loop 0.29–0.42 s, payload 49–111 bytes.
- **One honest line:** Android implementation and two-phone testing are in progress.
