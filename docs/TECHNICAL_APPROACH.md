# SIH26173 — iTantra: Technical Approach (EXECUTED EVIDENCE)
**Team B.J.P (Bug Janta Party)** | Problem: ISRO *iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver*
All numbers below are MEASURED on this machine (macOS arm64, Apple Silicon, 17 GB RAM), 18 Sep 2026.
**This is not a phone.** Phone numbers will be worse. Every figure labelled MEASURED vs TARGET.

---

## 0. THE PROBLEM, RESTATED AS ENGINEERING

ISRO wants a **voice transceiver for low-bitrate links**: you speak on phone A, the app converts
speech→text (tiny payload), sends the *text* over Wi-Fi/Bluetooth instead of audio, and phone B
converts text→speech. Because text is ~1000× smaller than audio, it fits on links that cannot
carry voice.

The two-phone loop: **A speaks → STT → text → radio → text → TTS → B hears.**

---

## 1. WHAT WE ACTUALLY RAN (the evidence)

### 1.1 ASR — Meta Omnilingual ASR CTC 300M INT8 via sherpa-onnx
| Property | Value | Status |
|---|---|---|
| Model file | `model.int8.onnx` | MEASURED |
| Size | 365,352,120 B = **348 MiB** | MEASURED |
| License | **Apache 2.0** (read from LICENSE) | VERIFIED |
| Load time | 0.528 s | MEASURED |
| Peak RAM (with TTS) | ~1,096 MiB | MEASURED |
| RTF on this CPU | **0.036–0.043** (~25× real time) | MEASURED |
| Languages | 1,668 IDs incl. `hin_Deva`, `eng_Latn` + 8 more Indian | VERIFIED |

### 1.2 Accuracy on REAL human speech (FLEURS test set, 6 clips/lang)
| Language | WER | CER |
|---|---|---|
| Hindi | 0.444 | 0.239 |
| Odia | 0.402 | 0.083 |
| Bengali | 0.313 | 0.089 |
| Marathi | **0.744** | 0.229 |
| English (sherpa wavs) | ~0 | ~0 |

**Read CER, not WER.** In Devanagari/Bengali script, one phoneme error corrupts a whole "word"
for WER but only a few characters for CER. CER 0.08–0.24 = text a reader understands.

### 1.3 TTS — Piper VITS voices via sherpa-onnx
| Voice | Size | License | Load | RTF |
|---|---|---|---|---|
| `hi_IN` pratham (Hindi) | 60.6 MiB | **MIT** | 0.64 s | **0.036** |
| `en_US` lessac (English) | 60.3 MiB | **MIT** | 0.53 s | **0.035** |
| espeak-ng-data (phonemes) | 7.2 MiB | GPL (data) | — | — |

Both licensed for any use. **No CC-BY-NC, no commercial risk.**

### 1.4 END-TO-END LOOP (the walkie-talkie round trip)
| Lang | Utterance | ASR | TTS-B | Compute total | Payload | vs audio |
|---|---|---|---|---|---|---|
| Hindi | नमस्ते, यह एक आपातकालीन संदेश है। | 0.20 s | 0.10 s | **0.42 s** | **83 B** | **1,128× smaller** |
| Hindi | तुम्हें तुरंत सुरक्षित स्थान पर जाना होगा। | 0.11 s | 0.09 s | **0.29 s** | **111 B** | **720× smaller** |
| English | Emergency alert. Move to a safe location... | 0.14 s | 0.11 s | **0.38 s** | **52 B** | **2,201× smaller** |
| English | This is a test of the emergency broadcast... | 0.11 s | 0.09 s | **0.29 s** | **49 B** | **1,789× smaller** |

**~0.3 s total compute, ~1000× bandwidth reduction.** Hindi round-trips cleanly.

---

## 2. SYSTEM ARCHITECTURE

```
PHONE A (speaker)                          PHONE B (listener)
┌──────────────────────────┐               ┌──────────────────────────┐
│ Mic → AudioRecord 16 kHz │               │  Text in (socket)        │
│      ↓                   │               │      ↓                   │
│ Silero VAD (segment)     │   TEXT over   │ Piper TTS (hi / en)      │
│      ↓                   │  Wi-Fi-Direct │      ↓                   │
│ Omnilingual ASR 300M INT8│  or BLE       │ AudioTrack 22050 Hz      │
│      ↓                   │  (UTF-8 bytes)│      ↓                   │
│ text + lang tag          │ ─────────────▶│ loud, non-interruptible  │
└──────────────────────────┘               └──────────────────────────┘
```

**Both phones run the identical stack.** Either can talk, either can receive; push-to-talk
decides who has the floor. Speech is recognised on the sending phone and only the text crosses
the link, which is what keeps the payload at 50-250 bytes instead of streaming audio.
Neither phone runs both models at once (except in phone-mode), which halves the per-device load.

---

## 3. COMPONENT-BY-COMPONENT BUILD PLAN

### 3.1 VAD (pause detection) — Silero VAD
- `silero_vad.int8.onnx` = **208 KB**, 16 kHz, exported by k2-fsa. MEASURED size.
- **This is what implements "detecting pauses and stoppages should form the sentences".**
- sherpa `Vad` class: `acceptWaveform()` → `empty()`/`front()`/`pop()`. Segments on endpoint.
- Already integrated in the official `SherpaOnnxVadAsr` Android app (the reference app).

