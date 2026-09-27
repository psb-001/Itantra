"""Full-scale FLEURS evaluation for SIH26173 — real recorded human speech.

Improves on fleurs_eval.py in four ways:
  1. Many more clips per language (default 200, was 6).
  2. Corpus-level WER/CER (total edits / total units) — the standard metric.
     The old script averaged per-utterance ratios, which is not standard.
  3. Reports BOTH raw and normalised scores. The model does CTC greedy decode,
     so it emits no punctuation or capitalisation. Scoring raw against punctuated
     FLEURS references penalises it for a known output-format limitation rather
     than an accuracy failure. Normalised = NFC + casefold + punctuation/zero-width
     stripped. Report both; never only the flattering one.
  4. Median + p95 latency, not just a mean.

Usage:
    python fleurs_eval_full.py [n_per_language] [lang_code ...]
    python fleurs_eval_full.py 200
    python fleurs_eval_full.py 200 hi_in bn_in
"""
import json
import os
import re
import struct
import sys
import time
import unicodedata

import numpy as np
import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))

# Allow comparing model versions without editing this file:
#   OMNI_DIR=omni300m_v2/sherpa-onnx-...-v2-int8-2026-02-05 fleurs_eval_full.py 200
#   OMNI_TAG=v2   -> results land in SUMMARY_full_v2.json / asr_full_<code>_v2.json
_MODEL_DIR = os.environ.get("OMNI_DIR", "omni300m")
TAG = os.environ.get("OMNI_TAG", "").strip()
_SUFFIX = f"_{TAG}" if TAG else ""

MODEL = os.path.join(BASE, _MODEL_DIR, "model.int8.onnx")
TOKENS = os.path.join(BASE, _MODEL_DIR, "tokens.txt")
# The 7.1 GB FLEURS corpus lives at repo-root dataset/fleurs. Keep the historical eval/fleurs
# location working, but allow an override so nobody has to copy or symlink 7 GB to re-run a
# measurement:  FLEURS_DIR=../dataset/fleurs ./.venv/bin/python fleurs_eval_full.py 200
FLEURS = os.environ.get("FLEURS_DIR", os.path.join(BASE, "fleurs"))
if not os.path.isdir(FLEURS):
    fallback = os.path.join(os.path.dirname(BASE), "dataset", "fleurs")
    if os.path.isdir(fallback):
        FLEURS = fallback
    else:
        sys.exit(
            f"FLEURS corpus not found at {FLEURS!r} or {fallback!r}.\n"
            f"Set FLEURS_DIR, or restore it with: eval/fetch_missing_langs.sh"
        )

LANGUAGES = [
    ("Odia", "or_in"),
    ("Hindi", "hi_in"),
    ("Bengali", "bn_in"),
    ("Marathi", "mr_in"),
    ("Gujarati", "gu_in"),
    ("Kannada", "kn_in"),
    ("Tamil", "ta_in"),
    ("Telugu", "te_in"),
    ("Malayalam", "ml_in"),
    ("English", "en_us"),
]

ZERO_WIDTH = dict.fromkeys(map(ord, "\u200b\u200c\u200d\u2060\ufeff"), None)


def normalize(text: str) -> str:
    """NFC + casefold + drop punctuation and zero-width joiners."""
    text = unicodedata.normalize("NFC", text)
    text = text.translate(ZERO_WIDTH)
    text = "".join(
        " " if unicodedata.category(ch).startswith("P") else ch for ch in text
    )
    text = text.casefold()
    return re.sub(r"\s+", " ", text).strip()


