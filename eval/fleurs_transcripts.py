"""
Fetch FLEURS reference transcripts for the ten languages iTantra ships, and cache them.

The vocabulary prune needs a text corpus to decide which tokens to keep. The earlier run used
FLEURS but built the corpus ad hoc and the result was not reproducible (the keep_ids.json lived
in /tmp and is gone). This script pins the source and the exact set of transcripts: the HF
datasets-server rows API, text only, no audio, no datasets/torch dependency.

    python eval/fleurs_transcripts.py                        # fills eval/corpus_transcripts.json
    python eval/fleurs_transcripts.py --allow-parquet-download

Cache shape: {"hi_in": ["...", ...], "en_us": [...], ...}. Re-running tops the cache up.

For configs whose validation parquet is too large for the rows API (mr_in, ml_in at 359/387 MB
against a 300 MB scan limit) the fallback downloads the whole parquet to a temp dir, keeps only
the `transcription` column, and deletes the file. That is a ~750 MB one-time download and needs
pyarrow, which is why it is opt-in.

FLEURS is CC-BY-4.0 (google/fleurs); this is a derived text sample for vocab coverage.
"""

import argparse
import json
import os
import shutil
import sys
import tempfile
import time
import urllib.parse
import urllib.request

LANGS = ["hi_in", "mr_in", "bn_in", "ta_in", "te_in", "ml_in", "gu_in", "kn_in", "or_in", "en_us"]
# The rows API refuses to scan a parquet group larger than 300 MB, and train files embed audio
# well past that. validation/test are the tiers that fit; take validation first, then top up
# from test where its file is small enough.
SPLITS = ["validation", "test"]
PER_LANG = 800
PAGE = 100
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "corpus_transcripts.json")


def fetch_rows(cfg, split, offset):
    q = urllib.parse.urlencode({
        "dataset": "google/fleurs", "config": cfg, "split": split,
        "offset": offset, "length": PAGE,
    })
    url = f"https://datasets-server.huggingface.co/rows?{q}"
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def parquet_url(cfg, split):
    listing = json.load(urllib.request.urlopen(
        "https://datasets-server.huggingface.co/parquet?dataset=google%2Ffleurs", timeout=60))
    return next(f["url"] for f in listing["parquet_files"]
                if f["config"] == cfg and f["split"] == split)


def fetch_via_parquet(cfg, split, want):
    url = parquet_url(cfg, split)
    with tempfile.TemporaryDirectory(prefix="fleurs-") as td:
        path = os.path.join(td, "part.parquet")
        print(f"    downloading {url} (temp, deleted after)", file=sys.stderr)
        with urllib.request.urlopen(url, timeout=600) as r, open(path, "wb") as out:
            shutil.copyfileobj(r, out, length=1 << 20)
        import pyarrow.parquet as pq                       # opt-in path only
        table = pq.read_table(path, columns=["transcription"])
        return table.column("transcription").to_pylist()[:want]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--allow-parquet-download", action="store_true",
                    help="fall back to downloading mr_in/ml_in validation parquets (~750 MB)")
    args = ap.parse_args()

    cache = json.load(open(CACHE)) if os.path.exists(CACHE) else {}
    for cfg in LANGS:
        texts = cache.setdefault(cfg, [])
        for split in SPLITS:
            while len(texts) < PER_LANG:
                try:
                    page = fetch_rows(cfg, split, len(texts))
                except Exception as e:
                    print(f"{cfg}/{split}: {e}", file=sys.stderr)
                    break
                rows = page.get("rows", [])
                if not rows:
                    break
                texts.extend(row["row"]["transcription"] for row in rows)
                time.sleep(0.3)
            if len(texts) >= PER_LANG:
                break
        if not texts and args.allow_parquet_download:
            for split in SPLITS:
                try:
                    texts.extend(fetch_via_parquet(cfg, split, PER_LANG))
                    break
                except Exception as e:
                    print(f"{cfg}/{split} parquet: {e}", file=sys.stderr)
        json.dump(cache, open(CACHE, "w"), ensure_ascii=False, indent=0)
        print(f"{cfg:>7}  {len(texts):>5} transcripts")
    total = sum(len(v) for v in cache.values())
    print(f"\nwrote {CACHE} -- {total} transcripts across {len(cache)} languages")


if __name__ == "__main__":
    main()
