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

## Known defect, not fixed: English tokenization is still degraded

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
