#!/usr/bin/env python3
"""
Fetch MIT Piper TTS voices and generate the phoneme table sherpa-onnx needs.

WHY A SCRIPT AND NOT A wget LINE
---------------------------------
A Piper VITS voice needs three files in `assets/tts/`:

    <voice>.onnx        the acoustic model
    <voice>.onnx.json   the config
    <voice>.tokens      the espeak phoneme -> id table

Piper's HuggingFace repo ships only the first two. The `.tokens` file is what sherpa-onnx
passes as `OfflineTtsVitsModelConfig.tokens`, and there is no copy on HuggingFace for most
voices. The obvious workaround — grab a prebuilt tarball from the sherpa-onnx releases page —
does not work for these languages: only `vits-piper-ml_IN-arjun-medium` is packaged there, and
mr_IN / bn_BD / te_IN all return 404.

It does not need to. The phoneme table is already inside `<voice>.onnx.json`, under
`phoneme_id_map`. This script regenerates `.tokens` from it. Verified byte-equivalent to the
two voices that were bundled by hand:

    hi_IN-pratham-medium   157 symbols   generated == bundled   True
    en_US-lessac-medium    154 symbols   generated == bundled   True

So the table is derived, not guessed, and it is checked against known-good files above.

LICENCES — read this before shipping
-----------------------------------
Piper's *code and weights wrapper* is MIT. The *training data* behind each voice is not
uniformly permissive, and the voice inherits those terms in most readings:

    hi_IN-pratham-medium   CC-BY-NC-SA 4.0   (AI4Bharat indicnlp_corpus)
    en_US-lessac-medium    Blizzard 2013 custom licence
    ml_IN-arjun-medium     unclear (Kaggle corpus)
    bn_BD-google-medium    CC-BY-SA 4.0 + CMU Indic
    mr_IN-google-medium    CC-BY-SA 4.0       (OpenSLR 64)
    te_IN-venkatesh-medium CC-BY-4.0          (ai4bharat indicvoices_r)

`hi_IN-pratham-medium` is NON-COMMERCIAL and SHARE-ALIKE. Any submission that claims an
"MIT / Apache-2.0 / GPL only, no CC-BY-NC anywhere" stack is already inaccurate, because that
voice ships in the app today. See docs/TTS_LICENCE_AUDIT.md.

USAGE
-----
    .venv/bin/python eval/fetch_tts_voices.py --list
    .venv/bin/python eval/fetch_tts_voices.py --dest app/app/src/main/assets/tts
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

try:
    import onnx
except ImportError:  # pragma: no cover
    sys.exit("This script needs `onnx` to patch model metadata. Run it with the project venv.")

HF = "https://huggingface.co/rhasspy/piper-voices/resolve/main"

# (voice key, path inside the Piper repo OR a full URL, dataset licence, what it is for)
#
# NOTE ON SOURCES. The first four are from rhasspy/piper-voices and resolve as
# "<path>/<key>.onnx". The Tamil voice is NOT upstream — rhasspy/piper-voices has no ta/kn/gu/or
# at all (verified: 404 on all four, while control languages ne and ur return 200). It comes from
# a community fork, so it is given as a full HuggingFace URL. Its graph signature is identical to
# our working hi_IN voice (input/input_lengths/scales -> output), which is why the same token
# derivation and metadata patch applies unchanged.
VOICES: list[tuple[str, str, str, str]] = [
    ("mr_IN-google-medium",     f"{HF}/mr/mr_IN/google/medium",   "CC-BY-SA 4.0 (OpenSLR 64)",  "Marathi"),
    ("bn_BD-google-medium",     f"{HF}/bn/bn_BD/google/medium",   "CC-BY-SA 4.0 + CMU Indic",   "Bengali"),
    ("ml_IN-arjun-medium",      f"{HF}/ml/ml_IN/arjun/medium",    "unclear (Kaggle corpus)",    "Malayalam"),
    ("te_IN-venkatesh-medium",  f"{HF}/te/te_IN/venkatesh/medium", "CC-BY-4.0 (indicvoices_r)", "Telugu"),
    ("ta_IN-rasa_female-medium",
     "https://huggingface.co/tinisoft/piper-ta_IN-rasa_female-medium/resolve/main",
     "CC-BY-4.0", "Tamil"),
]


def download(url: str, dest: Path, label: str) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    try:
        with urllib.request.urlopen(url, timeout=180) as r, open(tmp, "wb") as f:
            total = 0
            while chunk := r.read(1 << 20):
                f.write(chunk)
                total += len(chunk)
        tmp.replace(dest)
        print(f"  {label:<34} {total/1048576:7.1f} MB")
        return True
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
        print(f"  {label:<34} FAILED: {e}", file=sys.stderr)
        tmp.unlink(missing_ok=True)
        return False


def tokens_from_config(cfg_path: Path, out_path: Path) -> int:
    """
    Regenerate the phoneme table from the model's own phoneme_id_map.

    MULTI-CODEPOINT SYMBOLS ARE DROPPED, and that is not a workaround — it is what k2-fsa's
    Piper->sherpa converter does. Some Piper phoneme maps contain English diphthongs as
    multi-codepoint IPA sequences:

        mr_IN / bn_BD phoneme_id_map:  aɪ [161]  aʊ [162]  ɔɪ [163]  eɪ [164]  oʊ [165]

    sherpa-onnx's piper-phonemize-lexicon.cc:ReadTokens rejects them:

        Error when reading tokens at Line aɪ 161. size: 2

    and that is a NATIVE abort with no Python traceback, so it kills the process and looks like
    a crash rather than a config problem. Verified against the one Indian voice k2-fsa actually
    packages, vits-piper-ml_IN-arjun-medium: its shipped tokens.txt has 161 symbols, all
    single-codepoint, and matches this function's output byte-for-byte.

    Dropping them is low-risk: they are English diphthongs left in a shared inventory, and the
    Marathi/Bengali espeak voices do not emit them in practice. Splitting each into one line per
    codepoint was also tested and also works, but "drop" reproduces k2-fsa's own output, so it
    is the behaviour we can actually vouch for.
    """
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    pid = cfg.get("phoneme_id_map")
    if not pid:
        sys.exit(f"{cfg_path} has no phoneme_id_map - cannot derive a phoneme table")

    # Values are lists (a symbol can map to several ids); take the first, which is what the
    # hand-bundled files contain. Verified against hi_IN and en_US above.
    flat = {k: (v[0] if isinstance(v, list) else v) for k, v in pid.items()}

    dropped = [k for k in flat if len(k) != 1]
    usable = {k: v for k, v in flat.items() if len(k) == 1}
    if dropped:
        print(f"  {'dropped multi-codepoint':<34} {len(dropped):>7} {''.join(dropped)}")

    # Sort by id so the file is deterministic. sherpa-onnx builds a symbol->id lookup, so line
    # order does not affect decoding, but a stable order makes the file diffable.
    lines = [f"{sym} {i}" for sym, i in sorted(usable.items(), key=lambda kv: kv[1])]
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(usable)


def patch_metadata(onnx_path: Path, json_path: Path) -> None:
    """
    Write the ONNX metadata_props that sherpa-onnx requires, taken from <voice>.onnx.json.

    Raw Piper models on HuggingFace ship with ZERO metadata_props. sherpa-onnx's
    offline-tts-vits-model.cc aborts natively on a missing `sample_rate`:

        'sample_rate' does not exist in the metadata

    That is a process abort with no Python traceback, so it kills the whole run and looks like
    a crash rather than a config problem. The two voices that were bundled by hand (hi_IN,
    en_US) went through k2-fsa's Piper->sherpa converter, which adds these; the raw files do
    not. We replicate that step here rather than hand-converting four 60-73 MB models.

    Properties written, matching the bundled hi_IN model exactly:
        model_type, comment, language, voice, has_espeak, n_speakers, sample_rate
    """
    cfg = json.loads(json_path.read_text(encoding="utf-8"))
    audio = cfg.get("audio") or {}
    sample_rate = audio.get("sample_rate")
    if not sample_rate:
        sys.exit(f"{json_path} has no audio.sample_rate - cannot patch metadata")
    lang = cfg.get("language") or {}
    n_speakers = cfg.get("num_speakers") or 1

    props = {
        "model_type": "vits",
        "comment": "piper",
        "language": lang.get("name_english") or lang.get("code") or "",
        "voice": (lang.get("code") or "").split("_")[0],
        "has_espeak": "1",
        "n_speakers": str(n_speakers),
        "sample_rate": str(sample_rate),
    }

    model = onnx.load(str(onnx_path))
    # Clear first: onnx.save is additive and a re-run would otherwise duplicate every key.
    del model.metadata_props[:]
    for k, val in props.items():
        entry = model.metadata_props.add()
        entry.key = k
        entry.value = val
    onnx.save(model, str(onnx_path))
    print(f"  {'metadata_props patched':<34} {len(props):>7} keys (sr={sample_rate})")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--dest", type=Path, help="assets/tts directory to write into")
    ap.add_argument("--list", action="store_true", help="list voices and licences, download nothing")
    ap.add_argument("--only", nargs="*", help="fetch only these voice keys")
    args = ap.parse_args()

    if args.list:
        print(f"{'voice':<28}{'language':<12}{'dataset licence'}")
        print("-" * 76)
        for key, _p, lic, lang in VOICES:
            print(f"{key:<28}{lang:<12}{lic}")
        print("\nhi_IN-pratham-medium (already shipped) is CC-BY-NC-SA 4.0 — NON-COMMERCIAL.")
        return 0

    if not args.dest:
        ap.error("--dest is required unless --list is given")
    dest: Path = args.dest

    wanted = [v for v in VOICES if not args.only or v[0] in args.only]
    if args.only:
        unknown = set(args.only) - {v[0] for v in VOICES}
        if unknown:
            sys.exit(f"unknown voice(s): {sorted(unknown)}. Known: {[v[0] for v in VOICES]}")

    print(f"fetching {len(wanted)} voice(s) into {dest}\n")
    ok = True
    for key, rel, lic, lang in wanted:
        print(f"{key}  ({lang}, {lic})")
        got_onnx = download(f"{rel}/{key}.onnx", dest / f"{key}.onnx", f"{key}.onnx")
        got_json = download(
            f"{rel}/{key}.onnx.json", dest / f"{key}.onnx.json", f"{key}.onnx.json"
        )
        if got_onnx and got_json:
            n = tokens_from_config(dest / f"{key}.onnx.json", dest / f"{key}.tokens")
            print(f"  {'<voice>.tokens (derived)':<34} {n:>7} symbols")
            patch_metadata(dest / f"{key}.onnx", dest / f"{key}.onnx.json")
        else:
            ok = False
        print()

    if not ok:
        sys.exit("one or more downloads failed - nothing was half-written, re-run to retry")
    print("done. Next: run eval/tts_test.py to confirm each voice actually speaks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
