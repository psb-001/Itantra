# BRIEF — per-language pronunciation audit (all 7 bundled voices)

You are working in /Users/prathameshbhujbal/iTantra-SIH26173 — the iTantra offline voice
transceiver (SIH26173). Read this brief fully, then execute it to completion.
**Do NOT modify anything under `app/`. Do NOT git commit anything.** When the report file is
written, stop.

## Why this exists

The app turns speech into text on one phone and text back into speech on the other. A Hindi-only
text normaliser (`app/app/src/main/java/com/bjp/itantra/SpeechText.kt`: a Latin→Devanagari loanword
map plus acronym letter-spacing, gated on Devanagari voices) was measured to lift Hindi round-trip
similarity from 0.77 to 0.93 mean. The user now asks to fine-tune **all** languages: for each
language, where does the text → speech → (ASR back) chain hurt, and would a language-specific
normaliser rule measurably help?

## Bundled voices (in `app/app/src/main/assets/tts/`)

- hi_IN-pratham-medium
- mr_IN-google-medium
- bn_BD-google-medium
- ta_IN-rasa_female-medium
- te_IN-venkatesh-medium
- ml_IN-arjun-medium
- en_US-lessac-medium

Shared espeak data: `app/app/src/main/assets/tts/espeak-ng-data`.
ASR model: `app/app/src/main/assets/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8/`.

## Read first (reuse, do not reinvent)

- `eval/probe_normaliser_roundtrip.py` — how to build TTS + ASR from the app assets, synthesise,
  ASR back, and compute similarity. Its helpers are the reference implementation.
- `eval/probe_hindi_numbers.py` — the digit diagnostic and its caveat.
- `eval/language_filter.py`, `eval/probe_hinglish_routing.py` — the routing/noise rules.
- `app/app/src/main/java/com/bjp/itantra/SpeechText.kt` — mirror its exact rules in the probe
  (loanword map is Hindi-voice-only; acronym letter-spacing for sos/fir/ndrf/sdrf/cbi).
- `docs/MODEL_AND_TTS_FINDINGS.md` (Addenda 1–3) and `research/tts/voices.md` — measured facts you
  must not contradict.

Run everything as: `.venv/bin/python <probe> 2>&1 | grep -v "resampler\|in_sample_rate\|output_sample_rate"`

## Measured facts you must respect

- VITS synthesis is stochastic: a single run swings ±0.1. **3 runs, report the mean.**
- Digits come back spelled as words in the ASR text; that is a spelling artefact, **not** a bug —
  the audio is correct. Do not report digit "failures" unless the AUDIO is wrong.
- The pruned model cannot emit scripts outside the nine allowed ones.

## Sentences

Choose natural, emergency-relevant sentences yourself (8–14 words each), but each language's
set must include:

1. a sentence containing an English loanword that speakers of that language actually use
   (e.g. school / hospital / police / ambulance — the natural code-mixed form);
2. a sentence containing a number (108 or 500);
3. an acronym if one is natural in that language (NDRF, SOS, FIR, CBI).

## What to measure (per language, all 7 including English)

- **(A) Round-trip**: intended text → bundled voice → ASR → heard text. Similarity(intended,
  heard), 3-run mean + min/max. This is the app's real receive path.
- **(B) Loanword script**: inside bn/ta/te/ml/mr/hi text, what script does the ASR write the
  English loanword in — the native script or Latin? (The Hindi rules exist because the ASR wrote
  Latin. If Bengali/Tamil/…-back text keeps loanwords in the native script, no map is needed.)
  Show the actual heard strings — they are the evidence.
- **(C) Digits**: is the audio correct? (Text will spell them out; that is expected.)
- **(D) Conditioners**: run the `SpeechText.kt` rules (python mirror) over each sentence; report
  whether they change anything for non-Hindi languages, and whether the change helps or hurts
  the round-trip score (A with vs without).

## Deliverables

1. `eval/probe_language_normalisers.py` — self-contained, deterministic, prints the results table.
2. `research/tts/language_normalisers.md` — the table plus a findings section with a clear
   per-language verdict:
   - "chain fine — no rule needed", or
   - "rule X would help because measured Y" (quote the actual heard strings and numbers).
   - Honest negative results are wanted; do not invent an improvement.

## Rules

- Only write under `eval/` and `research/tts/`. Never touch `app/`, never commit.
- If a measurement looks wrong, re-run it 3 times before reporting it.
- Keep total compute under ~30 minutes; parallelise where easy.
- If something blocks you for more than ~10 minutes, note it honestly in the report and move on.
  Do not stop early without writing the report.
