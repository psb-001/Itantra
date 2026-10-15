"""
Does the conditioning actually help? Synthesise raw vs conditioned, transcribe back.

Every KEPT rule in `SpeechText` exists because a measurement showed the raw form failing. This
probe re-runs those measurements through the shipped voices so each claim in the code comments is
checkable in one command:

    1. loanwords   "स्कूल में emergency alert भेजो"   -> Devanagari spellings (Hindi voice)
    2. acronym     "send sos to the police"           -> "send S O S to the police" (English voice)
    3. control     a pure-Hindi sentence, unchanged
    4. control     English with digits, unchanged (digits are NOT normalised — see the note below)

VITS sampling is stochastic and our own ASR renders number words loosely, so every condition is
synthesised RUNS times; what matters is the mean, and the qualitative difference between the
heard forms. A single run is not evidence — that mistake is how a number-to-words rule briefly
got shipped and then removed (see `eval/probe_hindi_numbers.py`).

The conditioned strings are copied from the Kotlin unit tests (`SpeechTextTest`), which is exactly
what the app will speak.

ALSO: a diagnostic for the six Indic voices on raw digits. The Hindi voice reads "108" correctly
("ek so art" = एक सौ आठ, re-measured in `eval/probe_hindi_numbers.py`); this prints the same
round trip for the other voiced languages so a future digit complaint can be checked in one place.

Run:
    .venv/bin/python eval/probe_normaliser_roundtrip.py
"""

import difflib
import os

import numpy as np
import sherpa_onnx

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TTS_DIR = os.path.join(REPO, "tts")
ASSET_TTS_DIR = os.path.join(REPO, "app", "app", "src", "main", "assets", "tts")
MODEL_DIR = os.path.join(REPO, "models", "asr-v3-10lang",
                         "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8")

RUNS = 3

# (voice, raw text, conditioned text copied from the Kotlin tests, label)
PAIRS = [
    ("hi_IN-pratham-medium",
     "स्कूल में emergency alert भेजो",
     "स्कूल में इमरजेंसी अलर्ट भेजो",
     "1. english words in hindi -> devanagari"),
    ("en_US-lessac-medium",
     "send sos to the police",
     "send S O S to the police",
     "2. lowercase acronym -> letters"),
    ("hi_IN-pratham-medium",
     "पुलिस को बुलाओ, स्कूल में मदद भेजो",
     "पुलिस को बुलाओ, स्कूल में मदद भेजो",
     "3. hindi control (must not change)"),
    ("en_US-lessac-medium",
     "call 108 and tell them 500 people are at the school",
     "call 108 and tell them 500 people are at the school",
     "4. english + digits control (must not change)"),
]

DIGIT_DIAGNOSTIC = [
    ("hi_IN-pratham-medium", "known-good control"),
    ("bn_BD-google-medium", ""),
    ("mr_IN-google-medium", ""),
    ("ta_IN-rasa_female-medium", ""),
    ("te_IN-venkatesh-medium", ""),
    ("ml_IN-arjun-medium", ""),
]


def voice_dir(voice):
    """en/hi have dev copies in tts/; the rest live only in the app assets."""
    for d in (TTS_DIR, ASSET_TTS_DIR):
        if os.path.exists(os.path.join(d, f"{voice}.onnx")):
            return d
    raise SystemExit(f"no .onnx for {voice} in tts/ or assets/tts/")


def make_asr():
    return sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(MODEL_DIR, "model.int8.onnx"),
        tokens=os.path.join(MODEL_DIR, "tokens.txt"),
        num_threads=4,
        provider="cpu",
    )


def make_tts(voice):
    d = voice_dir(voice)
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=f"{d}/{voice}.onnx",
                tokens=f"{d}/{voice}.tokens",
                data_dir=f"{TTS_DIR}/espeak-ng-data"),
            num_threads=2, provider="cpu")))


def hear(asr, tts, text):
    audio = tts.generate(text, sid=0, speed=1.0)
    samples = np.asarray(audio.samples, dtype=np.float32)
    s = asr.create_stream()
    s.accept_waveform(audio.sample_rate, samples)
    asr.decode_stream(s)
    return s.result.text.strip()


def sim(a, b):
    return difflib.SequenceMatcher(None, a.lower(), b.lower()).ratio()


def main():
    asr = make_asr()
    engines = {}

    print("=" * 78)
    print("NORMALISER ROUND TRIP - raw vs conditioned, %d runs each" % RUNS)
    print("=" * 78)

    for voice, raw, cond, label in PAIRS:
        if voice not in engines:
            engines[voice] = make_tts(voice)
        tts = engines[voice]

        raw_backs, cond_backs = [], []
        for _ in range(RUNS):
            raw_backs.append(hear(asr, tts, raw))
            cond_backs.append(hear(asr, tts, cond))
        raw_mean = sum(sim(cond, b) for b in raw_backs) / RUNS
        cond_mean = sum(sim(cond, b) for b in cond_backs) / RUNS

        print(f"\n[{label}]  {voice}")
        print(f"  said raw  : {raw}")
        for b in raw_backs:
            print(f"    heard   : {b}   ({sim(cond, b):.2f})")
        print(f"    mean vs conditioned target: {raw_mean:.2f}")
        print(f"  said cond : {cond}")
        for b in cond_backs:
            print(f"    heard   : {b}   ({sim(cond, b):.2f})")
        print(f"    mean vs conditioned target: {cond_mean:.2f}")

    print()
    print("=" * 78)
    print("DIGIT DIAGNOSTIC - raw digits through every voiced language")
    print("=" * 78)
    for voice, note in DIGIT_DIAGNOSTIC:
        if voice not in engines:
            engines[voice] = make_tts(voice)
        back = hear(asr, engines[voice], "108 और 500")
        mark = f" ({note})" if note else ""
        print(f"  [{voice}]{mark}\n    heard: {back}")


if __name__ == "__main__":
    main()