### 3.2 ASR — Omnilingual 300M INT8
- **Pre-built Android APK exists**: `sherpa-onnx-1.13.8-arm64-v8a-vad_asr-1600-omnilingual_asr_300M_ctc_int8.apk`
- Size **297,969,404 B = 284 MiB**, direct download verified. MEASURED.
- This APK bundles VAD + our exact model. We fork `SherpaOnnxVadAsr` as the starting point.

### 3.3 TTS — Piper (Hindi + English)
- We must **convert** Piper voices with the official script (add ONNX metadata + tokens.txt).
- Done here; both voices load and synthesise. REPRODUCED.
- espeak-ng-data (7.2 MB) must ship in app assets — it holds the hi/en phoneme dictionaries.

### 3.4 Transport — Wi-Fi Direct (primary) / Bluetooth (fallback)
- Wi-Fi Direct: true P2P, no router, no internet. `WifiP2pManager` discoverPeers→connect.
- One device becomes group owner; open a plain `ServerSocket` / `Socket`; stream UTF-8.
- Bluetooth Classic (RFCOMM) as fallback; sufficient for ~100 B/s of text.
- **Nearby Connections** (Google) also works fully offline but needs Play Services — avoid for
  low/mid-range phones without GMS.

### 3.5 Alert mode — "highest volume, non-interruptible"
- `AudioManager` → set `STREAM_MUSIC`/`STREAM_ALARM` to max, request `AudioFocus` with
  `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, route to speaker, loop until acknowledged.

---

## 4. THE 3-LANGUAGE SCOPE — AND THE HINGLISH PROBLEM

You asked for **Hindi, English, Hinglish**. Here is the honest situation.

**Hinglish is not a language the ASR model knows.** I checked all 1,668 language IDs:
`hin_Deva` and `eng_Latn` exist; **`hin_Latn` (romanised Hindi) does not. Zero romanised
Indian languages are supported.**

This does not break the plan, but it changes it. Three honest options:

1. **Scope to Hindi + English (recommended for SIH submission).** Both are fully covered by
   the ASR model AND by Piper TTS. We have measured end-to-end loops for both. This is real.
2. **Hinglish as a UI convenience only.** Accept romanised Hindi *input text* on phone B for
   TTS — espeak can phonemise romanised Hindi reasonably — but never claim ASR outputs it.
3. **Hinglish ASR as R&D, clearly labelled.** Omnilingual may partially transcribe romanised
   Hindi since it saw some romanised data in training, but **this is untested and must not be
   presented as a feature.**

**Do not promise 10 languages.** We measured 4; Marathi is bad (WER 0.74); the other 6 are
untested. The submission should say: *"3-language demonstrator (Hindi, English, +1), with a
model proven to cover all 10; per-language accuracy reported honestly."*

---

## 5. METRICS MAPPED TO ISRO'S EVALUATION (20/40/20)

**Efficiency 20%**
- ASR model 348 MiB + TTS 2×~60 MiB + VAD 0.2 MiB + espeak data 7 MiB ≈ **~475 MiB of models**
- Official APK arm64 = 284 MiB (that's the ASR+VAD app alone) → MEASURED
- Peak RAM ~1.1 GiB with all models loaded on Mac — **must be measured on a real phone**
- Idle CPU = VAD-only listening (ASR/TTS models are NOT loaded until a segment fires) → this is
  a genuine design advantage: **idle listening costs almost nothing**

**Accuracy 40%**
- Hindi CER 0.24, Odia CER 0.08, Bengali CER 0.09, English ~0 on real speech
- TTS legibility: human listening test required (we cannot score this automatically)

**Latency 20%**
- ASR 0.11–0.20 s, TTS 0.09–0.11 s → compute ~0.3 s per utterance (MEASURED, Mac)
- + VAD endpointing delay (~0.3–0.5 s of trailing silence, configurable)
- + radio transport (~10 ms on Wi-Fi Direct)
- **mouth-to-ear target: under 1 s** (TARGET, not yet measured end-to-end on phone)

---

## 6. WHAT IS STILL NOT PROVEN (labelled honestly)

1. **Android.** Everything above is a Mac. The 284 MiB APK runs on arm64 phones, but we have not
   measured RTF/RAM on a low/mid-range device. **This is the #1 risk.**
2. **Human speech in noisy conditions.** FLEURS is clean studio speech; a disaster zone is not.
3. **TTS legibility score.** Requires a human listening panel.
4. **Wi-Fi Direct reliability** across Android OEMs (each vendor implements P2P differently).
5. **Marathi and 6 other languages** — untested or weak.

---

## 7. BUILD ORDER (what to do first)

1. Fork `k2-fsa/sherpa-onnx/android/SherpaOnnxVadAsr` — it already does mic→VAD→ASR→text.
2. Add the two Piper TTS voices + espeak-ng-data to assets; wire a TTS screen.
3. Add Wi-Fi Direct socket transport; get text A→B.
4. Push-to-talk: A holds button = STT mode; B = TTS mode; release sends.
5. Alert mode: max volume, non-interruptible playback on B.
6. Then benchmark on the cheapest phone you can find — that's the real test.

**Files:** `end_to_end.py`, `tts_bench.py`, `fleurs_eval.py`, `asr_run.py`,
`tts/convert_piper.py`, `VadAsr_reference.kt`, `web_scan*.json`.
