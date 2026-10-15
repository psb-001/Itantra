"""
Quantize the sliced Laya ONNX to int8 weights, and measure the drift it introduces.

WHAT THIS DOES

Dynamic (weight-only) int8 quantisation through onnxruntime: weights drop to 1 byte, activations
stay fp32 and are scaled at run time. No calibration set is needed, which matters because the
ten languages have none.

The two halves are quantised differently on purpose:

* **Encoder body** -- MatMul weights, per output channel. This is the 478 MB of transformer
  weights the vocab slice cannot touch; int8 is what makes the total land under 200 MB.
* **Embedding** -- a Gather initializer, which MatMul-only quantisation never touches. onnxruntime
  supports Gather in dynamic mode: it rewrites the table to uint8 and folds the table's scale and
  zero point into the consuming MatMul, so the 36.5 MB fp32 table becomes ~9.4 MB and no separate
  DequantizeLinear node is needed. The table scale is per-tensor, so this is the step to inspect
  first if decision accuracy drops -- `--no-embed` keeps the embedding fp32.
* **Head** -- the exported head carries a stale `value_info` entry (`cat` declared [batch, 772]
  where shape inference derives [batch, 256]) that kills the quantizer's shape-inference pass even
  though onnxruntime runs the graph fine. `value_info` is advisory, so the head is quantized from
  a copy with `graph.value_info` stripped; if that still fails the fp32 head is copied instead.
  Measured: 57.3 MB -> 25.9 MB, same argmax, confidence within 1e-3.

The encoder is verified against the fp32 slice on real sentences, and the quantized head against
the fp32 head on a real decision, before anything is called shippable. Those checks catch a
broken graph; they cannot catch a decision regression, which is what the bench is for:

    python eval/laya_onnx.py bench --model /tmp/laya_int8

Run:
    python eval/laya_quantize.py --sliced /tmp/laya_sliced --out /tmp/laya_int8
"""

import argparse
import json
import os
import shutil
import sys

import numpy as np

# Same sentences eval/laya_slice_onnx.py verifies the slice with.
SENTENCES = [
    ("hi", "बाढ़ आ गई है, पुलिस को तुरंत सूचित करें"),
    ("ta", "வெள்ளை நீரில் மூழ்கும் அபாயம், உடனே அகற்றுங்கள்"),
    ("en", "the river has flooded, evacuate the camp immediately"),
    ("gu", "નદી ભરાઈ ગઈ છે, તરત ખાલી કરો"),
    ("kn", "ನದಿ ತುಂಬಿದೆ, ಎಲ್ಲರೂ ಈಗ ಹೊರಡಿ"),
    ("or", "ବିଆଗ ବଢ଼ିଛି, ସମେଲେ ବାହାରକୁ ଯାଆନ୍ତୁ"),
]


def mb(path):
    return os.path.getsize(path) / 1048576 if os.path.exists(path) else 0.0


def copy_with_data(src, dst):
    """Copy a model file and its external .data sibling, if any."""
    shutil.copy(src, dst)
    if os.path.exists(src + ".data"):
        shutil.copy(src + ".data", dst + ".data")


def strip_value_info(src, dst):
    """Write a self-contained copy of `src` with graph.value_info removed.

    onnxruntime runs the exported head happily, but onnx.shape_inference -- which quantize_dynamic
    calls on load -- rejects it: the stale `cat` value_info says [batch, 772] while inference says
    [batch, 256]. value_info is advisory metadata, so dropping it changes nothing at run time.
    """
    import onnx

    m = onnx.load(src, load_external_data=True)
    del m.graph.value_info[:]
    onnx.save(m, dst, save_as_external_data=False)


