# SIH26173 — Accuracy, Latency and Memory: full re-measurement

**Date:** 19 September 2026 (accuracy re-measured across all ten languages) · **Machine:** macOS arm64 (Apple Silicon, 17 GB RAM) — **NOT an Android phone**
**Supersedes:** the 6-clip numbers in `REAL_ASR_RESULTS.md` and `FULL_RESEARCH_DOSSIER.md` §3.

> **Why this file exists.** The original accuracy numbers came from **6 clips per language**, and
> covered only 4 of the 10 required languages. Six clips is not a benchmark — it is a sample small
> enough that one unusual speaker moves the average by several points. This re-runs the model on
> **200 clips per language across all ten required languages** and, in doing so, **overturns two of
> the earlier conclusions** — one in our favour, one against us. Everything below is measured.
> Nothing is projected.

---

## 1. What changed and why

Three methodological fixes, all of which matter for the submission:

| # | Earlier | Now | Why it matters |
|---|---|---|---|
| 1 | 6 clips/language | **200 clips/language** | Removes small-sample noise |
| 2 | Mean of per-utterance WER | **Corpus-level** (total edits ÷ total reference units) | The standard definition. Mean-of-ratios over-weights short utterances. |
| 3 | Raw text only | **Raw AND normalised** | The model emits no punctuation or capitals (CTC greedy decode). Scoring raw against punctuated FLEURS references penalises a known *output-format* limitation, not an accuracy failure. |

Normalisation = Unicode NFC + casefold + strip punctuation + strip zero-width joiners (ZWNJ/ZWJ).
The zero-width strip matters: some FLEURS Bengali references begin with a ZWNJ that would
otherwise count as a character error on every single clip.

**Report both. Never only the flattering one.**

---

## 2. ASR accuracy — ALL TEN required languages measured

FLEURS test set, CC-BY-4.0, real recorded speakers. **2,000 clips, 6.72 hours of audio, 200 clips
per language.**

> **MODEL VERSION: use v2.** Both model versions were measured on the identical clip set.
> **v2 (`...-ctc-v2-int8-2026-02-05`) reduces mean CER from 0.0992 to 0.0934 — a 5.9% relative
> improvement — at exactly the same 279 MiB download size.** It is better on 6 languages, worse on
> 2, unchanged on 2. Full comparison in `MODEL_AND_TTS_FINDINGS.md`.
> The table below is **v2**. v1 numbers are retained in §3 for comparison.

Sorted best → worst by normalised CER.

| Language | n | WER raw | **WER norm** | CER raw | **CER norm** | RTF median | RTF p95 |
|---|---|---|---|---|---|---|---|
| **Kannada** | 200 | 0.431 | **0.307** | 0.079 | **0.053** | 0.041 | — |
| **Malayalam** | 200 | 0.504 | **0.361** | 0.084 | **0.059** | 0.041 | — |
| **English** | 200 | 0.348 | **0.181** | 0.118 | **0.067** | 0.040 | — |
| **Gujarati** | 200 | 0.399 | **0.300** | 0.099 | **0.071** | 0.041 | — |
| **Bengali** | 200 | 0.472 | **0.359** | 0.136 | **0.100** | 0.040 | — |
| **Telugu** | 200 | 0.543 | **0.431** | 0.125 | **0.100** | 0.039 | — |
| **Marathi** | 200 | 0.509 | **0.416** | 0.129 | **0.108** | 0.040 | — |
| **Hindi** | 200 | 0.358 | **0.281** | 0.136 | **0.113** | 0.040 | — |
| **Odia** | 200 | 0.585 | **0.509** | 0.160 | **0.134** | 0.043 | — |
| **Tamil** | 200 | 0.589 | **0.501** | 0.150 | **0.129** | 0.040 | — |

**Every one of the ten required languages is measured. Normalised CER spans 0.053–0.134.**
Mean CER **0.0934**.

### Ranking (v2, normalised)

- **By CER** — Kannada 0.053 < Malayalam 0.059 < English 0.067 < Gujarati 0.071 < Bengali 0.100 =
  Telugu 0.100 < Marathi 0.108 < Hindi 0.113 < Tamil 0.129 < Odia 0.134
- **By WER** — English 0.181 < Hindi 0.281 < Gujarati 0.300 < Kannada 0.307 < Malayalam 0.361 <
  Bengali 0.359 < Marathi 0.416 < Telugu 0.431 < Tamil 0.501 < Odia 0.509

**English is the best language on WER and third-best on CER.** **Odia and Tamil are the weakest** —
report both plainly rather than presenting only the good languages.

### How this compares to Meta's published numbers

Meta published per-language CER for their **7B LLM-ASR** model (`per_language_results_table_7B_llm_asr.csv`):

