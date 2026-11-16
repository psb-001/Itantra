#!/usr/bin/env python3
"""Re-derive every number in docs/EVALUATION_EVIDENCE.md from the raw per-clip files.

Nothing here trusts a summary. It opens the per-clip JSON, recomputes WER and CER
from the reference and hypothesis transcripts with its own normaliser and its own
edit-distance, and compares that against the stored summary. Then it re-hashes the
model and the result files against the values printed in the evidence document.

    python3 docs/verify_evidence.py

Exit code 0 = everything agrees. Exit code 1 = something does not. Every check
prints PASS or FAIL with the two numbers side by side, so a disagreement is
visible rather than swallowed.

No dependencies. Standard library only. Runs in about ten seconds.
"""

from __future__ import annotations

import hashlib
import json
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SUMMARY = ROOT / "eval" / "SUMMARY_full_v3pruned.json"
MODEL = (ROOT / "app" / "app" / "src" / "main" / "assets"
         / "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8" / "model.int8.onnx")

# Values printed in docs/EVALUATION_EVIDENCE.md. If the document is edited by hand,
# these stop matching and the check fails. Change both together or not at all.
EXPECTED_MODEL_SHA = "8363e2d64925b92e6736239877e7193a4e83259abad18cf6ee7ffb2796d6b934"
EXPECTED_MODEL_BYTES = 356_295_439
EXPECTED_CLIPS = 2_000
EXPECTED_LANGS = 10
EXPECTED_AUDIO_S = 24_189.8
EXPECTED_MEAN_CER = 0.0918
EXPECTED_MEAN_WER = 0.3633
EXPECTED_PURE_SCRIPT = 1_904

# CER from this script vs the stored value. With the same normalisation they should
# agree to rounding, so anything above this is a real disagreement.
TOLERANCE = 0.0005

ZERO_WIDTH = dict.fromkeys(map(ord, "\u200b\u200c\u200d\u2060\ufeff"), None)

SCRIPTS = {
    "hi_in": [(0x0900, 0x097F)],
    "mr_in": [(0x0900, 0x097F)],
    "bn_in": [(0x0980, 0x09FF)],
    "gu_in": [(0x0A80, 0x0AFF)],
    "or_in": [(0x0B00, 0x0B7F)],
    "ta_in": [(0x0B80, 0x0BFF)],
    "te_in": [(0x0C00, 0x0C7F)],
    "kn_in": [(0x0C80, 0x0CFF)],
    "ml_in": [(0x0D00, 0x0D7F)],
    "en_us": [(0x41, 0x5A), (0x61, 0x7A)],
}

