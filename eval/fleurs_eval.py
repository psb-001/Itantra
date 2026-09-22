"""Download real FLEURS Odia/Hindi clips and run Omnilingual ASR 300M INT8.

Ground truth = FLEURS 'raw_transcription' column. We compute word error rate.
FLEURS is CC-BY-4.0. Audio is real recorded human speech (not synthetic).
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

import numpy as np
import sherpa_onnx
import wave

BASE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(BASE, "omni300m", "model.int8.onnx")
TOKENS = os.path.join(BASE, "omni300m", "tokens.txt")
FLEURS = os.path.join(BASE, "fleurs")
AUDIO = "https://huggingface.co/datasets/google/fleurs/resolve/main/data"


def wer(ref: str, hyp: str) -> float:
    r, h = ref.split(), hyp.split()
    if not r:
        return 0.0 if not h else 1.0
    d = [[0] * (len(h) + 1) for _ in range(len(r) + 1)]
    for i in range(len(r) + 1):
        d[i][0] = i
    for j in range(len(h) + 1):
        d[0][j] = j
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            cost = 0 if r[i - 1] == h[j - 1] else 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
    return d[len(r)][len(h)] / len(r)


def cer(ref: str, hyp: str) -> float:
    """Character-level error rate (proper character edit distance)."""
    r, h = list(ref.replace(" ", "")), list(hyp.replace(" ", ""))
    if not r:
        return 0.0 if not h else 1.0
    prev = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        cur = [i] + [0] * len(h)
        for j in range(1, len(h) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (0 if r[i - 1] == h[j - 1] else 1))
        prev = cur
    return prev[len(h)] / len(r)


def read_wav(path):
    """Read FLEURS clips. They are IEEE float (wFormatTag 3), not 16-bit PCM."""
    import struct

    with open(path, "rb") as f:
        riff = f.read(4)
        assert riff == b"RIFF", path
        f.seek(40)  # past headers to data chunk assumption is unsafe; parse properly
        f.seek(12)
        fmt = None
        data = None
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
    _tag, channels, sr, _brate, _ba, _bits = fmt
    if _bits == 32:
        a = np.frombuffer(data, dtype=np.float32)
    elif _bits == 16:
        a = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    else:
        raise ValueError(f"unsupported bits={_bits} in {path}")
    if channels == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return np.ascontiguousarray(a, dtype=np.float32), sr


def main(lang, code, n=8):
    tsv = os.path.join(FLEURS, f"{code}_test.tsv")
    rows = []
    with open(tsv, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 4:
                rows.append(parts)
    rows = rows[:n]

    os.makedirs(os.path.join(FLEURS, "wav", code), exist_ok=True)
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=MODEL, tokens=TOKENS, num_threads=8, provider="cpu", debug=False
    )

    out = {"language": lang, "fleurs_config": code, "n": len(rows), "utterances": []}
    for parts in rows:
        fn, ref = parts[1], parts[2]
        local = os.path.join(FLEURS, "test", fn)
        if not os.path.exists(local):  # languages extracted as test-<code>
            alt = os.path.join(FLEURS, f"test-{code}", fn)
            if os.path.exists(alt):
                local = alt
        if not os.path.exists(local):
            raise FileNotFoundError(f"missing clip {local}; extract {code} test.tar.gz")

        samples, sr = read_wav(local)
        t0 = time.perf_counter()
        stream = rec.create_stream()
        stream.accept_waveform(sr, samples)
        rec.decode_stream(stream)
        text = stream.result.text.strip()
        took = time.perf_counter() - t0
        dur = len(samples) / sr
        out["utterances"].append(
            {
                "file": fn,
                "duration_s": round(dur, 3),
                "recognize_s": round(took, 3),
                "rtf": round(took / dur, 3),
                "ref": ref,
                "hyp": text,
                "wer": round(wer(ref, text), 4),
                "cer": round(cer(ref, text), 4),
            }
        )

    out["mean_wer"] = round(float(np.mean([u["wer"] for u in out["utterances"]])), 4)
    out["mean_cer"] = round(float(np.mean([u["cer"] for u in out["utterances"]])), 4)
    out["total_audio_s"] = round(sum(u["duration_s"] for u in out["utterances"]), 3)
    out["total_recognize_s"] = round(sum(u["recognize_s"] for u in out["utterances"]), 3)
    with open(os.path.join(BASE, f"asr_{code}.json"), "w") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    print(json.dumps(out, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 8)
