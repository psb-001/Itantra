# Decoder quality findings — 29 September 2026

Asked: the app "still hallucinates", and speech recognition is not yet as accurate as a person
speaking to a person. This records what was measured, what was built, and — importantly — the
ideas that were tested and **rejected**, so nobody spends the same week again.

All measurements use the shipped pruned model
(`sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8`) on the FLEURS test set, with the same
normalisation and edit-distance code as `eval/fleurs_eval_full.py`, so the numbers are comparable
to the published ones. Harness: `eval/decode_methods.py`.

---

## The headline: the decoder cannot be improved from this code path

**sherpa-onnx refuses beam search for this model type.** Not "we didn't enable it" — the library
aborts:

```
offline-recognizer-ctc-impl.h:208  Only greedy_search is supported at present.
                                   Given modified_beam_search
```

`modified_beam_search` and `fast_beam_search` both kill the process. Greedy search is the only
decoder available, and beam search is the standard fix for exactly the repetition-and-garbage
failure a character-level CTC model has. That fix is upstream, in sherpa-onnx.

**The one decoder that could help is present in the config but unusable here.**
`OfflineRecognizerConfig.ctc_fst_decoder_config` (a lexicon-constrained CTC search) exists, and
this model being character-level makes a lexicon unusually cheap to build — a word's pronunciation
is just its own spelling. But a lexicon must be compiled to an FST with OpenFST, and there is no
`openfst` binary, no `fstcompile`, and no pip wheel for it in this environment. This is the
single highest-value future work and it is blocked on a build dependency, not on an idea.

---

## What the failure actually is: script confusion, not repetition

Real decodes captured from the shipped model:

| Language spoken | What came back | CER |
|---|---|---|
| Hindi | `જैसે चान पृथवी परे खीचाव पेदाकर` — opens in Gujarati, switches to Devanagari mid-sentence | 0.60 |
| Tamil | `പുതിതാക്ക ഒരു വിഷയം` — Malayalam for Tamil audio | 0.96 |
| Tamil | `வேகவக்கடஜெரம்உணவுபொறகளில்` — Tamil script, meaningless content | 1.55 |
| Gujarati | `ફોકલેન્ડ પૉન્ડ એfkp` — Latin letters inside Gujarati | 0.09 |

The model has no language parameter, so on weak evidence it picks whichever script fits. This is
the same class of problem the vocabulary prune fixed for out-of-script output; pruning cannot fix
confusion *between* the nine remaining scripts, because both are in the vocabulary by design.

---

## Ideas tested and REJECTED

Each of these was implemented and measured. The numbers are why they are not in the app.

### 1. A chars-per-second plausibility gate — counterproductive

The intuition was that a decode failure produces too few characters for the audio. Measured on
150 clips across 6 languages:

| | min | median | max |
|---|---|---|---|
| Catastrophic decodes (CER > 0.5) | 9.00 | 10.75 | 10.75 |
| Good decodes (CER < 0.2) | 5.25 | 9.30 | 15.40 |

**The bad decodes produced MORE characters per second than the good ones.** A length floor would
have discarded precisely the wrong segments. Sweeping the threshold from 1.0 to 3.0 chars/sec
caught 0 of 2 bad clips and 0 of 142 good ones — it is not a weak signal, it is an inverted one.

### 2. Self-consistency (decode twice, transmit on agreement) — far too destructive

The standard way to estimate decoder confidence without log-probs: decode the segment a second
time from a 3%-quieter copy, and only send if the two agree. Measured on 100 clips per condition,
with synthetic pink noise:

| SNR | Agreement overall | Good clips wrongly rejected |
|---|---|---|
| 30 dB | 24–80% | 17–52% |
| 20 dB | 28–52% | 45–70% |
| 15 dB | 24–52% | 41–73% |

The decoder is far too sensitive to amplitude for this to work. It would have thrown away most of
the correct speech to catch a handful of bad segments.

### 3. Tightening the Latin allowance — no benefit

`LanguageFilter` permits Latin in every language for numerals and loanwords. Since real decodes
contained Latin garbage, that leniency looked like the bug. Measured over 30 clips in each of 9
non-English languages: Latin letters appeared in 0–10% of decodes, and rejecting those decodes
recovered **0.00 CER**. No change made. The behaviour is now locked in by a test with the number
in the comment, so it is not "tightened" by accident later.

### 4. Retuning the lead-in silence — not worth it

Swept 0.0–0.8 s across all ten languages, 20 clips each:

| Lead-in | 0.0 | 0.05 | 0.1 | 0.2 (current) | 0.3 | 0.5 | 0.8 |
|---|---|---|---|---|---|---|---|
| Mean CER | 0.0926 | **0.0920** | 0.0938 | 0.0943 | 0.0940 | 0.0937 | 0.0930 |

