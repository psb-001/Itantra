# Vocabulary pruning — the fix for the wrong-script failure

**Date:** 27 September 2026 · **Supersedes:** the script-flip risk in `NOISE_ROBUSTNESS_FINDINGS.md`
**Model:** Omnilingual ASR CTC 300M **v2**, output vocabulary pruned 10,288 → 1,002 tokens
**Tool:** `eval/prune_vocab.py` · **Harness:** `eval/fleurs_eval_full.py`, `eval/noise_robustness.py`

---

## 1. The bug, reproduced on clean audio

The user reported that speaking "hello" produced something like "hola". It was not an accent.
The model was substituting a **phonetically identical character from an unrelated script**:

```
spoken              model output        substitution
'hello'             'هello'             Arabic heh  U+0647  for Latin h
'hi'                'हय'                Devanagari ya  for Latin y
'help me'           'help مe'            Arabic meem U+0645  for Latin m
'are you okay'      'اr yوك'             Arabic alif + Arabic waw
'hello hello'       'هلو هلو'            fully Arabic
'hi there'          'หย'                 Thai
```

**No noise. No microphone gain problem. Clean TTS audio at 22.05 kHz.** On 19 short English
phrases, **3/19 (16%) produced non-Latin output.** The failure rate rises as utterances get
shorter, because CTC has less context to disambiguate.

## 2. Why the old filter made it worse

`LanguageFilter` scored the fraction of letters in an allowed script and, if it cleared
`THRESHOLD = 0.60`, **transmitted the transcript with the foreign characters deleted.** Replaying
the real outputs through the old logic:

```
spoken              model gave      fraction   old behaviour
'hello'             'هello'         0.80       SEND "ello"
'help me'           'help مe'       0.83       SEND "help e"
'are you okay'      'اr yوك'        0.60       SEND "r yk"      <- exactly on the line
'hi'                'हय'            0.00       DROP (silently)
```

So the pipeline turned a *detectable* error into an *undetectable wrong answer*. The receiving
phone displayed a confident, garbled emergency message with nothing marking it as suspect. For
an app whose entire purpose is emergency text, that is the worst available failure — worse than
silence, because a human acts on it.

## 3. The fix: delete the output columns

The Omnilingual model is character-level CTC with **no language parameter**, and its output layer
spans ~10k characters across 1,600+ scripts. Greedy search is free to pick any of them.

Pruning deletes every output column outside the nine scripts the ten required languages use, so
**the model cannot emit those characters at all.** When the acoustics are ambiguous between
Latin `h` and Arabic `ه`, the best *remaining* candidate now wins, so output degrades toward a
readable in-language guess instead of an unreadable foreign one.

What the head looks like in this export:

```
Cast -> MatMulInteger(x_q[*,1024], W_int8[1024,V], x_zp, W_zp) -> int32
     -> Mul(W_scale) -> Add(final_proj.bias[V]) -> logits
```

`W_scale` and `W_zp` are **scalars** (`dims == []`), so the surgery is three slices — no
per-column scale vector to reslice. `prune_vocab.py` asserts this rather than assuming it,
because slicing weights without reslicing per-column scales would be silently wrong.

### The one trap

`<unk>` is the **CTC blank**. Drop it and the decoder cannot collapse repeated frames, so it
emits a character for *every* frame and the output becomes `"w-hellow-'-"` instead of `"hello"`.
This happened, and the cause was a one-character bug: `set("<s><pad></s><unk>")` builds a set of
**characters**, not of those four strings, so all four specials were dropped. The script now uses
an explicit set literal and refuses to write a model without the required tokens.

## 4. Results — FLEURS, 200 clips × 10 languages, 2,000 clips, 6.7 hours

| Language | CER norm v2 | CER norm pruned | Δ | WER norm v2 | WER norm pruned |
|---|---|---|---|---|---|
| Odia | 0.134 | 0.134 | 0.000 | 0.509 | 0.509 |
| **Hindi** | 0.113 | **0.096** | **−0.017** | 0.281 | 0.269 |
| Bengali | 0.100 | 0.100 | 0.000 | 0.359 | 0.359 |
| Marathi | 0.108 | 0.108 | 0.000 | 0.416 | 0.416 |
| Gujarati | 0.071 | 0.071 | 0.000 | 0.300 | 0.300 |
| Kannada | 0.053 | 0.053 | 0.000 | 0.307 | 0.307 |
| Tamil | 0.129 | 0.129 | 0.000 | 0.501 | 0.501 |
| Telugu | 0.100 | 0.100 | 0.000 | 0.431 | 0.431 |
| Malayalam | 0.059 | 0.059 | 0.000 | 0.361 | 0.361 |
| English | 0.067 | 0.067 | 0.000 | 0.181 | 0.181 |
| **MEAN CER** | **0.0934** | **0.0918** | **−1.8%** | | |

**Nine of ten languages are bit-identical. Hindi improved 15% relative.** Mean RTF fell from
0.0422 to 0.0352 (**−16.4%** — a smaller head is cheaper). Model size 348.9 MB → 339.8 MB.

