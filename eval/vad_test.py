"""Silero VAD functional test — SIH26173.

The dossier claimed the VAD file was "208 KB, MEASURED" but the file was never on disk.
It has now been extracted from the prebuilt Android APK, where it ships as
`assets/silero_vad.onnx` (643,854 bytes).

This script proves it actually works: it should find speech in a real recording and
find nothing in silence. That is the "detect pauses and form sentences" requirement
from the problem statement.

Run from the repo root:
    output/option_a_stt/.venv/bin/python output/option_a_stt/vad_test.py
"""
import json
import os
import struct
import time

import numpy as np
import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
VAD_MODEL = os.path.join(BASE, "omni300m_vad", "silero_vad.onnx")
FLEURS = os.path.join(BASE, "fleurs")
SR = 16000
WINDOW = 512


def read_wav(path):
    """FLEURS clips are IEEE float (wFormatTag 3); Python's wave module rejects them."""
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
    _tag, channels, sr, _br, _ba, bits = fmt
    a = (
        np.frombuffer(data, dtype=np.float32)
        if bits == 32
        else np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    )
    if channels == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return np.ascontiguousarray(a, dtype=np.float32), sr


def build_vad():
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = VAD_MODEL
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.25
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = 10.0
    cfg.silero_vad.window_size = WINDOW
    cfg.sample_rate = SR
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)


def run(vad, samples):
    """Feed audio, collect detected segments. Returns list of segment lengths in seconds."""
    vad.reset()
    segs = []
    for i in range(0, len(samples) - WINDOW, WINDOW):
        vad.accept_waveform(samples[i : i + WINDOW])
        while not vad.empty():
            segs.append(len(vad.front.samples) / SR)
            vad.pop()
    vad.flush()
    while not vad.empty():
        segs.append(len(vad.front.samples) / SR)
        vad.pop()
    return segs


def main():
    if not os.path.exists(VAD_MODEL):
        raise SystemExit(f"VAD model missing at {VAD_MODEL}")

    size = os.path.getsize(VAD_MODEL)
    print(f"VAD model : {VAD_MODEL}")
    print(f"size      : {size:,} bytes ({size / 1024:.1f} KB)")
    print(f"source    : extracted from the prebuilt Android APK (assets/silero_vad.onnx)")
    print()

    vad = build_vad()
    print("VAD constructed OK\n")

    report = {"vad_model_bytes": size, "sample_rate": SR, "results": []}

    # --- test 1: silence must produce nothing ---
    silence = np.zeros(SR * 5, dtype=np.float32)
    segs = run(vad, silence)
    print(f"[silence 5.0 s]  segments detected: {len(segs)}  {'PASS' if not segs else 'FAIL'}")
    report["results"].append({"test": "silence_5s", "segments": len(segs), "pass": not segs})

    # --- test 2: real speech must produce segments ---
    for lang, code in (("Hindi", "hi_in"), ("Odia", "or_in"), ("Tamil", "ta_in")):
        tsv = os.path.join(FLEURS, f"{code}_test.tsv")
        if not os.path.exists(tsv):
            print(f"[{lang}] no tsv — skipping")
            continue
        with open(tsv, encoding="utf-8") as f:
            row = f.readline().rstrip("\n").split("\t")
        fn = row[1]
        wav = None
        for cand in (os.path.join(FLEURS, f"test-{code}", fn), os.path.join(FLEURS, "test", fn)):
            if os.path.exists(cand):
                wav = cand
                break
        if not wav:
            print(f"[{lang}] audio missing — skipping")
            continue

        samples, sr = read_wav(wav)
        if sr != SR:
            n = int(len(samples) * SR / sr)
            samples = np.interp(
                np.linspace(0, len(samples) - 1, n), np.arange(len(samples)), samples
            ).astype(np.float32)

        t0 = time.perf_counter()
        segs = run(vad, samples)
        took = time.perf_counter() - t0
        dur = len(samples) / SR
        total_seg = sum(segs)

        print(
            f"[{lang} {dur:5.1f} s]  segments: {len(segs)}  "
            f"speech detected: {total_seg:5.1f} s ({100 * total_seg / dur:4.1f}% of clip)  "
            f"VAD RTF: {took / dur:.4f}"
        )
        report["results"].append(
            {
                "test": f"speech_{code}",
                "audio_s": round(dur, 2),
                "segments": len(segs),
                "segment_lengths_s": [round(s, 2) for s in segs],
                "speech_detected_s": round(total_seg, 2),
                "vad_rtf": round(took / dur, 4),
                "pass": len(segs) > 0,
            }
        )

    print()
    ok = all(r["pass"] for r in report["results"])
    print("RESULT:", "PASS — VAD detects speech and ignores silence" if ok else "FAIL")

    with open(os.path.join(BASE, "vad_test.json"), "w") as f:
        json.dump(report, f, indent=2)
    print("Wrote vad_test.json")


if __name__ == "__main__":
    main()
