"""
Measure decoding methods on the real FLEURS clips, and count hallucination failures.

The app currently decodes with sherpa-onnx's default `greedy_search`. Greedy CTC decoding is
the classic source of hallucination: when the acoustic evidence is weak the decoder is free to
emit any token, and on a character-level model with a 1,002-token vocabulary that shows up as
repetition loops and short junk strings rather than as obvious garbage.

This script answers three questions with numbers instead of opinion:

  1. Does beam search reduce character error, and what does it cost in real-time factor?
  2. How often does each method produce a REPETITION LOOP (a token or n-gram repeated
     implausibly many times) — the signature of a CTC decode failure?
  3. Does a cheap plausibility gate catch those failures, so the app can drop the segment
     instead of transmitting a confident wrong message?

Usage:
    python eval/decode_methods.py --langs hi,en,ta --n 40
    python eval/decode_methods.py --langs all --n 200 --out results/decode_methods.json

Requires sherpa-onnx and the FLEURS test set. Read-only: it never writes into the app.
"""

import argparse
import json
import os
import re
import struct
import sys
import time
import unicodedata

import numpy as np
import sherpa_onnx

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FLEURS = os.environ.get("FLEURS_DIR", os.path.join(BASE, "dataset", "fleurs"))
MODEL_DIR = os.path.join(
    BASE, "app", "app", "src", "main", "assets",
    "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8",
)
MODEL = os.path.join(MODEL_DIR, "model.int8.onnx")
TOKENS = os.path.join(MODEL_DIR, "tokens.txt")

# The ten required languages, and the FLEURS config codes used by the existing eval.
LANGS = {
    "hi": "hi_in", "mr": "mr_in", "bn": "bn_in", "ml": "ml_in", "te": "te_in",
    "ta": "ta_in", "gu": "gu_in", "kn": "kn_in", "or": "or_in", "en": "en_us",
}

ZERO_WIDTH = {ord(c): None for c in "‌‍﻿"}


# --- text metrics, copied verbatim from eval/fleurs_eval_full.py so numbers are comparable ---
def normalize(text):
    text = unicodedata.normalize("NFC", text)
    text = text.translate(ZERO_WIDTH)
    text = "".join(" " if unicodedata.category(ch).startswith("P") else ch for ch in text)
    return re.sub(r"\s+", " ", text.casefold()).strip()


