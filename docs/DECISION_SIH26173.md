# DECISION: SIH26173 — committed 18 September 2026

**Team:** B.J.P (Bug Janta Party) · **Deadline:** 30 September 2026 · **Days left: 12**

> This file supersedes `FINAL_DECISION_SIH26095.md` (16 Sep) and the SIH26223 work in
> `option_b_canva/`. Both are parked, not deleted. Nothing here is a claim of a completed
> submission — no portal submission has been made.

---

## The problem statement

**SIH26173 — iTantra: Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for
low bitrate links.** Organisation: ISRO, Department of Space. Category: Software. Theme: Smart
Automation.

Voice is too data-heavy for low-bitrate links. In distress scenarios you still need voice, because
it reaches people regardless of literacy. The ask: an Android app that runs **offline STT and TTS
in 10 Indian languages** on a low-power phone, sends the recognised **text** over Wi-Fi/Bluetooth,
and speaks it out on the receiving phone. Two phones should work as a walkie-talkie over
push-to-talk.

**Why this one won:** it is the only problem statement where we had already executed real
measurements rather than written research notes. See `option_a_stt/FULL_RESEARCH_DOSSIER.md`.

**Published rubric:** Efficiency 20% · Accuracy 40% · Latency 20%. These total **80%, not 100%** —
the remaining 20% is not published. Do not invent it; ask the organisers if there is time.

---

## Folder map — what is live and what is parked

| Path | Status | What it is |
|---|---|---|
| `option_a_stt/` | **LIVE** | The submission. Model, evals, TTS bench, end-to-end loop, 6-slide deck. |
| `comms_feasibility_review/` | **LIVE** | Prior-art notes (MeshSpeech, Meshtastic, bitchat, OpenSuperWhisper). Feeds §9 of the dossier. |
| `offline_build_confirmation/` | **LIVE** | Android transport + TTS + Wi-Fi Direct facts. Feeds the build plan. |
| `speech_model_research/` | **LIVE (background)** | Model survey that led to Omnilingual. Mostly superseded by the actual tests. |
| `winner_decks_verified/`, `winner_research/` | **LIVE (reusable)** | 3 verified SIH winning decks + a 6-point rubric. Use it when writing the deck. |
| `option_b_canva/` | Parked | Finished SetuRelay deck for SIH26223. |
| `hecn_canva_export/` | Parked | HECN deck exports — reference material only. |
| `offline_voice_smoke/` | Parked | Two-file smoke test. |
| `kabadi_*.json`, `osm_recyclers*.json`, `E-Waste_Rules_2022.pdf` | Parked | SIH26229 e-waste datasets. Real work, wrong PS. |
| `SIH26034_*.md`, `SIH26229_Data_Plan.md`, `FINAL_DECISION_SIH26095.md` | **Stale** | Abandoned problem statements. Kept for the reasoning trail only. |

`output/` is now in `.gitignore`. It is not part of the Scrapling library and must never be
committed there.

---

## Where the evidence actually stands

### Efficiency — 20%
| Asked | Status |
|---|---|
| Model size | **MEASURED** — ASR 348 MiB + TTS ~124 MiB + espeak 7.2 MiB + VAD 0.2 MiB ≈ 475 MiB |
| App size | **MEASURED** — official arm64 APK = 284 MiB |
| RAM footprint | **DESKTOP ONLY** — never measured on a phone |
| Idle CPU | **BY DESIGN, NOT MEASURED** — VAD-only listening (208 KB); ASR/TTS not loaded until a segment fires |

### Accuracy — 40%
| Asked | Status |
|---|---|
| Low STT WER | **MEASURED — all 10 required languages on model v2**, 200 clips each on FLEURS. Normalised CER **0.053 (Kannada) → 0.134 (Odia)**, mean **0.0934** |
| TTS legibility | **PROXY ONLY** — ASR round-trip: English CER 0.030, Hindi CER 0.339. Human panel still required |
| TTS language coverage | **7 of 10** have a neural voice. Kannada, Tamil, Odia fall back to espeak-ng. See `MODEL_AND_TTS_FINDINGS.md` |
| Language coverage | **COMPLETE** — 10 of 10 required languages measured on real human speech. No gaps |

### Latency — 20%
| Asked | Status |
|---|---|
| Word-said → STT complete | **MEASURED (Mac)** |
| Text-received → audio played | **MEASURED (Mac)** |
| RTF | **MEASURED (Mac)** — ASR 0.036–0.043, TTS 0.032–0.044 |
| Mouth-to-ear | **TARGET** — compute 0.29–0.42 s + VAD endpointing ~0.3–0.5 s + radio ~10 ms |

**The word "Mac" appears in four of the six rows above. That is now the submission's ONLY central weakness** — language coverage is complete.

---

## Critical path to 30 September

Ordered by what actually moves the score, not by what is comfortable to work on.

1. **Get the app onto a real Android phone.** The APK now exists — install it and record one real
   RTF and RAM figure. This single measurement is worth more than any further desktop work. It
   converts Efficiency and Latency from "TARGET" to "MEASURED".
   *Note: the prebuilt reference APK has already proven the model runs on a 6 GB phone, so this
   is about measuring our own app, not about feasibility.*
2. ~~Strengthen the accuracy evidence.~~ **DONE 19 Sep** — all 10 required languages measured at
   200 clips each (2,000 clips, 6.7 hours). Normalised CER 0.052–0.149. See `ACCURACY_RESULTS.md`.
