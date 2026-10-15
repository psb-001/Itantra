"""
Hinglish routing: when a Devanagari sentence carries English words, which voice wins?

Case A is the one that matters ("स्कूल में emergency alert भेजो"): it is LATIN-dominant by letter
count (14 vs 6), so the current dominance rule routes it to the ENGLISH voice — while the fix for
its mangled English words (SpeechText's loanword map) only runs under the Hindi voice. This probe
renders every combination, voice-in -> our own ASR back, so the decision to bias the router is
made on numbers:

    a. en voice  <- raw A                    (what dominance gives today)
    b. hi voice  <- raw A                    (Hindi voice, unmapped Latin words)
    c. hi voice  <- mapped A                 (Hindi voice + SpeechText's loanword map)
    d. hi voice  <- "the police went to स्कूल"  (Latin sentence with one Hindi word)
    e. en voice  <- "the police went to स्कूल"  (the alternative for that sentence)

Run:
    .venv/bin/python eval/probe_hinglish_routing.py
"""

import difflib
import os

import numpy as np
import sherpa_onnx

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TTS_DIR = os.path.join(REPO, "tts")
MODEL_DIR = os.path.join(REPO, "models", "asr-v3-10lang",
                         "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8")

RAW_A = "स्कूल में emergency alert भेजो"
MAPPED_A = "स्कूल में इमरजेंसी अलर्ट भेजो"
C = "the police went to स्कूल"

CASES = [
    ("en_US-lessac-medium", RAW_A, "A: en voice, raw (today's dominance routing)"),
    ("hi_IN-pratham-medium", RAW_A, "A: hi voice, raw"),
    ("hi_IN-pratham-medium", MAPPED_A, "A: hi voice, loanword-mapped"),
    ("hi_IN-pratham-medium", C, "C: hi voice, Latin sentence + one Hindi word"),
    ("en_US-lessac-medium", C, "C: en voice, same sentence"),
]


def main():
    asr = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(MODEL_DIR, "model.int8.onnx"),
        tokens=os.path.join(MODEL_DIR, "tokens.txt"),
        num_threads=4,
        provider="cpu",
    )
    voices = {}
    for voice, text, label in CASES:
        if voice not in voices:
            voices[voice] = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
                model=sherpa_onnx.OfflineTtsModelConfig(
                    vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                        model=f"{TTS_DIR}/{voice}.onnx",
                        tokens=f"{TTS_DIR}/{voice}.tokens",
                        data_dir=f"{TTS_DIR}/espeak-ng-data"),
                    num_threads=2, provider="cpu")))
        audio = voices[voice].generate(text, sid=0, speed=1.0)
        samples = np.asarray(audio.samples, dtype=np.float32)

        s = asr.create_stream()
        s.accept_waveform(audio.sample_rate, samples)
        asr.decode_stream(s)
        back = s.result.text.strip()

        deva_back = sum(1 for c in back if "\u0900" <= c <= "\u097F")
        target = MAPPED_A
        target_is_deva = deva_back > 0 and all(c.isascii() is False or not c.isalpha() for c in back) is False
        sim = (difflib.SequenceMatcher(None, target, back).ratio()
               if sum(1 for c in back if c.isascii() and c.isalpha()) < deva_back else None)
        print(f"[{label}]")
        print(f"  said  : {text}")
        print(f"  heard : {back}")
        print(f"  vs intended Hindi sentence: " + (f"similarity {sim:.2f}" if sim is not None else "English-script output"))
        print()


if __name__ == "__main__":
    main()