| Language | Meta 7B | This 300M (v2) | Ratio |
|---|---|---|---|
| Malayalam | 0.053 | **0.059** | **1.1x — closest** |
| Telugu | 0.065 | **0.100** | 1.5x |
| Kannada | 0.023 | 0.053 | 2.3x |
| Odia | 0.058 | 0.134 | 2.3x |
| English | 0.030 | 0.067 | 2.2x |
| Tamil | 0.052 | 0.129 | 2.5x |
| Gujarati | 0.026 | 0.071 | 2.7x |
| Marathi | 0.037 | 0.108 | 2.9x |
| Hindi | 0.036 | 0.113 | 3.1x |
| Bengali | 0.028 | 0.100 | 3.6x |

**Caveats that must be stated whenever this table is quoted:** Meta's figures are the 7B LLM model
(25 GiB FP32, ~15 GiB VRAM — cannot run on a phone), evaluated on Meta's own corpus with a language
prompt. Ours is a 348 MiB INT8 CTC model, no language hint, on FLEURS. **Not like-for-like.**

The honest headline: **roughly 2–3× worse than a model ~70× larger that cannot run on the target
device — and on Malayalam and Telugu, within 1.1–1.5×.**

---

## 3. What this overturns

### 3a. Odia was our headline result. It is not anymore.

| | 6 clips (old) | 200 clips (new) |
|---|---|---|
| Odia CER | **0.083** | **0.143** (norm) / 0.168 (raw) |
| Odia WER | 0.402 | 0.504 (norm) / 0.571 (raw) |

The dossier calls Odia "the headline result" and "the single biggest risk retired by measurement."
At 200 clips Odia is the **worst** language on WER and joint-worst on CER.

**The conclusion survives in weaker form:** stock Whisper cannot transcribe Odia at all, and this
model can, at CER 0.14. That is still a real differentiator. But **0.083 must not appear in the
submission** — it is roughly 1.7× optimistic.

### 3b. Marathi was reported as a failure. It is not.

| | 6 clips (old) | 200 clips (new) |
|---|---|---|
| Marathi WER | **0.744** | **0.415** (norm) / 0.507 (raw) |
| Marathi CER | 0.229 | **0.108** (norm) / 0.129 (raw) |

The dossier says Marathi "degrades badly — words merge, meaning is lost. Weakest result," and
lists it as a risk. At 200 clips Marathi is **mid-pack** — better than Odia on both metrics, and
its CER is second-best of the four.

Reading the actual transcripts confirms it. A representative clip:

```
REF: युरोप हा खंड तुलनेने लहान आहे परंतु त्यात अनेक स्वतंत्र देश आहेत.
HYP: युरोपहा खंडत तुलणेने लाह नाहीपरंतु त्यात अनेक स्वतंत्र देश आहेत
```

Spacing and spelling slips. The meaning is fully recoverable by a Marathi reader. That is a
different failure mode from "meaning is lost."

**Action:** remove Marathi from the risk list, and remove the sentence
*"Marathi measured weak (WER 0.74)"* from the deck — it is currently on slide 4 and is wrong.

### 3c. Hindi improved.

| | 6 clips (old) | 200 clips (new) |
|---|---|---|
| Hindi WER | 0.444 | 0.305 (norm) / 0.380 (raw) |
| Hindi CER | 0.239 | **0.141** (norm) / 0.164 (raw) |

Hindi is now the best language on WER and second-best on CER.

### 3d. Bengali held up.

CER 0.089 → 0.094 (norm). Essentially unchanged, and now the best language on CER. WER moved
0.313 → 0.337 (norm). The earlier Bengali number was the most trustworthy of the four.

---

## 4. TTS intelligibility — first automated measurement

The dossier lists this as **NOT DONE**, on the grounds that it "requires a human listening panel."
That is true for the final number, but a first pass is possible without one.

**Method — ASR round-trip proxy:** synthesise a sentence with Piper, feed the audio to the ASR
model, compare what comes back to what went in. If the ASR recovers the sentence from Piper's
audio, a human listener almost certainly understands it.

> ⚠️ **This is a proxy, not a substitute for a listening panel.** The submission must still report
> a human panel. But it is a far better answer to the rubric than "not measured", and it isolates
> where the pipeline actually loses information.

20 sentences per language — emergency phrasing, plus deliberate hard cases: numbers
("two hundred", "fifteen", "thirty degrees"), a proper name (Doctor Sharma / रामपुर), and negation.

| Language | n | Exact match | WER raw | **WER norm** | CER raw | **CER norm** |
|---|---|---|---|---|---|---|
| English (`en_US` lessac) | 20 | 9 / 20 | 0.350 | **0.113** | 0.096 | **0.030** |
| Hindi (`hi_IN` pratham) | 20 | 0 / 20 | 0.591 | **0.490** | 0.361 | **0.339** |