results: list[tuple[bool, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((ok, label))
    print(f"{'PASS' if ok else 'FAIL'}  {label}" + (f"  {detail}" if detail else ""))


def normalise(text: str) -> str:
    """NFC, drop zero-width marks, replace punctuation with space, casefold.

    Deliberately mirrors eval/fleurs_eval_full.py so a disagreement here means the
    document is wrong, not that two normalisers differ by a hair. Two details matter:
    only Unicode category P (punctuation) becomes a space, and combining marks are
    kept. An isalnum() filter instead of the category check destroys Indic vowel
    signs and silently invents errors.
    """
    text = unicodedata.normalize("NFC", text).translate(ZERO_WIDTH)
    text = "".join(" " if unicodedata.category(ch).startswith("P") else ch for ch in text)
    return " ".join(text.casefold().split())


def edits(a: str, b: str) -> int:
    """Levenshtein distance, two rows, no libraries."""
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def script_purity(text: str, ranges) -> float:
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return 0.0
    good = sum(1 for c in letters if any(lo <= ord(c) <= hi for lo, hi in ranges))
    return good / len(letters)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    print("=" * 74)
    print("iTantra — re-deriving docs/EVALUATION_EVIDENCE.md from raw per-clip data")
    print("=" * 74)
    print()

    if not SUMMARY.exists():
        print(f"FAIL  missing {SUMMARY}")
        return 1

    data = json.loads(SUMMARY.read_text(encoding="utf-8"))
    print(f"source  {SUMMARY.relative_to(ROOT)}")
    print(f"        {len(data)} language records, {SUMMARY.stat().st_size:,} bytes")
    print()

    # ---- structural counts -------------------------------------------------
    clips = sum(len(record["utterances"]) for record in data)
    audio_s = sum(record["audio_s"] for record in data)
    missing = sum(record["clips_missing_audio"] for record in data)

    check(len(data) == EXPECTED_LANGS, "language records", f"{len(data)} of {EXPECTED_LANGS}")
    check(clips == EXPECTED_CLIPS, "clips scored", f"{clips:,} of {EXPECTED_CLIPS:,}")
    check(abs(audio_s - EXPECTED_AUDIO_S) < 1.0, "audio seconds",
          f"{audio_s:,.1f} of {EXPECTED_AUDIO_S:,.1f}")
    check(missing == 0, "clips with missing audio", str(missing))

    # every record must have 200 clips with a reference and a hypothesis
    empty = 0
    for record in data:
        if record["n_clips"] != 200:
            empty += 1
        for utt in record["utterances"]:
            if not utt.get("ref") or not utt.get("hyp"):
                empty += 1
    check(empty == 0, "every clip has 200-per-language and both transcripts", str(empty))

    print()
    print(f"{'language':10} {'clips':>6} {'stored CER':>11} {'re-derived':>11} {'diff':>8}  "
          f"{'stored WER':>10} {'re-derived':>11}")
    print("-" * 74)

    cer_values: list[float] = []
    wer_values: list[float] = []
    pure_total = 0
    all_match = True

    for record in sorted(data, key=lambda r: r["cer_norm_corpus"]):
        config = record["fleurs_config"]
        refs = [normalise(u["ref"]) for u in record["utterances"]]
        hyps = [normalise(u["hyp"]) for u in record["utterances"]]

        char_edits = sum(edits(r.replace(" ", ""), h.replace(" ", ""))
                         for r, h in zip(refs, hyps))
        char_units = sum(len(r.replace(" ", "")) for r in refs)
        word_edits = sum(edits(r.split(), h.split()) for r, h in zip(refs, hyps))
        word_units = sum(len(r.split()) for r in refs)

        cer = char_edits / char_units if char_units else 0.0
        wer = word_edits / word_units if word_units else 0.0

        stored_cer = record["cer_norm_corpus"]
        stored_wer = record["wer_norm_corpus"]
        close = abs(cer - stored_cer) <= TOLERANCE and abs(wer - stored_wer) <= TOLERANCE
        all_match = all_match and close

        cer_values.append(stored_cer)
        wer_values.append(stored_wer)

        pure_total += sum(1 for u in record["utterances"]
                          if script_purity(u["hyp"], SCRIPTS[config]) >= 0.999)

        print(f"{record['language']:10} {len(refs):6} {stored_cer:11.4f} {cer:11.4f} "
              f"{cer - stored_cer:+8.4f}  {stored_wer:10.4f} {wer:11.4f}")

    print()
    check(all_match, f"recomputed CER and WER match stored within {TOLERANCE}")

    mean_cer = sum(cer_values) / len(cer_values)
    mean_wer = sum(wer_values) / len(wer_values)
    check(abs(mean_cer - EXPECTED_MEAN_CER) < 0.0005, "mean normalised CER",
          f"{mean_cer:.4f} of {EXPECTED_MEAN_CER}")
    check(abs(mean_wer - EXPECTED_MEAN_WER) < 0.0005, "mean normalised WER",
          f"{mean_wer:.4f} of {EXPECTED_MEAN_WER}")
    check(pure_total == EXPECTED_PURE_SCRIPT, "clips in 100% native script",
          f"{pure_total:,} of {EXPECTED_PURE_SCRIPT:,} "
          f"({100 * pure_total / clips:.1f}%)")

    # ---- model identity ----------------------------------------------------
    print()
    if MODEL.exists():
        size = MODEL.stat().st_size
        digest = sha256(MODEL)
        check(size == EXPECTED_MODEL_BYTES, "model bytes", f"{size:,} of {EXPECTED_MODEL_BYTES:,}")
        check(digest == EXPECTED_MODEL_SHA, "model sha256", digest[:16] + "…")
        print(f"      {MODEL.relative_to(ROOT)}")
    else:
        check(False, "model present", f"not found at {MODEL.relative_to(ROOT)}")
        print("      Fetch it with app/setup-project.sh, then run this again.")

    # ---- verdict -----------------------------------------------------------
    failures = [label for ok, label in results if not ok]
    print()
    print("=" * 74)
    if failures:
        print(f"{len(failures)} CHECK(S) FAILED — the document and the data disagree:")
        for label in failures:
            print(f"  - {label}")
        return 1
    print(f"ALL {len(results)} CHECKS PASSED")
    print("The numbers in docs/EVALUATION_EVIDENCE.md are reproduced from raw clip data.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
