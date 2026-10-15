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
* **Head** -- best effort. The earlier attempt on the unsliced head died in shape inference
  (`dim 0: 772 vs 256`); if that still happens the fp32 head is copied and reported, which costs
  ~57 MB but cannot be wrong.

The encoder is verified against the fp32 slice on real sentences before anything is called
shippable. That check catches a broken graph; it does not catch a decision regression, which is
what the bench is for:

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

    # ---------------------------------------------------------------- encoder
    src_enc = os.path.join(args.sliced, "encoder.onnx")
    out_enc = os.path.join(args.out, "encoder.onnx")
    ops = ["MatMul", "Gemm"] + ([] if args.no_embed else ["Gather"])
    fp32_enc = mb(src_enc)
    print(f"quantizing encoder ({fp32_enc:.1f} MB fp32, ops {ops}, per_channel={per_channel})",
          flush=True)
    int8_enc = quantize_file(src_enc, out_enc, ops, per_channel)
    print(f"  encoder {fp32_enc:.1f} -> {int8_enc:.1f} MB ({fp32_enc/int8_enc:.1f}x)")

    # ---------------------------------------------------------------- head
    src_head = os.path.join(args.sliced, "head.onnx")
    out_head = os.path.join(args.out, "head.onnx")
    fp32_head = mb(src_head)
    try:
        print(f"quantizing head ({fp32_head:.1f} MB fp32)", flush=True)
        int8_head = quantize_file(src_head, out_head, ["MatMul", "Gemm"], per_channel)
        print(f"  head {fp32_head:.1f} -> {int8_head:.1f} MB ({fp32_head/int8_head:.1f}x)")
    except Exception as e:
        shutil.copy(src_head, out_head)
        int8_head = fp32_head
        print(f"  head did not quantize ({type(e).__name__}: {e}) -- copied fp32 instead")

    for f in ("tokenizer.json", "rl_agent_config.json"):
        src = os.path.join(args.sliced, f)
        if os.path.exists(src):
            shutil.copy(src, os.path.join(args.out, f))

    total = sum(os.path.getsize(os.path.join(args.out, f))
                for f in os.listdir(args.out) if os.path.isfile(os.path.join(args.out, f)))
    print(f"\n  int8 total: {total/1048576:.1f} MB   (sliced fp32 was "
          f"{fp32_enc + fp32_head + mb(os.path.join(args.sliced, 'tokenizer.json')):.1f} MB)")

    # ---------------------------------------------------------------- verify
    # A load failure or a wildly different encoder here means the graph was damaged. Small
    # numeric drift is expected and fine; only the bench can say whether decisions survived.
    print("\nverifying the int8 encoder against the fp32 slice", flush=True)
    import onnxruntime as ort
    from tokenizers import Tokenizer

    tk = Tokenizer.from_file(os.path.join(args.sliced, "tokenizer.json"))
    s_int8 = ort.InferenceSession(out_enc, providers=["CPUExecutionProvider"])
    s_fp32 = ort.InferenceSession(src_enc, providers=["CPUExecutionProvider"])

    print(f"  {'lang':<5}{'max abs diff':>14}{'rel':>10}   verdict")
    worst = 0.0
    for lang, text in SENTENCES:
        ids = np.array([tk.encode(text, add_special_tokens=False).ids], np.int64)
        am = np.ones((1, ids.shape[1]), np.int64)
        (h8,) = s_int8.run(None, {"input_ids": ids, "attention_mask": am})
        (hf,) = s_fp32.run(None, {"input_ids": ids, "attention_mask": am})
        d = float(np.abs(h8 - hf).max())
        rel = d / max(float(np.abs(hf).max()), 1e-6)
        worst = max(worst, rel)
        print(f"  {lang:<5}{d:>14.2e}{rel:>10.2e}   {'OK' if rel < 5e-2 else 'LARGE'}")
    print(f"\n  worst relative encoder drift: {worst:.2e}")

    print(f"\nnext: python eval/laya_onnx.py bench --model {args.out}")
    print("     8/12 is the fp32 reference. If it drops, try --no-embed first, then "
          "--no-per-channel.")


if __name__ == "__main__":
    main()
