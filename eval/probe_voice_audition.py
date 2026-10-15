"""
Voice upgrade audition: is a stronger English voice worth its bytes?

The bundled English voice (en_US-lessac-medium) reads clean text well (Addendum 1), but every word
people actually hear comes out of one of the shipped voices. Three candidates, all fully offline
ONNX, auditioned against the baseline on the same bench:

    en_US-lessac-high       ~114 MB   same speaker as the baseline, "high" quality — safest swap
    en_US-ryan-high          ~89 MB   different (male) speaker, "high" quality
    Kokoro int8              ~59 MB   model ~30 MB + voices ~28 MB + tokens/lexicon, many voices
                                      in one file, Apache-2.0, 24 kHz

This probe synthesises a small English bench with each voice (RUNS times each), transcribes it back
with the same ASR the app uses, and reports mean similarity + synthesis wall time + real-time
factor. Measured on this machine (macOS arm64, CPU); a phone will be slower — the ranking and the
gap to the baseline are the point, not the absolute seconds.

Kokoro holds many speakers in one voices.bin. Speaker ids are trialled on one sentence first and
the best English sid (by ASR-back similarity) carries into the bench; all trials are printed.

Run:
    .venv/bin/python eval/probe_voice_audition.py
"""

import difflib
import os
import time

import numpy as np
import sherpa_onnx

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TTS_DIR = os.path.join(REPO, "tts")
MODEL_DIR = os.path.join(REPO, "models", "asr-v3-10lang",
                         "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8")
AUDITION_DIR = os.environ.get(
    "AUDITION_DIR",
    "/private/var/folders/v6/y389hvkd5vbdfjpc1555v58c0000gn/T/opencode/voices-audition")

BENCH = [
    "hello i am fine how are you",
    "the bridge has collapsed please send help to the school",
    "call 108 and tell them 500 people are at the school",
    "the river water is rising send a rescue team to the village",
]
RUNS = 2
KOKORO_SID_TRIAL = [0, 1, 2, 3]
KOKORO_SID_SENTENCE = "the bridge has collapsed please send help to the school"


def make_asr():
    return sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(MODEL_DIR, "model.int8.onnx"),
        tokens=os.path.join(MODEL_DIR, "tokens.txt"),
        num_threads=4,
        provider="cpu",
    )


def vits_config(model, tokens):
    return sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=model, tokens=tokens, data_dir=os.path.join(TTS_DIR, "espeak-ng-data")),
            num_threads=2, provider="cpu"))


def kokoro_config():
    """This python build may not know every Kokoro kwarg; try the full set, then shed keys."""
    base = dict(
        model=os.path.join(AUDITION_DIR, "kokoro-model.int8.onnx"),
        voices=os.path.join(AUDITION_DIR, "kokoro-voices.bin"),
        tokens=os.path.join(AUDITION_DIR, "kokoro-tokens.txt"),
    )
    attempts = [
        dict(base, data_dir=os.path.join(TTS_DIR, "espeak-ng-data"),
             lexicon=os.path.join(AUDITION_DIR, "kokoro-lexicon-us-en.txt"), lang="en-us"),
        dict(base, data_dir=os.path.join(TTS_DIR, "espeak-ng-data"),
             lexicon=os.path.join(AUDITION_DIR, "kokoro-lexicon-us-en.txt")),
        dict(base, data_dir=os.path.join(TTS_DIR, "espeak-ng-data"), lang="en-us"),
        dict(base, data_dir=os.path.join(TTS_DIR, "espeak-ng-data")),
        dict(base),
    ]
    last = None
    for kw in attempts:
        try:
            cfg = sherpa_onnx.OfflineTtsKokoroModelConfig(**kw)
            print(f"  kokoro config accepted: {sorted(kw)}")
            return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
                model=sherpa_onnx.OfflineTtsModelConfig(kokoro=cfg, num_threads=2, provider="cpu")))
        except TypeError as e:  # unknown kwarg in this build
            last = e
    raise last


