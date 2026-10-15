"""
Does the tag/voice mismatch actually mangle English? Voice-in, voice-out.

probe_en_script.py showed English speech is transcribed in Latin script (22/22 clips) and Hindi
in Devanagari (6/6). With `langTag` hardcoded to HINDI, a Latin English message is still sent --
Latin characters are allowed alongside every expected script, for loanwords -- and it is tagged
`hin_Deva`, so the receiving phone speaks it with the HINDI voice.

This probe renders both pairings through the shipped models and transcribes the audio back, so
the mangling is visible as text:

    hi voice + Latin English   <- what the receiver does today
    en voice + Latin English   <- what it should do

The transcripts are the evidence; a similarity number is only printed when input and output share
a script (cross-script comparison would be meaningless).

Run:
    .venv/bin/python eval/probe_voice_mismatch.py
"""

import difflib
import os

import numpy as np
import sherpa_onnx

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TTS_DIR = os.path.join(REPO, "tts")
MODEL_DIR = os.path.join(REPO, "models", "asr-v3-10lang",
                         "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8")

CASES = [
    ("hi_IN-pratham-medium", "hello i am fine how are you", "TODAY'S PATH: Hindi voice reads Latin English"),
    ("en_US-lessac-medium", "hello i am fine how are you", "correct pairing"),
    ("hi_IN-pratham-medium", "हैलो आई एम फाइन", "Hindi voice reads Devanagari-English"),
    ("en_US-lessac-medium", "हैलो आई एम फाइन", "cross check: English voice reads Devanagari"),
    ("hi_IN-pratham-medium", "पुलिस को बुलाओ, स्कूल में मदद भेजो", "Hindi control"),
    ("hi_IN-pratham-medium", "the bridge has collapsed please send help to the school",
     "TODAY'S PATH: longer English on the Hindi voice"),
    ("en_US-lessac-medium", "the bridge has collapsed please send help to the school",
     "correct pairing: longer English"),
]


def script_kind(text):
    deva = sum(1 for c in text if "\u0900" <= c <= "\u097F")
    latin = sum(1 for c in text if c.isascii() and c.isalpha())
    if deva > latin:
        return "deva"
    if latin > 0:
        return "latin"
    return "other"


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
        tts = voices[voice]
        audio = tts.generate(text, sid=0, speed=1.0)
        samples = np.asarray(audio.samples, dtype=np.float32)

        s = asr.create_stream()
        s.accept_waveform(audio.sample_rate, samples)
        asr.decode_stream(s)
        back = s.result.text.strip()

        same = script_kind(text) == script_kind(back)
        sim = ""
        if same:
            sim = f"  similarity {difflib.SequenceMatcher(None, text.lower(), back.lower()).ratio():.2f}"
        print(f"{label}")
        print(f"  voice : {voice.split('-')[0]}   ({len(samples)/audio.sample_rate:.2f}s)")
        print(f"  said  : {text}")
        print(f"  heard : {back}{sim}")
        print()


if __name__ == "__main__":
    main()
