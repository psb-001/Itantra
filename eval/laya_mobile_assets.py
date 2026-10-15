"""
Stage the Laya int8 model for the Android app, and generate the tokenizer parity fixture.

WHAT THIS DOES

1. A pure-Python re-implementation of the exact tokenizer pipeline the Kotlin side will
   implement -- Replace normalizer (space -> U+2581), Metaspace pre-tokenizer (prepend the
   marker unless the text already starts with it, then split on the marker with
   "merged with next"), then BPE with byte fallback (`<0x0A>`-style tokens, `<unk>` when a
   byte token is missing). Cross-checked against the real `tokenizers` library on every
   string that matters: the 12 bench sentences, whitespace/script edge cases, and a
   deterministic sample of the corpus. **Any mismatch aborts the run**, so a fixture can
   never be generated from a model that does not match the library.

2. The compact tokenizer the app loads at runtime, written as `vocab.b64` and
   `merges.b64` in the asset directory: one base64-encoded UTF-8 token per line, ids and
   merge ranks implied by line order. This is what keeps a JSON parser out of the app and
   avoids a 1.6 MB tokenizer.json parse in a 2 GB-RAM phone's receiving path.

3. The JVM parity fixture, written as a line-oriented file (base64 fields, no JSON) so the
   unit test needs no parser dependency either:

       VOCAB  <b64 token>                 one line per id, 0..vocab_size-1
       MERGES <b64 a> <b64 b>             one line per rank, best rank first
       CASE   <b64 text> <ids csv> <b64 of pieces joined by 0x01>

Usage:
    python eval/laya_mobile_assets.py \
        --tokenizer /tmp/laya_sliced/tokenizer.json \
        --model-dir /tmp/laya_int8 \
        --out-assets app/app/src/main/assets/laya \
        --out-fixture app/app/src/test/resources/laya_tokenizer_cases.txt
"""

import argparse
import base64
import json
import os
import random
import sys

CORPUS_SAMPLE = 40          # transcripts pulled from the corpus for the parity cases
SEED = 20261015


# ---------------------------------------------------------------- the pipeline, ported

def load_tokenizer(path):
    with open(path) as f:
        cfg = json.load(f)
    model = cfg["model"]
    assert model["type"] == "BPE", f"expected BPE, got {model['type']}"
    assert model["byte_fallback"], "the app port assumes byte_fallback=true"
    assert not model["ignore_merges"], "the app port assumes ignore_merges=false"
    vocab = dict(model["vocab"])                       # token -> id
    vocab_r = [None] * len(vocab)
    for tok, i in vocab.items():
        vocab_r[i] = tok
    assert all(t is not None for t in vocab_r), "vocab ids are not dense 0..n-1"
    merges = {}
    for rank, pair in enumerate(model["merges"]):
        a, b = pair if isinstance(pair, list) else pair.split(" ")
        merges[(a, b)] = rank
    return vocab, vocab_r, merges


def byte_token(b):
    return "<0x%02X>" % b


def reference_encode(text, vocab, merges):
    """Mirror of what LayaBpe.kt does; returns (ids, pieces)."""
    if text == "":
        return [], []

    # Normalizer: Replace " " -> "▁" (all occurrences), then Metaspace sees the result.
    norm = text.replace(" ", "▁")
    if not norm.startswith("▁"):
        norm = "▁" + norm

    # Metaspace split: every marker starts a piece with the following text; a marker that
    # is followed by another marker (or the end) is its own "▁" piece.
    pieces = []
    cur = []
    for i, ch in enumerate(norm):
        if ch == "▁":
            if cur:
                pieces.append("".join(cur))
                cur = []
            if i + 1 < len(norm) and norm[i + 1] != "▁":
                cur.append("▁")
            else:
                pieces.append("▁")
        else:
            cur.append(ch)
    if cur:
        pieces.append("".join(cur))

    out = []
    for piece in pieces:
        syms = []
        for ch in piece:
            if ch in vocab:
                syms.append(ch)
            else:
                btoks = [byte_token(b) for b in ch.encode("utf-8")]
                if all(t in vocab for t in btoks):
                    syms.extend(btoks)
                else:
                    syms.append("<unk>")
        while True:
            best_rank, best_i = None, -1
            for i in range(len(syms) - 1):
                r = merges.get((syms[i], syms[i + 1]))
                if r is not None and (best_rank is None or r < best_rank):
                    best_rank, best_i = r, i
            if best_i < 0:
                break
            syms[best_i:best_i + 2] = [syms[best_i] + syms[best_i + 1]]
        out.extend(syms)

    ids = [vocab.get(t, vocab["<unk>"]) for t in out]
    return ids, out


# ---------------------------------------------------------------- cases

def edge_cases():
    return [
        "", " ", "  ", "\t", "\n", "a\tb", "a\nb", "a  b", "  leading", "trailing  ",
        "▁", "▁▁", "hello world", "नमस्ते दुनिया", "हैलो", "வணக்கம்", "ഹലോ", "12345",
        "3.14", "A-B_C!", "#hashtag", "😀", "बाढ़आ", "पानी", "போர்",
    ]


