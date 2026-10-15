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
