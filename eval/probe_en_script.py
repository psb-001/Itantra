"""
What does the shipped (pruned) ASR emit for English audio -- and where does the app's live path
send it?

Live capture passes every segment through `LanguageFilter.filter(raw, langTag)` with
`langTag = Lang.HINDI` hardcoded (MainActivity.kt:140), and that filter drops the whole segment
if it contains a character outside Devanagari. So an English utterance can only end up in one of
three buckets:

    latin   -> dropped ("could not read that clearly", the REPEAT path), never sent
    deva    -> sent, tagged hin_Deva, and the receiving phone speaks it with the HINDI voice
    mixed   -> dropped

This probe measures the bucket distribution on real audio with the model the app ships:
the team's own bench recordings, FLEURS en_us clips, and FLEURS hi_in clips as a control.

Run:
    .venv/bin/python eval/probe_en_script.py
"""

import glob
import os
import random
import sys
import wave

import numpy as np
import sherpa_onnx

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(REPO, "models", "asr-v3-10lang",
                         "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8")
SEED = 20261015


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
    if sr != 16000:                                     # nearest-neighbour is enough for a probe
        idx = (np.arange(int(len(a) * 16000 / sr)) * sr / 16000).astype(int)
        a = a[np.clip(idx, 0, len(a) - 1)]
    return a


def bucket(text):
    deva = sum(1 for c in text if "\u0900" <= c <= "\u097F")
    latin = sum(1 for c in text if c.isascii() and c.isalpha())
    if deva + latin == 0:
        return "empty"
    if latin == 0:
        return "deva"
    if deva == 0:
        return "latin"
    return "mixed"


def main():
    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(MODEL_DIR, "model.int8.onnx"),
        tokens=os.path.join(MODEL_DIR, "tokens.txt"),
        num_threads=4,
        provider="cpu",
    )

    rng = random.Random(SEED)
    cases = [("bench_00_en (our own recording)", "app/app/src/main/assets/bench/bench_00_en.wav"),
             ("bench_01_en (our own recording)", "app/app/src/main/assets/bench/bench_01_en.wav"),
             ("bench_02_hi (control)", "app/app/src/main/assets/bench/bench_02_hi.wav")]
    for d in sorted(glob.glob(os.path.join(REPO, "dataset", "fleurs", "*en_us*"))):
        if os.path.isdir(d):
            files = sorted(glob.glob(os.path.join(d, "*.wav")))
            cases += [(f"fleurs_en_us/{os.path.basename(f)}", f) for f in rng.sample(files, 20)]
            break
    for d in sorted(glob.glob(os.path.join(REPO, "dataset", "fleurs", "*hi_in*"))):
        if os.path.isdir(d):
            files = sorted(glob.glob(os.path.join(d, "*.wav")))
            cases += [(f"fleurs_hi_in/{os.path.basename(f)}", f) for f in rng.sample(files, 5)]
            break

    counts = {}
    for label, path in cases:
        s = rec.create_stream()
        s.accept_waveform(16000, load_wav(path))
        rec.decode_stream(s)
        text = s.result.text.strip()
        b = bucket(text)
        counts.setdefault((label.split("/")[0].split(" ")[0], b), 0)
        counts[(label.split("/")[0].split(" ")[0], b)] += 1
        print(f"[{b:5s}] {label}")
        print(f"        {text[:96]}")

    print("\nsummary — what the live path does with each bucket (langTag is HINDI):")
    print("  deva   -> sent as hin_Deva; the HINDI voice speaks Devanagari text  (correct)")
    print("  latin  -> SENT as hin_Deva; the HINDI voice reads LATIN ENGLISH     (the bug)")
    print("  mixed  -> sent as hin_Deva; Hinglish, Hindi voice reads both        (meh)")
    print("  empty  -> REPEAT / SILENT, handled earlier by QualityGate")
    groups = {}
    for (g, b), n in counts.items():
        groups.setdefault(g, {}).setdefault(b, 0)
        groups[g][b] = n
    for g in sorted(groups):
        total = sum(groups[g].values())
        parts = "  ".join(f"{b}={n}" for b, n in sorted(groups[g].items()))
        print(f"  {g:14s} n={total:3d}  {parts}")


if __name__ == "__main__":
    main()
