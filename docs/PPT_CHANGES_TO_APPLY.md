# `sih 173.pptx` — exact changes to make it accurate

**Audited:** 21 September 2026, 09:15 · **Deck:** `/Users/prathameshbhujbal/Downloads/sih 173.pptx` (unchanged since 20 Sep 14:52)
**Sources of truth:** `SUMMARY_full_v2.json`, `ACCURACY_RESULTS.md`, `android/TECH_STACK_AND_ARCHITECTURE.md` (new), `android/VERIFICATION_STATUS.md` (updated), `NOISE_ROBUSTNESS_FINDINGS.md`

> Every change below is **old text → new text**. Apply in order; slide numbers are as in the deck.

---

## Structural findings — what the deck actually is

Inspected shape-by-shape through the editor (`file_id: sid_muapn2zd_t42zjxse`, 6 slides, 960×540 pt).
Four things that change *how* you make the edits:

| Finding | Detail | Consequence |
|---|---|---|
| **Slide 4's data table is a bitmap, not a table.** | `<a:tbl>` count on slide 4 = **0**. The numbers are `Picture 12` → `ppt/media/image17.jpeg` (1070×1078 px), placed at 18,174 pt, 371×278 pt. | **You cannot edit a cell.** You must regenerate the image and replace it — or delete the picture and insert a real PowerPoint table. |
| **That image is a screenshot with a crop applied.** | `srcRect l=2836 t=8000 r=2453 b=2728` — the crop hides the source screenshot's stray heading **"The complete table"** and a scroll arrow at the bottom. | It currently renders clean. But if anyone replaces the image, replicate the crop or make a clean export — otherwise the "The complete table" heading reappears on your slide. |
| **No slide has speaker notes.** | All six notes pages are empty (slide 1 has no notes page at all). | There is nowhere for a script to live. `PRESENTATION_SCRIPT.md` can be pasted in as notes. |
| **The deck carries no product name.** | "iTantra" appears nowhere except inside the PS title string on slide 1. | Add it — slide 1 and slide 2 both need it. |

Shape IDs, for reference when editing:

| Slide | Key shapes |
|---|---|
| 1 | `00007b` — the PS block with the two blank fields |
| 2 | `000087` — title text · `0000ad` — "46 – 111 bytes" · `0000af` — payload caption |
| 3 | `0000c6` — "meta omnilingual ASR + Sherpa—ONNX" · `0000ca` — "Wi-Fi Direct/Bluetooth" · `0000e4`–`0000e7` — tech stack rows · `0000e9` — bottom banner |
| 4 | `0000fc` — header · `0000fe` — "Language Recognition Results" · `0000ff` — **the table image** · `000101` — caption · `000102`/`000104`/`000106` — the three callout boxes |
| 5 | `00011a`–`000128` — the three columns of benefit text |
| 6 | `000135` — the single reference text block |

---

## 0. What changed since the last audit

Three things landed in the folder overnight that the deck has to absorb:

| Change | Source | Why it matters to the deck |
|---|---|---|
| **Transport priority is reversed.** Bluetooth Classic RFCOMM is now **primary**; Wi-Fi Direct is **fallback**. | `TECH_STACK_AND_ARCHITECTURE.md` §5.4 | Deck slide 3 implies Wi-Fi Direct is primary. Wrong direction now. |
| **5 new verified Android items** — Bluetooth reaches the adapter, Wi-Fi Direct starts discovery, transport switcher, device picker, connection banner. | `VERIFICATION_STATUS.md` #22–26 | You can now claim more than "works on Android" — but still not a working link. |
| **Canonical tech-stack doc exists** with exact versions, sizes, packet format, APK composition. | `TECH_STACK_AND_ARCHITECTURE.md` | Slide 3 and 6 should be rewritten from it instead of from memory. |

Still true and still unaddressed: slide 4's table is **v1**, superseded by v2.

---

## Slide 1 — Title