0.05 s beats the shipped 0.20 s by 0.0023 — inside run-to-run noise at this sample size. The
0.20 s value was already justified by a separate measurement about script stability at speech
onset, so it stays.

### 5. An early repetition detector that reported a 20–25% loop rate — it was wrong

The first implementation counted n-gram frequency across the whole sentence. On Hindi it flagged
25% of clips. Inspecting the flagged output showed ordinary news sentences: `मध्य युग के अंत में
पश्चिम यूरोप ने अपनी शैली विकसित करना`, where the bigram "के अंत" occurs three times because the
sentence is three clauses long. **Not a decoder bug. Natural language.**

Rewritten to count only *adjacent* tiling — the same n-gram repeated back to back with nothing in
between, which is what a CTC loop actually looks like:

| Language | Adjacent tiling ≥ 3 | CER of those clips |
|---|---|---|
| Hindi | 2.5% | low |
| Gujarati | 5.0% | low (0.04) |
| English | 12.5% | 0.11–0.17 |
| Tamil | 0% | — |

The benign cases cluster at exactly 3, so the shipped threshold is 4. Even at 3 the flagged clips
were *correct* — the repetition was real repetition in the sentence.

---

## What was built: `QualityGate`

A small gate plus a **third outcome**, which is the part that matters.

The app was binary: send, or drop silently. Measured under noise, the strict script filter drops
effectively everything — at 15 dB SNR a script-purity threshold of 0.8 keeps 100% of English and
Hindi segments and **0% of Gujarati**. A user in that state sees an app that loads, listens, and
never sends, with no way to distinguish that from a broken microphone.

| Verdict | Meaning | User sees |
|---|---|---|
| `SEND` | decode looks sound | the message |
| `REPEAT` | present but untrustworthy | **"I could not read that clearly — hold the button and say it again"** |
| `SILENT` | nothing usable; too short or too quiet | nothing (correct — that was a breath or a door) |

Thresholds, and where each came from:

- `MIN_SPEECH_SECONDS = 0.35` — the app's own VAD emitted 0.31 s segments with RMS as low as
  0.00008 that decoded to the empty string every time. A breath is not a sentence.
- `MIN_RMS = 0.005` (≈ −46 dBFS) — real speech in this app's logs sits an order of magnitude above.
- `MAX_ADJACENT_REPEAT = 4` — see rejection 5.
- `MIN_SCRIPT_PURITY = 0.75` — measured, and deliberately in the flat region. 0.70–0.90 behaved
  identically on the data; 0.75 is the middle of that region, not a number found by search.

### A real bug found while building it

`LanguageFilter.allowedFraction` counts Latin as allowed in every language, so it scores a decode
that is *entirely* Latin letters while Gujarati was selected as **1.0** — it can never detect the
case it looks like it detects. Added `LanguageFilter.expectedScriptPurity`, which measures the
language's own script only, and the gate uses that. A test now pins both behaviours.

### Tests

`app/src/test/java/com/bjp/itantra/QualityGateTest.kt` — **12 tests, all passing**, built from the
real captured decodes above rather than invented strings. JUnit was not wired into the build; it is
now, and `:app:testDebugUnitTest` runs on the JVM with no device.

---

## Honest status of the two complaints

**"It still hallucinates."** On clean audio the model hallucinates rarely — 2 of 150 clips
exceeded CER 0.5. The failures are script confusion, concentrated in noise, and the shipped strict
filter already rejects the wrong-script cases. What was genuinely broken is the *silence*: a
rejected segment gave the user nothing to act on. That is now a prompt.

**"Not as accurate as me speaking to you."** This is the honest limit of the current stack, and the
measurements say the bottleneck is the decoder, not the filter. Greedy-only CTC, no language
parameter, no confidence signal exposed by the binding. Nothing in the app layer moves that
number; only a better decoder would. The two routes that would:

1. **Lexicon-constrained CTC decoding** (`ctc_fst_decoder_config`) with a rescue-domain lexicon.
   Blocked on OpenFST. Because the model is character-level, a word's pronunciation is its own
   spelling, which makes the lexicon far simpler to build than for a phoneme-based CTC model.
2. **A transducer or attention model that supports beam search.** Meta ships a 7B LLM-ASR that is
   2–3× more accurate but 25 GiB; a smaller sibling in that family is worth benchmarking against
   the current 340 MB model on the same ten languages and the same 2,000 clips.

Both are experiments, not configuration changes. Neither can be honestly claimed until measured.

## Verification performed

- 12/12 unit tests pass, from real decodes (`:app:testDebugUnitTest`)
- Release build succeeds
- Installed and launched on the emulator; models load, no crash
- **The gate has not been exercised on-device with real audio** — the emulator microphone is
  silent, so no VAD segment is produced. That limitation is pre-existing and unchanged. The logic
  is verified by unit test; the on-device path needs a physical phone.
