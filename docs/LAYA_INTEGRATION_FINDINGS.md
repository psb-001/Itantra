# Laya integration — what actually works, and what blocks it

**29 September 2026.** Laya is now exported, verified, and running on real text. It is **not yet in
the app**, and the reason is size, not capability. Both halves of that sentence are measured
below.

Reproduce with:

```bash
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install laya onnx onnxruntime onnxscript safetensors huggingface_hub
python eval/laya_onnx.py export --out /tmp/laya_onnx
python eval/laya_onnx.py bench  --model /tmp/laya_onnx
```

---

## What Laya is, and why it fits this app

`NandhaKishorM/laya` — 27.5k stars, **Apache-2.0** (contrast with our CC-BY-NC ASR), created
10 days before this work. A non-autoregressive **decision engine**: it reads text and returns
typed decisions in one forward pass, in 100+ languages, with a router that picks the checkpoint by
script.

The fit is structural. iTantra's receiving phone **already has the decoded text** — a 129-byte
packet of it. Decisions that currently need manual action become one forward pass:

| Today | With Laya |
|---|---|
| sender hand-toggles `ALERT` | read the text, decide if it is an evacuation order |
| receiver must already know the language | decide the language from the text, pick the Piper voice |
| — | extract a place name or headcount from a spoken sentence |

**It read Gujarati, Kannada and Odia correctly** — the three languages the app cannot speak aloud
at all. Laya is not gated by voice availability, so the *understanding* side of the app can be
10/10 even while speech output stays 7/10.

---

## It works. Measured.

Export: `encoder.onnx` + `head.onnx` via Laya's own `laya-ts/scripts/export_onnx.py`, which
verifies torch against ONNX **before writing**:

```
verify ok: torch vs onnx match within 1e-4  (logits 2.1e-06, act_probs 0.0e+00)   x3 lengths
```

Zero-shot decisions on real disaster sentences, 10 required languages, 4-way `choice`:

| lang | expected | got | | conf | enc ms | head ms |
|---|---|---|---|---|---|---|
| hi | evacuation | medical emergency | ✗ | 0.824 | 63 | 8 |
| hi | routine | evacuation | ✗ | 0.412 | 56 | 7 |
| ta | evacuation | evacuation | ✓ | 0.976 | 69 | 9 |
| te | evacuation | evacuation | ✓ | 0.579 | 64 | 8 |
| mr | evacuation | evacuation | ✓ | 0.965 | 57 | 7 |
| bn | evacuation | evacuation | ✓ | 0.981 | 67 | 8 |
| **gu** | evacuation | evacuation | ✓ | 0.758 | 63 | 8 |
| ml | evacuation | evacuation | ✓ | 0.562 | 68 | 9 |
| **or** | evacuation | safety warning | ✗ | 0.800 | 85 | 11 |
| **kn** | evacuation | evacuation | ✓ | 0.751 | 67 | 9 |
| en | evacuation | evacuation | ✓ | 0.992 | 47 | 6 |
| en | routine | evacuation | ✗ | 0.408 | 51 | 6 |

**8/12. Encoder median 63 ms, head median 8 ms, total 71 ms** on this desktop CPU, fp32.

A phone is 3–5× slower. The honest expectation is **100–200 ms per decision**, which is
affordable: the decision runs once, on a message that arrived as a packet, while the phone is
otherwise idle. It is nowhere near the 1 020 ms the whole ASR leg takes.

### The failures are all one kind, and they matter

Every miss is **over-triggering on the emergency class** — "routine message" called
"evacuation order", Hindi evacuation called "medical emergency". Not a multilingual weakness:
the languages that failed are the ones with a *plausible* emergency reading, and the model is
zero-shot with criteria this document wrote in ten minutes.

For iTantra this is the wrong way to be wrong. A false "evacuation order" makes people move for
nothing, and in a disaster that has a real cost. **If this ships, the decision must be
asymmetric by construction**: one-sided criteria that make escalation hard, and a confidence floor
under which nothing escalates. Laya exposes that directly — `min_confidence` plus opt-in
abstention, returning `low_confidence: True` instead of a guess.

Do not read 8/12 as "it works". Read it as: **the capability is real, the prompt needs tuning,
and the safety policy has to be designed.**

---

## The blocker: 1 228 MB

| Artefact | Size |
|---|---|
| `encoder.onnx.data` | 1 170.9 MB |
| `head.onnx.data` | 57.1 MB |
| `tokenizer.json` | 32.8 MB |
| **total fp32** | **~1 261 MB** |

The current APK is 804 MB. Dropping this in as exported would take it past **2 GB**.

**Why it is that big.** One initializer dominates:

```
encoder.embeddings.tok_embeddings.weight   256000 x 768   =  750.0 MB
```

That is 61% of the whole encoder, and it is there because the checkpoint is a 100+ language
model with a 256 000-token vocabulary. The source `model.safetensors` is 614 MB because it is
stored fp16; the ONNX export upcasts to fp32, which doubles it before anything else happens.

