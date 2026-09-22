"""Diagnose: does feeding VAD segments (vs the whole file) change the output script?

The on-device pipeline test showed Hindi coming back in Arabic script when fed VAD segments,
while the same file fed whole transcribed correctly in Devanagari. This script isolates whether
that is a model behaviour or an Android-side bug, by doing exactly the same thing in Python.

Run from the repo root:
    output/option_a_stt/.venv/bin/python output/option_a_stt/vad_segment_diagnostic.py
"""
import os
import struct
import sys
import unicodedata

import numpy as np
import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.environ.get(
    "OMNI_DIR", "omni300m_v2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05"
)
MODEL = os.path.join(BASE, MODEL_DIR, "model.int8.onnx")
TOKENS = os.path.join(BASE, MODEL_DIR, "tokens.txt")
VAD_MODEL = os.path.join(BASE, "omni300m_vad", "silero_vad.onnx")
BENCH = os.path.join(BASE, "android/iTantraApp/app/src/main/assets/bench")
SR = 16000
WINDOW = 512


def read_pcm16(path):
    with open(path, "rb") as f:
        assert f.read(4) == b"RIFF"
        f.seek(12)
        fmt = data = None
        while True:
            h = f.read(8)
            if len(h) < 8:
                break
            cid, size = struct.unpack("<4sI", h)
            p = f.read(size)
            if cid == b"fmt ":
                fmt = struct.unpack("<HHIIHH", p[:16])
            elif cid == b"data":
                data = p
                break
    _t, ch, sr, _br, _ba, bits = fmt
    a = (np.frombuffer(data, dtype=np.float32) if bits == 32
         else np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0)
    if ch == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return np.ascontiguousarray(a, dtype=np.float32), sr


def script_mix(text):
    counts = {}
    for ch in text:
        if not ch.isalpha():
            continue
        try:
            n = unicodedata.name(ch, "")
        except ValueError:
            continue
        for s in ("DEVANAGARI", "ARABIC", "LATIN", "BENGALI", "GURMUKHI", "GUJARATI"):
            if n.startswith(s):
                counts[s] = counts.get(s, 0) + 1
                break
    return counts


def main():
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=MODEL, tokens=TOKENS, num_threads=8, provider="cpu", debug=False
    )
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = VAD_MODEL
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.25
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = 10.0
    cfg.silero_vad.window_size = WINDOW
    cfg.sample_rate = SR

    def transcribe(a):
        s = rec.create_stream()
        s.accept_waveform(SR, a)
        rec.decode_stream(s)
        return s.result.text.strip()

    for name in ("bench_00_en.wav", "bench_02_hi.wav"):
        path = os.path.join(BENCH, name)
        audio, sr = read_pcm16(path)
        print("=" * 76)
        print(f"{name}  ({len(audio)/SR:.2f}s)")
        print("=" * 76)

        whole = transcribe(audio)
        print(f"  WHOLE FILE      : {whole[:70]}")
        print(f"    scripts       : {script_mix(whole)}")

        # now the same thing the Android pipeline does: chunked VAD, then flush
        vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
        segs = []
        for i in range(0, len(audio) - WINDOW, WINDOW):
            vad.accept_waveform(audio[i:i + WINDOW])
            while not vad.empty():
                segs.append(np.array(vad.front.samples, dtype=np.float32))
                vad.pop()
        before = len(segs)
        vad.flush()
        while not vad.empty():
            segs.append(np.array(vad.front.samples, dtype=np.float32))
            vad.pop()
        print(f"  VAD segments    : {before} before flush, {len(segs)} after")
        for i, seg in enumerate(segs):
            txt = transcribe(seg)
            print(f"    seg{i} {len(seg)/SR:5.2f}s : {txt[:60]}")
            print(f"           scripts : {script_mix(txt)}")

        # does padding the segment with leading silence change the script?
        if segs:
            print("  --- leading-silence sweep on seg0 ---")
            for pad_s in (0.0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.5, 0.75):
                n = int(SR * pad_s)
                padded = (np.concatenate([np.zeros(n, dtype=np.float32), segs[0]])
                          if n else segs[0])
                txt = transcribe(padded)
                mix = script_mix(txt)
                print(f"    +{pad_s:4.2f}s : {str(mix):42s} {txt[:44]}")
        print()


if __name__ == "__main__":
    main()
