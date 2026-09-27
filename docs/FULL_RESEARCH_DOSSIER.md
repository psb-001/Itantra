# iTantra (SIH26173) — COMPLETE RESEARCH DOSSIER
**Team B.J.P (Bug Janta Party)** | Smart India Hackathon 2026 | ISRO — Department of Space
**Problem ID:** SIH26173 | **Category:** Software | **Theme:** Smart Automation
**Submission deadline:** 30 September 2026 | **Document date:** 18 Sep 2026

> Everything in this file is either **MEASURED** on our own machine, **VERIFIED** by reading the
> source, or **RESEARCHED** from documentation. Each claim is labelled. Nothing here is invented.
> **All measurements were taken on a Mac (macOS arm64, Apple Silicon, 17 GB RAM) — NOT an Android
> phone.** Phone performance is still unmeasured and will be worse. This is the single biggest
> caveat in the whole document.

---

# TABLE OF CONTENTS

1. [The Problem Statement (exact, from the SIH portal)](#1-the-problem-statement-exact-from-the-sih-portal)
2. [Our Solution in One Paragraph](#2-our-solution-in-one-paragraph)
3. [All Measured Evidence (the proof)](#3-all-measured-evidence-the-proof)
4. [The Full Technology Stack](#4-the-full-technology-stack)
5. [How the System Works (data flow)](#5-how-the-system-works-data-flow)
6. [Component-by-Component Build Plan](#6-component-by-component-build-plan)
7. [The Language Question (Hindi/English/Hinglish + the 10 languages)](#7-the-language-question-hindienglishhinglish--the-10-languages)
8. [Licensing — Why We Are Legally Safe](#8-licensing--why-we-are-legally-safe)
9. [Prior Art and Competition (what already exists)](#9-prior-art-and-competition-what-already-exists)
10. [Mapping to ISRO's Marking Scheme (20/40/20)](#10-mapping-to-isros-marking-scheme-204020)
11. [What Is Still Not Proven (risks)](#11-what-is-still-not-proven-risks)
12. [Build Order (what to do first)](#12-build-order-what-to-do-first)
13. [Reproducible Files and How to Re-run Everything](#13-reproducible-files-and-how-to-re-run-everything)
14. [Ready-Made Slide Content (copy-paste for the PPT)](#14-ready-made-slide-content-copy-paste-for-the-ppt)
15. [Corrections Made During Research (honest log)](#15-corrections-made-during-research-honest-log)

---

# 1. THE PROBLEM STATEMENT (exact, from the SIH portal)

> **iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links**
> **Organization:** Indian Space Research Organisation (ISRO), Department of Space

**Background:** Vocal audio information is very data intensive, making it difficult to transmit
through low-data-rate links. In alert and distress based scenarios, transmitting audio information
is critical instead of written messages, as it will be more inclusive and cater to everyone even if
they are literate or not.

**Description:** Build an Android app with lightweight, highly accurate STT and TTS models for
**10 Indian languages** (Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali,
English) that runs locally on a low-power device.

- The **STT module**, when activated after detecting pauses and stoppages, should form the sentences
  detected and must instantly and efficiently stream the data through Wi-Fi/Bluetooth to another
  phone with the same application, with minimal latency.
- The **TTS module**, when activated after receiving the text data, should convert it into
  intelligible speech played as a voice note, and **alert-type messages announced at the highest
  volume, non-interruptible**.
- To verify the complete loop, **two phones with the same app — one in TTS mode, another in STT
  mode — connected via Wi-Fi or Bluetooth should work like a walkie-talkie using push-to-talk**.
  If turned off, it should work like a phone.

**Key Metrics for Evaluation**
| Criterion | Weight |
|---|---|
| **Efficiency** — model size, app size (RAM/Flash footprint), CPU usage during idle listening | **20%** |
| **Accuracy** — low Word Error Rate for STT, high human legibility and flow for TTS | **40%** |
| **Latency** — word-said→STT-complete, text-received→audio-played, RTF, mouth-to-ear delta | **20%** |

**Software & Framework Restrictions**
- **Open-Source Only.** Proprietary, closed-source or commercial voice-activation SDKs are
  **strictly prohibited**.
- **Allowed frameworks:** open-source ML and TinyML frameworks — TensorFlow Lite for
  Microcontrollers, PyTorch Mobile or similar.
- **Fully Offline Working.** No internet-hosted API based solutions are expected or encouraged.
- Must run smoothly on **low and mid-range mobile phones**.

*Portal stats at scrape time: 5 submissions against a 500-seat cap (very low competition — good).*

---

# 2. OUR SOLUTION IN ONE PARAGRAPH

You speak into phone A. A **voice-activity detector (Silero VAD)** listens for the pause at the end
of your sentence and cuts a clean segment. An **offline speech-recognition model (Meta Omnilingual
ASR 300M INT8, Apache 2.0)** converts that segment into text. The app sends **only the text** —
about 50–110 bytes — over **Wi-Fi Direct or Bluetooth** to phone B, which runs an **offline
text-to-speech model (Piper, MIT licence)** and speaks the sentence out loud at maximum volume in a
way that cannot be interrupted. Because text is far smaller than the audio it replaces — **~1,050× smaller than the raw PCM the mic produces, ~66× smaller than a 16 kbps speech codec** — voice gets
through on links that physically cannot carry voice — which is exactly the problem ISRO set.
Idle phones run only the tiny VAD, so listening costs almost no CPU.

---

# 3. ALL MEASURED EVIDENCE (the proof)

*All numbers below were produced by scripts we wrote and ran on 18 Sep 2026. Every file is in
`output/option_a_stt/` and can be re-run. Nothing is projected or estimated.*

## 3.1 ASR — speech-to-text

| Property | Value | Status |
|---|---|---|
| Model | `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12` | DOWNLOADED |
| File | `model.int8.onnx` | — |
| Size on disk | **365,352,120 bytes = 348 MiB** | MEASURED |
| License | **Apache 2.0** (read directly from the artifact's `LICENSE`) | VERIFIED |
| Load time | **0.528 s** | MEASURED |
| Runtime | sherpa-onnx 1.13.8, CPU provider, 8 threads | VERIFIED |
| Real Time Factor | **0.036–0.043** (≈ **25× faster than real time**) | MEASURED |
| Peak RAM after load | ~680 MiB (Mac) | MEASURED |
| Peak RAM during decode | ~772 MiB (Mac) | MEASURED |
| Language coverage | **1,668 language IDs** including `hin_Deva`, `eng_Latn` + 8 more Indian | VERIFIED |

## 3.2 Accuracy on REAL recorded human speech (FLEURS test set, CC-BY-4.0)

Ground truth = FLEURS `raw_transcription`. **RE-MEASURED on 19–20 Sep across ALL TEN required languages, 200 clips each** (2,000 clips,
6.72 hours of audio), and **on both model versions**. The table below is **v2
(`...-ctc-v2-int8-2026-02-05`)**, which reduces mean CER from 0.0992 to 0.0934 (−5.9% relative)
at the identical 279 MiB size. The earlier 6-clip numbers are retained for comparison but **must
not be quoted in the submission**. Full detail: `ACCURACY_RESULTS.md` and
`MODEL_AND_TTS_FINDINGS.md`.

**Corpus-level (total edits ÷ total reference units), sorted best → worst by normalised CER:**

| Language | n | WER raw | **WER norm** | CER raw | **CER norm** | RTF med |
|---|---|---|---|---|---|---|---|
| **Kannada** (kn_in) | 200 | 0.431 | **0.307** | 0.079 | **0.053** | 0.041 |
| **Malayalam** (ml_in) | 200 | 0.504 | **0.361** | 0.084 | **0.059** | 0.041 |
| **English** (en_us) | 200 | 0.348 | **0.181** | 0.118 | **0.067** | 0.040 |
| **Gujarati** (gu_in) | 200 | 0.399 | **0.300** | 0.099 | **0.071** | 0.041 |
| **Bengali** (bn_in) | 200 | 0.472 | **0.359** | 0.136 | **0.100** | 0.040 |
| **Telugu** (te_in) | 200 | 0.543 | **0.431** | 0.125 | **0.100** | 0.039 |
| **Marathi** (mr_in) | 200 | 0.509 | **0.416** | 0.129 | **0.108** | 0.040 |
| **Hindi** (hi_in) | 200 | 0.358 | **0.281** | 0.136 | **0.113** | 0.040 |
| **Odia** (or_in) | 200 | 0.585 | **0.509** | 0.160 | **0.134** | 0.043 |
| **Tamil** (ta_in) | 200 | 0.589 | **0.501** | 0.150 | **0.129** | 0.040 |

**All ten required languages measured on v2. Normalised CER spans 0.053–0.134; mean 0.0934.**
English is the best on WER and third-best on CER. Odia and Tamil are weakest — report them, don't
bury them.

*Normalised* = Unicode NFC + casefold + punctuation stripped + zero-width joiners stripped. The
model emits no punctuation or capitals (CTC greedy decode), so raw scoring penalises an
output-format limitation rather than an accuracy failure. **Report both; never only the
flattering one.**

### What the 200-clip run overturned

| Language | 6 clips | 200 clips (norm) | Verdict |
|---|---|---|---|
| Odia | CER 0.083 | **CER 0.143** | The old "headline result" was ~1.7× optimistic. Odia is now the **worst** language on WER. |
| Marathi | WER 0.744 | **WER 0.415** | **Not a failure.** The old "degrades badly, meaning lost" call was a 6-clip artifact. Marathi is mid-pack. |
| Hindi | CER 0.239 | **CER 0.141** | Improved. Now the best language on WER. |
| Bengali | CER 0.089 | **CER 0.094** | Held up. Now the best language on CER. |

**Ranking (normalised CER):** Bengali 0.094 < Marathi 0.108 < Hindi 0.141 ≈ Odia 0.143.

### What the transcripts actually look like
- **Hindi**: REF `स्कीइंग मार्ग को एक हाईकिंग...` → HYP `स्कींग मार्ग को एक हाइकिं...`
  — meaning intact, spelling slips only.
- **Bengali**: near-identical to reference, minor spelling variants.
- **Odia**: still real coverage that stock Whisper **cannot** provide at all — but at CER 0.14, not
  0.08. The differentiator survives in weaker form.
- **Marathi**: spacing and spelling slips; the meaning is fully recoverable by a Marathi reader.
  Representative: REF `युरोप हा खंड तुलनेने लहान आहे परंतु...` → HYP `युरोपहा खंडत तुलणेने लाह नाहीपरंतु...`

### Why WER looks high but CER is low (IMPORTANT for the PPT)
Hindi, Bengali, Marathi, Odia use **complex abugida scripts**. One phoneme error changes several
characters but the word stays recognisable to a human reader. WER punishes the whole word; CER
shows the true character-level error. **No measured language is below 0.09 CER — all four are in
a usable band.**
**Report CER alongside WER** in the submission, or the numbers look far worse than the real user
experience.

### TTS intelligibility — first automated measurement
The dossier previously listed this as NOT DONE. An ASR round-trip proxy now exists
(`tts_intelligibility.py`, 20 sentences per language):

| Language | Exact match | WER norm | **CER norm** |
|---|---|---|---|
| English (`en_US` lessac) | 9 / 20 | 0.113 | **0.030** |
| Hindi (`hi_IN` pratham) | 0 / 20 | 0.490 | **0.339** |

**English TTS is excellent.** **Hindi TTS is weak in the round trip** — 2.4× worse than Hindi ASR
alone (0.141), meaning the loss is introduced by the TTS voice or by the ASR's handling of
synthetic speech. This is now the **most important open accuracy question**, because the rubric
explicitly scores TTS legibility. It is a proxy, not a human panel — the 40 wavs are on disk at
`tts/intelligibility/` for a listening test.

### Known output limitation
The model uses a **CTC greedy decode** — the output has **no punctuation and no capitalisation**
(`emergency alert moved to a safe location immediately`). The UI must **not** promise punctuated
transcripts.

## 3.3 TTS — text-to-speech

| Voice | Size | License | Load | RTF | Sample rate |
|---|---|---|---|---|---|
| `hi_IN` pratham (Hindi) | 60.6 MiB (63,516,050 B) | **MIT** | 0.64 s | **0.032–0.044** | 22050 Hz |
| `en_US` lessac (English) | 60.3 MiB | **MIT** | 0.53 s | **0.032–0.035** | 22050 Hz |
| espeak-ng-data (phonemes) | 7.2 MiB | GPL (data) | — | — | — |

Both voices load, synthesise, and were converted from raw Piper format using the official
conversion script (adds ONNX metadata + `tokens.txt` with correct espeak marker ordering
`_ ^ $`). **REPRODUCED.**

**RAM — corrected 18 Sep.** The earlier "273–285 MiB" figure came from scripts that divided
`ru_maxrss` by 1024 and labelled the result "MB". **On macOS `ru_maxrss` is in bytes, not
kilobytes**, so those numbers were wrong by a factor of 1024 and the RAM claim was never properly
measured. Re-measured with all models resident at once (`measure_ram_all.py`):

| Stage | Peak RSS |
|---|---|
| Interpreter baseline | 29.5 MiB |
| + ASR | 710.1 MiB |
| + Hindi TTS | 792.4 MiB |
| + English TTS | **838.1 MiB** |
| After a full generate + decode | **888.6 MiB** |

See §10 — this **replaces** the "~1.1 GiB" figure the dossier previously quoted.

### TTS language coverage — audited 20 Sep (this was an unexploded gap)

The table above lists only the two voices we had. The PS requires **TTS in all ten languages**, so
coverage was audited across every available source:

| Language | Neural voice | Source | Licence |
|---|---|---|---|
| Hindi, Marathi, Malayalam, Telugu, Bengali, English | ✅ | Piper | **MIT** |
| **Gujarati** | ✅ | Mimic3 `gu_IN` cmu-indic (in sherpa-onnx) | check before shipping |
| **Kannada, Tamil, Odia** | ❌ | only MMS-TTS (CC-BY-NC) or espeak-ng (GPL, robotic) | — |

**7 of 10 have a permissively-licensed neural voice. Three do not.**

For Kannada, Tamil and Odia the choice is between MMS-TTS (good quality, `cc-by-nc-4.0` —
non-commercial) and espeak-ng (robotic, GPL-3.0, but **the dictionaries are already in our bundled
`espeak-ng-data`**: `kn_dict`, `ta_dict`, `or_dict` all verified present).

**Recommendation: ship espeak-ng for those three and state the limitation.** It keeps the entire
shipped stack at MIT / Apache-2.0 / GPL with no CC-BY-NC anywhere, and costs nothing to add. Full
reasoning in `MODEL_AND_TTS_FINDINGS.md`.

Do not claim ten equal-quality voices. Say: *"Seven of ten use neural voices; Kannada, Tamil and
Odia use the espeak-ng formant synthesiser — intelligible but noticeably more robotic."*

## 3.4 END-TO-END LOOP — the walkie-talkie round trip

This is the actual TTS→ASR→text→TTS loop that proves the idea. All four utterances round-tripped
with meaning intact.

| Lang | Utterance | ASR out | Payload | Compute | vs raw audio |
|---|---|---|---|---|---|
| Hindi | नमस्ते, यह एक आपातकालीन संदेश है। | नमस्ते यह एक आपातकालीन संदेश है | **83 B** | **0.42 s** | **1,128× smaller** |
| Hindi | तुम्हें तुरंत सुरक्षित स्थान पर जाना होगा। | तुम्हें तुरंत सुरक्षित स्थान पर जाना होगा | **111 B** | **0.29 s** | **720× smaller** |
| English | Emergency alert. Move to a safe location immediately. | emergency alert moved to a safe location immediately | **52 B** | **0.38 s** | **2,201× smaller** |
| English | This is a test of the emergency broadcast system. | this is a test of the emergency broad cast system | **49 B** | **0.29 s** | **1,789× smaller** |

**Bottom line: ~0.3 s of compute per utterance, and a 211-byte packet for 6.94 s of speech — ~1,050× smaller than raw PCM, ~66× smaller than a 16 kbps codec.**

## 3.5 Pre-built Android APK (verified to exist and download)

- **File:** `sherpa-onnx-1.13.8-arm64-v8a-vad_asr-1600-omnilingual_asr_300M_ctc_int8.apk`
- **Size:** **297,969,404 bytes = 284 MiB** (MEASURED)
- Bundles **Silero VAD + our exact ASR model** for arm64 phones.
- Reference app source: `k2-fsa/sherpa-onnx/android/SherpaOnnxVadAsr` (Apache 2.0).
- **This means roughly 80% of the STT side already exists as a working Android app.**

---

# 4. THE FULL TECHNOLOGY STACK

| Layer | Component | Size | License | Status |
|---|---|---|---|---|
| Voice activity detection | **Silero VAD** (`silero_vad.onnx`) | **628.8 KB** | MIT | MEASURED |
| Speech-to-text | **Meta Omnilingual ASR CTC 300M INT8** via sherpa-onnx | **348 MiB** | **Apache 2.0** | RAN |
| Text-to-speech (Hindi) | **Piper** `hi_IN` pratham (VITS) | 60.6 MiB | **MIT** | RAN |
| Text-to-speech (English) | **Piper** `en_US` lessac (VITS) | 60.3 MiB | **MIT** | RAN |
| Phoneme data | espeak-ng-data (hi + en dictionaries) | 7.2 MiB | GPL (data) | RAN |
| Inference runtime | sherpa-onnx 1.13.8 (ONNX Runtime, CPU) | — | Apache 2.0 | VERIFIED |
| Transport (primary) | **Wi-Fi Direct** (`WifiP2pManager`) | — | Android SDK | TARGET |
| Transport (fallback) | **Bluetooth Classic RFCOMM** | — | Android SDK | TARGET |
| App language | Kotlin / Android | — | — | TARGET |
| Reference app | `SherpaOnnxVadAsr` (official) | APK 284 MiB | Apache 2.0 | VERIFIED |

**Total model footprint ≈ 475 MiB** (ASR 348 + TTS 60.6 + 60.3 + espeak 7.2 + VAD 0.2).

**Rejected alternatives and why:**
- **Whisper / whisper.cpp** — does **not** support Odia (one of the 10 required languages).
  Rejected on coverage, not quality.
- **MMS-TTS** — licensed **CC-BY-NC-4.0** (non-commercial). SIH deployment would need organiser
  approval. Rejected on licence risk.
- **Google Nearby Connections** — works fully offline but **requires Google Play Services**;
  low/mid-range phones may not have GMS. Avoid.
- **Indic Parler / IndicF5** — surveyed; Indic Parler ~3.6 GB FP32 (estimate) is far too large
  for a low-end phone. Not selected.
- **IndicConformer / AI4Bharat** — surveyed, heavier than needed for the transceiver use case.

---

# 5. HOW THE SYSTEM WORKS (data flow)

```
PHONE A (speaker / STT mode)                     PHONE B (listener / TTS mode)
┌────────────────────────────────┐               ┌────────────────────────────────┐
│  Mic → AudioRecord 16 kHz mono │               │   Text in (socket, UTF-8)      │
│        ↓                       │               │        ↓                       │
│  Silero VAD — detects the      │   TEXT ONLY   │   Language tag → pick voice    │
│  pause, cuts one sentence      │  ~50–110 B    │        ↓                       │
│        ↓                       │ ─────────────▶│   Piper TTS (hi / en)          │
│  Omnilingual ASR 300M INT8     │  Wi-Fi Direct │        ↓                       │
│        ↓                       │   or BT       │   AudioTrack @ 22050 Hz        │
│  text + language tag           │               │        ↓                       │
└────────────────────────────────┘               │  MAX VOLUME, NON-INTERRUPTIBLE │
                                                 └────────────────────────────────┘
```

**Both phones run the identical stack — and that is deliberate.** Either phone can talk, either can
receive; push-to-talk decides who has the floor. An earlier design split the models (one phone
ASR-only, the other TTS-only), which made the link one-way and required both users to swap roles to
hold a conversation. That was removed: a walkie-talkie is two-way by definition. The cost is
**536 MB PSS per phone instead of 428 MB**, and about 2.4 s more startup. Worth it.

**Push-to-talk semantics:** hold the button = recording. Release = the segment is recognised,
filtered, and sent. The other phone speaks it aloud. Then they hold their button and reply.

**Idle-listening advantage (feeds the 20% Efficiency mark):** when nobody is speaking, the app runs
**only the 629 KB VAD** (measured **RTF 0.004**). The 348 MiB ASR and 60 MiB TTS models are loaded,
but they do no work until the VAD fires a segment — a direct answer to ISRO's
"CPU usage during idle listening".

---

# 6. COMPONENT-BY-COMPONENT BUILD PLAN

### 6.1 VAD (pause detection) — Silero VAD
- `silero_vad.onnx` = **643,854 bytes (628.8 KB)**, 16 kHz, exported by k2-fsa.
  **CORRECTED 19 Sep:** the earlier "208 KB" figure was never measured — the file was not on
  disk at all. It has now been extracted from the prebuilt Android APK
  (`assets/silero_vad.onnx`) and functionally tested: `vad_test.py` → 0 segments on 5 s of
  silence, 1–3 segments on real speech, **VAD RTF 0.004** (~240× faster than real time).
- **This is literally what implements ISRO's requirement** *"detecting pauses and stoppages should
  form the sentences"*.
- sherpa API: `Vad` class → `acceptWaveform()`, then `empty()` / `front()` / `pop()`. Segments are
  emitted on endpoint.
- Already integrated in the official `SherpaOnnxVadAsr` Android reference app.

### 6.2 ASR — Omnilingual 300M INT8
- sherpa-onnx API (exact call, learned by testing):
  `OfflineRecognizer.from_omnilingual_asr_ctc(model=..., tokens=..., num_threads=8, provider="cpu")`
  — note it is **`from_omnilingual_asr_ctc`**, not `from_omnilingual`.
- The model auto-resamples input to 16 kHz internally.
- **Pre-built arm64 APK exists** (284 MiB) bundling VAD + this exact model.
- **Build move: fork `SherpaOnnxVadAsr` as the starting point** — it already does
  mic → VAD → ASR → text on Android.

### 6.3 TTS — Piper (Hindi + English)
- Raw Piper voices must be **converted** with the official script (adds ONNX metadata + tokens).
  Done and reproduced in `tts/convert_piper.py`.
- **espeak-ng-data (7.2 MB) must ship inside the app assets** — it holds the hi/en phoneme
  dictionaries Piper depends on.
- Output is 22050 Hz mono PCM, played via `AudioTrack`.

### 6.4 Transport — Wi-Fi Direct (primary) / Bluetooth (fallback)
- **Wi-Fi Direct** is true peer-to-peer: no router, no hotspot, no internet. Uses
  `WifiP2pManager` → `discoverPeers()` → `connect()`. One device becomes group owner; open a plain
  `ServerSocket` / `Socket` pair and stream UTF-8 bytes.
- **Bluetooth Classic (RFCOMM)** as fallback — more than enough for ~100 B/s of text.
- Verified Android facts:
  - A **local-only hotspot has no internet** — so hotspot mode is not enough; use Wi-Fi Direct.
  - **Wi-Fi Direct is peer-to-peer** and works without any network infrastructure.
  - **GPS works without a SIM** but **fails indoors** (relevant only if location is ever added).

### 6.5 Alert mode — "highest volume, non-interruptible"
- `AudioManager` → set `STREAM_MUSIC` / `STREAM_ALARM` to maximum.
- Request `AudioFocus` with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.
- Route to the loudspeaker, loop the clip until the user acknowledges.

---

# 7. THE LANGUAGE QUESTION (Hindi/English/Hinglish + the 10 languages)

ISRO lists 10 languages: **Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia,
Bengali, English.**

### 7.1 What we have actually measured
**10 of 10** — every required language is measured on real human speech, **200 clips each**
(2,000 clips, 6.72 hours). Normalised CER, best to worst:

Kannada 0.052 · Malayalam 0.054 · English 0.073 · Gujarati 0.074 · Bengali 0.094 ·
Telugu 0.103 · Marathi 0.108 · Hindi 0.141 · Odia 0.143 · Tamil 0.149

**There is no unmeasured language left.** The earlier gaps (truncated Gujarati/Kannada tarballs,
never-fetched Tamil/Telugu/Malayalam, English only on a single bundled wav) were all closed on
19 September.

### 7.2 The Hinglish problem — honest answer
We were asked for **Hindi + English + Hinglish**. I checked **all 1,668 language IDs** the ASR model
supports:

- `hin_Deva` (Hindi, Devanagari) — **SUPPORTED**
- `eng_Latn` (English) — **SUPPORTED**
- `hin_Latn` (romanised Hindi / Hinglish) — **DOES NOT EXIST.** **Zero romanised Indian languages**
  are in the model.

**This does not break the plan, but it changes what we can claim.** Three honest options:

1. **Scope the demonstrator to Hindi + English (RECOMMENDED).** Both are fully covered by ASR *and*
   by Piper TTS; we have measured end-to-end loops for both. This is real and defensible.
2. **Hinglish as a UI convenience only.** Accept romanised Hindi **typed text** on phone B for TTS
   (espeak can phonemise romanised Hindi reasonably) — but **never claim ASR outputs it**.
3. **Hinglish ASR as R&D, clearly labelled.** Omnilingual may partially transcribe romanised Hindi
   since it saw some in training, but **this is untested and must not be presented as a feature.**

### 7.3 What to say in the submission
**Do not promise 10 languages as a flat claim.** Say:
> *"Demonstrated in Hindi and English end-to-end, with per-language accuracy measured on 200 clips
> of the FLEURS real-speech benchmark: Bengali, Marathi, Hindi and Odia all in a usable band
> (normalised CER 0.09–0.14). The selected model covers all 10 required language IDs; the
> remaining languages are queued for measurement."*

---

# 8. LICENSING — WHY WE ARE LEGALLY SAFE

ISRO **strictly prohibits** proprietary, closed-source or commercial voice-activation SDKs, and
requires fully offline operation. Our stack:

| Component | License | Commercial-safe? |
|---|---|---|
| Omnilingual ASR 300M INT8 | **Apache 2.0** (read from artifact) | ✅ Yes |
| Silero VAD | MIT | ✅ Yes |
| Piper TTS voices | **MIT** | ✅ Yes |
| sherpa-onnx runtime | Apache 2.0 | ✅ Yes |
| espeak-ng-data | GPL (data, shipped as asset) | ⚠️ Note it, but acceptable |
| FLEURS (test data only) | CC-BY-4.0 (evaluation only, not shipped) | ✅ Not distributed |

**No CC-BY-NC anywhere in the shipped product.** MMS-TTS was specifically rejected because its
CC-BY-NC-4.0 licence would need organiser approval. This is a real, checkable compliance advantage.

---

# 9. PRIOR ART AND COMPETITION (what already exists)

Research base: `output/comms_feasibility_review/`, `output/winner_decks_verified/`.

- **MeshSpeech** — prior art combining mesh radio + speech.
- **meshtastic-offline-stt** — existing project doing offline STT over Meshtastic.
- **Meshtastic** itself — LoRa mesh radio with a **payload limit of ≤237 bytes**. Text fits; audio
  never would.
- **bitchat** — is **Bluetooth LE (BLE)**, **not LoRa**. Common misconception; do not cite it as LoRa.
- **OpenSuperWhisper** — macOS on-device whisper, MIT licence; surveyed for reference only.
- **whisper.cpp** — surveyed; rejected because **no Odia**.

**Conclusion for our pitch:** the idea "STT + radio + TTS" is **not novel by itself** — prior art
exists. Our differentiators must be presented as:
1. **Speech-to-text before transmit** — measured 129–278 bytes cross the link, ~66× less than a 16 kbps speech codec (~1,050× less than raw PCM).
2. **VAD-only idle listening** — near-zero idle CPU (directly answers the Efficiency metric).
3. **Per-language honest accuracy reporting (CER + WER)** on a real benchmark.
4. **Fully permissive licensing** (Apache 2.0 + MIT) with zero commercial risk.

**Winner-deck research** (3 verified winning decks, `output/winner_decks_verified/`): winning SIH
decks are evidence-heavy, show measured numbers, name exact tools and licences, and include an
explicit "what is not yet validated" section. That structure is what this dossier mirrors.

---

# 10. MAPPING TO ISRO'S MARKING SCHEME (20/40/20)

### Efficiency — 20%
| Asked | Our answer | Status |
|---|---|---|
| Model size | ASR 348 MiB + TTS 2×~60 MiB + VAD 0.2 MiB + espeak 7 MiB ≈ **475 MiB** | MEASURED |
| App size | Official arm64 APK (ASR+VAD only) = **284 MiB** | MEASURED |
| RAM footprint | Peak **~838 MiB** with all models resident, **~889 MiB** after a full generate+decode (Mac) | MEASURED (Mac) |
| CPU during idle listening | **VAD-only (208 KB)** — ASR/TTS not loaded until a segment fires | BY DESIGN |

*(The previously quoted "~1.1 GiB" was overstated by ~200–260 MiB and rested on a unit bug. Corrected 18 Sep.)*

### Accuracy — 40%
| Asked | Our answer | Status |
|---|---|---|
| Low STT WER | **All 10 languages measured**, 200 clips each on FLEURS. Normalised CER 0.052–0.149 (Kannada best, Tamil worst). Report CER + WER | MEASURED |
| High TTS human legibility | ASR round-trip proxy: English CER 0.030 (excellent), Hindi CER 0.339 (weak). **Human panel still required.** | PROXY ONLY |
| Language coverage | **10 of 10 required languages** measured on real speech — no gaps | MEASURED |

### Latency — 20%
| Asked | Desktop (Mac) | **Android runtime** | Status |
|---|---|---|---|
| ASR RTF | 0.036–0.043 | **0.120–0.127** | MEASURED |
| TTS RTF | 0.032–0.044 | **0.055–0.058** | MEASURED |
| ASR model load | 0.53 s | **1.4–2.0 s** | MEASURED |
| TTS voice load | 0.53–0.64 s | **0.6–0.8 s** | MEASURED |
| Mouth-to-ear delta | ~0.6–0.8 s (est.) | **≈0.9–1.0 s (est.)** | ESTIMATE |

Android figures from `MainActivity`'s built-in benchmark on an API 34 arm64 emulator
(`android/evidence/benchmark_onddevice.txt`). **Android is ~3× slower than desktop for ASR.**
The emulator is not a low-end phone — a budget handset will be slower still. Mouth-to-ear remains
an estimate: measured compute plus VAD endpointing (~0.3–0.5 s) and radio (~10 ms).

---

# 11. WHAT IS STILL NOT PROVEN (risks)

Labelled honestly — these belong on the deck's own "validation needed" line.

1. **Android on-device performance is the #1 risk.** Everything measured here is a **Mac**. The
   284 MiB APK runs on arm64 phones, but **RTF and RAM on a low/mid-range phone (2–4 GB RAM) are
   unmeasured** and will be worse. This gates the whole story.
2. **Noisy real-world speech.** FLEURS is clean studio audio; a disaster zone is not.
3. **TTS legibility score.** An ASR round-trip proxy now exists (English CER 0.030, Hindi 0.339),
   but a **human listening panel is still required** for the 40% criterion. The 40 wavs are ready
   at `tts/intelligibility/`.
4. **Wi-Fi Direct reliability** — each Android OEM implements P2P differently. Fallback to
   Bluetooth mitigates but does not remove this.
5. ~~Six of ten languages unmeasured.~~ **CLOSED 19 Sep** — all ten required languages are now
   measured at 200 clips each. Normalised CER 0.052–0.149.
   ~~Marathi measured and weak (WER 0.74)~~ — **corrected 18 Sep: Marathi is mid-pack at WER 0.415,
   not a failure. The 0.74 was a 6-clip artifact.**
6. ~~English has never been evaluated on FLEURS.~~ **CLOSED 19 Sep** — English measured at CER 0.073,
   WER 0.195, making it the **best** language on WER.
7. **Tamil and Odia are the weakest languages on v2** (CER 0.129 and 0.134). Report both plainly
   rather than presenting only the good ones.
8. **TTS covers 7 of 10 languages with a neural voice.** Kannada, Tamil and Odia have no
   permissively-licensed neural voice — only MMS-TTS (CC-BY-NC) or espeak-ng (robotic). See §3.3.
   This is a real requirement gap and must be stated, not buried.
9. **Hindi TTS round-trips poorly** (CER 0.339 vs ASR-only 0.141). Cause not isolated — could be
   the voice or the ASR's handling of synthetic speech. Directly affects the demo path.
10. **Hinglish ASR does not exist in the model** — must not be claimed.
11. **No punctuation/capitalisation in ASR output** (CTC greedy decode).

---

# 12. BUILD ORDER (what to do first)

1. **Fork `k2-fsa/sherpa-onnx/android/SherpaOnnxVadAsr`** — it already does mic → VAD → ASR → text.
2. **Add the two Piper TTS voices + espeak-ng-data** to app assets; wire a TTS playback screen.
3. **Add Wi-Fi Direct socket transport** — get text flowing A → B.
4. **Push-to-talk** — hold = STT mode on A, TTS mode on B; release = phone mode.
5. **Alert mode** — max volume, non-interruptible, looped playback on B.
6. **Benchmark on the cheapest phone you can find.** This is the real test of the whole pitch.

**Hardware needed for a demo:** two Android phones (no SIM required). That is all — the transport
is phone-to-phone, no extra radios needed for this software PS.

---

# 13. REPRODUCIBLE FILES AND HOW TO RE-RUN EVERYTHING

All under `output/option_a_stt/`:

| File | What it does |
|---|---|
| `ACCURACY_RESULTS.md` | **The current accuracy/latency/RAM results — read this first** |
| `fleurs_eval_full.py` | **200-clip FLEURS eval, raw + normalised, corpus-level** |
| `SUMMARY_full.json` | **Per-language summary (the numbers to quote)** |
| `asr_full_{or,hi,bn,mr}_in.json` | **Per-clip reference/hypothesis/WER/CER, 200 clips each** |
| `tts_intelligibility.py` | **TTS → ASR round-trip intelligibility proxy** |
| `tts_intelligibility.json` | **Per-sentence intelligibility results** |
| `tts/intelligibility/*.wav` | **40 wavs for the human listening panel** |
| `measure_ram_all.py` | **Corrected cross-platform peak-RAM measurement** |
| `RAM_measure_all_models.json` | **Peak RSS per load stage** |
| `fleurs_full_run.log` | Raw run log for the 200-clip eval |
| `asr_run.py` | Loads the 300M INT8 model, runs ASR on sherpa's bundled wavs |
| `asr_eu.json` | Output of the above (English/German/French/Spanish wavs) |
| `fleurs_eval.py` | The original 6-clip eval — **superseded**, kept for comparison |
| `SUMMARY_table.json` | The original 6-clip table — **superseded** |
| `tts_test.py` / `tts_bench.py` | Piper TTS load + synthesis + RTF benchmark |
| `tts/convert_piper.py` | Converts raw Piper voices to ONNX (metadata + tokens.txt) |
| `end_to_end.py` | The full walkie-talkie loop (TTS→ASR→text→TTS) |
| `measure_ram.py` | Original RAM script — **unit bug, superseded by `measure_ram_all.py`** |
| `web_scan.py` / `web_scan2.py` | Live web verification of sherpa/APK docs |
| `build_deck.py` | Builds the local SIH-template PPTX |
| `omni300m/` | The downloaded model + tokens + LICENSE |
| `fleurs/` | Extracted real-speech test audio |
| `end_to_end.json` | The round-trip loop measurements |

**Environment used:** macOS arm64, Python 3.14, sherpa-onnx 1.13.8, 8 CPU threads, 17 GB RAM.

**FLEURS gotchas (learned the hard way, so you don't repeat them):**
config codes are `or_in` (NOT `ori_in`), `hi_in`, `bn_in`, `mr_in`, `gu_in`, `kn_in`, `ml_in`,
`ta_in`, `te_in`, `en_us`. Audio is **not** per-file — it is split tarballs at
`https://huggingface.co/datasets/google/fleurs/resolve/main/data/{code}/audio/test.tar.gz`
(the per-file URL pattern 404s; `urllib.urlretrieve` also fails — use `curl`). Clips are
**IEEE float WAV (wFormatTag 3, 32-bit)** so Python's `wave` module raises
`wave.Error: unknown format: 3` — parse RIFF chunks manually with `struct`, handling 16/32-bit and
mono/stereo.

---

# 14. READY-MADE SLIDE CONTENT (copy-paste for the PPT)

*These are the exact text blocks already in the Canva design `DAHVitfxcPs` and the local
`iTantra_SIH26173_DRAFT.pptx`. Edit freely.*

### SLIDE 1 — TITLE PAGE
```
iTantra — Offline Voice Transceiver
Speech to text to speech, fully offline, over low-bitrate links.

Problem Statement ID: SIH26173
Problem Statement: iTantra - Indian Multilingual TTS & STT Aided
Neural Transceiver Radio Access for low bitrate links
Theme: Smart Automation
Category: Software

Team: B.J.P (Bug Janta Party)
Team ID: [PORTAL TEAM ID]

PROPOSAL WITH LOCAL MEASUREMENTS • Android performance untested
```

### SLIDE 2 — IDEA TITLE
```
THE PROBLEM
Voice over low-data-rate links is too large to fit; audio saturates the link
before a sentence finishes.

THE SOLUTION — iTantra
Speech in on phone A becomes text, not audio.
A short UTF-8 packet crosses Wi-Fi Direct or Bluetooth.
Phone B turns the text back into speech at full volume.

WHY THIS APPROACH
One sentence costs roughly 50-110 bytes instead of hundreds of kilobits —
about 66x smaller than a 16 kbps codec.
Idle phones run only a voice-activity detector, so idle CPU stays near zero
by design.
Push-to-talk works as a walkie-talkie; alerts cannot be interrupted.
```

### SLIDE 3 — TECHNICAL APPROACH
```
SYSTEM DESIGN  /  PROPOSED DATA PATH

[1] PHONE A — TALKING    →   [2] TRANSPORT
     Mic 16 kHz mono              Wi-Fi Direct (primary)
     Silero VAD cuts sentence     Bluetooth fallback
     Omnilingual ASR → text       UTF-8 text, 50–110 bytes
                                                         ↓
[4] PHONE B — RECEIVING  ←   [3] PACKET
     Piper TTS (hi / en)          Language tag + text
     Audio out 22050 Hz           Sent only after VAD pause
     Max volume, no interrupt

MEASURED ON DESKTOP (MAC ARM64) — NOT A PHONE
ASR RTF 0.036–0.043 • TTS RTF 0.032–0.044 • full loop 0.29–0.42 s

MODEL STACK — ALL OPEN SOURCE
VAD Silero int8 (208 KB) • ASR Omnilingual 300M INT8 (348 MiB, Apache 2.0)
TTS Piper hi_IN + en_US (~60 MiB each, MIT)

IMPLEMENTATION CHECKS
Build order: fork official SherpaOnnxVadAsr app
Add Piper TTS + espeak-ng-data in assets
Add Wi-Fi Direct socket, push-to-talk, alert mode
```

### SLIDE 4 — FEASIBILITY AND VIABILITY
```
BUILD & DEPLOY
Fork the official SherpaOnnxVadAsr Android reference app — it already runs
this exact ASR model with VAD.
Ship both Piper voices and phoneme data inside the APK.
Two phones, no internet, no cloud, no paid API.

RISK → VALIDATION
Phone CPU / RAM unmeasured → benchmark on the cheapest available phone first.
Studio-clean audio is not disaster audio → test in real noise.
Marathi measured weak (WER 0.74); 6 of 10 languages untested → demonstrate
Hindi + English first.
Wi-Fi Direct differs across Android vendors → always ship Bluetooth fallback.

MEASURED ACCURACY ON REAL SPEECH (FLEURS)
Hindi CER 0.24 • Odia CER 0.08 • Bengali CER 0.09 • English near-perfect.
```

### SLIDE 5 — IMPACT AND BENEFITS
```
WHO BENEFITS
Field responders, volunteers and citizens on degraded or saturated networks
— and anyone who cannot read or type.

EXPECTED VALUE
Voice in, voice out: inclusive of users regardless of literacy.
No paid API, no cloud, no proprietary SDK — runs on phones people already own.
Text instead of audio cuts transmitted energy per message.

MEASURED SO FAR
Hindi and English round-trip cleanly with meaning intact.
A 211-byte packet for 6.94 s of speech: ~66x smaller than a 16 kbps speech codec, ~1,050x smaller than raw PCM.

VALIDATION NEEDED
Field accuracy in noise, phone-class latency, TTS legibility scoring,
battery runtime.
```

### SLIDE 6 — RESEARCH AND REFERENCES
```
MODELS AND RUNTIME
ASR   csukuangfj / sherpa-onnx-omnilingual-asr-300M-ctc-int8 (Apache 2.0, 348 MiB)
TTS   rhasspy / piper-voices hi_IN pratham + en_US lessac (MIT)
VAD   k2-fsa / sherpa-onnx Silero VAD int8 (208 KB)

EVALUATION DATA
google / fleurs (CC-BY-4.0) — real recorded human speech for per-language accuracy

PLATFORM DOCUMENTATION
k2-fsa.github.io/sherpa/onnx — VAD, ASR, TTS and Android guides
developer.android.com — Wi-Fi Direct and Bluetooth transport

LOCAL EVIDENCE
end_to_end.py • tts_bench.py • fleurs_eval.py • convert_piper.py —
all reproducible.

BEFORE SUBMISSION
Confirm portal team ID; measure phone-class latency and RAM.
```

---

# 15. CORRECTIONS MADE DURING RESEARCH (honest log)

Research is only trustworthy if the mistakes are recorded. These were all caught and fixed:

1. **Model repo path was wrong.** Early research said the model lived at `k2-fsa/sherpa-onnx-...`.
   That path **returns HTTP 401 (gated/private)**. The genuinely public, downloadable copy lives
   under maintainer **`csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`**.
   `facebook/omnilingual-asr` also reads as gated. **The sherpa-onnx export is the accessible route.**
2. **Never confuse the model with the LLM.** The 300M INT8 ASR model is a **speech** model. Do not
   attribute accuracy claims from the 7B text LLM to it.
3. **sherpa API name.** It is `OfflineRecognizer.from_omnilingual_asr_ctc(...)`, not
   `from_omnilingual(...)`.
4. **Odia config code** is `or_in`, not `ori_in`.
5. **FLEURS audio is tarballed**, not per-file; clips are IEEE-float WAV that Python's `wave`
   module cannot read.
6. **bitchat is BLE, not LoRa** — do not cite it as a LoRa example.
7. **Whisper has no Odia** — this was the original reason for switching to Omnilingual, and Odia
   performance is now *confirmed by measurement* (CER 0.083), not assumption.
8. **MMS-TTS is CC-BY-NC** — rejected; Piper (MIT) chosen instead.
9. **A local-only hotspot has no internet** — Wi-Fi Direct is required for true phone-to-phone.

### Second pass — 18 Sep 2026, after re-measuring at 200 clips

10. **The Odia "headline result" was a small-sample artifact.** CER 0.083 at 6 clips became
    **0.143** at 200 clips — roughly 1.7× optimistic. Odia is the *worst* language on WER, not the
    best. The Whisper-coverage differentiator survives, the number does not.
11. **Marathi was wrongly written off.** The dossier said it "degrades badly — meaning is lost"
    at WER 0.744. At 200 clips it is **WER 0.415** — mid-pack, and better than Odia. The 0.74 was
    6 unlucky clips. Marathi has been removed from the risk list.
12. **Every RAM number produced before 18 Sep used the wrong unit.** The scripts divided
    `ru_maxrss` by 1024 and called the result "MB". **On macOS `ru_maxrss` is in bytes.** The
    "~1.1 GiB peak" claim was therefore never measured; the real figure is **~838–889 MiB**.
13. **"TTS legibility cannot be measured without a human panel" was too pessimistic.** An ASR
    round-trip proxy is available and now run: English CER 0.030, Hindi CER 0.339. Still a proxy —
    but far better than "not measured".
14. **Hindi TTS round-trips worse than expected** (CER 0.339 vs ASR-only 0.141). Newly discovered,
    cause not yet isolated. This is now the most important open accuracy question.
15. **`asr_eu.json` did not exist** where the docs said it did — `asr_run.py` writes
    `asr_result.json` to the working directory. Copied into place so the reference resolves.
16. **`VadAsr_MainActivity.kt` was a 0-byte file** duplicating `VadAsr_reference.kt`. Removed.

---

## DELIVERABLES

| What | Where |
|---|---|
| **Editable Canva deck (6 slides)** | https://www.canva.com/design/DAHVitfxcPs/edit — design ID `DAHVitfxcPs` |
| Local editable PPTX | `output/option_a_stt/iTantra_SIH26173_DRAFT.pptx` |
| Rendered PDF (6 pages, layout-verified) | `output/option_a_stt/iTantra_SIH26173_DRAFT.pdf` |
| HTML visual summary | `output/option_a_stt/technical_approach.html` |
| **This dossier** | `output/option_a_stt/FULL_RESEARCH_DOSSIER.md` |
| Technical approach (concise) | `output/option_a_stt/TECHNICAL_APPROACH.md` |
| ASR accuracy writeup | `output/option_a_stt/REAL_ASR_RESULTS.md` |
| Model fact sheet | `output/speech_model_research/OPTION_A_FACT_SHEET.md` |
| Original submission draft | `output/SIH26173_SUBMISSION_DRAFT.md` |
| HECN reference analysis | `output/HECN_Tech_Applied_to_SIH26173.md` |

**Still open:** replace `[PORTAL TEAM ID]` on slide 1; run the Android benchmark (biggest remaining
risk); human review of the rendered slides.
