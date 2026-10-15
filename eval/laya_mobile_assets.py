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

4. The prompt-format fixture, written with `--out-prompt-fixture`. The prompt
   (`<bos> question <eos> (<mask> option)* <eos> state <eos>`) is part of the model's training,
   not a UI string, and the answer ids are only meaningful if the prompt ids are right; a prompt
   built with the wrong specials returns a uniform distribution with no error anywhere. Every
   line here is produced by the harness's own `LayaOnnx.build`, and this script aborts unless the
   portable re-implementation of the same assembly agrees with it.

       SPEC     <bos> <eos> <mask> <max_len>
       QUESTION <b64>
       OPTION   <b64>                     one line per option, in order
       PROMPT   <b64 state> <ids csv> <markers csv>

Usage:
    python eval/laya_mobile_assets.py \
        --tokenizer /tmp/laya_sliced/tokenizer.json \
        --model-dir /tmp/laya_int8 \
        --out-assets app/app/src/main/assets/laya \
        --out-fixture app/app/src/test/resources/laya_tokenizer_cases.txt \
        --out-prompt-fixture app/app/src/test/resources/laya_prompt_cases.txt
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


def laya_module(repo):
    """Import eval/laya_onnx.py: the constants and the prompt assembly live there, not here."""
    sys.path.insert(0, os.path.join(repo, "eval"))
    import laya_onnx                                        # noqa: E402
    return laya_onnx


def bench_cases(repo):
    return [text for _, text, _ in laya_module(repo).CASES]


def load_spec(repo, model_dir, tokenizer_path, unk_id):
    """Special ids and window size, from the same places the harness reads them."""
    laya = laya_module(repo)
    max_len = 1024
    if model_dir:
        cfg_path = os.path.join(model_dir, "rl_agent_config.json")
        if os.path.exists(cfg_path):
            with open(cfg_path) as f:
                max_len = int(json.load(f).get("max_len", 1024))
        # A prompt fixture is only evidence if the tokens that built it are the tokens the app
        # ships, so refuse a model dir whose tokenizer.json differs from --tokenizer.
        tok = os.path.join(model_dir, "tokenizer.json")
        if os.path.exists(tok):
            with open(tok, "rb") as f:
                other = f.read()
            with open(tokenizer_path, "rb") as f:
                ours = f.read()
            if other != ours:
                sys.exit(f"{tok} differs from {tokenizer_path}; the fixture would describe a "
                         f"tokenizer the app does not ship.")
    return {"bos": laya.BOS, "eos": laya.EOS, "unk": unk_id, "mask": laya.MASK, "max_len": max_len}


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


# ---------------------------------------------------------------- the prompt, ported

def build_prompt_port(state, vocab, merges, spec, question, options):
    """The same assembly as LayaOnnx.build, on the portable encoder that LayaBpe.kt mirrors."""
    def enc(text):
        return reference_encode(text, vocab, merges)[0]

    ids = [spec["bos"]] + enc(question) + [spec["eos"]]
    markers = []
    for o in options:
        markers.append(len(ids))
        ids.append(spec["mask"])
        ids += enc(o)
    ids.append(spec["eos"])
    ids += enc(state)
    ids.append(spec["eos"])
    return ids[: spec["max_len"]], [m for m in markers if m < spec["max_len"]]


def prompt_states(lib, spec, bench):
    """The bench sentences plus the inputs that make assembly bugs visible: empty, whitespace
    runs (the normalizer's job), an astral character, and one state long enough to be
    truncated at the window edge (so truncation is checked, not assumed)."""
    states = list(bench)
    states += ["", " ", "▁ mixed ▁ spacing  ", "😀", "  दो  स्‍पेस  "]
    base = "बाढ़ आ गई है, पुलिस को तुरंत सूचित करें"
    long_state = base
    while len(lib.encode(long_state, add_special_tokens=False).ids) <= spec["max_len"]:
        long_state += " " + base
    states.append(long_state)
    return list(dict.fromkeys(states))


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