**Standard INT8 quantisation barely helps, and the reason is specific:**

| | Size |
|---|---|
| fp32 weights | 1 228.0 MB |
| after `quantize_dynamic` on MatMul | 858.4 MB (1.43× smaller) |

750 MB of that is the embedding table, held as a **`Gather` initializer, not a MatMul weight** —
so MatMul quantisation never touches it. `head.onnx` does not quantise at all:
`ShapeInferenceError: Inferred shape and existing shape differ in dimension 0: (772) vs (256)`.

### The fix is the same trick that already worked on our ASR model

We pruned the ASR output vocabulary from 10 288 to 1 002 tokens and made the wrong-script failure
structurally impossible. The identical move applies here, and it is larger: Laya needs tokens for
ten languages, not 256 000 for a hundred.

Estimate, not yet measured: a 10-language vocabulary of ~30–50k tokens would put the embedding at
88–147 MB fp32, or ~25–40 MB at INT8, taking the whole thing to roughly **150–250 MB fp32 /
50–90 MB int8**. That is shippable. It is a script to write, not a research problem.

**The decision this needs from you:** an APK of ~1 GB is fine for sideloading and is what we
already ship. An APK of ~1.05–1.1 GB with Laya in it may be too large for some phones to install
or update over a degraded link. Since the whole premise of this project is that the device must
survive a bad network, that is worth deciding deliberately rather than discovering at install time.

---

## Two traps that cost real time, recorded so they are not re-hit

### 1. Wrong special tokens produce a perfectly silent failure

mmBERT, not BERT. The tokens are `<bos>`=2, `<eos>`=1, `<mask>`=4, `<pad>`=0. Reaching for
`[CLS]`, `[SEP]` and `[MASK]` — which is what the name "BERT" suggests — returns `None` for all
three ids, and the model then emits a **uniform 0.250 across four options with no error, no
warning, and full confidence in the code that ran.**

Uniform output is the tell. If the head ever returns exactly 1/k, the marker positions or the
special tokens are wrong, and nothing else in the stack will tell you.

### 2. The input shape is not "text"

```
<bos> question <eos> <mask>option1 <mask>option2 ... <eos> state <eos>
```

The decision head reads the hidden state **at each `<mask>` position, one marker per option.**
Passing only the state — the obvious thing to try — gives uniform output for the same reason.
`LayaOnnx.build()` in `eval/laya_onnx.py` reproduces this and is the reference for the Kotlin port.

---

## What shipping this would take, in order

1. **Decide the size question above.** Everything else depends on it.
2. **Vocab prune + INT8.** Target ≤ 90 MB. Then re-verify against the torch reference, because a
   pruned embedding is exactly the kind of change that passes a smoke test and fails quietly.
3. **Tune the criteria and make escalation asymmetric.** Add more decoys — "false alarm", "drill",
   "all clear" — so the emergency class stops being the default answer, and set a confidence floor.
4. **Build a labelled set** of real rescue messages across the ten languages. The 12 sentences here
   are not an evaluation set; they are a smoke test. Tune against a held-out set or the 8/12 will
   not survive contact with a judge's examples.
5. **Kotlin port.** `onnxruntime-android` directly — the graph is plain MatMul/LayerNorm/Softmax,
   and ONNX Runtime is already in the APK via sherpa-onnx, so this is a binding plus a tokenizer,
   not a new runtime. The tokenizer is the fiddly part: `tokenizers` in Kotlin is painful, so
   likely port the 32.8 MB `tokenizer.json` through a minimal hand-written BPE reader, or
   pre-tokenise.
6. **Wire it to the receiving path only** (`onPacket`), so it never touches the audio thread and
   cannot add latency to capture. It classifies a message that has already arrived.

Steps 1–3 are the ones that decide whether this is worth doing. Steps 4–6 are ordinary work.

## Not done

- **Not in the app.** No assets shipped, no Kotlin binding, nothing wired. The APK is unchanged
  from the `4c0e4d8` build apart from the `QualityGate` work in `e4b2b42`.
- **Vocab prune and INT8 not attempted.** Every size figure above for the pruned build is an
  estimate, clearly marked as one.
- **12 sentences is a smoke test, not an evaluation.** No held-out set, no per-language accuracy.
- **No on-device measurement.** Everything is desktop. Phone latency is a reasoned 3–5×
  extrapolation, not a measurement.
- **Dependencies added for the export only** — torch, onnx, onnxscript, laya, safetensors,
  huggingface_hub. None of these ship in the APK. The app would depend only on onnxruntime.

---

# Addendum — vocabulary prune, 29 September 2026

The vocab prune was implemented rather than left as an estimate. **It works, it halves the size,
and it is not ready to ship.** Both halves measured.

## Result

