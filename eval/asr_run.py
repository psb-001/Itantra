"""First real inference test of the Option A ASR pick.

Meta Omnilingual ASR CTC 300M INT8 via sherpa-onnx, run locally on Apple Silicon.
Measures: model load time, per-utterance recognition, text output, wall time, RTF.
NOTE: this is a Mac (arm64, 17 GB RAM). It is NOT an Android phone measurement.
"""
import json
import os
import time

import numpy as np
import sherpa_onnx
import wave

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "omni300m")
MODEL = os.path.join(BASE, "model.int8.onnx")
TOKENS = os.path.join(BASE, "tokens.txt")


def load():
    t0 = time.perf_counter()
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=MODEL,
        tokens=TOKENS,
        debug=False,
        num_threads=min(8, os.cpu_count() or 4),
        provider="cpu",
    )
    return rec, time.perf_counter() - t0


def wav_to_samples(path):
    with wave.open(path, "rb") as w:
        n, sr = w.getnframes(), w.getframerate()
        raw = w.readframes(n)
        sw = w.getsampwidth()
    dt = {1: np.int8, 2: np.int16, 4: np.int32}[sw]
    a = np.frombuffer(raw, dtype=dt).astype(np.float32) / 32768.0
    if w.getnchannels() == 2:  # noqa
        a = a.reshape(-1, 2).mean(axis=1)
    return a, sr


def main():
    out = {"machine": "macOS arm64 (NOT Android)", "model": "omnilingual-asr-300M-ctc-int8"}
    rec, load_s = load()
    out["model_load_seconds"] = round(load_s, 3)
    out["model_bytes"] = os.path.getsize(MODEL)

    results = []
    wavs = sorted(
        f for f in os.listdir(os.path.join(BASE, "test_wavs")) if f.endswith(".wav")
    )
    for name in wavs:
        path = os.path.join(BASE, "test_wavs", name)
        samples, sr = wav_to_samples(path)
        t0 = time.perf_counter()
        stream = rec.create_stream()
        stream.accept_waveform(sr, samples)
        rec.decode_stream(stream)
        text = stream.result.text.strip()
        dur = len(samples) / sr
        took = time.perf_counter() - t0
        results.append(
            {
                "file": name,
                "duration_s": round(dur, 3),
                "recognize_s": round(took, 3),
                "rtf": round(took / dur, 3),
                "text": text,
            }
        )
    out["utterances"] = results
    out["total_audio_s"] = round(sum(r["duration_s"] for r in results), 3)
    out["total_recognize_s"] = round(sum(r["recognize_s"] for r in results), 3)
    with open("asr_result.json", "w") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    print(json.dumps(out, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
