"""
Regression check for the pruned Laya tokenizer.

WHAT IT GUARDS

docs/LAYA_INTEGRATION_FINDINGS.md records a defect in the vocab-pruned tokenizer: the nine
Indic languages tokenized identically to the original, English did not (9 pieces became 39)
and every space fell back to bytes. The cause was in the BPE merge closure, not the weights:
the walk was top-down ("both merge inputs survive, so keep the result") and never guaranteed
that a kept token's own producer chain survived, so `▁the` was kept but `▁th` was not. The
encoder was then shown text it was never shaped for. See the merge-closure comment in
eval/laya_prune_vocab.py for the full note; the fix walks bottom-up with every producer.

This script runs the real pipeline functions -- eval/laya_prune_vocab.build_keep_set and
eval/laya_slice_onnx.rebuild_tokenizer -- against the unpruned tokenizer.json and fails if any
sample changes its pieces. It needs no ONNX graph and no model weights, so it runs in a small
throwaway environment with only `tokenizers`:

    uv venv --python 3.12 /tmp/laya-venv
    uv pip install --python /tmp/laya-venv/bin/python tokenizers
    /tmp/laya-venv/bin/python eval/laya_tokenizer_check.py --tokenizer TOKENIZER.json

With --corpus it additionally round-trips every transcript in eval/corpus_transcripts.json
(fetch with eval/fleurs_transcripts.py) and reports the keep-set size and the embedding size it
implies. That is the full tokenizer side of the prune; only the decision accuracy still needs
the ONNX export.

Exit status is non-zero if any string tokenizes differently after the prune.
"""

import argparse
import json
import os
import sys
import tempfile

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, EVAL_DIR)

from laya_prune_vocab import INFERENCE_STRINGS, build_keep_set      # noqa: E402
from laya_slice_onnx import rebuild_tokenizer                        # noqa: E402

# Real sentences in the languages the app ships. English is the one that regressed; the Indic
# languages are here so a fix for English cannot silently regress them.
SAMPLES = {
    "en": [
        "the river has flooded, evacuate the camp immediately",
        "lunch is at one, see you there",
        "help, we are trapped under the bridge, send a boat",
    ],
    "hi": ["बाढ़ आ गई है, पुलिस को तुरंत सूचित करें"],
    "mr": ["पूर आहे, सर्वांना लगेच बाहेर जा"],
    "bn": ["পদ্মা নদী বাড়ছে, সবাই এখনই সরে যান"],
    "ta": ["வெள்ளை நீரில் மூழ்கும் அபாயம், உடனே அகற்றுங்கள்"],
    "te": ["గండ్రి నీరు పెరుగుతోంది, వెంటనే విడిచండి"],
    "ml": ["പുഴയിൽ വെള്ളം ഉയർന്നു, ഉടൻ മാറിക്കുക"],
    "gu": ["નદી ભરાઈ ગઈ છે, તરત ખાલી કરો"],
    "kn": ["ನದಿ ತುಂಬಿದೆ, ಎಲ್ಲರೂ ಈಗ ಹೊರಡಿ"],
    "or": ["ବିଆଗ ବଢ଼ିଛି, ସମେଲେ ବାହାରକୁ ଯାଆନ୍ତୁ"],
}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tokenizer", required=True, help="unpruned tokenizer.json")
    ap.add_argument("--corpus", default="", help="JSON {lang: [texts]} to round-trip in full "
                                                 "(see eval/fleurs_transcripts.py)")
    ap.add_argument("--out", default="", help="keep the rebuilt tokenizer here")
    args = ap.parse_args()

    from tokenizers import Tokenizer

    tok = Tokenizer.from_file(args.tokenizer)
    cfg = json.load(open(args.tokenizer))
    vocab = cfg["model"]["vocab"]
    inv_old = {v: k for k, v in vocab.items()}
    cfg_merges = cfg["model"].get("merges", [])
    print(f"original: {len(vocab)} tokens, {len(cfg_merges)} merges\n")

    corpus_texts = {}
    if args.corpus:
        corpus_texts = json.load(open(args.corpus))
        n = sum(len(v) for v in corpus_texts.values())
        print(f"corpus: {n} transcripts across {len(corpus_texts)} languages\n")

    # Corpus tokens: the samples, plus the full corpus when one is given. build_keep_set adds
    # the rescue phrases and inference strings on top.
    corpus = set()
    for texts in SAMPLES.values():
        for text in texts:
            corpus |= set(tok.encode(text, add_special_tokens=False).ids)
    for texts in corpus_texts.values():
        for text in texts:
            corpus |= set(tok.encode(text, add_special_tokens=False).ids)

    print("building keep-set with eval/laya_prune_vocab.build_keep_set")
    keep = build_keep_set(tok, corpus, cfg_merges)

    out_dir = args.out or tempfile.mkdtemp(prefix="laya-tok-check-")
    os.makedirs(out_dir, exist_ok=True)
    tok_out = os.path.join(out_dir, "tokenizer.json")

    print("\nrebuilding with eval/laya_slice_onnx.rebuild_tokenizer")
    n_vocab, n_merges = rebuild_tokenizer(args.tokenizer, sorted(keep), tok_out)
    print(f"  vocab    : {len(vocab)} -> {n_vocab}")
    print(f"  merges   : {len(cfg_merges)} -> {n_merges}")
    print(f"  tokenizer: {tok_out} ({os.path.getsize(tok_out)/1048576:.1f} MB)")

    # New id i MUST be old id keep[i], because that is the row order of the sliced embedding.
    new_tok = Tokenizer.from_file(tok_out)
    ordered = sorted(keep)
    new_vocab = new_tok.get_vocab()
    for i, old_id in enumerate(ordered):
        want = inv_old.get(old_id)
        if want is not None:
            got = new_vocab.get(want)
            if got != i:
                sys.exit(f"remap broken: old id {old_id} ({want!r}) should be new id {i}, "
                         f"tokenizer says {got}")

    def compare(label, texts):
        bad = []
        for text in texts:
            want = tok.encode(text, add_special_tokens=False).tokens
            got = new_tok.encode(text, add_special_tokens=False).tokens
            if want != got:
                bad.append((text, want, got))
        if bad:
            text, want, got = bad[0]
            print(f"  BAD {label}: {len(bad)}/{len(texts)} strings differ, first:")
            print(f"      {text[:64]}")
            print(f"      want {want[:12]}")
            print(f"      got  {got[:12]}")
        return bad

    print("\ntokenizing the samples with both tokenizers")
    bad = compare("samples", [t for ts in SAMPLES.values() for t in ts])
    bad += compare("inference strings", INFERENCE_STRINGS)
    if corpus_texts:
        print("\ntokenizing the full corpus with both tokenizers")
        for lang, texts in corpus_texts.items():
            b = compare(lang, texts)
            print(f"  {lang:>7}  {len(texts) - len(b):>5}/{len(texts):<5} identical pieces")
            bad += b

    for name, flags in (("fp32", 4), ("int8", 1)):
        mb = len(keep) * 768 * flags / 1048576
        print(f"\nkeep-set {len(keep)} rows -> embedding {mb:.1f} MB {name}")

    if bad:
        sys.exit(f"\n{len(bad)} regression(s) -- do not rebuild the graph with this keep-set")
    print("\nOK: the pruned tokenizer is shape-identical to the original on every string tested")


if __name__ == "__main__":
    main()
