"""
Does the sender-side repetition-loop check deserve to be switched on?

WHERE THIS COMES FROM. QualityGate.adjacentRepeat(tokens) rejects a decode when a 1-3 token n-gram
tiles >= 4 times back to back, and QualityGateTest proves it on hand-built token lists. But the
LIVE path in MainActivity calls `QualityGate.evaluate(..., tokens = emptyList(), ...)` — the
recogniser returns tokens and AsrEngine drops them, so the check can never fire in the app. This
probe decides, by measurement on the shipped model, whether wiring the real tokens through is
safe or would start rejecting correct speech.

WHAT IS MEASURED. For 10 clips per language (FLEURS test sets), decode with the exact shipped
model + sherpa_onnx version the app bundles, then compute the SAME adjacent-repeat number the
Kotlin code would compute — on the recogniser's real token list — plus the same number over text
characters as a comparator.

STATUS (16 Oct 2026): ACTIVATED, with the single-digit exemption mirrored in the port below.
The final sweep at 25 clips/language (225 clips) rejected exactly one decode — a degenerate
three-glyph spacing artifact ("ं   ो    ख") — and zero ordinary sentences. The first sweep
(10 clips/language) is what produced the exemption: a clean Bengali sentence containing "100000"
was rejected without it. Accepted cost: a number with eight consecutive zeros still reaches the
threshold at n=2 (pinned in QualityGateTest); everything shorter is sent. The live app passes the
recogniser's real tokens (MainActivity -> AsrEngine.transcribeWithTokens -> QualityGate.evaluate).

Run:
    .venv/bin/python eval/probe_loopcheck_tokens.py 2>&1 | grep -v "resampler\\|in_sample_rate\\|output_sample_rate"
"""

import glob
import json
import os
import random
import wave

import numpy as np
import sherpa_onnx

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(REPO, "models", "asr-v3-10lang",
                         "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8")
SEED = 20261016
CLIPS_PER_LANG = 25
LANGS = ["en_us", "hi_in", "mr_in", "bn_in", "ta_in", "te_in", "ml_in", "gu_in", "kn_in"]


def load_wav(path):
    try:
        with wave.open(path, "rb") as w:
            raw = w.readframes(w.getnframes())
            sw, sr, ch = w.getsampwidth(), w.getframerate(), w.getnchannels()
        dt = {1: np.int8, 2: np.int16, 4: np.int32}[sw]
        a = np.frombuffer(raw, dtype=dt).astype(np.float32) / 32768.0
        if ch == 2:
            a = a.reshape(-1, 2).mean(axis=1)
    except wave.Error:
        # FLEURS ships float32 WAVs (format tag 3), which the wave module refuses to open.
        import struct
        with open(path, "rb") as f:
            data = f.read()
        fmt, payload, i = None, None, 12
        while i + 8 <= len(data):
            cid, size = data[i:i + 4], struct.unpack("<I", data[i + 4:i + 8])[0]
            body = data[i + 8:i + 8 + size]
            if cid == b"fmt ":
                fmt = struct.unpack("<HHIIHH", body[:16])   # tag, ch, sr, rate, align, bits
            elif cid == b"data":
                payload = body
            i += 8 + size + (size & 1)
        tag, ch, sr, _, _, bits = fmt
        assert tag == 3 and bits == 32, f"unexpected FLEURS format {tag} {bits}"
        a = np.frombuffer(payload, dtype="<f4").astype(np.float32)
        if ch == 2:
            a = a.reshape(-1, 2).mean(axis=1)
    if sr != 16000:
        idx = (np.arange(int(len(a) * 16000 / sr)) * sr / 16000).astype(int)
        a = a[np.clip(idx, 0, len(a) - 1)]
    return a


