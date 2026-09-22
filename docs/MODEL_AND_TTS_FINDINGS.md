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
| Marathi | ✅ | Piper `mr_IN` | **MIT** |
| Malayalam | ✅ | Piper `ml_IN` arjun / meera | **MIT** |
| Telugu | ✅ | Piper `te_IN` | **MIT** |
| Bengali | ✅ | Piper `bn` | **MIT** |
| English | ✅ | Piper `en_US` lessac | **MIT** |
| **Gujarati** | ✅ | **Mimic3 `gu_IN` cmu-indic** (in sherpa-onnx) | check before shipping |
| **Kannada** | ❌ | only MMS-TTS or espeak-ng | — |
| **Tamil** | ❌ | only MMS-TTS or espeak-ng | — |
| **Odia** | ❌ | only MMS-TTS or espeak-ng | — |

### The two options for Kannada, Tamil and Odia

**Option A — MMS-TTS (Meta).** Available for all three: `facebook/mms-tts-kan`, `-tam`, `-ory`.
Good neural quality. **Licence: `cc-by-nc-4.0` — non-commercial.**

> Read the PS restriction precisely: *"Proprietary, closed-source or commercial voice-activation
> SDKs are strictly prohibited."* CC-BY-NC is none of those three — the weights are public and
> open. It restricts *commercial use*, which a student hackathon is not. So it is arguably
> compliant. But it is a licence a judge could question, and it needs stating openly rather than
> being buried.

**Option B — espeak-ng.** Already in our stack: `tts/espeak-ng-data/` ships dictionaries for
**all nine Indic scripts** (`gu_dict`, `kn_dict`, `ta_dict`, `or_dict`, `hi_dict`, `mr_dict`,
`ml_dict`, `te_dict`, `bn_dict` — all verified present). Licence **GPL-3.0**.

Quality is robotic — it is a formant synthesiser, not neural. But it is intelligible, fully
offline, and adds **zero new dependencies or licence questions**.

### Recommendation

**Ship espeak-ng for the three, and state the limitation.** Reasons:

1. It keeps the entire shipped stack at MIT / Apache-2.0 / GPL — no CC-BY-NC anywhere. That is a
   clean, checkable compliance story, and the dossier already flags espeak-ng as a known GPL
   dependency.
2. It costs nothing to add — the data is already bundled.
3. The alternative is a licence argument you do not need to have with a judge.

Then say this plainly in the submission:

> *"Seven of the ten required languages use neural TTS voices (MIT-licensed Piper, plus Mimic3 for
> Gujarati). Kannada, Tamil and Odia use the espeak-ng formant synthesiser, which is intelligible
> but noticeably more robotic. Higher-quality neural voices exist for those three under a
> non-commercial licence and are the next upgrade if the organisers permit it."*

That is a stronger position than pretending all ten are equal, and stronger than silently shipping
three robotic voices.

---

## Part 3 — what this changes in the submission

| Document | Change needed |
|---|---|
| `FULL_RESEARCH_DOSSIER.md` | §3.3 TTS table — add coverage column and the 3-language caveat; §4 stack table |
| `ACCURACY_RESULTS.md` | add the v1/v2 comparison section |
| `build_deck.py` | slide 4 accuracy strip → v2 numbers; slide 6 → name the TTS gap |
| `ARCHITECTURE.md` | §7 known coverage gap — update with the resolution |
| `DECISION_SIH26173.md` | record the v2 decision and the TTS resolution |
