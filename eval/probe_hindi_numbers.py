"""
Do the Hindi number WORDS actually sound right, or is it only the digits that break?

ANSWER, 15 Oct 2026: neither breaks. Digits are read correctly by the Hindi voice — "108" comes
back as "ek so art" (एक सौ आठ), and "108 पर कॉल करो" comes back as "एकसौ आठ पर कॉल करो". The
"digits are broken" reading that started this investigation was an artefact of single-run
measurement: VITS sampling is stochastic and our own ASR renders number words loosely. For that
reason the number-to-words expander built on the bad reading was REMOVED from SpeechText.

The probe is kept as the record of the corrected measurement — re-run it before trusting any
future claim about digits.

Run:
    .venv/bin/python eval/probe_hindi_numbers.py
"""

import io
import os

import numpy as np
import sherpa_onnx

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TTS_DIR = os.path.join(REPO, "tts")
MODEL_DIR = os.path.join(REPO, "models", "asr-v3-10lang",
                         "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8")

VOICE = "hi_IN-pratham-medium"

CASES = [
    ("words ", "पाँच सौ"),                  # candrabindu spelling, the one SpeechText emits
    ("words ", "पांच सौ"),                  # the everyday spelling
    ("words ", "एक सौ आठ"),
    ("words ", "एक सौ आठ पर कॉल करो"),
    ("words ", "एक हज़ार लोग"),
    ("digits", "500"),
    ("digits", "108"),
    ("digits", "108 पर कॉल करो"),
]
RUNS = 3


def main():
    asr = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(MODEL_DIR, "model.int8.onnx"),
        tokens=os.path.join(MODEL_DIR, "tokens.txt"),
        num_threads=4,
        provider="cpu",
    )
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=f"{TTS_DIR}/{VOICE}.onnx",
                tokens=f"{TTS_DIR}/{VOICE}.tokens",
                data_dir=f"{TTS_DIR}/espeak-ng-data"),
            num_threads=2, provider="cpu")))

    for kind, text in CASES:
        print(f"[{kind}]  {text}")
        heard = []
        for _ in range(RUNS):
            with io.StringIO() as buf:  # the resampler note goes to Python stderr
                pass
            audio = tts.generate(text, sid=0, speed=1.0)
            samples = np.asarray(audio.samples, dtype=np.float32)
            s = asr.create_stream()
            s.accept_waveform(audio.sample_rate, samples)
            asr.decode_stream(s)
            back = s.result.text.strip()
            heard.append(back)
            print(f"    run: {back}")
        stable = "  (all runs identical)" if len(set(heard)) == 1 else ""
        print(f"    -> {len(set(heard))}/{RUNS} distinct renderings{stable}")
        print()


if __name__ == "__main__":
    main()