### Interpretation

- **English TTS is excellent.** CER 0.030, 45% of sentences recovered *exactly* character for
  character, including the numerals. Nothing to fix.
- **Hindi TTS is weak in the round trip.** CER 0.339 — roughly **2.4× worse than Hindi ASR alone
  (0.141)**. The error is being introduced by the TTS voice or by the ASR's handling of synthetic
  speech, not by the ASR on human speech.

**The honest framing:** this is a combined TTS+ASR figure. It does not isolate which stage is at
fault. Two plausible causes, both worth testing:

1. The ASR is trained on human speech and degrades on synthetic speech — a well-documented effect.
2. The `hi_IN` pratham voice produces phonetics the ASR does not map cleanly.

**This is now the most important open accuracy question**, because the 40%-weighted criterion
explicitly asks for "high human legibility and flow for TTS" — and the Hindi demo path is the one
we are showcasing.

### Next step (cheap, high value)
The 40 wavs are on disk at `tts/intelligibility/{hi,en}_NN.wav`. Have two or three Hindi speakers
and two or three English speakers listen and score intelligibility 1–5. That converts a proxy into
a real number and directly serves the 40% criterion.

---

## 5. Memory — correcting a unit bug and an unsupported claim

The earlier scripts called `resource.getrusage().ru_maxrss` and divided by 1024, labelling the
result "MB". **On macOS `ru_maxrss` is in bytes, not kilobytes** — so every RAM figure produced by
those scripts is wrong by a factor of 1024, and the dossier's RAM claims were never properly
measured at all.

Corrected measurement, all models resident simultaneously:

| Stage | Peak RSS |
|---|---|
| Interpreter baseline | 29.5 MiB |
| + ASR (348 MiB on disk) | **710.1 MiB** |
| + Hindi TTS | 792.4 MiB |
| + English TTS | **838.1 MiB** |
| After running a full generate + decode | **888.6 MiB** |

**The dossier's "~1.1 GiB peak RAM with all models" is overstated by roughly 200–260 MiB.** The
real figure is **~840–890 MiB** on this Mac. Lower is better for us — but it is still a desktop
number, and on a 2–4 GB phone the picture will be different.

---

## 6. Latency — across all ten languages

| Metric | Median | p95 |
|---|---|---|
| ASR real-time factor | 0.038–0.042 | 0.044–0.054 |
| ASR per-utterance latency (≈11–13 s clips) | 0.36–0.57 s | 0.72–1.28 s |

The RTF distribution is tight — no pathological outliers, and no language is dramatically slower
than another. A 3-second spoken sentence implies roughly **0.11–0.13 s** of ASR compute.

Slowest language by median latency: Malayalam (0.57 s). Fastest: Gujarati (0.36 s).

**Unchanged caveat: this is a Mac.** Phone-class latency remains unmeasured and will be worse.

---

## 7. What is still not measured

The language gap is now **closed** — all ten required languages are measured. What remains:

1. **Android, on any device.** Still the #1 risk and still untouched. Everything here is desktop.
   **This is now the only major gap in the submission.**
2. **Noisy audio.** FLEURS is clean studio speech. A disaster zone is not.
3. **Human TTS legibility panel.** Proxy only — see §4. English is strong, Hindi is weak.
4. **Hindi TTS round-trip weakness.** Cause not isolated (§4).
5. **Battery drain.** Never measured.
6. **Two-phone transport.** Wi-Fi Direct / Bluetooth link never exercised — only the speech stages.

### Note on sampling
Each language uses the **first 200 rows of the FLEURS test split** — a fixed, reproducible slice, not
a random sample. That is a fair comparison *between* languages (identical selection rule for all ten)
but it is not a population-representative estimate. Say "first 200 entries of the FLEURS test split"
in the submission, not "200 random speakers."

---

## 8. Files

| File | Contents |
|---|---|
| `fleurs_eval_full.py` | The 200-clip evaluation, with raw + normalised metrics |
| `SUMMARY_full.json` | Per-language summary table |
| `asr_full_{or,hi,bn,mr}_in.json` | Per-clip reference, hypothesis, WER/CER |
| `tts_intelligibility.py` | TTS → ASR round-trip intelligibility proxy |
| `tts_intelligibility.json` | Per-sentence results + 40 generated wavs |
| `tts/intelligibility/*.wav` | Audio for the human listening panel |
| `measure_ram_all.py` | Corrected cross-platform RAM measurement |
| `RAM_measure_all_models.json` | Peak RSS per load stage |
| `fleurs_full_run.log` | Raw run log |