def adjacent_repeat(tokens):
    """Exact port of QualityGate.adjacentRepeat, INCLUDING the single-digit exemption at n=1."""
    n_tok = len(tokens)
    if n_tok < 2:
        return 1
    best = 1
    for n in (1, 2, 3):
        if n_tok < n * 2:
            continue
        i = 0
        while i + n * 2 <= n_tok:
            gram = tokens[i:i + n]
            if n == 1 and len(gram[0]) == 1 and gram[0].isdigit():
                i += 1
                continue
            k = 0
            j = i
            while j + n <= n_tok and tokens[j:j + n] == gram:
                k += 1
                j += n
            if k > best:
                best = k
            i += 1
    return best


def main():
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(MODEL_DIR, "model.int8.onnx"),
        tokens=os.path.join(MODEL_DIR, "tokens.txt"),
        num_threads=4,
        provider="cpu",
    )

    rng = random.Random(SEED)
    print(f"sherpa_onnx {sherpa_onnx.__version__}  model={os.path.basename(MODEL_DIR)}")
    print(f"threshold under test: adjacent_repeat >= 4 would be REJECTED (REPEAT verdict)\n")

    report = {}
    sample_printed = False
    for lang in LANGS:
        d = os.path.join(REPO, "dataset", "fleurs", f"test-{lang}")
        files = sorted(glob.glob(os.path.join(d, "*.wav")))
        if not files:
            print(f"{lang:6s} no clips found at {d}")
            continue
        picked = rng.sample(files, min(CLIPS_PER_LANG, len(files)))
        tok_hist = {}
        char_hist = {}
        over = []
        worst = ("", 0)
        for path in picked:
            s = rec.create_stream()
            s.accept_waveform(16000, load_wav(path))
            rec.decode_stream(s)
            text = s.result.text.strip()
            tokens = list(s.result.tokens)
            if not sample_printed:
                print(f"one clip's tokens ({lang}): {tokens[:24]}{' ...' if len(tokens) > 24 else ''}")
                print(f"  its text: {text[:72]}\n")
                sample_printed = True
            tr = adjacent_repeat(tokens)
            cr = adjacent_repeat(list(text))
            tok_hist[tr] = tok_hist.get(tr, 0) + 1
            char_hist[cr] = char_hist.get(cr, 0) + 1
            if tr > worst[1]:
                worst = (text, tr)
            if tr >= 4:
                over.append({"clip": os.path.basename(path), "tiling": tr, "text": text[:120]})
        n = len(picked)
        report[lang] = {
            "n": n,
            "token_tiling_hist": dict(sorted(tok_hist.items())),
            "char_tiling_hist": dict(sorted(char_hist.items())),
            "clips_with_tiling_over_3": over,
        }
        hist = " ".join(f"{k}x{v}" for k, v in sorted(tok_hist.items()))
        flag = f"  <-- {len(over)} clip(s) would be REJECTED" if over else ""
        print(f"{lang:6s} n={n:3d}  token-tiling {hist}{flag}")
        if over:
            print(f"        worst: \"{worst[0][:96]}\" (tiling {worst[1]})")

    total = sum(v["n"] for v in report.values())
    rejected = sum(len(v["clips_with_tiling_over_3"]) for v in report.values())
    print(f"\ntotal {total} clean clips, {rejected} would be rejected by the activated check")
    if rejected == 0:
        print("VERDICT: every clean clip passes — the live token check rejects nothing here.")
    else:
        print(f"VERDICT: {rejected} rejection(s) — the texts above are the evidence. The shipped")
        print("rule: reject only degenerate decodes; ordinary sentences, numbers included (single")
        print("digits exempt at n=1), must pass. Recorded in docs/DELIVERY_FINDINGS.md.")

    out = os.path.join(REPO, "eval", "probe_loopcheck_tokens.json")
    with open(out, "w") as f:
        json.dump({"clips_per_lang": CLIPS_PER_LANG, "languages": report}, f, indent=2, ensure_ascii=False)
    print(f"json written: {os.path.relpath(out, REPO)}")


if __name__ == "__main__":
    main()
