"""TTS intelligibility via ASR round-trip — SIH26173.

The dossier lists "TTS human legibility" as NOT DONE because it "requires a human
listening panel". True for the final number, but not for a first pass. This script
produces an automated proxy:

    sentence -> Piper TTS -> audio -> Omnilingual ASR -> text -> compare to input

If the ASR recovers the sentence from Piper's audio, a human listener almost
certainly understands it. This is a *proxy*, not a substitute for a listening
panel. Label it that way everywhere it is reported.

It also measures peak RSS with the ASR model and BOTH TTS voices loaded at once,
which is the "~1.1 GiB" figure the dossier quotes without a backing file.

Audio is written to tts/intelligibility/ so a human panel can score it later.

Run from the repo root:
    output/option_a_stt/.venv/bin/python output/option_a_stt/tts_intelligibility.py
"""
import json
import os
import re
import resource
import time
import unicodedata

import numpy as np
import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
M = os.path.join(BASE, "omni300m")
D = os.path.join(BASE, "tts")
OUT = os.path.join(D, "intelligibility")
ASR_SR = 16000

HINDI = [
    "बाढ़ की चेतावनी जारी की गई है, ऊपरी मंजिल पर रहें।",
    "यह एक आपातकालीन संदेश है, सुरक्षित स्थान पर जाएं।",
    "भूकंप के बाद क्षेत्र में प्रवेश न करें।",
    "राहत शिविर में भोजन और पानी उपलब्ध है।",
    "दो सौ लोगों को सुरक्षित निकाल लिया गया है।",
    "पुल टूट गया है, नदी के रास्ते से जाएं।",
    "डॉक्टर शर्मा को तुरंत अस्पताल भेजा जाए।",
    "अग्निशमन दल पंद्रह मिनट में पहुंचेगा।",
    "बिजली की लाइन गिरी हुई है, उससे दूर रहें।",
    "सभी लोग निकासी केंद्र पर रिपोर्ट करें।",
    "आज मौसम साफ रहेगा और तापमान तीस डिग्री रहेगा।",
    "गाड़ी सुबह सात बजे रवाना होगी।",
    "अस्पताल में बीस बिस्तर खाली हैं।",
    "भोजन का राशन कल शाम तक पहुंच जाएगा।",
    "गांव का नाम रामपुर है।",
    "कृपया शांति बनाए रखें और अफवाह न फैलाएं।",
    "पानी उबालकर पिएं।",
    "घायलों को प्राथमिक चिकित्सा दी जा रही है।",
    "अगले आदेश तक इंतजार करें।",
    "संपर्क नंबर एक दो तीन चार पांच है।",
]

ENGLISH = [
    "A flood warning has been issued. Stay on the upper floor.",
    "This is an emergency message. Move to a safe location.",
    "Do not enter the area after the earthquake.",
    "Food and water are available at the relief camp.",
    "Two hundred people have been moved to safety.",
    "The bridge has collapsed. Take the river route.",
    "Send Doctor Sharma to the hospital immediately.",
    "The fire brigade will arrive in fifteen minutes.",
    "A power line has fallen. Stay away from it.",
    "Everyone must report to the evacuation centre.",
    "The weather will be clear today with a temperature of thirty degrees.",
    "The bus leaves at seven in the morning.",
    "Twenty beds are free at the hospital.",
    "The food ration will arrive by tomorrow evening.",
    "The name of the village is Rampur.",
    "Please remain calm and do not spread rumours.",
    "Boil the water before drinking it.",
    "The injured are receiving first aid.",
    "Wait until further orders.",
    "The contact number is one two three four five.",
]

ZERO_WIDTH = dict.fromkeys(map(ord, "\u200b\u200c\u200d\u2060\ufeff"), None)


def normalize(text):
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
            cur[j] = min(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + (0 if a[i - 1] == b[j - 1] else 1),
            )
        prev = cur
    return prev[len(b)]


def wer(ref, hyp):
    r, h = ref.split(), hyp.split()
    return (edit_distance(r, h) / len(r)) if r else (0.0 if not h else 1.0)


def cer(ref, hyp):
    r, h = ref.replace(" ", ""), hyp.replace(" ", "")
    return (edit_distance(r, h) / len(r)) if r else (0.0 if not h else 1.0)


def peak_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def resample(a, src_sr, dst_sr):
    if src_sr == dst_sr:
        return a
    n = int(len(a) * dst_sr / src_sr)
    idx = np.linspace(0, len(a) - 1, n)
    return np.interp(idx, np.arange(len(a)), a).astype(np.float32)


