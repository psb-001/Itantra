# Evaluation Evidence — iTantra (SIH26173)

**What this file is.** One page of proof that the speech recognition in iTantra was
*verified on real recorded audio, clip by clip*, and not merely assumed to work.

**What this file is not.** It is not a claim that we trained a speech model. We did not.
Section 1 states exactly what we built and what we borrowed, in plain words, first.

Measured 27 September 2026. Model shipped in release `v1.0.1`.

---

## 1. Who made the speech model

| Question | Honest answer |
|---|---|
| Did the team train the acoustic model? | **No.** |
| Where did the weights come from? | Meta's **Omnilingual ASR 300M CTC**, INT8 quantised. Redistributed by the sherpa-onnx project (`k2-fsa`), repo `csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05`, release tag `asr-models`. |
| What did the team actually do to the model? | Pruned the **output vocabulary** from 10,288 tokens to 1,002 tokens, keeping only the characters the ten target languages need. **No weight training, no fine-tuning, no gradient steps.** The network is byte-for-byte the published one except for the final linear layer. |
| Why prune it? | With the full 1,600-language vocabulary the model sometimes emitted a phonetically identical letter from the wrong alphabet: "hello" came back as "هello" (Arabic heh), "help me" as "help مe". Pruning removed those tokens at the source. Evidence: `docs/VOCAB_PRUNE_FINDINGS.md`. |
| What else is the team's? | The Android app, the two-phone Bluetooth transport, the VAD segmentation, the heartbeat and dead-link detection, the TTS layer, the language filter, the evaluation harness, and every number below. |
| Licence | CC-BY-NC-4.0. Non-commercial. Stated openly because it limits the app's use and we would rather say so than hide it. |

**Model file, as shipped inside the APK**

```
app/app/src/main/assets/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8/model.int8.onnx
size    356,295,439 bytes
sha256  8363e2d64925b92e6736239877e7193a4e83259abad18cf6ee7ffb2796d6b934
```

The same file is what was scored in Section 2. Same hash, no swap.

---

## 2. How it was verified: 2,000 real audio clips

| Item | Value |
|---|---|
| Dataset | **FLEURS** test split (Google), CC-BY-4.0, real human speakers in a studio |
| Clips per language | 200 |
| Languages | 10 (all ten the app claims) |
| **Total clips scored** | **2,000** |
| Total audio | **24,189.8 s = 6.72 hours** |
| Clips with missing audio | **0** |
| Script | `eval/fleurs_eval_full.py` |
| Raw output | `eval/asr_full_<language>_v3pruned.json` (one file per language, every clip) |
| Combined | `eval/SUMMARY_full_v3pruned.json` |

Each clip is decoded independently. The result file stores, **per clip**, the file name,
duration, decode time, the reference transcript, the produced transcript, and that clip's
own WER and CER. Nothing is averaged away and no clip is skipped.

### Reproduce it

```bash
cd eval
OMNI_DIR=../models/asr-v3-10lang/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8 \
  OMNI_TAG=v3pruned ../.venv/bin/python fleurs_eval_full.py 200
```

Needs the FLEURS test split at `dataset/fleurs` and the model at `models/asr-v3-10lang`.
Re-running regenerates `SUMMARY_full_v3pruned.json` from raw audio.

### Check the numbers without trusting this document

```bash
python3 docs/verify_evidence.py
```

It reads the raw JSON, recomputes every figure, re-hashes the model and the result files,
and prints PASS or FAIL per claim. If a number in this document were edited by hand, that
script would disagree with it.

---

## 3. Results, all ten languages, 200 clips each

Scored **corpus-level**: total edits ÷ total reference units. This is the standard
definition. Not the mean of per-clip ratios, which over-weights short clips.

Both raw and normalised scores are reported. **Read the normalised column.** The model does
CTC greedy decode, so it emits no punctuation and no capital letters; scoring that against
punctuated references charges it for a known output-format habit rather than for
mishearing. Normalisation is NFC + zero-width marks dropped + punctuation replaced by a
space + casefold. CER is then computed on the character sequence with spaces removed, which
is the standard definition. The raw column is here so nobody can accuse us of only
publishing the flattering number.