def edit_distance(ref, hyp):
    """Levenshtein distance with backtrace counts. O(len(hyp)) memory."""
    r, h = list(ref), list(hyp)
    if not r:
        return len(h), 0, 0, len(h)  # ins
    prev = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        cur = [i] + [0] * len(h)
        for j in range(1, len(h) + 1):
            cost = 0 if r[i - 1] == h[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[len(h)], 0, 0, 0


def wer(ref: str, hyp: str) -> float:
    r, h = ref.split(), hyp.split()
    if not r:
        return 0.0 if not h else 1.0
    return edit_distance(r, h)[0] / len(r)


def cer(ref: str, hyp: str) -> float:
    r, h = list(ref.replace(" ", "")), list(hyp.replace(" ", ""))
    if not r:
        return 0.0 if not h else 1.0
    return edit_distance(r, h)[0] / len(r)


def read_wav(path):
    """FLEURS clips are IEEE float (wFormatTag 3), which Python's wave module rejects."""
    with open(path, "rb") as f:
        assert f.read(4) == b"RIFF", path
        f.seek(12)
        fmt = data = None
        while True:
            hdr = f.read(8)
            if len(hdr) < 8:
                break
            cid, size = struct.unpack("<4sI", hdr)
            payload = f.read(size)
            if cid == b"fmt ":
                fmt = struct.unpack("<HHIIHH", payload[:16])
            elif cid == b"data":
                data = payload
                break
    assert fmt and data is not None, path
    _tag, channels, sr, _brate, _ba, bits = fmt
    if bits == 32:
        a = np.frombuffer(data, dtype=np.float32)
    elif bits == 16:
        a = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    else:
        raise ValueError(f"unsupported bits={bits} in {path}")
    if channels == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return np.ascontiguousarray(a, dtype=np.float32), sr


def locate(code, fn):
    for cand in (os.path.join(FLEURS, f"test-{code}", fn), os.path.join(FLEURS, "test", fn)):
        if os.path.exists(cand):
            return cand
    return None


def load_rows(code, n):
    tsv = os.path.join(FLEURS, f"{code}_test.tsv")
    if not os.path.exists(tsv):
        return None
    rows = []
    with open(tsv, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 4:
                rows.append((parts[1], parts[2]))
    return rows[:n]


def evaluate(rec, lang, code, n):
    rows = load_rows(code, n)
    if rows is None:
        print(f"[{code}] no tsv — skipping", flush=True)
        return None

    clips = [(fn, ref) for fn, ref in rows if locate(code, fn)]
    missing = len(rows) - len(clips)
    if not clips:
        print(f"[{code}] tsv present but 0 clips extracted — skipping", flush=True)
        return None

    utts = []
    for fn, ref in clips:
        samples, sr = read_wav(locate(code, fn))
        t0 = time.perf_counter()
        stream = rec.create_stream()
        stream.accept_waveform(sr, samples)
        rec.decode_stream(stream)
        hyp = stream.result.text.strip()
        took = time.perf_counter() - t0
        dur = len(samples) / sr

        ref_n, hyp_n = normalize(ref), normalize(hyp)
        utts.append(
            {
                "file": fn,
                "duration_s": round(dur, 3),
                "recognize_s": round(took, 3),
                "rtf": round(took / dur, 4) if dur else None,
                "ref": ref,
                "hyp": hyp,
                "ref_norm": ref_n,
                "hyp_norm": hyp_n,
                "wer_raw": round(wer(ref, hyp), 4),
                "cer_raw": round(cer(ref, hyp), 4),
                "wer_norm": round(wer(ref_n, hyp_n), 4),
                "cer_norm": round(cer(ref_n, hyp_n), 4),
            }
        )

    # Corpus-level (total edits / total units) — the standard definition.
    def corpus(key, unit):
        """Total edits / total reference units across the whole language."""
        norm = key.endswith("_norm")
        edits = units = 0
        for u in utts:
            r = u["ref_norm"] if norm else u["ref"]
            h = u["hyp_norm"] if norm else u["hyp"]
            toks = r.split() if unit == "word" else list(r.replace(" ", ""))
            hyp_toks = h.split() if unit == "word" else list(h.replace(" ", ""))
            if not toks:
                continue
            edits += edit_distance(toks, hyp_toks)[0]
            units += len(toks)
        return round(edits / units, 4) if units else None

    rtfs = [u["rtf"] for u in utts if u["rtf"]]
    audio_s = sum(u["duration_s"] for u in utts)
    recog_s = sum(u["recognize_s"] for u in utts)

    summary = {
        "language": lang,
        "fleurs_config": code,
        "n_clips": len(utts),
        "clips_missing_audio": missing,
        "audio_s": round(audio_s, 1),
        "recognize_s": round(recog_s, 1),
        "rtf_median": round(float(np.median(rtfs)), 4),
        "rtf_p95": round(float(np.percentile(rtfs, 95)), 4),
        "latency_s_median": round(float(np.median([u["recognize_s"] for u in utts])), 3),
        "latency_s_p95": round(float(np.percentile([u["recognize_s"] for u in utts], 95)), 3),
        "wer_raw_corpus": corpus("wer_raw", "word"),
        "cer_raw_corpus": corpus("cer_raw", "char"),
        "wer_norm_corpus": corpus("wer_norm", "word"),
        "cer_norm_corpus": corpus("cer_norm", "char"),
        "wer_norm_mean_utt": round(float(np.mean([u["wer_norm"] for u in utts])), 4),
        "cer_norm_mean_utt": round(float(np.mean([u["cer_norm"] for u in utts])), 4),
        "utterances": utts,
    }

    with open(os.path.join(BASE, f"asr_full_{code}{_SUFFIX}.json"), "w") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)

    print(
        f"[{code:6s}] n={len(utts):4d}  "
        f"WER raw {summary['wer_raw_corpus']:.3f} norm {summary['wer_norm_corpus']:.3f}  "
        f"CER raw {summary['cer_raw_corpus']:.3f} norm {summary['cer_norm_corpus']:.3f}  "
        f"RTF med {summary['rtf_median']:.3f}",
        flush=True,
    )
    return summary


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    wanted = sys.argv[2:] or None
    langs = [x for x in LANGUAGES if not wanted or x[1] in wanted]

    print(f"Loading {os.path.basename(MODEL)} ...", flush=True)
    t0 = time.perf_counter()
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=MODEL, tokens=TOKENS, num_threads=8, provider="cpu", debug=False
    )
    print(f"Loaded in {time.perf_counter() - t0:.2f} s. n per language = {n}", flush=True)

    results = []
    for lang, code in langs:
        r = evaluate(rec, lang, code, n)
        if r:
            results.append(r)

    with open(os.path.join(BASE, f"SUMMARY_full{_SUFFIX}.json"), "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print("\n=== SUMMARY (corpus-level) ===")
    print(f"{'language':10s} {'n':>4s} {'WER raw':>8s} {'WER norm':>9s} {'CER raw':>8s} {'CER norm':>9s} {'RTF med':>8s}")
    for r in results:
        print(
            f"{r['language']:10s} {r['n_clips']:4d} {r['wer_raw_corpus']:8.3f} "
            f"{r['wer_norm_corpus']:9.3f} {r['cer_raw_corpus']:8.3f} "
            f"{r['cer_norm_corpus']:9.3f} {r['rtf_median']:8.3f}"
        )
    print(f"\nWrote asr_full_<code>.json + SUMMARY_full.json")


if __name__ == "__main__":
    main()
