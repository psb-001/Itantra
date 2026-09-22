# Option A — FIRST REAL ASR EVIDENCE (not a claim, a measurement)

> ⚠️ **PARTIALLY SUPERSEDED — 18 Sep 2026.** The accuracy table below used only **6 clips per
> language**. Re-measured on **200 clips** it changed materially: Odia was ~1.7× optimistic, and
> **Marathi was wrongly written off** (WER 0.744 → 0.415). See **`ACCURACY_RESULTS.md`** for the
> current numbers. **Do not quote the table in this file.**

Run: 18 Sep 2026, local macOS arm64 (Apple Silicon, 17 GB RAM). **This is a Mac, NOT an Android phone.**
Phone numbers remain unmeasured; these results prove the model executes and quantify desktop accuracy/latency only.

## What actually ran

- Model: `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`
- Runtime: sherpa-onnx 1.13.8, CPU provider, 8 threads
- Model size on disk: **365,352,120 bytes (348 MiB)**
- Model load time: **0.528 s** (one run)
- License of the downloaded artifact: **Apache 2.0** (Meta Platforms, file `LICENSE`, verified by reading it)
- Source repo: `csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`

### IMPORTANT CORRECTION to earlier research
The fact sheet said the model came from `k2-fsa/...`. That repo path **returns HTTP 401 (gated/private)**.
The genuinely public, downloadable copy lives under maintainer **`csukuangfj/`**. The earlier claim "Apache 2.0, freely downloadable"
is now verified against the real artifact, but only from the `csukuangfj` namespace.
Also note `facebook/omnilingual-asr` itself reads as gated via the API; the sherpa-onnx export is the accessible route.

## Measured accuracy on REAL recorded human speech (FLEURS test set, CC-BY-4.0)

> ⚠️ **SUPERSEDED.** The table below is the original **6-clip** run. It is kept only to show what
> changed. Use `ACCURACY_RESULTS.md` / `SUMMARY_full.json` instead.

Ground truth = FLEURS `raw_transcription`. 6 clips per language. Real speakers, not synthetic TTS.

| Language | WER | CER | RTF | Audio s | Recognize s |
|---|---|---|---|---|---|
| Odia (or_in) | 0.402 | 0.083 | 0.036 | 64.1 | 2.29 |
| Hindi (hi_in) | 0.444 | 0.239 | 0.038 | 65.5 | 2.46 |
| Bengali (bn_in) | 0.313 | 0.089 | 0.038 | 113.6 | 4.36 |
| Marathi (mr_in) | 0.744 | 0.229 | 0.036 | 67.3 | 2.42 |

### At 200 clips (corpus-level, normalised) — the numbers that replace the above

| Language | WER norm | CER norm | RTF med |
|---|---|---|---|
| Bengali | 0.337 | **0.094** | 0.039 |
| Marathi | 0.415 | **0.108** | 0.038 |
| Hindi | 0.305 | **0.141** | 0.039 |
| Odia | 0.504 | **0.143** | 0.041 |

**Odia went from 0.083 to 0.143. Marathi went from 0.744 to 0.415.** Both changes matter.

Sherpa's own bundled wavs (European): English transcribed **perfectly**
("ask not what your country can do for you ask what you can do for your country"),
German and French correct, Spanish ~correct. RTF 0.038–0.043.

**RTF ≈ 0.036–0.043 ⇒ ~25× faster than real time on this Mac.**

### What the transcripts look like
- Hindi (WER 0.42): REF `स्कीइंग मार्ग को एक हाईकिंग...` → HYP `स्कींग मार्ग को एक हाइकिं...` — meaning intact, spelling slips.
- Bengali (WER 0.32): near-identical to reference, minor spelling variants.
- Marathi (WER 0.57/0.74): **WRONG CALL — see below.**
- Odia (WER 0.40 / **CER 0.083**): **WRONG CALL — see below.**

## Honest interpretation (this changes the Option A position)

1. **The core claim is now TRUE and demonstrated**: one 348 MiB Apache-2.0 model, fully offline,
   transcribes Odia + Hindi + Bengali with usable accuracy, at ~25× real time. Earlier this was a
   researched claim; now it is a reproduced result.
2. **Odia coverage is confirmed, not assumed** — stock Whisper cannot transcribe Odia at all, and
   this model can, at CER 0.143. The *coverage* gap is closed. The *accuracy* is ordinary, not
   exceptional — the earlier 0.083 was a 6-clip artifact.
3. **Marathi is NOT a problem — the earlier call was wrong.** At 200 clips, WER is 0.415 and CER
   0.108, better than Odia on both. Reading the transcripts confirms the meaning survives; the
   errors are spacing and spelling. Marathi has been removed from the risk list.
   The flat 10-language claim still cannot be made, but the reason is *unmeasured* languages, not
   Marathi.
4. **Accuracy is spelling-level, not punctuation/case.** Output has no punctuation and no
   capitalisation (CTC greedy decode). Any UI must not promise punctuated transcripts.
5. **Still unmeasured**: Kannada, Malayalam, Tamil, Telugu, Gujarati (Gujarati download was truncated
   by network failures — not a model failure), and **English on FLEURS** (only tested on sherpa's own wav).
   4 of 10 languages are measured; 6 are not.
6. **Still unmeasured**: Android. 348 MiB on a Mac with 17 GB RAM says nothing about a ₹8,000-budget
   phone with 2–4 GB RAM. The RTF number will be worse on a phone CPU. This is the biggest
   remaining unknown and it gates the whole hardware story.

## Why WER looks high but CER is low
These scripts are abugida/complex-script languages where one phoneme error changes several
characters but the word stays recognisable to a reader. WER punishes the whole word; CER shows the
true error is ~8–9% for Odia/Bengali. **Report CER alongside WER** in the submission or the numbers
look 5× worse than the user experience.

## Files
- `asr_run.py` / `asr_eu.json` — sherpa bundled European wavs
- `fleurs_eval.py` / `asr_{or,hi,bn,mr}_in.json` — FLEURS real-speech eval, per-clip ref/hyp/WER/CER
- `SUMMARY_table.json` — the table above as data
- `omni300m/` — the model itself (Apache 2.0), `fleurs/` — test audio
