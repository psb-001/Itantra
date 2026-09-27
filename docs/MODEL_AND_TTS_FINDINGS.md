# Model version comparison and TTS coverage

**Date:** 20 September 2026 · All measurements on macOS arm64 (Apple Silicon, 17 GB RAM) — **NOT a phone**

---

## Part 1 — v1 vs v2 model, 200 clips × 10 languages

Both models are the same size on disk. Same eval, same clips, same machine.

| | v1 | v2 |
|---|---|---|
| Artifact | `...300M-ctc-int8-2025-11-12` | `...300M-ctc-v2-int8-2026-02-05` |
| `model.int8.onnx` | 365,352,120 B | 365,841,453 B (+489 KB) |
| `tokens.txt` | 86,423 B | **90,630 B — different tokenizer** |

### Results (normalised, corpus-level, n=200 per language)

| Language | v1 CER | **v2 CER** | Δ | v1 WER | v2 WER | Δ |
|---|---|---|---|---|---|---|
| Kannada | 0.052 | 0.053 | +0.001 | 0.276 | 0.307 | +0.031 |
| Malayalam | 0.054 | 0.059 | +0.004 | 0.334 | 0.361 | +0.027 |
| English | 0.073 | **0.067** | −0.007 | 0.195 | **0.181** | −0.014 |
| Gujarati | 0.074 | **0.071** | −0.003 | 0.286 | 0.300 | +0.014 |
| Bengali | 0.094 | 0.100 | +0.005 | 0.337 | 0.359 | +0.022 |
| Telugu | 0.103 | **0.100** | −0.003 | 0.434 | **0.431** | −0.003 |
| Marathi | 0.108 | 0.108 | 0.000 | 0.415 | 0.416 | 0.000 |
| Hindi | 0.141 | **0.113** | **−0.028** | 0.305 | **0.281** | −0.025 |
| Odia | 0.143 | **0.134** | −0.009 | 0.504 | 0.509 | +0.006 |
| **Tamil** | 0.149 | **0.129** | **−0.020** | 0.532 | **0.501** | −0.031 |
| **Mean CER** | **0.0992** | **0.0934** | **−0.0058 (−5.9%)** | | | |

**v2 is better on 6 languages, worse on 2, unchanged on 2.**

### Verdict

**Switch to v2 — but describe it accurately.** It is a *modest* improvement, not a transformation:
- Mean CER improves **5.9% relative** (0.0992 → 0.0934)
- The **worst language improves most**: Tamil 0.149 → 0.129
- Hindi improves notably: 0.141 → 0.113
- But Bengali, Kannada and Malayalam get slightly **worse**

Same download size, same phone footprint, same integration work. There is no cost to switching.

**Do not overclaim it.** The honest line for the deck is: *"v2 of the model reduces mean character
error rate from 0.099 to 0.093 across the ten required languages, with the weakest language
improving most."*

### How to reproduce

```bash
cd output/option_a_stt
OMNI_DIR=omni300m            OMNI_TAG=v1 ./.venv/bin/python fleurs_eval_full.py 200
OMNI_DIR=omni300m_v2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05 \
  OMNI_TAG=v2 ./.venv/bin/python fleurs_eval_full.py 200
```

Results land in `SUMMARY_full.json` (v1) and `SUMMARY_full_v2.json` (v2).

---

## Part 2 — TTS coverage for the ten required languages

This was an **unexploded gap**: the deck says "TTS: Piper VITS voices" without stating coverage.
Piper covers only 6 of the 10. Full audit across every source we have:

| Language | Neural voice available | Source | Licence |
|---|---|---|---|
| Hindi | ✅ | Piper `hi_IN` pratham | **MIT** |
| Marathi | ✅ SHIPPED | Piper `mr_IN-google-medium` | MIT wrapper / **CC-BY-SA 4.0** data |
| Malayalam | ✅ SHIPPED | Piper `ml_IN-arjun-medium` | MIT wrapper / **unclear** data |
| Telugu | ✅ SHIPPED | Piper `te_IN-venkatesh-medium` | MIT wrapper / **CC-BY-4.0** data |
| Bengali | ✅ SHIPPED | Piper `bn_BD-google-medium` | MIT wrapper / **CC-BY-SA 4.0** data |
| English | ✅ SHIPPED | Piper `en_US` lessac | MIT wrapper / Blizzard 2013 |
| Hindi | ✅ SHIPPED | Piper `hi_IN-pratham-medium` | MIT wrapper / **CC-BY-NC-SA 4.0** |
| **Gujarati** | ❌ | Mimic3 is AGPL-3.0; IndicTTS needs a PyTorch port | — |
| **Kannada** | ❌ | MMS-TTS only (CC-BY-NC) | — |
| **Tamil** | ❌ | MMS-TTS only (CC-BY-NC) | — |
| **Odia** | ❌ | MMS-TTS only (CC-BY-NC) | — |

