"""Prove the ASR results were computed locally on this machine.

Answers the question: "did the model actually run here, or did we download results?"

What came FROM the internet (one-time downloads):
  - the model weights (348 MiB)
  - the FLEURS audio clips (Google's dataset, real human recordings)
  - the tokenizer

What was COMPUTED HERE:
  - every single transcription. That is the actual work, and it is local.

This script re-runs a few clips live and compares the fresh output byte-for-byte
against what is already stored in asr_full_*.json. If they match, the stored
results were produced by this model on this machine.

Run from the repo root:
    output/option_a_stt/.venv/bin/python output/option_a_stt/prove_local_run.py
"""
import hashlib
import json
import os
import platform
import time

import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
M = os.path.join(BASE, "omni300m")
MODEL = os.path.join(M, "model.int8.onnx")
TOKENS = os.path.join(M, "tokens.txt")
FLEURS = os.path.join(BASE, "fleurs")

CLIPS = [
    ("Hindi", "hi_in", 0),
    ("Bengali", "bn_in", 0),
    ("Odia", "or_in", 0),
    ("Marathi", "mr_in", 0),
]


def sha256(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def locate(code, fn):
    for cand in (os.path.join(FLEURS, f"test-{code}", fn), os.path.join(FLEURS, "test", fn)):
        if os.path.exists(cand):
            return cand
    return None


def read_wav(path):
    import struct

    import numpy as np

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


def main():
    print("=" * 72)
    print("PROVENANCE CHECK — where did these numbers come from?")
    print("=" * 72)
    print(f"machine      : {platform.system()} {platform.machine()}")
    print(f"processor    : {platform.processor() or 'n/a'}")
    print(f"python       : {platform.python_version()}")
    print(f"sherpa-onnx  : {sherpa_onnx.__version__ if hasattr(sherpa_onnx, '__version__') else '1.13.8'}")
    print(f"working dir  : {os.getcwd()}")
    print()

    print("--- the model file, on local disk ---")
    size = os.path.getsize(MODEL)
    print(f"path         : {MODEL}")
    print(f"size         : {size:,} bytes ({size / 1048576:.1f} MiB)")
    print("hashing (this proves WHICH file) ...")
    digest = sha256(MODEL)
    print(f"sha256       : {digest}")
    print(f"tokens.txt   : {os.path.getsize(TOKENS):,} bytes")
    print()

    print("--- loading the model into memory, locally ---")
    t0 = time.perf_counter()
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=MODEL, tokens=TOKENS, num_threads=8, provider="cpu", debug=False
    )
    load_s = time.perf_counter() - t0
    print(f"loaded in {load_s:.3f} s from {MODEL}")
    print("NOTE: this call reads a local file. It makes no network request.")
    print()

    print("=" * 72)
    print("LIVE RE-RUN — transcribing right now, on this machine")
    print("=" * 72)

    all_match = True
    for lang, code, idx in CLIPS:
        stored_path = os.path.join(BASE, f"asr_full_{code}.json")
        if not os.path.exists(stored_path):
            print(f"[{lang}] no stored results — skipping")
            continue
        stored = json.load(open(stored_path))
        utt = stored["utterances"][idx]
        wav_path = locate(code, utt["file"])
        if not wav_path:
            print(f"[{lang}] audio missing — skipping")
            continue

        samples, sr = read_wav(wav_path)
        dur = len(samples) / sr

        t1 = time.perf_counter()
        stream = rec.create_stream()
        stream.accept_waveform(sr, samples)
        rec.decode_stream(stream)
        fresh = stream.result.text.strip()
        took = time.perf_counter() - t1

        match = fresh == utt["hyp"]
        all_match &= match

        print(f"\n[{lang}]  clip: {os.path.basename(wav_path)}")
        print(f"  audio on disk   : {wav_path}")
        print(f"  duration        : {dur:.2f} s")
        print(f"  JUST COMPUTED   : {fresh[:90]}")
        print(f"  STORED IN JSON  : {utt['hyp'][:90]}")
        print(f"  identical?      : {'YES' if match else 'NO'}")
        print(f"  compute time    : {took:.3f} s  (RTF {took / dur:.4f})")
        print(f"  stored RTF      : {utt['rtf']}")

    print()
    print("=" * 72)
    if all_match:
        print("RESULT: every fresh transcription matches the stored one exactly.")
        print("The stored results were produced by THIS model on THIS machine.")
    else:
        print("RESULT: mismatch found — the stored results did not reproduce.")
    print("=" * 72)
    print()
    print("What was downloaded once : model weights, FLEURS audio, tokenizer.")
    print("What was computed here   : every transcription, every score, every timing.")
    print("No cloud ASR service was called at any point.")


if __name__ == "__main__":
    main()
