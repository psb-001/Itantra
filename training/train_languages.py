#!/usr/bin/env python3
"""Train one text language model per language. Fast, no dependencies.

Two ways to feed it:

1. FLEURS transcripts that already sit in this repo (default):
       python3 training/train_languages.py
   It reads dataset/fleurs/*_*.tsv, keeps one copy of each sentence, and
   trains hi, mr, bn, ta, te, ml, en, gu, kn, or separately.

2. Your own text, one folder, one file per language:
       python3 training/train_languages.py --corpus-dir my-text
   where my-text/hi.txt, my-text/mr.txt, ... hold one sentence per line.

Every language gets its own trigram model at training/out/text-lm/<lang>.json
and its own held-out perplexity. The FLEURS test split only has about 300
unique sentences per language, so expect noisy scores. Point --corpus-dir at
a bigger corpus (Wikipedia dump, subtitle files, your own messages) for a
model worth shipping.

This is a text model. It is not the speech model. sherpa-onnx does speech.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import train_text_lm as core  # noqa: E402

NATIVE = {
    "hi": "हिन्दी", "mr": "मराठी", "bn": "বাংলা", "ta": "தமிழ்", "te": "తెలుగు",
    "ml": "മലയാളം", "en": "English", "gu": "ગુજરાતી", "kn": "ಕನ್ನಡ", "or": "ଓଡ଼ିଆ",
}


def lines_from_tsv(path: Path) -> list[str]:
    seen: set[str] = set()
    lines: list[str] = []
    with path.open(encoding="utf-8", errors="replace") as handle:
        for row in handle:
            cells = row.rstrip("\n").split("\t")
            text = (cells[3] if len(cells) > 3 else cells[-1]).strip()
            if text and text not in seen:
                seen.add(text)
                lines.append(text)
    return lines


def discover_fleurs(root: Path) -> dict[str, Path]:
    return {path.name.split("_")[0]: path for path in sorted(root.glob("*_*.tsv"))}


def discover_dir(root: Path) -> dict[str, Path]:
    return {path.stem: path for path in sorted(root.glob("*.txt"))}


def train_one(lines: list[str], lang: str, out_dir: Path) -> dict:
    started = time.perf_counter()
    sentences = core.tokenize(lines)
    if len(sentences) < 20:
        raise ValueError(f"need at least 20 usable lines, got {len(sentences)}")

    split = max(1, int(len(sentences) * 0.9))
    train, held_out = sentences[:split], sentences[split:]
    raw_train = train

    raw_uni, _, _ = core.count_ngrams(train)
    keep = {w for w, c in raw_uni.items() if c >= 2} | {core.BOS, core.EOS}
    train = core.remap_unknown(train, keep)
    held_out = core.remap_unknown(held_out, keep)

    uni, bi, tri = core.count_ngrams(train)
    model = core.Model(uni, bi, tri)
    # Sampling uses the raw words. The scoring model maps rare words to <unk>,
    # which is right for perplexity but makes samples short and dull.
    sample_model = core.Model(*core.count_ngrams(raw_train))

    payload = {
        "format": "itantra-text-ngram-v1",
        "language": lang,
        "order": 3,
        "discount": core.Model.DISCOUNT,
        "tokens": model.total,
        "vocab": sorted(uni),
        "unigram": dict(uni),
        "bigram": {" ".join(g): c for g, c in bi.items()},
        "trigram": {" ".join(g): c for g, c in tri.items()},
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{lang}.json"
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))

    elapsed = time.perf_counter() - started
    return {
        "lang": lang,
        "native": NATIVE.get(lang, lang),
        "lines": len(sentences),
        "tokens": model.total,
        "vocab": len(uni),
        "trigrams": len(tri),
        "kb": path.stat().st_size / 1024,
        "secs": elapsed,
        "ppl": model.perplexity(held_out),
        "sample": sample_model.sample(random.Random(7), limit=14),
        "path": path,
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train one text language model per language.")
    parser.add_argument("--fleurs", type=Path, default=HERE.parent / "dataset" / "fleurs",
                        help="Folder with FLEURS *_*.tsv files.")
    parser.add_argument("--corpus-dir", type=Path, default=None,
                        help="Use <lang>.txt files from this folder instead of FLEURS.")
    parser.add_argument("--out", type=Path, default=HERE / "out" / "text-lm",
                        help="Folder for <lang>.json models.")
    parser.add_argument("--langs", default=None, help="Comma list, for example hi,mr,en.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    source = args.corpus_dir or args.fleurs
    if not source.exists():
        print(f"No corpus folder at {source}", file=sys.stderr)
        return 1

    files = discover_dir(source) if args.corpus_dir else discover_fleurs(source)
    if args.langs:
        wanted = {code.strip() for code in args.langs.split(",") if code.strip()}
        files = {code: path for code, path in files.items() if code in wanted}
    if not files:
        print(f"No language files found in {source}", file=sys.stderr)
        return 1

    print(f"source  {source}")
    print(f"models  {args.out}")
    print()
    print(f"{'lang':4} {'language':10} {'lines':>6} {'tokens':>7} {'vocab':>6} {'trigram':>8} {'model':>8} {'secs':>5} {'ppl':>7}  sample")

    rows: list[dict] = []
    for lang, path in files.items():
        lines = lines_from_tsv(path) if path.suffix == ".tsv" else core.read_lines([path])
        try:
            row = train_one(lines, lang, args.out)
        except ValueError as error:
            print(f"{lang:4} skipped: {error}", file=sys.stderr)
            continue
        rows.append(row)
        print(
            f"{row['lang']:4} {row['native']:10} {row['lines']:6,} {row['tokens']:7,} "
            f"{row['vocab']:6,} {row['trigrams']:8,} {row['kb']:7.0f}K {row['secs']:5.2f} "
            f"{row['ppl']:7.1f}  {row['sample'][:58]}"
        )

    if rows:
        total_tokens = sum(row["tokens"] for row in rows)
        total_secs = sum(row["secs"] for row in rows)
        total_kb = sum(row["kb"] for row in rows)
        print()
        print(f"{len(rows)} languages, {total_tokens:,} tokens, {total_kb / 1024:.2f} MB total, "
              f"{total_secs:.2f} s to train")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
