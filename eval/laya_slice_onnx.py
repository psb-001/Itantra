"""
Slice Laya's token embedding from 256,000 rows down to the keep-set, and remap the tokenizer.

Run after eval/laya_prune_vocab.py. Takes the exported encoder.onnx (+ its external .data) and
produces a graph whose embedding table is `len(keep)` rows instead of 256,000, plus a rewritten
tokenizer.json that maps the surviving tokens onto the new contiguous ids.

WHY THE REMAP AND NOT JUST A SLICE

An embedding slice renumbers rows. If the tokenizer still emits the old ids, every token lands on
the wrong row and the model produces fluent nonsense with no error. So the two must be rewritten
together: old id -> new id, and anything not kept becomes <unk> (id 3), which is always in the
keep-set.

Measured on the ten required languages, 7,264 FLEURS reference transcripts: 0.00% of corpus tokens
fall outside the keep-set, so the OOV rate on real speech in those languages is bounded by the
same number.

External data note: the exported encoder.onnx is >2 GB with weights in encoder.onnx.data, so it
must be loaded with load_external_data=True and re-saved with the same convention or the .data
file and the graph will disagree.
"""

import argparse
import json
import os
import shutil
import sys

import numpy as np


def load_graph(path):
    import onnx
    from onnx import external_data_helper
    m = onnx.load(path, load_external_data=True)
    external_data_helper.load_external_data_for_model(m, os.path.dirname(path))
    return m


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--onnx", default="/tmp/laya_onnx")
    ap.add_argument("--pruned", default="/tmp/laya_pruned")
    ap.add_argument("--out", default="/tmp/laya_sliced")
    ap.add_argument("--quantize", action="store_true", help="also write int8 encoder+head")
    args = ap.parse_args()

    import onnx
    from onnx import numpy_helper

    keep = sorted(json.load(open(os.path.join(args.pruned, "keep_ids.json"))))
    old_n = 256000
    print(f"keep-set: {len(keep)} of {old_n} rows")

    remap = np.full(old_n, -1, dtype=np.int64)
    for new_id, old_id in enumerate(keep):
        remap[old_id] = new_id
    unk_new = int(remap[3])          # <unk> is always in the keep-set
    print(f"  new ids 0..{len(keep)-1};  <unk> maps to {unk_new}")
    assert unk_new >= 0, "<unk> missing from the keep-set -- this would be unrecoverable"

    os.makedirs(args.out, exist_ok=True)

    # ---------------------------------------------------------------- encoder
    src = os.path.join(args.onnx, "encoder.onnx")
    print(f"\nloading {src} (about 1.2 GB with external data)...", flush=True)
    enc = load_graph(src)

    target = None
    for init in enc.graph.initializer:
        if init.dims and init.dims[0] == old_n and len(init.dims) == 2:
            target = init
            break
    if target is None:
        sys.exit(f"no [256000, d] initializer found in the encoder graph")

    emb = numpy_helper.to_array(target)
    print(f"  embedding {emb.shape} {emb.dtype} = {emb.nbytes/1048576:.1f} MB")

    sliced = emb[np.array(keep, dtype=np.int64)].astype(np.float32)
    print(f"  sliced    {sliced.shape} = {sliced.nbytes/1048576:.1f} MB "
          f"({emb.nbytes/sliced.nbytes:.1f}x smaller)")

    new_init = numpy_helper.from_array(sliced, target.name)
    new_init.ClearField("data_location")
    idx = list(enc.graph.initializer).index(target)
    enc.graph.initializer[idx].CopyFrom(new_init)

    # Any node hard-coding 256000 (a Reshape to [-1, 768], for instance) has to be fixed too,
    # or the graph will reshape the sliced table to the old width and fail at load time.
    from onnx import numpy_helper as nh
    fixed = 0
    for node in enc.graph.node:
        for a in node.attribute:
            if a.type == onnx.AttributeProto.INT and a.i == old_n:
                a.i = len(keep)
                fixed += 1
    print(f"  rewrote {fixed} hard-coded {old_n} attribute(s) in the graph")

    enc_path = os.path.join(args.out, "encoder.onnx")
    # The sliced graph is under the 2 GB protobuf limit, so save it self-contained. Any .data
    # left from a previous run is the old 1.2 GB blob and must not survive alongside it.
    onnx.save_model(enc, enc_path, save_as_external_data=False)
    stale = os.path.join(args.out, "encoder.onnx.data")
    if os.path.exists(stale):
        os.remove(stale)
    size = os.path.getsize(enc_path)
    print(f"  wrote {enc_path} = {size/1048576:.1f} MB")

    # ---------------------------------------------------------------- tokenizer
    # Rebuilt with the tokenizers library rather than by patching the JSON in place.
    # Hand-editing produced a file that was internally consistent (0 dangling references) and
    # still refused to load with "Token `\n` out of vocabulary" -- the loader cross-checks the
    # vocab, the merges, the added_tokens AND the decoder against each other, and a hand-written
    # subset does not satisfy it. Constructing it through the library guarantees consistency.
    from tokenizers import Tokenizer as _Tok
    from tokenizers.models import BPE

    tok_src = os.path.join(args.onnx, "tokenizer.json")
    src_tok = _Tok.from_file(tok_src) if os.path.exists(tok_src) else None
    old_cfg = json.load(open(tok_src))
    old_vocab = old_cfg["model"]["vocab"]
    inv = {v: k for k, v in old_vocab.items()}

    new_vocab, seen = {}, set()
    for new_id, old_id in enumerate(keep):
        tok_str = inv.get(old_id)
        if tok_str is None or tok_str in seen:
            continue
        seen.add(tok_str)
        new_vocab[tok_str] = new_id
    # the BPE refuses to build if the unk token is absent
    unk_str = old_cfg["model"].get("unk_token", "<unk>")
    if unk_str not in new_vocab:
        new_vocab[unk_str] = unk_new

    merges = [tuple(m) for m in old_cfg["model"].get("merges", [])
              if isinstance(m, list) and len(m) == 2
              and m[0] in new_vocab and m[1] in new_vocab]
    print(f"  vocab    : {len(old_vocab)} -> {len(new_vocab)}")
    print(f"  merges   : {len(old_cfg['model'].get('merges', []))} -> {len(merges)}")

    m = old_cfg["model"]
    # Pass only the parameters that are actually set. The BPE constructor is typed for str on
    # the optional prefix fields and rejects an explicit None, which is what the original file
    # stores for both of them.
    kw = dict(
        vocab=new_vocab,
        merges=merges,
        fuse_unk=bool(m.get("fuse_unk", True)),
        byte_fallback=bool(m.get("byte_fallback", False)),
    )
    for key in ("unk_token", "continuing_subword_prefix", "end_of_word_suffix"):
        if m.get(key):
            kw[key] = m[key]
    if m.get("dropout") is not None:
        kw["dropout"] = m["dropout"]
    bpe = BPE(**kw)

    out_tok = _Tok(bpe)

    # The pipeline nodes MUST be carried over. Without the pre_tokenizer the space character is
    # never turned into the U+2581 word-boundary marker, so every space falls through to byte
    # fallback and "the river has flooded" tokenises as
    #     th <0x65> <0x20> r <0x69> <0x76> e r ...
    # instead of
    #     \u2581the \u2581river \u2581has \u2581flooded
    # 9 pieces become 42, the model sees text it was never shaped for, and decisions collapse
    # from 8/12 to 2/12 with confidence bunched near 0.30. It looks like the prune damaged the
    # weights. It damaged nothing -- the tokenizer simply had no pre_tokenizer.
    if src_tok is not None:
        out_tok.normalizer = src_tok.normalizer
        out_tok.pre_tokenizer = src_tok.pre_tokenizer
        out_tok.post_processor = src_tok.post_processor
        out_tok.decoder = src_tok.decoder
    tok_out = os.path.join(args.out, "tokenizer.json")
    out_tok.save(tok_out)
    check = _Tok.from_file(tok_out)      # must load, or this whole file is worthless
    print(f"  tokenizer rebuilt and reloads cleanly: {tok_out} "
          f"({os.path.getsize(tok_out)/1048576:.1f} MB)")

    for f in ("rl_agent_config.json",):
        s_ = os.path.join(args.onnx, f)
        if os.path.exists(s_):
            shutil.copy(s_, os.path.join(args.out, f))
    for f in ("head.onnx", "head.onnx.data"):
        s_ = os.path.join(args.onnx, f)
        if os.path.exists(s_):
            shutil.copy(s_, os.path.join(args.out, f))

    total = sum(os.path.getsize(os.path.join(args.out, f))
                for f in os.listdir(args.out) if os.path.isfile(os.path.join(args.out, f)))
    print(f"  sliced total: {total/1048576:.1f} MB   (was 1,261 MB)")

    # ---------------------------------------------------------------- verify
    print("\nverifying the sliced graph matches the original on real text", flush=True)
    import onnxruntime as ort
    from tokenizers import Tokenizer

    tk = Tokenizer.from_file(os.path.join(args.out, "tokenizer.json"))
    s_new = ort.InferenceSession(enc_path, providers=["CPUExecutionProvider"])
    s_old = ort.InferenceSession(src, providers=["CPUExecutionProvider"])

    S = ["hi", "ta", "en", "gu", "kn", "or"]
    txt = {k: v for k, v in [
        ("hi", "बाढ़ आ गई है, पुलिस को तुरंत सूचित करें"),
        ("ta", "வெள்ளை நீரில் மூழ்கும் அபாயம், உடனே அகற்றுங்கள்"),
        ("en", "the river has flooded, evacuate the camp immediately"),
        ("gu", "નદી ભરાઈ ગઈ છે, તરત ખાલી કરો"),
        ("kn", "ನದಿ ತುಂಬಿದೆ, ಎಲ್ಲರೂ ಈಗ ಹೊರಡಿ"),
        ("or", "ବିଆଗ ବଢ଼ିଛି, ସମେଲେ ବାହାରକୁ ଯାଆନ୍ତୁ"),
    ]}
    print(f"  {'lang':<5}{'max abs diff':>14}{'rel':>10}   verdict")
    worst = 0.0
    for k in S:
        ids_old = None
        enc_id = tk.encode(txt[k], add_special_tokens=False).ids
        # map pruned ids back to the original space for the reference graph
        inv_map = np.full(len(keep), -1, np.int64)
        for new_id, old_id in enumerate(keep):
            inv_map[new_id] = old_id
        ids_new = np.array(enc_id, np.int64)
        ids_old = inv_map[ids_new]
        if (ids_old < 0).any():
            print(f"  {k:<5}  OOV after prune — skipped"); continue
        ii = np.array([ids_new], np.int64)
        io = np.array([ids_old], np.int64)
        am = np.ones((1, len(enc_id)), np.int64)
        (hn,) = s_new.run(None, {"input_ids": ii, "attention_mask": am})
        (ho,) = s_old.run(None, {"input_ids": io, "attention_mask": am})
        d = float(np.abs(hn - ho).max())
        rel = d / max(float(np.abs(ho).max()), 1e-6)
        worst = max(worst, rel)
        okk = "OK" if rel < 1e-3 else "DIFFERS"
        print(f"  {k:<5}{d:>14.2e}{rel:>10.2e}   {okk}")
    print(f"\n  worst relative difference: {worst:.2e}")
    if worst > 1e-3:
        sys.exit("sliced encoder does not match the original — do not ship this")


if __name__ == "__main__":
    main()
