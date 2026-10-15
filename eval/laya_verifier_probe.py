"""
Can Laya answer a NEW question well enough to act as a verifier?

Structurally yes: the head outputs one score per option marker (verified shape:
logits ['batch', 'markers']), so any multiple-choice question is legal. But legal is not the
same as trustworthy. The trained "kind" question is the one we measured; a question the model
was never trained on may come back as pure noise, or wobble inside the same int8 band that
flips at 0.36-0.80.

So before we promise anyone "the model will check the transcript", measure it:

  1. Garbled vs clean text -- 20 known-good sentences (12 bench + 8 corpus) and 40 corrupted
     copies of them (truncation, same-alphabet substitution). Can it separate them at all?
  2. Three phrasings of the same ask. A verifier whose answer flips on wording is not one.
  3. The safety metric that matters for a gate: wrong-but-confident. Coverage (right and above
     the 0.85 floor) is what the feature would act on; confident-and-wrong is what would make
     it dangerous, and it is the number to look at first.

Usage:
    python eval/laya_verifier_probe.py --model /tmp/laya_int8
"""

import argparse
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

CLEAR = "yes, it reads as a clear message"
BROKEN = "no, it is garbled or incomplete"
OPTIONS = [CLEAR, BROKEN]

PHRASINGS = [
    "Is this message clear and readable?",
    "Does this text read as a normal message?",
    "Is this text written naturally?",
]

FLOOR = 0.85
SEED = 20261015


def corrupt(text, kind, rng):
    chars = list(text)
    if kind == "trunc":
        return text[: max(1, int(len(text) * 0.4))]
    if kind == "subst":
        pool = sorted({c for c in text if c.isalpha()})
        if not pool:
            return text
        for i in rng.sample(range(len(chars)), max(1, int(len(chars) * 0.3))):
            if chars[i].isalpha():
                chars[i] = rng.choice(pool)
        return "".join(chars)
    raise ValueError(kind)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True)
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()

    from laya_onnx import CASES, LayaOnnx, QUESTION, URGENCY
    m = LayaOnnx(args.model)

    rng = random.Random(SEED)
    with open(os.path.join(args.repo, "eval", "corpus_transcripts.json")) as f:
        corpus = json.load(f)
    rows = [r["text"] if isinstance(r, dict) else r for t in corpus.values() for r in t] \
        if isinstance(corpus, dict) else corpus
    clean = [t for _, t, _ in CASES] + rng.sample(rows, 8)

    texts = [(t, "clean") for t in clean]
    for t in clean:
        texts.append((corrupt(t, "trunc", rng), "trunc"))
        texts.append((corrupt(t, "subst", rng), "subst"))
    print(f"texts: {len(clean)} clean + {len(texts) - len(clean)} garbled\n")

    # One sanity line first: the trained question still answers on this model (if this is
    # uniform noise, nothing below means anything).
    opt, conf, _, _, n = m.decide(clean[0], QUESTION, URGENCY)
    print(f"trained question on a clean bench sentence: '{opt}' {conf:.2f} ({n} tokens)\n")

    per_phrasing = []
    answers = {}
    for q in PHRASINGS:
        right_clear = right_garbled = 0
        conf_right, conf_wrong = [], []
        confident_right = confident_wrong = 0
        wrong_confident_examples = []
        for text, kind in texts:
            chosen, confidence, _, _, _ = m.decide(text, q, OPTIONS)
            got = "clean" if chosen == CLEAR else "garbled"
            ok = (got == "clean") == (kind == "clean")
            if kind == "clean":
                right_clear += got == "clean"
            else:
                right_garbled += got == "garbled"
            (conf_right if ok else conf_wrong).append(confidence)
            if confidence >= FLOOR:
                if ok:
                    confident_right += 1
                else:
                    confident_wrong += 1
                    if len(wrong_confident_examples) < 3:
                        wrong_confident_examples.append((text, kind, chosen, confidence))
            answers.setdefault(text, []).append(got)

        n_clean, n_garbled = len(clean), len(texts) - len(clean)
        acc = (right_clear + right_garbled) / len(texts)
        per_phrasing.append((q, acc, right_clear, n_clean, right_garbled, n_garbled,
                             confident_right, confident_wrong))
        print(f"Q: {q}")
        print(f"  clean -> clear   {right_clear}/{n_clean}")
        print(f"  garbled -> broken {right_garbled}/{n_garbled}")
        print(f"  accuracy {acc:.0%}   mean conf right {sum(conf_right)/max(1,len(conf_right)):.2f}"
              f"   wrong {sum(conf_wrong)/max(1,len(conf_wrong)):.2f}")
        print(f"  at/above the {FLOOR:.2f} floor: {confident_right} right, {confident_wrong} WRONG")
        for text, kind, chosen, confidence in wrong_confident_examples:
            print(f"    wrong+confident: [{kind}] {text[:50]!r} -> {chosen} {confidence:.2f}")
        print()

    agree = sum(1 for v in answers.values() if len(set(v)) == 1)
    print(f"agreement across the three phrasings: {agree}/{len(answers)} texts")

    # Can the trained question still do its own job on garbled text? (It is what the app ships.)
    wrong_by_kind = {}
    for text, kind in texts:
        chosen, confidence, _, _, _ = m.decide(text, QUESTION, URGENCY)
        wrong_by_kind.setdefault(kind, []).append((chosen, confidence))
    print("\nthe trained 'kind' question, by text kind (top answer / mean conf):")
    for kind, rows_ in wrong_by_kind.items():
        counts = {}
        for chosen, confidence in rows_:
            counts.setdefault(chosen, []).append(confidence)
        top = max(counts, key=lambda c: len(counts[c]))
        mean = sum(counts[top]) / len(counts[top])
        print(f"  {kind:6s} n={len(rows_):3d}  top '{top}' {len(counts[top])}/{len(rows_)}"
              f"  mean conf {mean:.2f}")


if __name__ == "__main__":
    main()