| | fp32 |
|---|---|
| exported | **1 261 MB** |
| vocab-sliced | **651 MB** |

The token embedding went 750.0 MB → **163.3 MB** (4.6×). The encoder is 586.7 MB, the head 57.3 MB,
the tokenizer 7.1 MB.

**The sliced encoder is bit-exact against the original** on real text in six languages —
`max abs difference 0.00e+00` for hi, ta, en, gu, kn, or. Slicing rows and remapping ids changed
nothing numerically.

**Out-of-vocabulary rate on the ten languages: 0.00%.** 7,264 FLEURS reference transcripts, every
token covered.

**Decisions: 8/12 unpruned → 7/12 pruned.** 155 ms median on this desktop, fp32.

## The keep-set is 55,737 tokens, not the 6,459 it looks like it should be

Tokenising the corpus directly touches only 5,946 distinct ids — 2.3%. That is not a shippable
set, and the reason is a BPE detail that costs three attempts to find:

> A BPE token is not atomic. The tokenizers library requires that **both merge inputs AND the
> merge result** are present in the vocab, and that every kept token can be rebuilt from the
> pieces below it. "कि" is `क` + `ि`; "▁the" is `▁` + `the`. Drop an intermediate and the
> tokenizer fails to build — reported only as ``Token `\n` out of vocabulary``, with no hint
> which token or why.

Taking the closure over every producer grows 6,459 → 33,526 → **55,737** (21.8% of the original
vocab). That is the honest floor for a buildable BPE, and it is why the embedding is 163 MB rather
than the 19 MB a naive slice suggests. Any plan quoting "50–90 MB" for this is wrong.

## A regression I introduced and nearly shipped

After the slice, decisions collapsed from 8/12 to **2/12**, with every confidence bunched near 0.30
and token counts inflating 3×. That looks like the prune destroyed the weights.

It had not. When I rewrote the tokenizer rebuild I removed a block I had judged dead — the one
copying the pipeline nodes from the original — and the **`pre_tokenizer` went with it**. Without it
the space character never becomes the `▁` word-boundary marker, so spaces fall through to byte
fallback:

```
original   ▁the ▁river ▁has ▁flooded                    9 pieces
no pre-tok th <0x65> <0x20> r <0x69> <0x76> e r ...    42 pieces
```

The encoder was never wrong. The model was being shown text it was never shaped for. Restoring
the four nodes returned it to 7/12.

Recorded because the failure mode is genuinely deceptive: a tokenizer bug presents as a quantised
model regression, and the numbers look like a slow drift rather than a missing pre_tokenizer.

## Known defect, not fixed: English tokenization is still degraded **(fixed 15 Oct — see Addendum 2)**

| | original | sliced |
|---|---|---|
| hi | 14 | 14 ✓ |
| ta | 23 | 23 ✓ |
| gu | 18 | 18 ✓ |
| or | 35 | 35 ✓ |
| kn | 20 | 20 ✓ |
| **en** | **9** | **39** ✗ |

The nine Indic languages tokenize identically. **English does not** — `▁the`, `▁river` and the
rest are present in the pruned vocab, but the merge chain that reconstructs them is incomplete, so
the tokenizer falls back to bytes. The question and options are English, so every decision pays
for it, which is most of the 8/12 → 7/12 loss.

Not shipped, and should not be, until English round-trips. The next thing to try is preserving
the full merge DAG for kept tokens rather than only the tokens themselves.

## INT8 not yet applied

651 MB fp32 is not final. `head.onnx` previously refused quantisation outright
(`ShapeInferenceError: dim 0: 772 vs 256`); now that the encoder is a plain single-file graph it
may go through, and the 163 MB embedding is a Gather table that needs `quantize_dynamic` to be
pointed at Gather initializers rather than MatMul weights. Expect roughly 180–220 MB; unmeasured.

## State of the integration

| | |
|---|---|
| Exported and verified | yes, torch vs ONNX 2.1e-06 |
| Vocab sliced | yes, 1 261 → 651 MB, bit-exact encoder |
| OOV measured | yes, 0.00% |
| Decisions preserved | 8/12 → 7/12, **English tokenizer defect outstanding** |
| INT8 | not done |
| **In the app** | **no** |

Not wired in, deliberately. Shipping a known-degraded tokenizer into a disaster app because the
size number looked good is the exact mistake this document keeps catching, and 7/12 with a known
defect is not a state worth putting in front of a judge.

---

# Addendum 2 — the English defect is fixed, and 55,737 was the wrong floor

**15 October 2026.** The defect recorded above is closed, and the "honest floor" claimed for the
keep-set was wrong by a factor of four. Measured, not estimated, except where marked.

## Root cause: the merge closure walked the wrong way