def write_wav(path, samples, sr):
    import struct

    pcm = (np.clip(samples, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
    with open(path, "wb") as f:
        f.write(b"RIFF")
        f.write(struct.pack("<I", 36 + len(pcm)))
        f.write(b"WAVEfmt ")
        f.write(struct.pack("<IHHIIHH", 16, 1, 1, sr, sr * 2, 2, 16))
        f.write(b"data")
        f.write(struct.pack("<I", len(pcm)))
        f.write(pcm)


def load_tts(model, tokens):
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=os.path.join(D, model),
                lexicon="",
                tokens=os.path.join(D, tokens),
                data_dir=os.path.join(D, "espeak-ng-data"),
            ),
            num_threads=4,
            debug=False,
            provider="cpu",
        )
    )
    return sherpa_onnx.OfflineTts(cfg)


def main():
    os.makedirs(OUT, exist_ok=True)

    print("Loading ASR + both TTS voices ...", flush=True)
    asr = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(M, "model.int8.onnx"),
        tokens=os.path.join(M, "tokens.txt"),
        num_threads=8,
        provider="cpu",
        debug=False,
    )
    hi_tts = load_tts("hi_IN-pratham-medium.onnx", "hi_IN-pratham-medium.tokens")
    en_tts = load_tts("en_US-lessac-medium.onnx", "en_US-lessac-medium.tokens")

    rss_all_models = peak_mb()
    print(f"all three models loaded — peak RSS {rss_all_models:.0f} MiB\n", flush=True)

    report = {
        "metric": "TTS intelligibility via ASR round-trip",
        "caveat": (
            "Automated proxy, NOT a human listening panel. If ASR recovers the "
            "sentence from Piper audio, a human almost certainly understands it. "
            "The submission must still report a human panel for the final number."
        ),
        "machine": "macOS arm64 (NOT Android)",
        "peak_rss_mb_all_models": round(rss_all_models, 1),
        "languages": {},
    }

    for lang, sentences, tts, tag in (
        ("Hindi", HINDI, hi_tts, "hi"),
        ("English", ENGLISH, en_tts, "en"),
    ):
        rows = []
        for i, text in enumerate(sentences):
            t0 = time.perf_counter()
            audio = tts.generate(text=text, sid=0, speed=1.0)
            tts_s = time.perf_counter() - t0
            samples = np.array(audio.samples, dtype=np.float32)
            dur = len(samples) / audio.sample_rate

            path = os.path.join(OUT, f"{tag}_{i:02d}.wav")
            write_wav(path, samples, audio.sample_rate)

            pcm = resample(samples, audio.sample_rate, ASR_SR)
            t1 = time.perf_counter()
            stream = asr.create_stream()
            stream.accept_waveform(ASR_SR, pcm)
            asr.decode_stream(stream)
            hyp = stream.result.text.strip()
            asr_s = time.perf_counter() - t1

            ref_n, hyp_n = normalize(text), normalize(hyp)
            rows.append(
                {
                    "index": i,
                    "ref": text,
                    "hyp": hyp,
                    "tts_s": round(tts_s, 3),
                    "asr_s": round(asr_s, 3),
                    "audio_s": round(dur, 3),
                    "wer_raw": round(wer(text, hyp), 4),
                    "cer_raw": round(cer(text, hyp), 4),
                    "wer_norm": round(wer(ref_n, hyp_n), 4),
                    "cer_norm": round(cer(ref_n, hyp_n), 4),
                    "exact": ref_n == hyp_n,
                    "wav": path,
                }
            )

        def corpus(key, unit):
            norm = key.endswith("_norm")
            e = u = 0
            for r in rows:
                a = r["ref"] if not norm else normalize(r["ref"])
                b = r["hyp"] if not norm else normalize(r["hyp"])
                at = a.split() if unit == "word" else list(a.replace(" ", ""))
                bt = b.split() if unit == "word" else list(b.replace(" ", ""))
                if not at:
                    continue
                e += edit_distance(at, bt)
                u += len(at)
            return round(e / u, 4) if u else None

        exact = sum(1 for r in rows if r["exact"])
        report["languages"][lang] = {
            "n_sentences": len(rows),
            "exact_match": exact,
            "exact_match_pct": round(100 * exact / len(rows), 1),
            "wer_raw_corpus": corpus("wer_raw", "word"),
            "cer_raw_corpus": corpus("cer_raw", "char"),
            "wer_norm_corpus": corpus("wer_norm", "word"),
            "cer_norm_corpus": corpus("cer_norm", "char"),
            "sentences": rows,
        }
        print(
            f"[{lang:8s}] n={len(rows):2d}  exact {exact:2d}/{len(rows)}  "
            f"WER raw {corpus('wer_raw','word'):.3f} norm {corpus('wer_norm','word'):.3f}  "
            f"CER raw {corpus('cer_raw','char'):.3f} norm {corpus('cer_norm','char'):.3f}",
            flush=True,
        )

    with open(os.path.join(BASE, "tts_intelligibility.json"), "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    print(f"\nWrote tts_intelligibility.json + {len(HINDI)+len(ENGLISH)} wavs to {OUT}")
    print("Next step for the submission: have humans score these wavs.")


if __name__ == "__main__":
    main()