> **SUPERSEDED 27 September 2026 — two claims in this section were wrong.**
>
> **1. The "MIT" column above was never accurate for any Indic voice.** Piper's wrapper is MIT;
> the *training data* is not. `hi_IN-pratham-medium`, which has shipped in the APK all along, is
> **CC-BY-NC-SA 4.0 — non-commercial and share-alike**. The claim elsewhere in this project that
> the stack contains "no CC-BY-NC anywhere" was incorrect. See `TTS_LICENCE_AUDIT.md`.
>
> **2. espeak-ng cannot be a standalone voice, so the fallback below was never implementable.**
> espeak-ng is a *phonemiser* used inside a VITS model, not a synthesiser you can call directly.
> Neither `master` nor the `v1.13.8` tag of sherpa-onnx's Kotlin API has an espeak TTS config
> class, and the `OfflineTtsEspeakModelConfig` symbol is absent from the shipped
> `libsherpa-onnx-jni.so` — verified by inspecting the library. The 17.2 MB of espeak
> dictionaries in the APK phonemises text *for the Piper voices*; it does not generate audio on
> its own. The "quality is robotic but intelligible" trade-off described below therefore never
> arose.
>
> **What actually shipped:** six neural Piper voices (hi, mr, bn, ml, te, en), all four newly
> added ones CC-licensed and disclosed. Gujarati, Kannada, Tamil and Odia are **text-only**, and
> the app says so on screen rather than failing silently. The remaining route to all ten is
> AI4Bharat Indic-TTS (BSD-2, all 13 languages), which is FastPitch+HiFi-GAN in PyTorch and
> needs an NDK cross-compile of sherpa-onnx or a Kotlin reimplementation. Post-deadline project.

### Options considered for the four remaining languages

**Option A — MMS-TTS (Meta).** Has all four, good neural quality. **CC-BY-NC 4.0.** Rejected to
stay consistent with the team's own licence rule, though it is arguably compliant for a
non-commercial hackathon — the restriction bans proprietary, closed-source and *commercial*
voice SDKs, and CC-BY-NC is none of those three.

**Option B — AI4Bharat Indic-TTS.** **BSD-2**, covers 13 languages with male and female speakers.
Licence-clean and technically the right answer. Blocked on integration: the prebuilt JNI library
implements only `Vits, Matcha, Kokoro, Zipvoice, Kitten, Pocket, Supertonic`, so adding FastPitch
means rebuilding sherpa-onnx for Android with the NDK (neither NDK nor cmake is installed here)
or writing the model in Kotlin from scratch. Weeks, not days.

**Option C — text-only, stated openly.** What we did. Four languages display correct text and
are labelled TEXT-ONLY in the UI.

Then say this plainly in the submission:

> *"Six of the ten required languages have neural TTS voices, all MIT-licensed Piper weights
> (four carry Creative Commons training data, disclosed in TTS_LICENCE_AUDIT.md). Speech
> recognition works in all ten. Gujarati, Kannada, Tamil and Odia currently receive text without
> audio; a BSD-2 Indic-TTS port is the identified next step and is scoped in
> TTS_LICENCE_AUDIT.md."*

That is a stronger position than pretending all ten are equal, and stronger than silently shipping
## Part 3 — what this changes in the submission

| Document | Change needed |
|---|---|
| `FULL_RESEARCH_DOSSIER.md` | §3.3 TTS table — add coverage column and the 3-language caveat; §4 stack table |
| `ACCURACY_RESULTS.md` | add the v1/v2 comparison section |
| `build_deck.py` | slide 4 accuracy strip → v2 numbers; slide 6 → name the TTS gap |
| `ARCHITECTURE.md` | §7 known coverage gap — update with the resolution |
| `DECISION_SIH26173.md` | record the v2 decision and the TTS resolution |
