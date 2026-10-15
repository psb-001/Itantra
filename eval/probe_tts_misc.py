"""
What actually needs normalising before TTS? Measure, do not guess.

The message text is always an ASR transcript, so its real alphabet is letters, digits and
punctuation — never emoji or currency symbols. This probe pushes the text shapes an emergency
transcript actually contains through the voices that will speak them, and transcribes the audio
back with our own ASR:

    digits      "call 108" / "स्कूल में 500 लोग"      does the voice read the number at all?
    acronyms    "sos" (what the CTC emits) / "SOS"    does case change anything?
    English inside Hindi  "स्कूल में emergency alert भेजो"  (read by the Hindi voice)

Run:
    .venv/bin/python eval/probe_tts_misc.py

CAVEAT, learned the hard way: VITS sampling is stochastic and our own ASR renders number words
loosely, so a SINGLE run is not evidence. The "digits are broken" reading this probe first
produced was re-measured with repeats in `eval/probe_hindi_numbers.py`, which showed the Hindi
voice reads "108" correctly — no number rule was kept. Treat every number in this file as a lead,
not a finding.
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
    ("en_US-lessac-medium", "call 108 and tell them 500 people are at the school", "digits, English"),
    ("en_US-lessac-medium", "send sos to the police", "acronym lowercase (what the CTC emits)"),
    ("en_US-lessac-medium", "send SOS to the police", "acronym uppercase"),
    ("hi_IN-pratham-medium", "स्कूल में 500 लोग हैं और 108 पर कॉल करो", "digits, Hindi"),
    ("hi_IN-pratham-medium", "स्कूल में emergency alert भेजो", "English words inside Hindi text"),
    ("hi_IN-pratham-medium", "पुलिस को बुलाओ, स्कूल में मदद भेजो", "Hindi control"),
    ("en_US-lessac-medium", "send 500 rupees to the school", "currency word (symbol is unreachable from ASR)"),
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
        audio = voices[voice].generate(text, sid=0, speed=1.0)
        samples = np.asarray(audio.samples, dtype=np.float32)

        s = asr.create_stream()
        s.accept_waveform(audio.sample_rate, samples)
        asr.decode_stream(s)
        back = s.result.text.strip()

        same = script_kind(text) == script_kind(back)
        sim = ("similarity %.2f" % difflib.SequenceMatcher(None, text.lower(), back.lower()).ratio()
               if same else "script changed")
        print(f"[{label}]  {voice.split('-')[0]}")
        print(f"  said  : {text}")
        print(f"  heard : {back}   ({sim})")
        print()


if __name__ == "__main__":
    main()