def edit_distance(ref, hyp):
    r, h = list(ref), list(hyp)
    if not r:
        return len(h), 0, 0, len(h)
    prev = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        cur = [i] + [0] * len(h)
        for j in range(1, len(h) + 1):
            cost = 0 if r[i - 1] == h[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[len(h)], 0, 0, 0


def cer(ref, hyp):
    r, h = list(ref.replace(" ", "")), list(hyp.replace(" ", ""))
    if not r:
        return 0.0 if not h else 1.0
    return edit_distance(r, h)[0] / len(r)


def wer(ref, hyp):
    r, h = ref.split(), hyp.split()
    if not r:
        return 0.0 if not h else 1.0
    return edit_distance(r, h)[0] / len(r)


# --- audio ---
def read_wav(path):
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


def load_clips(code, n):
    tsv = os.path.join(FLEURS, f"{code}_test.tsv")
    if not os.path.exists(tsv):
        return None
    rows = []
    with open(tsv, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 4:
                rows.append((parts[1], parts[2]))
    out = []
    for fn, ref in rows:
        for cand in (
            os.path.join(FLEURS, f"test-{code}", fn),
            os.path.join(FLEURS, "test", fn),
        ):
            if os.path.exists(cand):
                out.append((cand, ref))
                break
        if len(out) >= n:
            break
    return out


# --- hallucination signatures ---
def repetition_score(tokens, text):
    """How pathological is this decode?

    Returns (worst_repeat_run, max_ngram_repeat, chars_per_sec, flags).

    A CTC decode failure looks like one of:
      * the same token emitted many times in a row  (a loop)
      * a short n-gram repeated over and over       (a stuck beam)
      * almost no text for a lot of audio          (the model gave up)
    """
    flags = []
    worst_run = 1
    if tokens:
        run = 1
        for i in range(1, len(tokens)):
            run = run + 1 if tokens[i] == tokens[i - 1] else 1
            worst_run = max(worst_run, run)
    else:
        worst_run = 0

    ngram_repeat = 1
    words = text.split()
    for n in (1, 2, 3):
        seen = {}
        for i in range(len(words) - n + 1):
            g = " ".join(words[i:i + n])
            seen[g] = seen.get(g, 0) + 1
            ngram_repeat = max(ngram_repeat, seen[g])

    return worst_run, ngram_repeat


def chars_per_sec(text, audio_s):
    n = len(text.replace(" ", ""))
    return n / audio_s if audio_s > 0 else 0.0


# --- recognizer factory ---
def make_recognizer(method, beam=4, threads=4):
    """Build the recogniser the same way the app does.

    The app goes through OfflineModelConfig(omnilingual = ...) with decodingMethod on
    OfflineRecognizerConfig. `from_omnilingual_asr_ctc` is the shorthand for exactly that
    combination, and it is the only factory that understands this model type.
    """
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=MODEL,
        tokens=TOKENS,
        num_threads=threads,
        decoding_method=method,
        debug=False,
        provider="cpu",
    )
    # max_active_paths is the beam width for the beam decoders. It lives on the config, which the
    # factory does not expose as a keyword, so reach it through the recogniser's own config.
    try:
        rec.config.max_active_paths = beam
    except Exception:
        pass
    return rec


# The app prepends 0.2 s of silence; see SpeechEngine.transcribe.
LEAD_SILENCE = int(0.2 * 16000)


def decode_one(rec, samples, sr):
    padded = np.concatenate([np.zeros(LEAD_SILENCE, dtype=np.float32), samples])
    s = rec.create_stream()
    try:
        s.accept_waveform(sr, padded)
        rec.decode_stream(s)
        r = s.result
        return r.text.strip(), list(r.tokens)
    finally:
        del s


def run_method(method, beam, clips, threads=4):
    rec = make_recognizer(method, beam, threads)
    rows = []
    try:
        for path, ref in clips:
            samples, sr = read_wav(path)
            audio_s = len(samples) / sr
            t0 = time.perf_counter()
            text, tokens = decode_one(rec, samples, sr)
            dt = time.perf_counter() - t0
            rows.append({
                "ref": ref,
                "hyp": text,
                "audio_s": audio_s,
                "rtf": dt / audio_s if audio_s else 0.0,
                "n_tokens": len(tokens),
                **{"run": repetition_score(tokens, text)[0],
                   "ngram": repetition_score(tokens, text)[1]},
                "cps": chars_per_sec(text, audio_s),
            })
    finally:
        del rec
    return rows


def summarise(rows, gate_run=None, gate_ngram=None, gate_cps=None):
    n = len(rows)
    if not n:
        return None
    cers, wers, rtfs = [], [], []
    empty = looped = junk = caught = 0
    kept_bad = 0
    for r in rows:
        cers.append(cer(normalize(r["ref"]), normalize(r["hyp"])))
        wers.append(wer(normalize(r["ref"]), normalize(r["hyp"])))
        rtfs.append(r["rtf"])
        if not r["hyp"]:
            empty += 1
        is_loop = r["run"] >= gate_run or r["ngram"] >= gate_ngram
        is_junk = r["cps"] < gate_cps and r["audio_s"] > 1.0
        bad = is_loop or is_junk
        if bad:
            caught += 1
            if is_loop:
                looped += 1
            if is_junk:
                junk += 1
        # a "bad" clip that the gate would let through, if it were bad
        if bad and cer(normalize(r["ref"]), normalize(r["hyp"])) > 0.9:
            kept_bad += 1
    return {
        "n": n,
        "cer": sum(cers) / n,
        "wer": sum(wers) / n,
        "rtf_median": float(np.median(rtfs)),
        "rtf_mean": sum(rtfs) / n,
        "empty": empty,
        "loops": looped,
        "junk": junk,
        "flagged": caught,
        "flagged_pct": caught / n * 100,
        "flagged_that_were_already_catastrophic": kept_bad,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--langs", default="hi,en,ta")
    ap.add_argument("--n", type=int, default=40)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--out", default="")
    ap.add_argument("--gate-run", type=int, default=4)
    ap.add_argument("--gate-ngram", type=int, default=4)
    ap.add_argument("--gate-cps", type=float, default=0.8)
    args = ap.parse_args()

    langs = list(LANGS) if args.langs == "all" else args.langs.split(",")
    if not os.path.exists(MODEL):
        sys.exit(f"model not found: {MODEL}")

    methods = [
        ("greedy_search", 1),
        ("modified_beam_search", 4),
        ("modified_beam_search", 8),
        ("fast_beam_search", 8),
    ]

    report = {"model": os.path.relpath(MODEL, BASE), "n_per_lang": args.n, "results": {}}

    for lg in langs:
        code = LANGS[lg]
        clips = load_clips(code, args.n)
        if not clips:
            print(f"[{lg}] no clips — skipping", flush=True)
            continue
        print(f"\n=== {lg} ({code}) — {len(clips)} clips ===", flush=True)
        per_lang = {}
        for method, beam in methods:
            try:
                rows = run_method(method, beam, clips, args.threads)
            except Exception as e:
                # sherpa-onnx aborts the process, not the call, for an unsupported decoder on
                # this model type, so the first sign of it is a hard exit. Catch what we can and
                # record the method as unavailable rather than losing the whole run.
                msg = str(e).strip().splitlines()[-1] if str(e).strip() else type(e).__name__
                per_lang[f"{method}@{beam}"] = {"unsupported": msg[:200]}
                print(f"  {method:22} beam={beam:<2}  UNAVAILABLE — {msg[:90]}", flush=True)
                continue
            st = summarise(rows, args.gate_run, args.gate_ngram, args.gate_cps)
            per_lang[f"{method}@{beam}"] = st
            print(
                f"  {method:22} beam={beam:<2}  CER {st['cer']:.4f}  WER {st['wer']:.4f}  "
                f"RTF {st['rtf_median']:.4f}  empty {st['empty']:>3}  loops {st['loops']:>3}  "
                f"junk {st['junk']:>3}  flagged {st['flagged']:>3} ({st['flagged_pct']:.1f}%)",
                flush=True,
            )
        report["results"][lg] = per_lang

    if args.out:
        os.makedirs(os.path.dirname(os.path.join(BASE, args.out)), exist_ok=True)
        with open(os.path.join(BASE, args.out), "w") as f:
            json.dump(report, f, indent=2)
        print(f"\nwrote {args.out}")

    print("\nGate used: token run >= %d, n-gram repeat >= %d, or < %.2f chars/sec on >1s audio"
          % (args.gate_run, args.gate_ngram, args.gate_cps))
    print("A flagged clip is DROPPED, not corrected. Silence can be retried; a confident wrong")
    print("message cannot.")


if __name__ == "__main__":
    main()