| Language | n | WER raw | **WER norm** | CER raw | **CER norm** | median latency | RTF median |
|---|---|---|---|---|---|---|---|
| Kannada | 200 | 0.431 | **0.307** | 0.079 | **0.053** | 0.40 s | 0.033 |
| Malayalam | 200 | 0.504 | **0.361** | 0.084 | **0.059** | 0.48 s | 0.036 |
| English | 200 | 0.348 | **0.181** | 0.119 | **0.067** | 0.29 s | 0.032 |
| Gujarati | 200 | 0.399 | **0.300** | 0.099 | **0.071** | 0.32 s | 0.032 |
| Hindi | 200 | 0.355 | **0.269** | 0.118 | **0.096** | 0.40 s | 0.037 |
| Bengali | 200 | 0.472 | **0.359** | 0.136 | **0.100** | 0.50 s | 0.038 |
| Telugu | 200 | 0.543 | **0.431** | 0.125 | **0.100** | 0.38 s | 0.036 |
| Marathi | 200 | 0.510 | **0.416** | 0.129 | **0.108** | 0.42 s | 0.035 |
| Tamil | 200 | 0.589 | **0.501** | 0.150 | **0.129** | 0.40 s | 0.035 |
| Odia | 200 | 0.585 | **0.510** | 0.160 | **0.134** | 0.50 s | 0.039 |
| **Mean** | **2,000** | — | **0.363** | — | **0.0918** | — | **0.0377** |

**Headline:** mean character error rate **9.2%** across ten Indian languages and English, on
2,000 clips, with no network, no language hint, and a 340 MB model running on a phone.

RTF = processing time ÷ audio duration. 0.0377 means one minute of speech is transcribed in
about 2.3 seconds on the desktop measurement machine. On-device figures are lower quality
and are reported separately in `docs/DELIVERY_FINDINGS.md`.

`clips_missing_audio = 0` for all ten languages.

---

## 4. Real clips, shown as they came out

Verbatim from `eval/SUMMARY_full_v3pruned.json`. Not chosen for being good. The third row is
a failure and it is printed here on purpose.

| Language | Reference | Model output | CER |
|---|---|---|---|
| English | Unfortunately, studying traffic flow is difficult because driver behavior cannot be predicted with one-hundred | unfortunately studying traffic flow is difficult because driver behaviour cannot be predicted with 100 certain | 0.16 |
| Hindi | मनोविज्ञान सहित विज्ञान के सभी मामलों पर अरस्तू के विचारों को स्वीकार किया जाता है। | मनो विज्ञान सहित विज्ञान के सभी मांलों पर अर्स्टूस्त के विचारों को स्वीकार किया जाता है | 0.09 |
| Hindi **failure** | स्कीइंग मार्ग को एक हाईकिंग (लंबी पैदल यात्रा) मार्ग जैसा ही सोचें। | સकीं માर्‍को એक हાઈकिंग મतलબ લंबी पैદલ यात्रा માर्‍ग જેઝાही સोचे | **0.55** |

That third row is the honest risk in this model, still present after pruning: on some clips
it mixes scripts. It is not hidden, because a judge who finds it after the demo is worse
than a judge who hears it from us.

**Measured rate of that failure**, computed over all 2,000 clips:

| Language | Clips with 100% correct script | Mean character purity |
|---|---|---|
| English | 200 / 200 | 1.000 |
| Malayalam | 199 / 200 | 0.9999 |
| Tamil | 184 / 200 | 0.9957 |
| Gujarati | 186 / 200 | 0.9953 |
| Kannada | 184 / 200 | 0.9937 |
| Marathi | 192 / 200 | 0.9933 |
| Bengali | 197 / 200 | 0.9995 |
| Telugu | 186 / 200 | 0.9877 |
| Odia | 185 / 200 | 0.9855 |
| Hindi | 191 / 200 | 0.9802 |
| **All ten** | **1,904 / 2,000 = 95.2%** | — |

So: **95.2% of clips come back entirely in the correct alphabet.** The app's `LanguageFilter`
drops or repairs the remaining 4.8% before transmission, which is why the poster can claim
"no foreign characters on the radio link". That filter is ours, not the model's.

---

## 5. Noise, because a disaster is loud

50 clips per condition, four languages, synthetic white and pink noise mixed at fixed SNR.
Raw file: `results/noise_robustness.json`. **Caveat, stated in the file itself:** synthetic
noise is not a field recording. It measures sensitivity, not disaster-zone accuracy.