**9,286 of 10,288 tokens deleted** — CJK 5,750 · other 1,555 · Hangul 1,220 · Ethiopic 299 ·
Arabic 173 · Cyrillic 167 · Greek 67 · Thaana 47 · CJK-ext 10.

## 5. Results — script retention under noise (the metric that matters)

Percentage of outputs in the expected alphabet. **This is the metric that decides whether the
recipient can read the message at all.**

| Condition | Hindi v2 | Hindi pruned | Δ |
|---|---|---|---|
| clean | 94.0 | **100.0** | +6 |
| white 30 dB | 64.0 | 80.0 | +16 |
| white 20 dB | 56.0 | 80.0 | +24 |
| white 15 dB | 48.0 | 80.0 | **+32** |
| white 10 dB | 54.0 | 80.0 | +26 |
| white 5 dB | 64.0 | 92.0 | +28 |
| white 0 dB | 60.0 | 84.0 | +24 |
| pink 30 dB | 56.0 | 80.0 | +24 |
| pink 20 dB | 46.0 | 74.0 | +28 |
| pink 15 dB | 40.0 | 78.0 | **+38** |
| pink 10 dB | 40.0 | 74.0 | **+34** |
| pink 5 dB | 50.0 | 78.0 | +28 |
| pink 0 dB | 64.0 | 88.0 | +24 |
| **mean gain** | | | **+25.5** |

Tamil, English and Bengali were already at or near 100% and are unchanged, except English at
0 dB white noise which went 98% → 100%.

**Hindi clean-audio script retention is now 100%.** The 6% of clean Hindi utterances that used
to come back in the wrong alphabet are gone entirely. This was the single largest measured
weakness in the project (`NOISE_ROBUSTNESS_FINDINGS.md`) and it is now closed.

## 6. Second layer: the filter now drops instead of deleting

`LanguageFilter` no longer strips characters. **Any** letter outside the expected script drops the
whole segment, so nothing partially-mutilated is ever transmitted. `clean()` was deleted outright
and replaced with a whitespace-only `tidy()`, and `THRESHOLD` is marked retired.

This remains necessary even with pruning, because pruning cannot prevent confusion *within* the
target set — Hindi arriving in Gujarati, where both are in-vocabulary. The filter enforces the
expected script for the language the user selected.

`eval/language_filter.py` now carries the three real failures as regression cases and scores
**25/25**:

```
ok [Real bug: heh for h]   raw: هello        -> REJECT (1 foreign chars)
ok [Real bug: meem for m]  raw: help مe      -> REJECT (1 foreign chars)
ok [Real bug: alif+waw]    raw: اr yوك        -> REJECT (2 foreign chars)
```

## 7. What ships now

`SpeechEngine.ASR_DIR` and the vendored `getOfflineModelConfig(44)` both point at
`sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8`. This also closes the long-standing
v1/v2 discrepancy: the APK used to ship v1 (CER 0.0992) while the deck reported v2 (0.0934).
It now ships v2-pruned at a measured **0.0918**.

APK: 521 MB → **503 MB**.

## 8. Reproduce

```bash
# 1. build the pruned model from v2
.venv/bin/python eval/prune_vocab.py \
  --src models/asr-v2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05 \
  --dst models/asr-v3-10lang/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8

# 2. accuracy, 200 clips x 10 languages, both versions
cd eval
OMNI_DIR=../models/asr-v2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05 \
  OMNI_TAG=v2base   ../.venv/bin/python fleurs_eval_full.py 200
OMNI_DIR=../models/asr-v3-10lang/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8 \
  OMNI_TAG=v3pruned ../.venv/bin/python fleurs_eval_full.py 200

# 3. script retention under noise, both versions
OMNI_DIR=../models/asr-v2/... OMNI_TAG=v2base   ../.venv/bin/python noise_robustness.py 50 hi_in ta_in en_us bn_in
OMNI_DIR=../models/asr-v3-10lang/... OMNI_TAG=v3pruned ../.venv/bin/python noise_robustness.py 50 hi_in ta_in en_us bn_in
```

The FLEURS corpus lives at `dataset/fleurs`; both harnesses now accept `FLEURS_DIR` and fall back
to it automatically, so no 7 GB copy is needed.

## 9. Honest limitations

1. **This does not fix wrong-language-within-right-script.** Hindi still cannot be distinguished
   from Marathi, or Bengali from Assamese. That needs a language-ID model. Unchanged from before.
2. **Noise is synthetic.** White and pink noise measure *sensitivity*, not field accuracy in a
   disaster zone. Still unmeasured.
3. **The remaining 1,002 tokens span all nine scripts**, so cross-script confusion inside the
   target set is still possible. The filter is what catches that.
4. **Tamil still collapses at 0 dB** (44% retention) — pruning changed nothing there, because
   Tamil's failure mode is dropping syllables, not switching script. Not fixed.