def write_prompt_fixture(path, harness_build, vocab, merges, spec, question, options, states):
    """Write the prompt fixture, from the harness's own build() but with the portable assembly
    cross-checked against it for every case -- the fixture is what the Kotlin test compares to,
    so it must not be buildable from a tokenizer the port disagrees with."""
    prompts, n_markers, n_truncated = [], 0, 0
    for state in states:
        ids_ref, marks_ref = harness_build(state, question, options)
        ids_port, marks_port = build_prompt_port(state, vocab, merges, spec, question, options)
        if (ids_ref, marks_ref) != (ids_port, marks_port):
            sys.exit(f"prompt assembly mismatch for {state[:60]!r}\n"
                     f"  harness: {ids_ref[:40]}\n"
                     f"  port   : {ids_port[:40]}")
        if len(marks_ref) != len(options):
            sys.exit(f"prompt for {state[:60]!r} lost a marker in a {spec['max_len']}-token window")
        if len(ids_ref) == spec["max_len"]:
            n_truncated += 1
        prompts.append((state, ids_ref, marks_ref))
        n_markers += len(marks_ref)

    with open(path, "w") as f:
        f.write("# Laya prompt-format parity fixture -- generated by eval/laya_mobile_assets.py\n")
        f.write("# line types: SPEC <bos> <eos> <mask> <max_len> | QUESTION <b64>\n")
        f.write("#             OPTION <b64> | PROMPT <b64 state> <ids csv> <markers csv>\n")
        f.write("# '-' is an empty field (base64 of '' is '').\n")
        f.write(f"SPEC {spec['bos']} {spec['eos']} {spec['mask']} {spec['max_len']}\n")
        f.write(f"QUESTION {b64(question)}\n")
        for o in options:
            f.write(f"OPTION {b64(o)}\n")
        for state, ids, marks in prompts:
            f.write(f"PROMPT {b64(state) or '-'} {','.join(map(str, ids))} "
                    f"{','.join(map(str, marks))}\n")
    print(f"  prompts: {path} ({len(prompts)} prompts, {n_markers} markers, "
          f"{n_truncated} at the window edge, longest {max(len(i) for _, i, _ in prompts)} tokens)")


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
    ap.add_argument("--out-prompt-fixture", help="JVM prompt-format fixture to write")
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()

    vocab, vocab_r, merges = load_tokenizer(args.tokenizer)
    print(f"tokenizer: {len(vocab_r)} tokens, {len(merges)} merges")
    spec = load_spec(args.repo, args.model_dir, args.tokenizer, unk_id=vocab["<unk>"])
    print(f"spec: {spec}")

    bench = bench_cases(args.repo)
    cases = bench + edge_cases() + corpus_cases(args.repo)
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
        cfg = {"bos": spec["bos"], "eos": spec["eos"], "unk": spec["unk"],
               "mask": spec["mask"], "max_len": spec["max_len"]}
        with open(os.path.join(args.out_assets, "config.json"), "w") as f:
            json.dump(cfg, f, indent=1)
        total = sum(os.path.getsize(os.path.join(args.out_assets, f))
                    for f in os.listdir(args.out_assets))
        print(f"  assets : {args.out_assets} ({total/1048576:.1f} MB)")

    if args.out_fixture:
        os.makedirs(os.path.dirname(args.out_fixture), exist_ok=True)
        write_fixture(args.out_fixture, vocab_r, merges, cases, encoded)

    if args.out_prompt_fixture:
        laya = laya_module(args.repo)
        # The harness's real build(), without paying for its __init__: loading 143 MB of int8
        # sessions would buy this script nothing, build() needs only the tokenizer and max_len.
        harness = laya.LayaOnnx.__new__(laya.LayaOnnx)
        harness.tok = lib
        harness.max_len = spec["max_len"]
        os.makedirs(os.path.dirname(args.out_prompt_fixture), exist_ok=True)
        write_prompt_fixture(args.out_prompt_fixture, harness.build, vocab, merges, spec,
                             laya.QUESTION, laya.URGENCY, prompt_states(lib, spec, bench))


if __name__ == "__main__":
    main()