The closure was top-down: *if both merge inputs survive, keep the result*. That guarantees a
token is buildable from kept pieces. It says nothing about the reverse — that a kept token's own
producer chain survives. For English, `▁the` was kept but its input `▁th` was not (the bare
characters below `▁th` never got pulled in either), so every space-prefixed English word fell
back to byte tokens. The Indic scripts survived because their corpus tokens are dense syllables
whose pieces also appear in the corpus.

The walk has to go bottom-up: **for every kept token, pull in the inputs of every merge that
produces it, recursively.** Two details cost a measurement each:

- *Every* producer, not one. Following only the earliest-ranked merge that produces each token
  still left `▁immediately` and one Marathi token split — 2 of 12 samples.
- Do **not** keep the old top-down closure as well. Once the bottom-up walk pulls in base
  letters, "both inputs kept → keep the result" fans out to **170,888** tokens, two thirds of the
  vocabulary, for no correctness gain.

The merge filter needed the symmetric condition too: keep a merge only if **both inputs and the
result** are in the pruned vocab. It previously checked the inputs only.

## Measured result

| | before | after |
|---|---|---|
| keep-set | 55,737 (7,264-transcript corpus) | **12,454** (4.9%, 3,776-transcript corpus) |
| embedding | 163.3 MB fp32 | **36.5 MB fp32 / 9.1 MB int8** |
| English calibration sentence | 9 → 39 pieces, byte fallback | **identical pieces when corpus-covered** |
| corpus round-trip | measured only as token coverage | **100% of 3,776 transcripts, piece-identical** |
| inference strings (question + options) | not checked | **13/13 identical** |

The 55,737 was not the buildable floor. It was the floor *plus* roughly 43,000 tokens the
top-down closure added — while still not fixing English. Redo any size plan based on it: the
sliced build should land near **520 MB fp32** (was 651) — 420.9 MB encoder body + 36.5 MB
embedding + 57.3 MB head + 1.6 MB tokenizer — and INT8 should now take the encoder body to
roughly 110 MB, with the embedding at 9.1 MB no longer a factor.

## What a pruned tokenizer still changes, by design

Held-out text is *not* the original tokenizer. A word whose full-token id never appeared in the
corpus can split into finer known pieces — `▁flooded` → `▁flo` + `oded` — because the full-word
merge result is not in the kept vocab. That is the price of a 4.9% vocabulary, and it is
bounded: base characters, the byte-fallback alphabet and the full Latin+digit range survive, so
held-out text degrades to finer *known* pieces, never to raw bytes. Of the 12 held-out sample
sentences, 3 split finer and **0 fall back**.

`eval/laya_tokenizer_check.py` encodes that policy: corpus and inference strings are hard gates
(must be piece-identical), held-out text is a byte-fallback gate plus a reported drift count.
The rescue phrase list is the lever for domain text — if the bench shows a word class regressing,
add that class there first.

## Reproduced against the real pipeline functions

`eval/laya_tokenizer_check.py` imports `build_keep_set` and `rebuild_tokenizer` from the two
pipeline scripts — no copies to drift — and rebuilds the tokenizer against the unpruned
`tokenizer.json`. Only `tokenizers` is needed; no torch, no ONNX, no checkpoint:

```bash
uv venv --python 3.12 /tmp/laya-venv
uv pip install --python /tmp/laya-venv/bin/python tokenizers
/tmp/laya-venv/bin/python eval/laya_tokenizer_check.py \
    --tokenizer ~/.cache/huggingface/.../multilingual/tokenizer/tokenizer.json \
    --corpus eval/corpus_transcripts.json
```

It also asserts the remap invariant the embedding slice depends on: **new id i must be old id
`keep[i]`**, which is the row order `laya_slice_onnx.py` slices to. A silent mismatch here is the
failure mode that produces fluent nonsense with no error.