| Language | Clean CER | 30 dB | 10 dB | 0 dB |
|---|---|---|---|---|
| English | 0.087 | 0.095 | 0.151 | 0.399 |
| Bengali | 0.090 | 0.091 | 0.162 | 0.321 |
| Hindi | 0.133 | 0.424 | 0.550 | 0.588 |
| Tamil | 0.139 | 0.184 | 0.280 | 0.733 |

English and Bengali degrade gracefully. Hindi collapses early and stays flat; that is the
worst result in this repo and it is the reason the app ships with a push-to-talk button
rather than always-on listening.

---

## 6. Voice output, verified by round trip

TTS intelligibility was checked by synthesising a sentence, feeding the audio back into the
ASR model, and comparing. If the recogniser recovers the sentence, a human almost certainly
understood it. Raw file: `results/tts_intelligibility.json`.

| Language | Sentences | WER norm | CER norm | Exact match |
|---|---|---|---|---|
| English | 20 | 0.113 | 0.030 | 45% |
| Hindi | 20 | 0.490 | 0.339 | 0% |

**This is a proxy, not a human listening panel.** A real panel is still owed. Hindi TTS
round-trips badly at sentence level even though every word is recognisable, which is why the
app's own benches use short alert phrases rather than long sentences.

---

## 7. What the app actually costs on device

| Claim | Value | Source |
|---|---|---|
| ASR model on disk | 356,295,439 B (340 MiB) | file size |
| Peak RAM, ASR only | 710.1 MiB | `results/RAM_measure_all_models.json` |
| Peak RAM, ASR + both voices | 838.1 MiB | same |
| Peak RAM after use | 888.6 MiB | same |
| ASR load time | 0.403 s | same |

Measured on macOS arm64, not on a phone. On-device memory is the open item.

---

## 8. Every file this document cites, with hashes

| File | Bytes | SHA-256 |
|---|---|---|
| `eval/SUMMARY_full_v3pruned.json` | 3,320,399 | `8a6e1ec3e917844d0a99f107085d567de9202a8b0a8fb988f7d612be69b9b4d1` |
| `eval/asr_full_hi_in_v3pruned.json` | 317,485 | `a370e5493f45dbc35c05562d0f318192145548ad93da09a70ededbf3fefd16da` |
| `results/SUMMARY_full_v2.json` | 3,319,969 | `b535d4d05dae093316e9df6fecb12f916e880c818be3adbcc83bc530246a7757` |
| `results/noise_robustness.json` | 9,924 | `9ebe776bfe34ef3bef66b58ebb1a973d4b63d16afc37e9d6f79ea19616d3c1dd` |
| `results/tts_intelligibility.json` | 22,688 | `d2c734ef6f440da189ebc43eff8c1286e32476b60634c082f1a0b89e08f88083` |
| model `model.int8.onnx` | 356,295,439 | `8363e2d64925b92e6736239877e7193a4e83259abad18cf6ee7ffb2796d6b934` |

Shipped APK `v1.0.1`: 993,362,519 B, sha256
`00b16eb216f1985a859bde2c8fe751785523429b2ef09937c693d813dd0db40e`.
Signing certificate SHA-256: `d9c0b31cf8689061f3b6f142809be085fe6c4c676b2f796b77080cd5ca9c58b9`.

---

## 9. Five things a judge may ask, answered plainly

**"Did you train this model?"**
No. Meta trained it. We pruned its output vocabulary and shipped it. Section 1.

**"Then what did you verify?"**
That it works on real speakers in all ten languages we claim, on 2,000 clips we did not
record and did not pick, measured per clip. Section 2.

**"How do I know you did not just write these numbers?"**
Recompute them: `python3 docs/verify_evidence.py`. It reads the raw per-clip JSON and
re-derives every figure here.

**"What is the weakest part?"**
Hindi under noise (CER 0.55 at 10 dB), Tamil and Odia overall (CER 0.13), and Hindi TTS
round trip (CER 0.34). All three are in this document, not buried. Section 5 and 6.

**"What is still unmeasured?"**
On-device accuracy on a real phone (we have RTF and memory, not WER/CER), and a human
listening panel for the voice output. Both are stated, neither is claimed.

---

## 10. One line for the record

> The speech model in iTantra is Meta's Omnilingual ASR 300M, redistributed through
> sherpa-onnx, with its output vocabulary pruned from 10,288 to 1,002 tokens by the team. It
> was verified on 2,000 FLEURS clips across ten languages, 6.72 hours of real recorded
> speech, mean normalised CER 0.0918, with every per-clip transcript retained in this
> repository.
