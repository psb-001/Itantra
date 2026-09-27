#!/usr/bin/env python3
"""
Trim espeak-ng-data down to the dictionaries the ten required languages actually need.

WHY
---
espeak-ng-data ships 113 language dictionaries totalling 18 MB. Six of them are ours; the
other 107 are dead weight, and the largest are languages nobody in this project will ever
synthesise:

    ru_dict   8.1 MB      cmn_dict (Mandarin)  1.5 MB
    lb_dict   0.7 MB      yue_dict (Cantonese) 0.5 MB
    ar_dict   0.5 MB      ia_dict              0.3 MB   ... 101 more

We keep, besides the dictionaries:

    phondata  phonindex  phontab  phondata-manifest  intonations  lang  voices

Those seven are NOT per-language. They are the shared phoneme tables and voice registry that
espeak-ng loads for ANY language, and the phonemiser in libsherpa-onnx-jni.so aborts the
process if `phontab` is missing. They are 654 KB and are not optional.

Ten dictionaries are kept, not six: hi, mr, bn, ml, te, en are needed by the voices that ship
today, and gu, kn, ta, or are kept so that adding a voice for one of the four text-only
languages later does not require re-vendoring the data. All nine Indic dicts together are
0.88 MB.

Result: 18 MB -> ~1.6 MB.

VERIFY AFTER RUNNING
--------------------
Trimming must not break phonemisation, so this script does not trust the file list alone:

    .venv/bin/python eval/tts_smoke.py

synthesises one sentence in every bundled voice and checks the audio is non-silent. If a
missing dictionary were load-bearing, that fails.

Usage:
    .venv/bin/python eval/trim_espeak.py --dir app/app/src/main/assets/tts/espeak-ng-data
    .venv/bin/python eval/trim_espeak.py --dir <path> --check     # report only, change nothing
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Shared, language-independent FILES. Deleting any of these breaks the phonemiser natively.
SHARED_FILES = [
    "phondata",
    "phonindex",
    "phontab",
    "phondata-manifest",
    "intonations",
]

# Shared DIRECTORIES, kept whole.
#
# `lang/` and `voices/` are NOT per-language and are NOT tiny either - they are 532 KB and
# 416 KB. `lang/` is keyed by ISO 639-3 language FAMILY, not by language: hi, mr and bn all live
# under `inc` (Indo-Aryan), ml and te under `dra` (Dravidian), en under `gmw` (Germanic).
# Working out the right family for each of the ten languages is guesswork I could get subtly
# wrong, and a wrong guess means a voice silently phonemises with the wrong rules.
#
# Together they are under 1 MB against an 18 MB directory, so keeping them whole costs almost
# nothing and removes an entire class of silent breakage. The 16 MB of win is all in *_dict.
SHARED_DIRS = ["lang", "voices"]

# The ten languages SIH26173 requires, in ISO-639-3-ish short codes as espeak names them.
# Six have a bundled voice; four are text-only today but are kept so that adding a voice for
# one of them later does not require re-vendoring the data. All nine Indic dicts together are
# 0.88 MB.
REQUIRED_LANGS = ["hi", "mr", "bn", "ml", "te", "en", "gu", "kn", "ta", "or"]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--dir", required=True, type=Path, help="espeak-ng-data directory")
    ap.add_argument("--check", action="store_true", help="report only; do not delete")
    args = ap.parse_args()

    d = args.dir
    if not d.is_dir():
        sys.exit(f"not a directory: {d}")

    # Refuse to run against something that is not espeak-ng-data, rather than deleting 107
    # files out of an arbitrary directory.
    marker = d / "phontab"
    dicts = sorted(d.glob("*_dict"))
    if not marker.is_file() or not dicts:
        sys.exit(
            f"{d} does not look like espeak-ng-data "
            f"(phontab present: {marker.is_file()}, *_dict files: {len(dicts)}). Refusing."
        )

    before_total = sum(f.stat().st_size for f in d.rglob("*") if f.is_file())
    keep = {f"{lang}_dict" for lang in REQUIRED_LANGS}
    drop = [f for f in dicts if f.name not in keep]

    print(f"espeak-ng-data at {d}")
    print(f"  dictionaries found : {len(dicts)}")
    print(f"  keeping            : {len(keep & {f.name for f in dicts})} "
          f"({', '.join(sorted(lang for lang in REQUIRED_LANGS))})")
    print(f"  deleting           : {len(drop)}")
    print(f"  shared files kept  : {', '.join(SHARED_FILES)}")
    print(f"  shared dirs kept   : {', '.join(SHARED_DIRS)} (whole - keyed by language family)")

    missing_required = sorted(keep - {f.name for f in dicts})
    if missing_required:
        sys.exit(f"required dictionaries absent from the source: {missing_required}")

    missing_shared = [f for f in SHARED_FILES if not (d / f).is_file()]
    if missing_shared:
        sys.exit(f"shared espeak files absent: {missing_shared}")
    missing_dirs = [x for x in SHARED_DIRS if not (d / x).is_dir()]
    if missing_dirs:
        sys.exit(f"shared espeak dirs absent: {missing_dirs}")

    drop_bytes = sum(f.stat().st_size for f in drop)
    print(f"\n  would free         : {drop_bytes / 1048576:.1f} MB")

    if args.check:
        print("\n--check given, nothing deleted")
        return 0

    biggest = sorted(drop, key=lambda f: f.stat().st_size, reverse=True)[:5]
    for f in drop:
        f.unlink()
    print(f"  deleted {len(drop)} dictionaries "
          f"(largest were {', '.join(f.name for f in biggest)})")

    after_total = sum(f.stat().st_size for f in d.rglob("*") if f.is_file())
    print(f"\n  {before_total / 1048576:.1f} MB -> {after_total / 1048576:.1f} MB "
          f"({(before_total - after_total) / 1048576:.1f} MB saved)")

    remaining = sorted(f.name for f in d.glob("*_dict"))
    print(f"  dictionaries remaining: {', '.join(remaining)}")
    still_there = sorted(x.name for x in d.iterdir() if x.is_dir())
    if still_there != sorted(SHARED_DIRS):
        print(f"  note: unexpected dirs still present: {still_there}")
    print("\nNow run: .venv/bin/python eval/tts_smoke.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