| # | Change |
|---|---|
| 1.1 | **Problem Statement Title is blank.** Fill: `iTantra - Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links` |
| 1.2 | **Team ID is blank.** Fill with the portal Team ID. |
| 1.3 | Add the product name. The deck never says "iTantra" anywhere except the PS title. Add a subtitle: `iTantra — speak in any of 10 languages, send text, hear it back. Fully offline.` |
| 1.4 | Add an honesty line, bottom of slide: `Proposal with local measurements. Android app verified on emulator; two-phone radio link not yet exercised.` |

---

## Slide 2 — Idea and solution

| # | Old text | New text |
|---|---|---|
| 2.1 | `VOICE ACROSS  A TEXT-SIZED CONNECTION` | `VOICE ACROSS A TEXT-ONLY LINK` (kills the double space; the original doesn't parse) |
| 2.2 | `46 – 111 bytes` | **`49 – 111 bytes`** |
| 2.3 | `Observed text payload in local synthetic-speech tests / Compared with equivalent raw PCM audio, excluding packet overhead.` | `Measured text payload, 4 test utterances. 128–275 bytes with the packet envelope. Equivalent raw PCM is ~93 KB for a 3-second sentence — roughly 1,000× larger.` |
| 2.4 | `Cloud Dependency / Online speech Processing needs Internet access` | `Cloud dependency — online speech processing needs internet access` |
| 2.5 | `More Data Transfer / Continuous audio Increases transmitted Data and battery usage` | `More data transfer — continuous audio increases data and battery use` |
| 2.6 | `High Bandwidth / Raw voice requires Continuous data transmission` | `High bandwidth — raw voice requires continuous transmission` |

**Why 2.2 matters:** `end_to_end.json` gives payload_bytes = 49, 52, 83, 111. The value 46 appears in no data file. And the newer `TECH_STACK_AND_ARCHITECTURE.md` §5.2 states the envelope figure as **128–275 bytes**, which supersedes the 150–250 in the older `ARCHITECTURE.md`.

---

## Slide 3 — Technical approach

### 3a. Transport direction — the important one

| # | Old text | New text |
|---|---|---|
| 3.1 | `Wi-Fi Direct/ Bluetooth / Transmit only compact text` | **`Bluetooth Classic RFCOMM (primary)`** / `Wi-Fi Direct (fallback)` / `4-byte length-prefixed UTF-8 packets` |
| 3.2 | — | Add the reason, it's a strong slide line: `Payload is 129–278 bytes — ~66× smaller than a 16 kbps speech codec; both transports offer orders of magnitude more bandwidth than needed. Reliability decides, not speed — Wi-Fi Direct behaves differently across vendors, and Bluetooth pairing authenticates the sender of an emergency alert.` |

### 3b. Spelling and naming

| # | Old text | New text |
|---|---|---|
| 3.3 | `Sherpa—ONNX` (em dash) | `sherpa-onnx` (matches the actual project name) |
| 3.4 | `meta omnilingual ASR` | `Meta Omnilingual ASR` |
| 3.5 | `4. Received Text / Test received over Local link` | `4. Received text — length-prefixed packet decoded, duplicate and expiry checked` |
| 3.6 | `6. Voice Output / Played on phone speaker` | `6. Voice output — 22 050 Hz, STREAM_ALARM. ALERT messages loop at max volume and are non-interruptible.` |

### 3c. Technology stack — replace with measured facts

| # | Old text | New text |
|---|---|---|
| 3.7 | `Android / Kotlin / Android Audio & Connectivity APIs` | `Android — Kotlin 1.9.22, minSdk 21 (Android 5.0), targetSdk 33, compileSdk 34. Native APIs only: AudioRecord, AudioTrack, BluetoothSocket, WifiP2pManager.` |
| 3.8 | `Meta Omnilingual ASR / INT8 model using Sherpa-ONNX` | `Meta Omnilingual ASR 300M CTC, INT8 — **348.4 MB**, Apache 2.0, via sherpa-onnx v1.13.8` |
| 3.9 | `Piper TTS / Hindi & English voices (≈ 60 MiB each)` | `Piper VITS, MIT — hi_IN pratham **60.6 MB**, en_US lessac **60.3 MB**` |
| 3.10 | `Voice Activity Detection (VAD) / Detects speech / pause boundaries / Silero VAD` | `Silero VAD, MIT — **0.6 MB, RTF 0.004** (~240× faster than real time). Segments speech at pauses; only the VAD runs when idle.` |
| 3.11 | — | Add: `espeak-ng-data (~7 MB, GPL) for phoneme dictionaries` |

### 3d. Add the architecture decision — this is 20% of the score

The deck has no efficiency story. `TECH_STACK_AND_ARCHITECTURE.md` §4 has the exact answer:

> **One heavy model per phone.** The speaker loads VAD + ASR (~710 MB desktop resident, **428 MB PSS on Android**). The listener loads VAD + TTS (~100 MB). A naive design loads both models on both phones and doubles the memory for no benefit. When nobody is speaking, only the 0.6 MB VAD runs — ASR and TTS are not loaded until a segment fires. This is the direct answer to the PS requirement "CPU usage during idle listening."

Add this as a callout box on slide 3.

---

## Slide 4 — Feasibility and viability

### 4a. Replace the entire table

> **How to do this:** shape `0000ff` is a **picture**, not a table (see structural findings above).
> Right-click → Change Picture, or delete it and insert a real table. If you keep it as an image,
> export the new table cleanly — no heading, no scroll bar — and apply a crop, or the source
> screenshot's "The complete table" heading will show on the slide.

Every value currently on the slide is **v1**, which `ACCURACY_RESULTS.md` §2 explicitly retires ("MODEL VERSION: use v2"). Replace with v2, and **re-sort by CER** (the current RTF sort looks like it's burying the weak languages):

| Language | WER (norm) | CER (norm) | RTF median |
|---|---|---|---|
| Kannada | 0.307 | **0.053** | 0.041 |
| Malayalam | 0.361 | **0.059** | 0.041 |
| English | 0.181 | **0.067** | 0.040 |
| Gujarati | 0.300 | **0.071** | 0.041 |
| Bengali | 0.359 | **0.100** | 0.040 |
| Telugu | 0.431 | **0.100** | 0.039 |
| Marathi | 0.416 | **0.108** | 0.040 |
| Hindi | 0.281 | **0.113** | 0.040 |
| Tamil | 0.501 | **0.129** | 0.040 |
| Odia | 0.509 | **0.134** | 0.043 |

Old values for reference (what's on the slide now → what it becomes):
Kannada 0.276→0.307 · Malayalam 0.334→0.361 · English 0.195→0.181 · Gujarati 0.286→0.300 · Bengali 0.337→0.359 · Telugu 0.434→0.431 · Marathi 0.415→0.416 · Hindi 0.305→0.281 · Tamil 0.532→0.501 · Odia 0.504→0.509

### 4b. Fix the supporting claims

| # | Old text | New text |
|---|---|---|
| 4.1 | `Evaluation of offline ASR on real speech data across 10 Indian languages` | `Offline ASR evaluated on real human speech — Google FLEURS, **200 clips per language, 2,000 clips, 6.7 hours**, all 10 languages required by the PS` |
| 4.2 | `RTF values remain around 0.038 – 0.042 across the Tested languages.` | `RTF median **0.039 – 0.043** across all ten languages. p95 0.045–0.080. A 3-second sentence costs ~0.12 s of ASR compute.` |
| 4.3 | `10 Indian languages evaluated` | `All 10 PS-mandated languages measured. Mean CER **0.093**. Every language under 0.14.` |
| 4.4 | `Lower error rate = better recognition` | `Normalised CER — lower is better. Normalisation strips punctuation and case, because the CTC decoder emits neither. Raw WER runs ~1.3× higher.` |
| 4.5 | — | Add the baseline, it's your strongest comparison: `Meta's 7B LLM-ASR model is 2–3× better — but it is 25 GiB and cannot run on a phone. Ours is 348 MB and does. Malayalam within 1.1×, Telugu 1.5×.` |
| 4.6 | — | Add the weak end openly: `Weakest: Odia 0.134, Tamil 0.129. Stock Whisper cannot transcribe Odia at all.` |
| 4.7 | — | Add TTS legibility — it is inside the 40% accuracy criterion and is currently absent: `TTS round-trip intelligibility proxy: English CER 0.030 (strong, 45% exact), Hindi 0.339 (weak — open problem).` |
| 4.8 | — | Add latency end-to-end: `Full loop compute 0.29–0.42 s. Android emulator mouth-to-ear ~0.9–1.0 s.` |

**Note on 4.2:** `ACCURACY_RESULTS.md` §6 still says "0.038–0.042" and claims p95 of 0.044–0.054, and says slowest median latency is Malayalam at 0.57 s. All three disagree with `SUMMARY_full_v2.json`, which gives medians 0.0388–0.0432, p95 up to **0.0803** (Malayalam), and Malayalam median latency **0.634 s**. Fix that doc too, or the deck and the doc will contradict each other in front of a judge.

---

## Slide 5 — Impact and benefits

| # | Old text | New text |
|---|---|---|
| 5.1 | `ACCESSIBLE / Designed for existing Android phones.` | `ACCESSIBLE — minSdk 21, so Android 5.0 and up. Runs on phones people already own.` |
| 5.2 | `LOW DATA / Only compact text crosses the network` | `LOW DATA — 49–111 bytes of text crosses the network, vs ~93 KB for the same sentence as audio.` |
| 5.3 | `OFFLINE / Speech processing Happens locally` | `OFFLINE — all speech processing on-device. No cloud API, no internet.` |
| 5.4 | `ECONOMIC / Uses existing smartphones and Avoids per-request cloud speech costs` | `ECONOMIC — no paid API, no proprietary SDK. Everything Apache 2.0 / MIT.` |
| 5.5 | — | **Add the honesty line. Do not skip it:** `Accuracy, latency and memory are measured. Field accuracy in noise, battery runtime and human TTS legibility are NOT yet measured. Benefits described here are proposed outcomes, not measured results.` |
| 5.6 | — | Fix the random mid-sentence capitalisation throughout this slide (`Happens locally`, `Uses existing smartphones and Avoids`). |

---

## Slide 6 — Research and references

| # | Old text | New text |
|---|---|---|
| 6.1 | `Voices: Piper voice repository..` | `Voices: rhasspy/piper-voices — hi_IN pratham, en_US lessac. MIT licence.` (double period) |
| 6.2 | `Model: Meta Omnilingual ASR pretrained speech-recognition research.` | `ASR: csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12 — Apache 2.0, 348.4 MB` |
| 6.3 | `Runtime / Android reference: sherpa-onnx.` | `Runtime: k2-fsa/sherpa-onnx v1.13.8 (Apache 2.0) + sherpa-onnx-v1.13.8-android.tar.bz2 native libs` |
| 6.4 | `Evaluation recordings: Google FLEURS.` | `Evaluation data: google/fleurs, CC-BY-4.0 — real recorded human speech, 2,000 clips` |
| 6.5 | `Transport: Android Wi-Fi Direct documentation.` | `Transport: Android Bluetooth RFCOMM + Wi-Fi Direct documentation (developer.android.com)` |
| 6.6 | `License check: ASR artifact is Apache 2.0; voice/runtime /dependency redistribution obligation require component-level review, including eSpeak. Do not claim "everything is permissively licensed."` | **DELETE ENTIRELY.** Replace with: `Licensing: ASR Apache 2.0 · Piper voices MIT · Silero VAD MIT · sherpa-onnx Apache 2.0 · espeak-ng-data GPL (phoneme dictionaries, flagged for redistribution review). No CC-BY-NC component ships in the product.` |

**Why 6.6 must go:** it is an internal note to yourselves, sitting in front of a judge, reading as a self-contradiction. The replacement is stronger because it names the one GPL component precisely instead of disclaiming everything vaguely.

---

## Do-not-claim list

Things the deck currently implies but the folder does **not** support. Remove or soften:

| Claim | Reality |
|---|---|
| A working two-phone link | **RFCOMM socket never established. Wi-Fi Direct group never formed.** Both `VERIFICATION_STATUS.md` and `TECH_STACK_AND_ARCHITECTURE.md` list this as HIGH risk / not verified. Bluetooth now *reaches the adapter* and Wi-Fi Direct *starts discovery* — that is real progress, but it is not a link. |
| "Works on Android phones" as a proven fact | Verified on an **API 34 arm64 emulator**, not a phone. Real mic audio never seen (emulator mic is silent). |
| All 10 languages fully supported | **Kannada, Tamil, Odia TTS voices are not implemented** — `VERIFICATION_STATUS.md` #7, "decision was espeak-ng, which is not wired up." That is a PS requirement gap. |
| Field / disaster-zone readiness | Hindi CER goes **0.133 → 0.424 at 30 dB SNR** — noise a human cannot hear. |
| Everything permissively licensed | `espeak-ng-data` is GPL. Your own slide 6 says so. |
| "10 Indian languages" | Accurate — the PS itself lists English inside its 10. But say "all 10 PS-mandated languages" so it reads as compliance. |

---

## Numbers cheat sheet

| What | Value | Source |
|---|---|---|
| Mean CER (v2, normalised) | **0.0934** | `SUMMARY_full_v2.json` |
| CER range | 0.053 (Kannada) – 0.134 (Odia) | `SUMMARY_full_v2.json` |
| WER range | 0.181 (English) – 0.509 (Odia) | `SUMMARY_full_v2.json` |
| Evaluation size | 2,000 clips, 6.72 h, 200/lang | `SUMMARY_full_v2.json` |
| ASR RTF median (desktop) | 0.039 – 0.043 | `SUMMARY_full_v2.json` |
| ASR RTF (Android emu) | 0.120 – 0.127 | `benchmark_onddevice.txt` |
| TTS RTF (Android emu) | 0.055 – 0.058 | `benchmark_onddevice.txt` |
| TTS synth time (Android) | 49 – 380 ms per sentence | `loopback_test_verified.txt` |
| Mouth-to-ear (emulator) | ~0.9 – 1.0 s | `VERIFICATION_STATUS.md` |
| Payload | 49–111 B text · **128–275 B** framed | `TECH_STACK_AND_ARCHITECTURE.md` §5.2 |
| vs 16 kbps speech codec | 13,880 B → 211 B = **~66×** | derived from measured packet |
| vs raw PCM (16 kHz 16-bit) | 222 KB → 211 B = **~1,050×** | derived — name the basis when quoting |
| ASR model | 348.4 MB, INT8, Apache 2.0 | `TECH_STACK_AND_ARCHITECTURE.md` §3 |
| TTS voices | 60.6 MB + 60.3 MB, MIT | same |
| Silero VAD | 0.6 MB, RTF 0.004, MIT | same |
| espeak-ng-data | ~7 MB, GPL | same |
| APK | 515 MB debug (540,164,991 bytes), arm64 only | same, §8 |
| APK release estimate | ~370 MB | same, §8 |
| RAM — speaker (Android) | **428 MB PSS / 535 MB RSS** | `dumpsys meminfo` |
| RAM — listener (Android) | ~100 MB, not measured | `TECH_STACK_AND_ARCHITECTURE.md` §4 |
| RAM — desktop, all models | 838 – 889 MiB | `RAM_measure_all_models.json` |
| minSdk / targetSdk | 21 (Android 5.0) / 33 | `TECH_STACK_AND_ARCHITECTURE.md` §1 |
| Own Kotlin | 7 files, 2,308 lines | `TECH_STACK_AND_ARCHITECTURE.md` §8 |
| TTS round-trip CER | English 0.030 · Hindi 0.339 | `ACCURACY_RESULTS.md` §4 |
| Noise @ 30 dB, Hindi | 0.133 → 0.424 | `NOISE_ROBUSTNESS_FINDINGS.md` |
| Bluetooth RFCOMM / Wi-Fi Direct group | **not verified** | `VERIFICATION_STATUS.md` §NOT VERIFIED |

**Unit note:** the APK is 540,164,991 bytes = 515 MiB. Your docs write "515 MB" and "528 MB" in different places. Pick **515 MB** and use it consistently — it matches the current build's MiB figure, and the 528 figure is stale.
