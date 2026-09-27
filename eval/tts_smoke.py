#!/usr/bin/env python3
"""
Confirm every bundled TTS voice actually produces audible speech.

WHY THIS EXISTS
---------------
Two ways a voice can be "present in the APK" and still silent:

  1. **Missing ONNX metadata.** Raw Piper models from HuggingFace have zero `metadata_props`,
     and sherpa-onnx then aborts *natively* on `'sample_rate' does not exist in the metadata`.
     A native abort kills the process with no Python traceback, so it reads as a crash rather
     than a config problem. `eval/fetch_tts_voices.py` patches the metadata; this checks it stuck.
  2. **Missing pronunciation dictionary.** espeak-ng loads `<lang>_dict` from `espeak-ng-data`
     to phonemise text. `eval/trim_espeak.py` deletes 107 dictionaries to save 16 MB. If it ever
     deleted a needed one, voices would go silent or fall back to garbage. This catches that.

Each voice is run in a SUBPROCESS on purpose. A native abort in sherpa-onnx takes the whole
interpreter with it, so testing all six in one process would report only the first failure and
hides the rest.

The check is on the AUDIO, not on the absence of an exception: a voice that "succeeds" while
emitting digital silence is exactly the failure this is looking for.

Usage:
    .venv/bin/python eval/tts_smoke.py
    .venv/bin/python eval/tts_smoke.py --tts-dir app/app/src/main/assets/tts
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PY = REPO / ".venv" / "bin" / "python"

# voice stem -> (language name, one emergency sentence in that language)
VOICES: dict[str, tuple[str, str]] = {
    "hi_IN-pratham-medium": ("Hindi", "यह एक आपातकालीन संदेश है, कृपया सुरक्षित स्थान पर जाएं"),
    "en_US-lessac-medium": ("English", "Emergency alert. Please move to a safe location."),
    "mr_IN-google-medium": ("Marathi", "हा एक आणीबाणीचा संदेश आहे, कृपया सुरक्षित जागी जा"),
    "bn_BD-google-medium": ("Bengali", "এটি একটি জরুরি বার্তা, অনুগ্রহ করে নিরাপদ স্থানে যান"),
    "ml_IN-arjun-medium": ("Malayalam", "ഇത് ഒരു അടിയന്തിര സന്ദേശമാണ്, ദയചെയുമായി സുരക്ഷിത സ്ഥലത്തേക്ക് പോകുക"),
    "te_IN-venkatesh-medium": ("Telugu", "ఇది అత్యవసర సందేశం, దయచేసి క్షేమస్థలానికి వెళ్ళండి"),
    "ta_IN-rasa_female-medium": ("Tamil", "இது ஒரு அவசர செய்தி, அபாயமில்லாமல் இருக்கவும்"),
}

# A synthesised emergency sentence is well above this. Silence or near-silence means the
# phonemiser failed and produced nothing, which still "succeeds" without raising.
MIN_RMS = 0.01
MIN_SECONDS = 0.5

# The Tamil voice ships a third as loud as the rest (peak 0.204 vs ~0.6-0.75), so the app applies
# a per-voice gain in TtsEngine.synthesise(). This test measures the RAW model output, before
# that gain, so its threshold must accept the quiet original rather than flag it as a failure.
#
# The gain itself is verified by the device test in app/evidence/, not here.
QUIET_VOICES = {"ta_IN-rasa_female-medium"}

CHILD = r'''
import sys, numpy as np, sherpa_onnx
tts_dir, voice, text, out_wav = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(
        model=f"{tts_dir}/{voice}.onnx",
        tokens=f"{tts_dir}/{voice}.tokens",
        data_dir=f"{tts_dir}/espeak-ng-data"),
    num_threads=2, provider="cpu")))
a = tts.generate(text, sid=0, speed=1.0)
s = np.asarray(a.samples, dtype=np.float32)
import wave
with wave.open(out_wav, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate)
    w.writeframes((np.clip(s, -1, 1) * 32767).astype(np.int16).tobytes())
print(f"{a.sample_rate} {len(s)/a.sample_rate:.3f} {np.sqrt((s**2).mean()):.5f} {np.abs(s).max():.4f}")
'''


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--tts-dir", type=Path, default=REPO / "app/app/src/main/assets/tts")
    ap.add_argument("--wav-out", type=Path, default=REPO / "tts/smoke")
    args = ap.parse_args()

    tts_dir: Path = args.tts_dir
    if not tts_dir.is_dir():
        sys.exit(f"tts dir not found: {tts_dir}")
    args.wav_out.mkdir(parents=True, exist_ok=True)

    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False) as f:
        f.write(CHILD)
        child = f.name

    print(f"voices: {tts_dir}\n")
    print(f"{'language':<11}{'voice':<28}{'secs':>7}{'rms':>9}{'peak':>8}   verdict")
    print("-" * 84)

    failures: list[str] = []
    for voice, (lang, text) in VOICES.items():
        onnx = tts_dir / f"{voice}.onnx"
        tokens = tts_dir / f"{voice}.tokens"
        if not onnx.is_file() or not tokens.is_file():
            print(f"{lang:<11}{voice:<28}{'-':>7}{'-':>9}{'-':>8}   MISSING FILES")
            failures.append(f"{lang}: missing .onnx or .tokens")
            continue

        wav = args.wav_out / f"{voice}.wav"
        try:
            r = subprocess.run(
                [str(PY), child, str(tts_dir), voice, text, str(wav)],
                capture_output=True, text=True, timeout=300,
            )
        except subprocess.TimeoutExpired:
            print(f"{lang:<11}{voice:<28}{'-':>7}{'-':>9}{'-':>8}   TIMEOUT")
            failures.append(f"{lang}: timed out")
            continue

        if r.returncode != 0:
            tail = (r.stderr or r.stdout).strip().splitlines()[-1:] or ["no output"]
            print(f"{lang:<11}{voice:<28}{'-':>7}{'-':>9}{'-':>8}   CRASH: {tail[0][:28]}")
            failures.append(f"{lang}: {tail[0][:80]}")
            continue

        try:
            sr, secs, rms, peak = r.stdout.strip().split()[-4:]
            secs, rms, peak = float(secs), float(rms), float(peak)
        except ValueError:
            print(f"{lang:<11}{voice:<28}{'-':>7}{'-':>9}{'-':>8}   UNREADABLE OUTPUT")
            failures.append(f"{lang}: unreadable child output")
            continue

        min_rms = MIN_RMS * 0.5 if voice in QUIET_VOICES else MIN_RMS
        if rms < min_rms:
            print(f"{lang:<11}{voice:<28}{secs:>7.2f}{rms:>9.4f}{peak:>8.3f}   SILENT")
            failures.append(f"{lang}: rms {rms:.5f} below {min_rms} (phonemiser likely broken)")
        elif secs < MIN_SECONDS:
            print(f"{lang:<11}{voice:<28}{secs:>7.2f}{rms:>9.4f}{peak:>8.3f}   TOO SHORT")
            failures.append(f"{lang}: only {secs:.2f}s of audio")
        else:
            print(f"{lang:<11}{voice:<28}{secs:>7.2f}{rms:>9.4f}{peak:>8.3f}   OK  -> {wav.name}")

    os.unlink(child)
    print("-" * 84)
    if failures:
        print(f"FAILED: {len(failures)} of {len(VOICES)} voices")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"RESULT: {len(VOICES)}/{len(VOICES)} voices synthesise audible speech")
    print(f"wavs in {args.wav_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
