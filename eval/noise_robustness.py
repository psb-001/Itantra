"""Noise robustness — how much accuracy do we lose when the audio is not studio-clean?

WHY THIS EXISTS
Every accuracy number in this project comes from FLEURS, which is clean studio speech.
The deck lists "clean-studio accuracy is not disaster-zone accuracy" as a RISK. That is honest,
but a risk is a guess. This turns it into a measurement.

WHAT IT DOES
Takes real FLEURS clips and mixes in controlled noise at defined signal-to-noise ratios, then
re-runs the same ASR model and recomputes CER. The result is a degradation curve.

Two noise types, because they fail differently:
  - WHITE  : flat spectrum. The textbook worst case, and what "noisy" usually means.
  - PINK   : 1/f spectrum, closer to traffic, wind and crowd rumble.

SNR levels: 20 dB (mild), 10 dB (moderate), 5 dB (bad), 0 dB (severe).
For reference, a quiet office is roughly 30-40 dB SNR, a busy street 5-15 dB.

WHAT THIS IS NOT
Synthetic noise is not a disaster zone. This measures *sensitivity* to noise, not real-world
accuracy. The submission must still say "field accuracy in noise is unmeasured" unless someone
records actual field audio.

Run from the repo root:
    output/option_a_stt/.venv/bin/python output/option_a_stt/noise_robustness.py [n_clips] [lang...]
    output/option_a_stt/.venv/bin/python output/option_a_stt/noise_robustness.py 100 hi_in ta_in en_us
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
MODEL_DIR = os.environ.get(
    "OMNI_DIR", "omni300m_v2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05"
)
MODEL = os.path.join(BASE, MODEL_DIR, "model.int8.onnx")
TOKENS = os.path.join(BASE, MODEL_DIR, "tokens.txt")
FLEURS = os.path.join(BASE, "fleurs")
SR = 16000

LANGUAGES = [("Hindi", "hi_in"), ("Tamil", "ta_in"), ("English", "en_us"), ("Bengali", "bn_in")]

# The script each language is SUPPOSED to produce. The model is a 1,600-language character-level
# CTC model and will happily switch script under noise — so measuring "is the output even in the
# right alphabet?" matters more for this app than CER does.
EXPECTED_SCRIPT = {
    "hi_in": "DEVANAGARI",
    "ta_in": "TAMIL",
    "en_us": "LATIN",
    "bn_in": "BENGALI",
}


def dominant_script(text: str) -> str:
    """Most common Unicode script among the letters of `text`."""
    counts = {}
    for ch in text:
        if not ch.isalpha():
            continue
        try:
            name = unicodedata.name(ch, "")
        except ValueError:
            continue
        for s in ("DEVANAGARI", "BENGALI", "GURMUKHI", "GUJARATI", "ORIYA", "TAMIL", "TELUGU",
                  "KANNADA", "MALAYALAM", "SINHALA", "ARABIC", "CYRILLIC", "GREEK", "HEBREW",
                  "THAI", "ETHIOPIC", "MYANMAR", "KHMER", "TIBETAN", "HANGUL", "HIRAGANA",
                  "KATAKANA", "CJK", "LATIN"):
            if name.startswith(s):
                counts[s] = counts.get(s, 0) + 1
                break
    if not counts:
        return "NONE"
    return max(counts, key=counts.get)

# 30 dB is included deliberately: noise 1000x weaker than the signal should barely change the
# result. If CER at 30 dB is far above clean, the SNR scaling is wrong and the rest is suspect.
SNR_LEVELS = [None, 30, 20, 15, 10, 5, 0]      # None = clean baseline
NOISE_TYPES = ["white", "pink"]

ZERO_WIDTH = dict.fromkeys(map(ord, "\u200b\u200c\u200d\u2060\ufeff"), None)


# ---------------------------------------------------------------- scoring

def normalize(text: str) -> str:
    text = unicodedata.normalize("NFC", text)
    text = text.translate(ZERO_WIDTH)
    text = "".join(" " if unicodedata.category(c).startswith("P") else c for c in text)
    return re.sub(r"\s+", " ", text.casefold()).strip()


def edit_distance(a, b):
    a, b = list(a), list(b)
    if not a:
        return len(b)
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1,
                         prev[j - 1] + (0 if a[i - 1] == b[j - 1] else 1))
        prev = cur
    return prev[len(b)]


def cer(ref: str, hyp: str) -> float:
    r, h = ref.replace(" ", ""), hyp.replace(" ", "")
    return edit_distance(r, h) / len(r) if r else 0.0


# ---------------------------------------------------------------- audio

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
    a = (np.frombuffer(data, dtype=np.float32) if bits == 32
         else np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0)
    if channels == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return np.ascontiguousarray(a, dtype=np.float32), sr


def pink_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    """1/f noise via spectral shaping. Closer to real ambient noise than white."""
    white = rng.normal(0, 1, n)
    spec = np.fft.rfft(white)
    freqs = np.fft.rfftfreq(n, d=1.0 / SR)
    freqs[0] = freqs[1] if len(freqs) > 1 else 1.0
    spec /= np.sqrt(freqs)               # 1/sqrt(f) in amplitude -> 1/f in power
    out = np.fft.irfft(spec, n)
    return out.astype(np.float32)


def add_noise(signal: np.ndarray, snr_db: float, kind: str,
              rng: np.random.Generator) -> np.ndarray:
    """Mix noise into the signal at the requested SNR (signal power / noise power)."""
    sig_power = float(np.mean(signal ** 2))
    if sig_power <= 0:
        return signal
    noise = (rng.normal(0, 1, len(signal)).astype(np.float32) if kind == "white"
             else pink_noise(len(signal), rng))
    noise_power = float(np.mean(noise ** 2))
    if noise_power <= 0:
        return signal
    target_noise_power = sig_power / (10.0 ** (snr_db / 10.0))
    noise = noise * np.sqrt(target_noise_power / noise_power)
    return np.clip(signal + noise, -1.0, 1.0).astype(np.float32)


# ---------------------------------------------------------------- main

def load_clips(code, n):
    tsv = os.path.join(FLEURS, f"{code}_test.tsv")
    if not os.path.exists(tsv):
        return []
    rows = []
    with open(tsv, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 4:
                rows.append((parts[1], parts[2]))
    out = []
    for fn, ref in rows[:n]:
        for cand in (os.path.join(FLEURS, f"test-{code}", fn), os.path.join(FLEURS, "test", fn)):
            if os.path.exists(cand):
                out.append((cand, ref))
                break
    return out


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    wanted = sys.argv[2:] or None
    langs = [x for x in LANGUAGES if not wanted or x[1] in wanted]

    print("=" * 78)
    print("NOISE ROBUSTNESS — how much accuracy survives real-world noise?")
    print("=" * 78)
    print(f"model    : {os.path.basename(MODEL_DIR)}")
    print(f"clips    : {n} per language per condition")
    print(f"languages: {', '.join(l for l, _ in langs)}")
    print(f"SNR      : {SNR_LEVELS}  (None = clean)")
    print(f"noise    : {NOISE_TYPES}")
    print(f"total inferences: {len(langs) * n * (1 + len(SNR_LEVELS[1:]) * len(NOISE_TYPES))}")
    print()

    rec = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=MODEL, tokens=TOKENS, num_threads=8, provider="cpu", debug=False
    )

    report = {
        "model": os.path.basename(MODEL_DIR),
        "n_per_condition": n,
        "snr_levels": SNR_LEVELS,
        "noise_types": NOISE_TYPES,
        "caveat": ("Synthetic noise, not field recordings. Measures sensitivity to noise, "
                   "not real-world disaster-zone accuracy."),
        "results": [],
    }

    def transcribe(samples):
        stream = rec.create_stream()
        stream.accept_waveform(SR, samples)
        rec.decode_stream(stream)
        return stream.result.text.strip()

    for lang, code in langs:
        clips = load_clips(code, n)
        if not clips:
            print(f"[{lang}] no clips — skipping")
            continue

        row = {"language": lang, "fleurs_config": code, "n": len(clips), "conditions": {}}

        # clean baseline
        t0 = time.perf_counter()
        clean_cers = []
        clean_ok = []
        for path, ref in clips:
            audio, sr = read_wav(path)
            if sr != SR:
                m = int(len(audio) * SR / sr)
                audio = np.interp(np.linspace(0, len(audio) - 1, m),
                                  np.arange(len(audio)), audio).astype(np.float32)
            hyp = transcribe(audio)
            clean_cers.append(cer(normalize(ref), normalize(hyp)))
            clean_ok.append(dominant_script(hyp) == EXPECTED_SCRIPT.get(code, "?"))
        base = float(np.mean(clean_cers))
        base_ok = 100.0 * sum(clean_ok) / len(clean_ok)
        row["conditions"]["clean"] = {
            "mean_cer": round(base, 4),
            "script_retention_pct": round(base_ok, 1),
        }
        print(f"[{lang}] clean                CER {base:.4f}  script OK {base_ok:5.1f}%"
              f"   ({time.perf_counter()-t0:.0f}s)")

        # noisy conditions
        for kind in NOISE_TYPES:
            for snr in SNR_LEVELS[1:]:
                rng = np.random.default_rng(1234 + snr)   # fixed seed -> reproducible
                cers = []
                script_ok = []
                for path, ref in clips:
                    audio, sr = read_wav(path)
                    if sr != SR:
                        m = int(len(audio) * SR / sr)
                        audio = np.interp(np.linspace(0, len(audio) - 1, m),
                                          np.arange(len(audio)), audio).astype(np.float32)
                    noisy = add_noise(audio, snr, kind, rng)
                    hyp = transcribe(noisy)
                    cers.append(cer(normalize(ref), normalize(hyp)))
                    script_ok.append(dominant_script(hyp) == EXPECTED_SCRIPT.get(code, "?"))
                mean = float(np.mean(cers))
                delta = mean - base
                ok = 100.0 * sum(script_ok) / len(script_ok)
                row["conditions"][f"{kind}_{snr}db"] = {
                    "mean_cer": round(mean, 4),
                    "script_retention_pct": round(ok, 1),
                    "delta_vs_clean": round(delta, 4),
                    "relative_increase_pct": round(100 * delta / base, 1) if base > 0 else None,
                }
                print(f"[{lang}] {kind:5s} {snr:2d} dB SNR    CER {mean:.4f}  "
                      f"script OK {ok:5.1f}%  ({delta:+.4f} vs clean)")

        report["results"].append(row)
        print()

    # ---- summary table ----
    print("=" * 78)
    print("SUMMARY — mean CER across languages")
    print("=" * 78)
    header = f"{'condition':16s}" + "".join(f"{r['language']:>10s}" for r in report["results"])
    print(header)
    for cond in ["clean"] + [f"{k}_{s}db" for k in NOISE_TYPES for s in SNR_LEVELS[1:]]:
        line = f"{cond:16s}"
        for r in report["results"]:
            c = r["conditions"].get(cond)
            line += f"{c['mean_cer']:>10.4f}" if c else f"{'-':>10s}"
        print(line)
    print()
    print("script retention (% of outputs in the EXPECTED alphabet — the app-relevant metric)")
    print(header)
    for cond in ["clean"] + [f"{k}_{s}db" for k in NOISE_TYPES for s in SNR_LEVELS[1:]]:
        line = f"{cond:16s}"
        for r in report["results"]:
            c = r["conditions"].get(cond)
            line += f"{c['script_retention_pct']:>10.1f}" if c else f"{'-':>10s}"
        print(line)

    with open(os.path.join(BASE, "noise_robustness.json"), "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print("\nWrote noise_robustness.json")


if __name__ == "__main__":
    main()