def build(name):
    """Return (tts, size_mb, load_seconds) for one candidate name."""
    t0 = time.perf_counter()
    if name == "lessac-medium":
        tts = sherpa_onnx.OfflineTts(vits_config(
            os.path.join(TTS_DIR, "en_US-lessac-medium.onnx"),
            os.path.join(TTS_DIR, "en_US-lessac-medium.tokens")))
        size = os.path.getsize(os.path.join(TTS_DIR, "en_US-lessac-medium.onnx"))
    elif name in ("lessac-high", "ryan-high"):
        tts = sherpa_onnx.OfflineTts(vits_config(
            os.path.join(AUDITION_DIR, f"{name}.onnx"),
            os.path.join(AUDITION_DIR, f"{name}.tokens")))
        size = os.path.getsize(os.path.join(AUDITION_DIR, f"{name}.onnx"))
    elif name == "kokoro":
        tts = kokoro_config()
        size = sum(os.path.getsize(os.path.join(AUDITION_DIR, f))
                   for f in ("kokoro-model.int8.onnx", "kokoro-voices.bin", "kokoro-tokens.txt"))
    else:
        raise SystemExit(f"unknown voice {name}")
    return tts, size / 1e6, time.perf_counter() - t0


def hear(asr, tts, text, sid=0):
    t0 = time.perf_counter()
    audio = tts.generate(text, sid=sid, speed=1.0)
    dt = time.perf_counter() - t0
    samples = np.asarray(audio.samples, dtype=np.float32)
    s = asr.create_stream()
    s.accept_waveform(audio.sample_rate, samples)
    asr.decode_stream(s)
    seconds = len(samples) / audio.sample_rate
    return s.result.text.strip(), dt, seconds


def sim(a, b):
    return difflib.SequenceMatcher(None, a.lower(), b.lower()).ratio()


def main():
    asr = make_asr()

    print("=" * 78)
    print("KOKORO SPEAKER TRIAL — which sid is English and intelligible")
    print("=" * 78)
    kokoro, kokoro_size, kokoro_load = build("kokoro")
    best_sid, best_sim = 0, -1.0
    for sid in KOKORO_SID_TRIAL:
        back, dt, secs = hear(asr, kokoro, KOKORO_SID_SENTENCE, sid=sid)
        s = sim(KOKORO_SID_SENTENCE, back)
        print(f"  sid {sid}: {s:.2f}  ({dt:.2f}s synth)  heard: {back}")
        if s > best_sim:
            best_sid, best_sim = sid, s
    print(f"  -> sid {best_sid} carries into the bench")

    voices = ["lessac-medium", "lessac-high", "ryan-high", "kokoro"]
    results = {}
    for name in voices:
        try:
            if name == "kokoro":
                tts, size_mb, load_s = kokoro, kokoro_size, kokoro_load
            else:
                tts, size_mb, load_s = build(name)
        except Exception as e:  # a broken download must not sink the whole audition
            print(f"\n{name}: FAILED TO LOAD — {e}")
            continue

        sid = best_sid if name == "kokoro" else 0

        print()
        print("=" * 78)
        print(f"{name}  ({size_mb:.0f} MB, loaded in {load_s:.1f}s)")
        print("=" * 78)

        sims, dts, rts = [], [], []
        for text in BENCH:
            backs = []
            for _ in range(RUNS):
                back, dt, secs = hear(asr, tts, text, sid=sid)
                backs.append(back)
                dts.append(dt)
                rts.append(dt / secs if secs else 0.0)
            mean = sum(sim(text, b) for b in backs) / RUNS
            sims.append(mean)
            for b in backs:
                print(f"  [{mean:.2f}] {b}")
        result = (sum(sims) / len(sims), sum(dts) / len(dts), sum(rts) / len(rts), size_mb, load_s)
        results[name] = result
        print(f"  -> mean similarity {result[0]:.3f}   mean synth {result[1]:.2f}s   "
              f"real-time factor {result[2]:.2f}")

    print()
    print("=" * 78)
    print("SUMMARY (macOS arm64, CPU — phone numbers will differ; ranking is the point)")
    print("=" * 78)
    print(f"{'voice':<16}{'mean sim':>9}{'synth s':>9}{'rtf':>7}{'size MB':>9}{'load s':>8}")
    for name in voices:
        if name not in results:
            print(f"{name:<16}{'FAILED TO LOAD':>42}")
            continue
        s, d, rtf, size, load = results[name]
        print(f"{name:<16}{s:>9.3f}{d:>9.2f}{rtf:>7.2f}{size:>9.0f}{load:>8.1f}")


if __name__ == "__main__":
    main()