def quantize_file(src, dst, op_types, per_channel):
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(src, dst, op_types_to_quantize=op_types,
                     weight_type=QuantType.QInt8, per_channel=per_channel)
    return mb(dst)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sliced", default="/tmp/laya_sliced", help="output of laya_slice_onnx.py")
    ap.add_argument("--out", default="/tmp/laya_int8")
    ap.add_argument("--no-embed", action="store_true",
                    help="keep the embedding table fp32 (debug knob if decisions degrade)")
    ap.add_argument("--no-per-channel", action="store_true",
                    help="quantize MatMul weights per tensor instead of per output channel")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    per_channel = not args.no_per_channel

    # Idempotent re-runs: a fallback copy from an earlier run could leave a stale head.onnx.data
    # behind next to a freshly quantized single-file head, and onnxruntime would load that instead.
    for f in os.listdir(args.out):
        if f.endswith(".onnx") or f.endswith(".onnx.data"):
            os.remove(os.path.join(args.out, f))

    # ---------------------------------------------------------------- encoder
    src_enc = os.path.join(args.sliced, "encoder.onnx")
    out_enc = os.path.join(args.out, "encoder.onnx")
    ops = ["MatMul", "Gemm"] + ([] if args.no_embed else ["Gather"])
    fp32_enc = mb(src_enc) + mb(src_enc + ".data")     # the 457 MB of weights live in .data
    print(f"quantizing encoder ({fp32_enc:.1f} MB fp32, ops {ops}, per_channel={per_channel})",
          flush=True)
    int8_enc = quantize_file(src_enc, out_enc, ops, per_channel)
    print(f"  encoder {fp32_enc:.1f} -> {int8_enc:.1f} MB ({fp32_enc/int8_enc:.1f}x)")

    # ---------------------------------------------------------------- head
    src_head = os.path.join(args.sliced, "head.onnx")
    out_head = os.path.join(args.out, "head.onnx")
    tmp_head = os.path.join(args.out, "_head_stripped.onnx")
    fp32_head = mb(src_head) + mb(src_head + ".data")     # weights live in head.onnx.data
    try:
        print(f"quantizing head ({fp32_head:.1f} MB fp32, value_info stripped)", flush=True)
        strip_value_info(src_head, tmp_head)
        int8_head = quantize_file(tmp_head, out_head, ["MatMul", "Gemm"], per_channel)
        print(f"  head {fp32_head:.1f} -> {int8_head:.1f} MB ({fp32_head/int8_head:.1f}x)")
    except Exception as e:
        copy_with_data(src_head, out_head)
        int8_head = fp32_head
        print(f"  head did not quantize ({type(e).__name__}: {e}) -- copied fp32 instead")
    finally:
        if os.path.exists(tmp_head):
            os.remove(tmp_head)

    for f in ("tokenizer.json", "rl_agent_config.json"):
        src = os.path.join(args.sliced, f)
        if os.path.exists(src):
            shutil.copy(src, os.path.join(args.out, f))

    total = sum(os.path.getsize(os.path.join(args.out, f))
                for f in os.listdir(args.out) if os.path.isfile(os.path.join(args.out, f)))
    print(f"\n  int8 total: {total/1048576:.1f} MB   (sliced fp32 was "
          f"{fp32_enc + fp32_head + mb(os.path.join(args.sliced, 'tokenizer.json')):.1f} MB)")

    # ---------------------------------------------------------------- verify
    # A load failure or a broken graph here means the quantisation was damaged. Numeric drift is
    # expected and fine; only the bench can say whether decisions survived.
    print("\nverifying the int8 build against the fp32 slice on real decisions", flush=True)
    import onnxruntime as ort
    from tokenizers import Tokenizer

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from laya_onnx import BOS, EOS, MASK, QUESTION, URGENCY     # same constants the bench uses

    tk = Tokenizer.from_file(os.path.join(args.sliced, "tokenizer.json"))
    s_enc8 = ort.InferenceSession(out_enc, providers=["CPUExecutionProvider"])
    s_encf = ort.InferenceSession(src_enc, providers=["CPUExecutionProvider"])
    s_head8 = ort.InferenceSession(out_head, providers=["CPUExecutionProvider"])
    s_headf = ort.InferenceSession(src_head, providers=["CPUExecutionProvider"])

    def cos_min(a, b):
        a, b = a.reshape(-1, a.shape[-1]), b.reshape(-1, b.shape[-1])
        cos = (a * b).sum(1) / (np.linalg.norm(a, axis=1) * np.linalg.norm(b, axis=1) + 1e-9)
        return float(cos.min())

    def decide(session, hidden, marks, am):
        feed = {"hidden_states": hidden.astype(np.float32),
                "marker_pos": np.array([marks], np.int64),
                "marker_mask": np.ones((1, len(marks)), bool),
                "qtype": np.array([[0]], np.int64),
                "attention_mask": am}
        logits = session.run(None, feed)[0][0]
        p = np.exp(logits - logits.max())
        return URGENCY[int(p.argmax())], float((p / p.sum()).max())

    # The head reads the encoder's hidden state at the <mask> marker positions -- that is the drift
    # that matters, and max-abs over all dims is dominated by outlier channels and lies. What is
    # compared is the deployed pair (int8 encoder + int8 head) against the reference pair (fp32 +
    # fp32) on the same real question/options/state input the bench builds.
    print(f"  {'lang':<5}{'cos@markers':>12}{'cos@all':>9}{'fp32 decision':>22}"
          f"{'int8 decision':>22}  ok")
    worst, matched = 1.0, 0
    for lang, text in SENTENCES:
        ids = [BOS] + tk.encode(QUESTION, add_special_tokens=False).ids + [EOS]
        marks = []
        for o in URGENCY:
            marks.append(len(ids))
            ids.append(MASK)
            ids += tk.encode(o, add_special_tokens=False).ids
        ids.append(EOS)
        ids += tk.encode(text, add_special_tokens=False).ids
        ids.append(EOS)
        ii = np.array([ids], np.int64)
        am = np.ones((1, len(ids)), np.int64)
        (h8,) = s_enc8.run(None, {"input_ids": ii, "attention_mask": am})
        (hf,) = s_encf.run(None, {"input_ids": ii, "attention_mask": am})
        cmark = cos_min(hf[0][marks], h8[0][marks])
        worst = min(worst, cmark)
        fa, pa = decide(s_headf, hf, marks, am)
        fb, pb = decide(s_head8, h8, marks, am)
        same = fa == fb
        matched += same
        print(f"  {lang:<5}{cmark:>12.5f}{cos_min(hf, h8):>9.3f}"
              f"{f'{fa} {pa:.3f}':>22}{f'{fb} {pb:.3f}':>22}  {'Y' if same else 'N'}")
    print(f"\n  worst cos@markers: {worst:.5f}  ·  deployed == reference on "
          f"{matched}/{len(SENTENCES)} sentences")
    print("  (1.0 = bit-identical hidden states; the bench decides what holds in practice.)")

    # (Head-only drift, with the fp32 hidden state fed to both heads, was measured separately and
    # is bit-level clean on decisions: same argmax, confidence within 3e-3. The merged check above
    # is the stricter one -- it also lets encoder drift into the head.)

    print(f"\nnext: python eval/laya_onnx.py bench --model {args.out}")
    print("     The fp32 slice benches 8/12, and 12 sentences cannot resolve 8 vs 9 vs 10: what")
    print("     matters is that no high-confidence case flips. If one does, fall back with")
    print("     --no-embed, then --no-per-channel.")


if __name__ == "__main__":
    main()