The corpus itself is now reproducible: `eval/fleurs_transcripts.py` pins the source (FLEURS
validation/test via the HF rows API, text only; `mr_in`/`ml_in` fall back to a temp parquet
download because their files exceed the API's 300 MB scan cap) and commits
`eval/corpus_transcripts.json`, 3,776 transcripts. The September corpus was built ad hoc and its
keep-set lived in `/tmp` — that is why the defect could not be rechecked without redoing the
export.

## Still not measured

- **Decisions after the fix.** The 7/12 was measured with the degraded English tokenizer; the
  pre-prune reference is 8/12. With tokenization now identical on everything the model is
  prompted with and the slice bit-exact, 8/12 is the expected number — an expectation, not a
  measurement, until the ONNX export is re-run.
- **INT8.** Still not applied. `eval/laya_quantize.py` now exists — dynamic int8 for the
  encoder body and the embedding Gather, best-effort head, with an encoder drift check — but it
  cannot be run until the model is re-exported. Accuracy risk lives there now.
- **In the app.** Unchanged.

## State of the integration (updated)

| | |
|---|---|
| Exported and verified | yes — torch vs ONNX 2.1e-06 (29 Sep) |
| Vocab sliced | yes — encoder bit-exact (29 Sep) |
| Tokenizer | **fixed: 12,454 tokens, 100% piece-identical on 3,776 transcripts + 13 inference strings** |
| Held-out text | splits finer on unseen full-word tokens, no byte fallback |
| Decisions | 8/12 pre-prune reference; post-fix re-measurement pending the export |
| INT8 | not done; quantizer script written and probe-tested on a synthetic Gather→MatMul graph |
| **In the app** | **no** |

# Addendum 3 — export re-run, INT8 measured, and what the numbers can actually support

**15 October 2026.** The full pipeline was re-run end to end after the tokenizer fix: export →
prune → slice → int8 → bench. Everything below is measured on this machine (Apple Silicon, CPU
execution provider, 2 intra-op threads to match the app) unless marked otherwise.

## The headline numbers

| variant | size | encoder / head / total | 12-case smoke |
|---|---|---|---|
| fp32 sliced (reference) | 518.9 MB | 66 / 9 / **75 ms** | 8/12 |
| **int8** (embedding + body + head) | **143.9 MB** | 26 / 4 / **31 ms** | 10/12 |
| int8 body+head, embedding kept fp32 (`--no-embed`) | 171.2 MB | 27 / 5 / 32 ms | 9/12 |

3.6x smaller and 2.4x faster than the fp32 slice, from one quantize step. The phone is expected
to be 3-5x slower: **100-200 ms per decision**, which is nothing for a message that arrived as a
129-byte packet and is allowed to sit in a queue.

## What was re-verified, in order

- **Export** — re-run against the current checkpoint: torch vs ONNX 2.1e-06, identical to the
  29 September run.
- **Prune** — 12,454 tokens (4.9%), **0.00% OOV** on all 3,776 corpus transcripts.
- **Slice** — embedding 750.0 → 36.5 MB; the sliced encoder matches the original **bit-exactly
  (0.00e+00) on all six verify languages, English included**. Addendum 2 could not say that about
  English; this is the first end-to-end confirmation that the tokenizer fix survives slicing.
- **Quantize** — encoder 459.9 → 117.5 MB (3.9x), head 57.3 → 24.7 MB (2.3x, see below), the
  embedding folded into its consuming matmul by onnxruntime's Gather support.

## Two defects found during the run, both fixed in the scripts

**1. The embedding shape lives in three places, not two.** `laya_slice_onnx.py` rewrote the
initializer and any hard-coded `256000` node attributes, but not the initializer's `value_info`
entry. onnxruntime tolerates the stale `[256000, 768]`; `onnx.shape_inference` — which
`quantize_dynamic` calls on load — does not: *"Inferred shape and existing shape differ in
dimension 0: (12454) vs (256000)"*. That is the failure that killed the September quantization
attempt. Fix: rewrite the matching `value_info` dims during the slice. The slice stays bit-exact.

**2. The head has its own stale `value_info`.** The exported head declares a `cat` tensor
`[batch, 772]` where inference derives `[batch, 256]` (the marker count) — a second, unrelated
shape-inference failure. `value_info` is advisory metadata, so the quantizer now strips it from a
scratch copy of the head before quantizing: 57.3 → 24.7 MB, and a decision-parity check (same
hidden states into both heads, six real sentences) is **6/6 same argmax, confidence within 3e-3**.
While here, two false friends were removed: on a fallback copy the quantizer now copies
`head.onnx.data` too (it used to copy only the 0.2 MB graph and leave the 57 MB of weights
behind), and it wipes stale model files from the output directory so a re-run cannot pair a fresh
single-file head with an old `.data`.

## The metric lesson: max-abs drift lies, cosine at the markers doesn't

The first int8 drift check compared max absolute difference of hidden states and read "LARGE"
(rel 0.3-1.2) in every language — useless. Hidden states are dominated by a few outlier channels
(hundreds in magnitude); their movement swamps the metric while the decision-relevant signal is
intact. What is checked now:

- **cos@markers** — per-position cosine at the `<mask>` marker positions, the only positions the
  decision head reads. Measured: 0.76-0.94 for int8. (The whole-sequence minimum drops to 0.014 on
  one Hindi case — invisible to the head, and uninformative.)
- **Decision parity, deployed vs reference** — int8 encoder+head vs fp32 encoder+head on the same
  real input: 4/6 same on the six-sentence verify set, the two flips being the `hi` and `or` rows
  below.

This is the drift check that can go red meaningfully; the bench remains the arbiter of decisions.

## What the flips mean (and what 8 vs 9 vs 10 is worth)

Every decision that moved, moved in the 0.36-0.80 confidence band:

| case | fp32 | int8 | truth |
|---|---|---|---|
| hi evacuation | medical emergency 0.824 | evacuation order 0.489 | evacuation |
| or evacuation | safety warning 0.800 | evacuation order 0.797 | evacuation |
| en routine | evacuation order 0.385 | medical emergency 0.541 | routine |
| hi routine | evacuation order 0.412 | medical emergency 0.400 | routine |

Nothing at ≥0.87 confidence flipped in any variant: ta 0.976→0.955, mr 0.965→0.950,
bn 0.981→0.938, en evacuation 0.994→0.972 all hold their argmax. So the honest reading of
8/12 (fp32) vs 10/12 (int8) is **not** "int8 is more accurate" — it is twelve sentences, and both
variants share the same zero-shot over-trigger weakness recorded in Addendum 1. What the
measurements support is a policy, not an accuracy claim:

- escalate (ALERT) only above a confidence floor, and never invert an escalation from the low band;
- treat 0.4-0.8 as "repeat the question / ask a human", not as a decision;
- never let Laya alone make a destructive decision. This is a 0.6B decision model, not a
  classifier with a validation set.

A labelled evaluation is still the prerequisite for shipping decisions; this run changes what is
available to evaluate, not that requirement.

## Reproduce

```bash
# scratch venv (torch is needed only by the export)
uv pip install --python /tmp/laya-venv/bin/python torch transformers safetensors \
    huggingface_hub onnxscript onnx onnxruntime tokenizers
# the vendored exporter imports laya.common.build_model: pip install laya, or PYTHONPATH a clone
python eval/laya_onnx.py export      --out /tmp/laya_onnx
python eval/laya_prune_vocab.py      --onnx /tmp/laya_onnx --out /tmp/laya_pruned \
                                     --corpus-text eval/corpus_transcripts.json
python eval/laya_slice_onnx.py       --onnx /tmp/laya_onnx --pruned /tmp/laya_pruned \
                                     --out /tmp/laya_sliced
python eval/laya_quantize.py         --sliced /tmp/laya_sliced --out /tmp/laya_int8
python eval/laya_onnx.py bench       --model /tmp/laya_int8
```

## State of the integration (updated)

| | |
|---|---|
| Exported and verified | yes — 2.1e-06; re-run 15 Oct identical |
| Vocab sliced | yes — bit-exact incl. English, 0.00e+00 |
| Tokenizer | 12,454 tokens; 100% piece-identical on 3,776 transcripts + 13 inference strings |
| Decisions | fp32 8/12 · int8 10/12 · int8 no-embed 9/12 — differences are low-band noise, see above |
| INT8 model | **done — 143.9 MB**; embedding folded; head quantized after the `value_info` strip |
| Fallbacks | `--no-embed` measured (9/12, +27 MB); `--no-per-channel` written, not measured |
| Still not measured | labelled accuracy; on-phone latency; Android ORT opset-18 coverage; Kotlin tokenizer byte-parity |
| **In the app** | **no** — next: Kotlin ONNX session + BPE reader behind a toggle |

---

# Addendum 4 — in the app: tokenizer port, the right ONNX Runtime, and a toggle

Addendum 3 ended with "next: Kotlin ONNX session + BPE reader behind a toggle". This is that
work, plus the one finding that changed the plan: the ONNX Runtime that ASR and TTS already run
on could not be swapped without breaking them.

## What went into the app

| Piece | Where | Notes |
|---|---|---|
| Kotlin BPE port | `app/.../laya/LayaBpe.kt` | Metaspace + byte-fallback BPE, hand-rolled base64 (needs no API 26, works in JVM tests) |
| Parity gate | `app/.../laya/LayaBpeTest.kt` + `app/src/test/resources/laya_tokenizer_cases.txt` | 75 cases, 2,970 ids, vocab 12,454 and merges 30,829 pinned |
| Prompt builder | `app/.../laya/LayaPrompt.kt` | pure Kotlin (no Android, no ORT); `<bos> question <eos> (<mask> option)* <eos> state <eos>` |
| Prompt gate | `app/.../laya/LayaPromptTest.kt` + `app/src/test/resources/laya_prompt_cases.txt` | 18 prompts from the harness's own `build()`: ids, marker positions, the truncation edge, and the runtime `config.json` |
| Shared fixture readers | `app/.../laya/LayaFixtures.kt` (test sources) | line-oriented, base64 fields, no JSON parser |
| Fixture/staging generator | `eval/laya_mobile_assets.py` | cross-checks the Python port against `tokenizers` (and the portable prompt assembly against `LayaOnnx.build`) before writing anything |
| Triage engine | `app/.../laya/LayaTriage.kt` | ORT sessions, asset copy, softmax, escalation policy |
| Receive-path hook | `MainActivity.onPacket` | behind the Settings toggle; runs on the `tts` worker thread |
| Runtime | `jniLibs/arm64-v8a/libonnxruntime4j_jni.so` + `app/libs/onnxruntime-android-1.28.2.jar` | 114 KB + 119 KB, from csukuangfj's v1.28.2 release |

The fixture is generated only *after* the pure-Python port matches the real `tokenizers` library
on every case — the 12 bench sentences, whitespace and script edge cases, and a deterministic
sample of the 3,776-transcript corpus. The Kotlin test then replays those recorded ids and
pieces on the JVM: no model, no device, no Python in the loop.

That gate earned its keep on the first run. The emoji case failed:

```
case '😀': ids expected:<[11883, 395, 314, 307, 283]> but was:<[11883, 218, 218]>
```

A Python or Rust "char" is one code point; a Kotlin `Char` is one UTF-16 unit, so 😀 arrived as
two unpaired surrogates, each of which encodes to a replacement byte and degrades to `<unk>` —
two tokens instead of four `<0x…>` byte tokens. Iterating code points fixes it. Nothing about
the model would have made that visible; it was the fixture, on the JVM, in 10 seconds.

## The runtime, and why the obvious plan was wrong

The Laya model needs an ONNX Runtime **Java API**; the app ships sherpa-onnx's JNI, which only
speaks the C API. The obvious move — add Maven's `onnxruntime-android` AAR — turns out to be a
trap that would have broken the speech path at runtime, with nothing to see at build time:

```
$ nm -D --defined-only libonnxruntime.so          # the library already in jniLibs
   T OrtGetApiBase@@VERS_1.28.2
   T OrtSessionOptionsAppendExecutionProvider_CPU@@VERS_1.28.2
$ nm -D --undefined-only libonnxruntime4j_jni.so  # Maven 1.28.0's JNI
   U OrtGetApiBase@VERS_1.28.0
$ nm -D --undefined-only libonnxruntime4j_jni.so  # Maven 1.29.0's JNI
   U OrtGetApiBase@VERS_1.29.0
```

Android's linker matches **versioned symbols exactly**. sherpa's JNI requires
`OrtGetApiBase@VERS_1.28.2`; neither Maven library defines that version. Copying either over the
shipped `libonnxruntime.so` — the plan standing at the end of Addendum 3 — would have produced a
`dlopen` failure (or an unresolved symbol) on the *ASR/TTS* path, not on the new one.

The way out is the source of the shipped binary: csukuangfj publishes the whole Android package
for the same release. `onnxruntime-android-1.28.2.zip` contains `classes.jar`,
`libonnxruntime4j_jni.so`, and a `libonnxruntime.so` whose sha256 is
`33847ad4…2ffcf2da1` — **byte-identical to the one in `jniLibs`**. So the app takes only the Java
side (jar + 114 KB JNI) and keeps exactly one ONNX Runtime, the one every measurement in this
document was taken with. `setup-project.sh` verifies that hash and refuses the download if it
ever differs.

Two secondary questions, settled before writing the code:

- **Is the release a full build?** Yes. Its workflow builds with `--android --android_api 27
  --config Release` and no `--minimal_build`, so the op registrations are the full set. The 42
  ops the int8 model uses are a subset; VITS TTS already exercises the unusual ones
  (`Expand`, `GatherND`, `ScatterND`, `Where`, `Equal`, `ConstantOfShape`, `RandomNormalLike`) on
  this exact binary on a phone.
- **Is the AAR's manifest a problem?** It is two lines: `package="ai.onnxruntime"` and
  `minSdkVersion 24`. Consuming the jar directly means no manifest merge at all; the app's
  `minSdk` stays 21 and the triage layer itself is gated at 24.

**A method that does not work, recorded so it is not tried again:** grepping op names out of the
binary. NUL-bounded counts were **0 for every op** in all three libraries — `Conv`, `Erf` and
`MatMul` included, all of which provably run on these devices — because op names are not stored
as standalone NUL-terminated strings. Absence of an op name in `strings` output is not evidence
of anything.

## The policy the app implements

`LayaTriage` reads the message once and returns the option the head prefers. It can only
**escalate**:

| Read | What the app does |
|---|---|
| label is `evacuation order` / `medical emergency`, confidence ≥ **0.85** | announcement is upgraded to ALERT (loud, loops) |
| anything else, any confidence | message is spoken normally, and the read is printed: `[safety warning 62%]` |
| layer not ready, empty text, any exception | message is spoken normally; nothing is logged as a decision |

The 0.85 floor is not a round number picked for comfort: Addendum 3 measured every int8-vs-fp32
flip between 0.36 and 0.80, and **nothing at or above 0.87 changed its argmax**. The floor sits
above the band where the model is known to wobble. Below-floor reads are shown rather than
hidden, because that band is exactly where the layer is least trustworthy — and there is no code
path in which Laya suppresses, edits, downgrades, or delays a message.

Mechanically: the header asset copy lands on a `.part` name and is verified against the asset's
`openFd` length (both `.onnx` files are stored uncompressed), so a process death mid-copy cannot
leave a truncated model that a later run trusts; sessions run on 2 intra-op threads, the same
budget ASR uses; the `SessionOptions` object is closed as soon as both sessions have copied what
they need; all tensor buffers are direct buffers in native byte order; the prompt is built
byte-for-byte as `eval/laya_onnx.py` builds it
(`<bos> question <eos> (<mask> option)* <eos> state <eos>`), because that format is part of the
model's training, not a UI string.

### The prompt format is gated like data, not trusted

The prompt is the quietest place for this layer to be wrong. Special ids from the wrong
vocabulary (`[CLS]`/`[SEP]` instead of this model's 2/1/4) raise nothing and log nothing — the
head simply returns a uniform distribution — and a marker one token off re-scores every option
from the wrong position with no shape change anywhere. So the assembly is not merely mirrored in
Kotlin (`LayaPrompt`, pure Kotlin, no Android, no ORT); it is gated against the harness's own
`LayaOnnx.build`.

`eval/laya_mobile_assets.py --out-prompt-fixture` writes
`app/src/test/resources/laya_prompt_cases.txt` from that function: 18 prompts covering the 12
bench sentences, empty and whitespace-run states, an astral character, a Devanagari state, and
one state long enough to hit the window (1 prompt at exactly 1024 tokens, so truncation is
*checked*, not assumed). While writing the fixture the script also re-runs the portable assembly
and aborts unless it agrees with the harness on every prompt, so the fixture can never describe
ids the port cannot produce. `LayaPromptTest` replays all 18 id-for-id with their marker
positions, checks every marker points at a `<mask>` token, and checks the fixture's `SPEC`
against the `config.json` the app reads at runtime.

The gate is falsifiable and was falsified once on purpose: changing `max_len` in
`app/src/main/assets/laya/config.json` from 1024 to 512 makes `the runtime config matches the
fixture's spec` fail. The suite is now 18 tests (12 QualityGate + 2 tokenizer + 4 prompt).

## What is still not measured

- **On-phone latency.** Desktop is 31 ms per decision (26 encoder + 4 head, Apple Silicon, 2
  threads). Android is expected 3–5x slower: 100–200 ms, paid once per received message, off the
  audio thread. Unmeasured until it runs on a phone.
- **Labelled accuracy of the decisions.** The 12-sentence bench and the 10/12 result are not an
  accuracy estimate; the escalation threshold is a *safety* choice informed by measured variance,
  not a calibrated operating point.
- **Cost.** +142.8 MB of assets (118 MB encoder, 25 MB head, 530 KB tokenizer), +233 KB of native
  code and jar; ~150 MB resident while the layer is on. The assets are copied into app storage on
  first enable (~10 s on a mid-range phone, once).
- **That the escalation behaviour is right in the field.** Twelve sentences cannot settle it.

## Reproduce / verify

```bash
# 1. JVM parity gate — 75 tokenizer cases + 18 prompts, no model, no device
cd app && ./build.sh testDebugUnitTest

# 2. regenerate either fixture from the harness (aborts unless the port agrees with it)
T=/tmp/laya                                    # wherever the venv + models live
"$T/laya-venv/bin/python" eval/laya_mobile_assets.py \
    --tokenizer "$T/laya_sliced/tokenizer.json" --model-dir "$T/laya_int8" \
    --out-fixture app/app/src/test/resources/laya_tokenizer_cases.txt \
    --out-prompt-fixture app/app/src/test/resources/laya_prompt_cases.txt

# 3. stage the runtime + model and build (fetches the 1.28.2 Java API, verifies the lib hash)
app/setup-project.sh
cd app && ./build.sh assembleDebug

# 4. the expectation the phone's log should match
python eval/laya_onnx.py bench --model models/laya-int8
```

On the phone: Settings → "Triage received messages (Laya, experimental)" (the toggle sits above
the log, not at the bottom of it), then send the bench sentences from the other phone. The
receiving log shows one `triage <id>: <label> <p>%` line per message, with
"(below the 85% floor — the band where int8 flips)" on anything under it. `app/pull_logs.sh`
pulls the log and prints those lines together with the command that produced the desktop
expectation.

## State of the integration (updated)

| | |
|---|---|
| Tokenizer parity | **done** — Kotlin port matches the reference on all 75 fixture cases (one surrogate-pair bug found and fixed) |
| Prompt format | **done** — `LayaPrompt` is gated against the harness's own `build()` on 18 prompts (ids + marker positions + truncation edge + runtime config), falsified once on purpose |
| Android runtime | **resolved** — one ORT stays in the APK (1.28.2, byte-identical to the shipped lib); versioned-symbol constraint documented above |
| In the app | **yes** — receive path, behind a Settings toggle, off by default |
| Escalation policy | **implemented** — escalate only above 0.85, only for destructive labels; nothing is ever suppressed or downgraded |
| Still not measured | labelled accuracy; on-phone latency; field behaviour of the escalation; Android-side memory profile of the copy |
| Unchanged by this work | ASR/TTS binary, models, and every number in Addendum 3 |
