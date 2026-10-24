#!/usr/bin/env python3
"""Train a small word n-gram language model from plain text. Fast, no dependencies.

This is a real trainer, not a mock: it counts trigrams, scores held-out text,
and writes a JSON model you can ship or inspect. It trains in seconds on a
laptop because it never touches a GPU.

What it is for in iTantra: a text-only language model that can rescore or
repair speech-to-text output ("the school is flooded" vs "the skull is
flooded"). It is NOT the speech model. The ASR model is sherpa-onnx and is
not trained by this script.

Usage:
    python3 training/train_text_lm.py corpus.txt
    python3 training/train_text_lm.py corpus.txt --model models/text-lm.json
    python3 training/train_text_lm.py corpus.txt --next "the school"

Any UTF-8 text works: books, subtitles, chat logs, your own sentences. One
line is one sentence. Blank lines are ignored.

Output: a JSON file with counts, plus held-out perplexity and a sample
sentence in the terminal.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

TRIM = ".,;:!?\"'()[]{}<>|/\\…।॥“”‘’"
BOS = "<s>"
EOS = "</s>"
UNK = "<unk>"


def read_lines(paths: list[Path]) -> list[str]:
    lines: list[str] = []
    for path in paths:
        with path.open(encoding="utf-8", errors="replace") as handle:
            lines.extend(handle.read().splitlines())
    return lines


def words_in(line: str) -> list[str]:
    """Split on spaces, trim edge punctuation. Keeps Indic words whole.

    A regex like \\w+ splits Devanagari at every vowel sign, because those are
    combining marks and not letters. Whitespace is the reliable boundary.
    """
    flat = line.replace("\u2019", "'").replace("\u2018", "'")
    words = []
    for raw in flat.split():
        word = raw.strip(TRIM).lower()
        if word:
            words.append(word)
    return words


def tokenize(lines: list[str]) -> list[list[str]]:
    sentences: list[list[str]] = []
    for line in lines:
        words = words_in(line)
        if words:
            sentences.append([BOS, *words, EOS])
    return sentences


def count_ngrams(sentences: list[list[str]]):
    uni: Counter[str] = Counter()
    bi: Counter[tuple[str, str]] = Counter()
    tri: Counter[tuple[str, str, str]] = Counter()
    for sentence in sentences:
        uni.update(sentence)
        bi.update(zip(sentence, sentence[1:]))
        tri.update(zip(sentence, sentence[1:], sentence[2:]))
    return uni, bi, tri


def remap_unknown(sentences: list[list[str]], keep: set[str]) -> list[list[str]]:
    """Words outside the kept vocabulary become <unk>, so held-out text can be scored."""
    return [[w if w in keep else UNK for w in sentence] for sentence in sentences]


class Model:
    """Absolute-discounting trigram model (Kneser-Ney style backoff)."""

    DISCOUNT = 0.75

    def __init__(self, uni, bi, tri):
        self.uni, self.bi, self.tri = uni, bi, tri
        self.total = sum(uni.values())
        self.vocab = len(uni)
        self.next2: dict[tuple[str, str], Counter[str]] = defaultdict(Counter)
        self.next1: dict[str, Counter[str]] = defaultdict(Counter)
        for (a, b, w), c in tri.items():
            self.next2[(a, b)][w] += c
        for (b, w), c in bi.items():
            self.next1[b][w] += c

    def prob(self, w: str, a: str, b: str) -> float:
        d = self.DISCOUNT
        seen = self.bi[(a, b)]
        if not seen:
            return self.prob2(w, b)
        lower = self.prob2(w, b)
        return max(self.tri[(a, b, w)] - d, 0.0) / seen + d * len(self.next2[(a, b)]) / seen * lower

    def prob2(self, w: str, b: str) -> float:
        d = self.DISCOUNT
        seen = self.uni[b]
        if not seen:
            return self.prob1(w)
        lower = self.prob1(w)
        return max(self.bi[(b, w)] - d, 0.0) / seen + d * len(self.next1[b]) / seen * lower

    def prob1(self, w: str) -> float:
        return (self.uni[w] + 0.1) / (self.total + 0.1 * self.vocab)

    def perplexity(self, sentences: list[list[str]]) -> float:
        total = 0.0
        count = 0
        for sentence in sentences:
            for i in range(2, len(sentence)):
                total += -math.log2(self.prob(sentence[i], sentence[i - 2], sentence[i - 1]))
                count += 1
        return 2 ** (total / count) if count else float("nan")

    def _weighted(self, rng: random.Random, options, temperature: float) -> str | None:
        if not options:
            return None
        items = [(w, c) for w, c in options.items() if w != UNK]
        if not items:
            return None
        weights = [c ** (1.0 / temperature) for _, c in items]
        return rng.choices([w for w, _ in items], weights=weights, k=1)[0]

    def sample(self, rng: random.Random, temperature: float = 0.8, limit: int = 24) -> str:
        first = self._weighted(rng, self.next1.get(BOS), temperature)
        if not first:
            return ""
        words = [first]
        a, b = BOS, first
        for _ in range(limit - 1):
            word = self._weighted(rng, self.next2.get((a, b)) or self.next1.get(b), temperature)
            if not word or word == EOS:
                break
            words.append(word)
            a, b = b, word
        return " ".join(words)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train a word n-gram language model from .txt files.")
    parser.add_argument("corpus", nargs="+", type=Path, help="One or more UTF-8 text files.")
    parser.add_argument("--model", type=Path, default=Path("models/text-lm.json"), help="Where to write the model.")
    parser.add_argument("--min-count", type=int, default=1, help="Drop trigrams seen fewer times. 1 keeps all.")
    parser.add_argument("--next", dest="seed", default=None, help="Print the top next words for this phrase.")
    parser.add_argument("--sample", type=int, default=3, help="How many sample sentences to print.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    started = time.perf_counter()

    lines = read_lines(args.corpus)
    sentences = tokenize(lines)
    if len(sentences) < 20:
        print("Corpus too small: need at least 20 non-empty lines.", file=sys.stderr)
        return 1

    split = max(1, int(len(sentences) * 0.9))
    train, held_out = sentences[:split], sentences[split:]

    raw_uni, _, _ = count_ngrams(train)
    keep = {w for w, c in raw_uni.items() if c >= 2} | {BOS, EOS}
    train = remap_unknown(train, keep)
    held_out = remap_unknown(held_out, keep)
    oov = sum(1 for sentence in held_out for w in sentence if w == UNK)
    oov_share = 100 * oov / max(1, sum(len(s) for s in held_out))

    uni, bi, tri = count_ngrams(train)
    if args.min_count > 1:
        # Bigrams stay complete: they are the backoff floor and cheap to keep.
        tri = Counter({g: c for g, c in tri.items() if c >= args.min_count})

    model = Model(uni, bi, tri)
    elapsed = time.perf_counter() - started

    payload = {
        "format": "itantra-text-ngram-v1",
        "order": 3,
        "discount": Model.DISCOUNT,
        "min_count": args.min_count,
        "tokens": model.total,
        "vocab": sorted(uni),
        "unigram": dict(uni),
        "bigram": {" ".join(g): c for g, c in bi.items()},
        "trigram": {" ".join(g): c for g, c in tri.items()},
    }
    args.model.parent.mkdir(parents=True, exist_ok=True)
    with args.model.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
    size = args.model.stat().st_size

    ppl = model.perplexity(held_out)
    corpus_bytes = sum(p.stat().st_size for p in args.corpus)

    print(f"corpus        {corpus_bytes / 1e6:.2f} MB in {len(args.corpus)} file(s)")
    print(f"lines         {len(lines):,}  ({len(sentences):,} usable)")
    print(f"split         {len(train):,} train / {len(held_out):,} held out")
    print(f"oov           {oov_share:.2f}% of held-out words unseen in training (mapped to <unk>)")
    print(f"tokens        {model.total:,}")
    print(f"vocab         {len(uni):,}")
    print(f"bigrams       {len(bi):,}")
    print(f"trigrams      {len(tri):,}")
    print(f"train time    {elapsed:.2f} s")
    print(f"model         {size / 1e6:.2f} MB  ->  {args.model}")
    print(f"perplexity    {ppl:.1f} on held-out text (lower is better)")

    rng = random.Random(7)
    for _ in range(max(0, args.sample)):
        print(f"sample        {model.sample(rng)}")

    if args.seed:
        words = words_in(args.seed)
        a = words[-2] if len(words) >= 2 else BOS
        b = words[-1] if words else BOS
        options = model.next2.get((a, b)) or model.next1.get(b) or {}
        top = sorted(options.items(), key=lambda kv: kv[1], reverse=True)[:5]
        rendered = ", ".join(f"{w} ({c})" for w, c in top) or "no continuation seen"
        print(f"next after    {args.seed!r}: {rendered}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