3. **Reconcile the dossier with the raw numbers.** The audit found five claims that do not match
   the evidence files. See "Known issues" below.
4. **Wire the two-phone loop.** Wi-Fi Direct transport + push-to-talk. This is the demo.
5. **Human TTS legibility check.** Recruit two or three listeners per language, play the
   generated wavs in `option_a_stt/tts/`, record intelligibility. Cheap, and it is 40% of the marks.
6. **Finalise the deck** on the official 2026 template: **max 6 slides, PDF only, instruction
   slide removed.** `option_b_canva/` already proves this pipeline works.

---

## Decisions taken 20 Sep 2026

1. **Switch to model v2** (`...-ctc-v2-int8-2026-02-05`). Same 279 MiB size, mean CER 0.0992 →
   0.0934 (−5.9% relative), better on 6 of 10 languages. No cost to switching.
2. **Ship espeak-ng for Kannada, Tamil and Odia**, not MMS-TTS. Keeps the whole shipped stack at
   MIT / Apache-2.0 / GPL with zero CC-BY-NC. State the quality limitation openly.
3. **Android app BUILDS.** `option_a_stt/android/iTantraApp/` — Kotlin, Bluetooth + Wi-Fi Direct
   transports, TTS playback, language filter. Toolchain installed 20 Sep at `~/android-dev/`
   (JDK 17 + Android SDK 34 + Gradle 8.2). **521 MB debug APK produced and verified.**
   Not yet installed on a device.
4. **The prebuilt APK is confirmed working on a real 6 GB phone.** Stage 0 ("does the model run
   on Android at all") is answered: yes.
5. **Our own app now runs.** Built, installed and launched on an Android 34 emulator. The 348 MB
   model loads; **428 MB PSS / 535 MB RSS** measured. Phone-class CPU latency still unmeasured.
6. **The language filter now enforces the expected script**, not just "one of the nine". Noise
   testing showed Hindi flips to Arabic or Gujarati script under inaudible noise, which the
   original filter would have transmitted as unreadable text. See `NOISE_ROBUSTNESS_FINDINGS.md`.
7. **Open decision: which languages to demonstrate.** English and Bengali hold 100% script
   retention to 10 dB SNR; Hindi falls to ~50% at 15 dB. Either demonstrate in English + Bengali,
   or keep Hindi and add a read-back confirmation step.

---

## Known issues — status after the 18 Sep re-measurement

The audit found inconsistencies between the dossier prose and the raw evidence files. **All but one
are now fixed**, and the re-measurement found two more serious problems.

| # | Issue | Status |
|---|---|---|
| 1 | Dossier claimed TTS peak RAM 273–285 MiB; evidence showed English at 364–366 MiB | ✅ **Fixed** — replaced with a proper all-models measurement (838–889 MiB) |
| 2 | "~1.1 GiB peak RAM with all models" had no backing file | ✅ **Fixed** — `measure_ram_all.py` measured it; real figure is ~838 MiB. The claim was *overstated*, not understated |
| 3 | `REAL_ASR_RESULTS.md` cited `asr_eu.json`, which did not exist | ✅ **Fixed** — `asr_run.py` writes `asr_result.json`; copied into place |
| 4 | Hindi WER quoted as 0.444 in one place and 0.42 in another | ✅ **Fixed** — superseded by the 200-clip numbers |
| 5 | `VadAsr_MainActivity.kt` was a 0-byte duplicate | ✅ **Fixed** — removed |
| 6 | Gujarati + Kannada tarballs truncated | ✅ **Fixed** — re-downloaded 19 Sep, along with Tamil, Telugu, Malayalam and English |
| 7 | **Odia's "headline" CER 0.083 was a 6-clip artifact** | ✅ **Fixed** — real value is **0.143**. Odia is now the worst language on WER |
| 8 | **Marathi was wrongly written off as a failure (WER 0.744)** | ✅ **Fixed** — real value is **0.415**, mid-pack. Removed from the risk list and from the deck |
| 9 | **Every RAM figure used the wrong unit** (`ru_maxrss` is bytes on macOS, not KB) | ✅ **Fixed** — `measure_ram_all.py` handles both platforms |
| 10 | **Hindi TTS round-trips poorly** (CER 0.339 vs ASR-only 0.141) | ⬜ **NEW — open.** Cause not isolated. Most important open accuracy question |

Full detail and method: `option_a_stt/ACCURACY_RESULTS.md`.

---

## The scope claim — read this before writing the deck

**Do not promise 10 languages as a flat claim.** Measured on real speech: Odia, Hindi, Bengali,
Marathi, and English. Marathi is weak. The model's 1,668 language IDs *include* the other required
languages, but coverage is not the same as measured accuracy.

**Hinglish does not exist in the model.** All 1,668 language IDs were checked: `hin_Deva` and
`eng_Latn` exist; `hin_Latn` (romanised Hindi) does not. Zero romanised Indian languages are
supported. Never claim ASR outputs Hinglish.

Honest framing for the submission:

> *"Demonstrated end-to-end in Hindi and English, with per-language accuracy measured on the FLEURS
> real-speech benchmark. Strong in Odia, Bengali and English; Hindi moderate; Marathi currently
> weak. The selected model covers all ten required language IDs; remaining languages are queued
> for measurement."*