def bench_cases(repo):
    sys.path.insert(0, os.path.join(repo, "eval"))
    import laya_onnx                                        # noqa: E402
    return [text for _, text, _ in laya_onnx.CASES]


def corpus_cases(repo):
    path = os.path.join(repo, "eval", "corpus_transcripts.json")
    with open(path) as f:
        data = json.load(f)
    if isinstance(data, dict):
        rows = []
        for lang, texts in data.items():
            for t in texts:
                rows.append(t)
    else:                                                   # list of {lang, text}
        rows = [r["text"] if isinstance(r, dict) else r for r in data]
    rng = random.Random(SEED)
    return rng.sample(rows, min(CORPUS_SAMPLE, len(rows)))


# ---------------------------------------------------------------- output

def b64(s):
    return base64.b64encode(s.encode("utf-8")).decode("ascii")


def write_compact(path_vocab, path_merges, vocab_r, merges):
    with open(path_vocab, "w") as f:
        for tok in vocab_r:
            f.write(b64(tok) + "\n")
    with open(path_merges, "w") as f:
        for (a, b) in sorted(merges, key=merges.get):
            f.write(f"{b64(a)} {b64(b)}\n")


def write_fixture(path, vocab_r, merges, cases, encoded):
    n_ids = sum(len(ids) for ids, _ in encoded)
    with open(path, "w") as f:
        f.write("# Laya tokenizer parity fixture -- generated by eval/laya_mobile_assets.py\n")
        f.write("# line types: VOCAB <b64 token> | MERGES <b64 a> <b64 b> | CASE <b64 text> <ids> <b64 pieces>\n")
        for tok in vocab_r:
            f.write(f"VOCAB {b64(tok)}\n")
        for (a, b) in sorted(merges, key=merges.get):
            f.write(f"MERGES {b64(a)} {b64(b)}\n")
        for text, (ids, pieces) in zip(cases, encoded):
            # "-" marks an empty field: base64 of "" is "", and trailing empty fields would be
            # invisible to a naive whitespace split.
            text_s = b64(text) or "-"
            ids_s = ",".join(map(str, ids)) or "-"
            pieces_s = b64(chr(1).join(pieces)) or "-"
            f.write(f"CASE {text_s} {ids_s} {pieces_s}\n")
    print(f"  fixture: {path} ({len(cases)} cases, {n_ids} ids total)")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tokenizer", required=True, help="pruned tokenizer.json")
    ap.add_argument("--model-dir", help="dir with encoder.onnx/head.onnx to stage (optional)")
    ap.add_argument("--out-assets", help="app assets dir; gets the compact tokenizer + models")
    ap.add_argument("--out-fixture", help="JVM parity fixture to write")
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()

    vocab, vocab_r, merges = load_tokenizer(args.tokenizer)
    print(f"tokenizer: {len(vocab_r)} tokens, {len(merges)} merges")

    cases = bench_cases(args.repo) + edge_cases() + corpus_cases(args.repo)
    cases = list(dict.fromkeys(cases))                      # dedupe, keep order
    print(f"cases: {len(cases)} strings")

    from tokenizers import Tokenizer
    lib = Tokenizer.from_file(args.tokenizer)

    bad = 0
    encoded = []
    for text in cases:
        ids_ref, pieces_ref = reference_encode(text, vocab, merges)
        enc = lib.encode(text, add_special_tokens=False)
        encoded.append((ids_ref, pieces_ref))
        if enc.ids != ids_ref or enc.tokens != pieces_ref:
            bad += 1
            print(f"  MISMATCH {text!r}")
            print(f"    library: {enc.tokens}")
            print(f"    port   : {pieces_ref}")
    if bad:
        sys.exit(f"\n{bad} mismatch(es) -- the reference port does not match the library. "
                 f"Fix the port before generating fixtures.")
    print(f"  reference port matches the library on all {len(cases)} cases")

    if args.out_assets:
        os.makedirs(args.out_assets, exist_ok=True)
        write_compact(os.path.join(args.out_assets, "vocab.b64"),
                      os.path.join(args.out_assets, "merges.b64"), vocab_r, merges)
        if args.model_dir:
            import shutil
            for f in ("encoder.onnx", "head.onnx"):
                src, dst = os.path.join(args.model_dir, f), os.path.join(args.out_assets, f)
                if os.path.exists(src):
                    shutil.copy(src, dst)
            for f in os.listdir(args.model_dir):            # .onnx.data siblings, if any
                if f.endswith(".onnx.data"):
                    shutil.copy(os.path.join(args.model_dir, f), os.path.join(args.out_assets, f))
        cfg = {"bos": 2, "eos": 1, "unk": 3, "mask": 4, "max_len": 1024}
        with open(os.path.join(args.out_assets, "config.json"), "w") as f:
            json.dump(cfg, f, indent=1)
        total = sum(os.path.getsize(os.path.join(args.out_assets, f))
                    for f in os.listdir(args.out_assets))
        print(f"  assets : {args.out_assets} ({total/1048576:.1f} MB)")

    if args.out_fixture:
        os.makedirs(os.path.dirname(args.out_fixture), exist_ok=True)
        write_fixture(args.out_fixture, vocab_r, merges, cases, encoded)


if __name__ == "__main__":
    main()
