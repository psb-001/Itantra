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
REPO = os.path.dirname(BASE)

# The ASR model and the TTS voices now live in the repo-root model/ and tts/ layout, not in a
# stale eval/ copy. Both are overridable so this can be pointed at a variant for comparison.
M = os.environ.get(
    "OMNI_DIR",
    os.path.join(REPO, "models", "asr-v3-10lang",
                 "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8"),
)
# Where the VOICE MODELS live (TTS_DIR). Deliberately allowed to differ from where the output
# wavs go: writing 80 test wavs into app/app/src/main/assets/tts/ silently added 8.5 MB of
# evaluation output to the shipped APK, because anything under assets/ gets packaged.
D = os.environ.get("TTS_DIR", os.path.join(REPO, "tts"))
OUT = os.environ.get("WAV_OUT", os.path.join(REPO, "tts", "intelligibility"))
ASR_SR = 16000
TAG = os.environ.get("OMNI_TAG", "").strip()

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

# Added 27 Sep 2026 for the four newly bundled voices. Deliberately mirror the Hindi set:
# emergency phrasing plus the hard cases (numbers, a proper noun, negation) that the Hindi
# round trip turned out to fail on.
MARATHI = [
    "पुराव्याचा इशारा देण्यात आला आहे, वरच्या माळ्यावर रहा.",
    "हा एक आणीबाणीचा संदेश आहे, सुरक्षित जागी जा.",
    "भूकंपानंतर या भागात प्रवेश करू नका.",
    "दोनशे लोक बेघर झाले आहेत.",
    "पंधासा मिनिटांत मदत येईल.",
    "गावाचं नाव रामपूर आहे.",
    "कृपया शांतता पाळा.",
    "पाणी उकळून प्या.",
    "जखमींना प्रथमोपचार दिला जात आहे.",
    "पुढील आदेशापर्यंत प्रतीक्षा करा.",
]

BENGALI = [
    "বন্যার সতর্কতা জারি করা হয়েছে, উপরের তলায় থাকুন।",
    "এটি একটি জরুরি বার্তা, নিরাপদ স্থানে যান।",
    "ভূমিকম্পের পর এই এলাকায় প্রবেশ করবেন না।",
    "দুইশো জন মানুষ উচ্ছবিচ্ছিন্ন হয়েছেন।",
    "পনেরো মিনিটের মধ্যে সাহায্য পৌঁছে যাবে।",
    "গ্রামের নাম রামপুর।",
    "অনুগ্রহ করে শান্তি বজায় রাখুন।",
    "জল ফুটিয়ে পান করুন।",
    "আহতদের প্রাথমিক চিকিৎসা দেওয়া হচ্ছে।",
    "পরবর্তী আদেশ পর্যন্ত অপেক্ষা করুন।",
]

MALAYALAM = [
    "വെള്ളിയുടെ മുന്തറിയിപ്പിന് നിങ്ങൾ മുകളിൽ താഴേക്ക് സ്ഥിരതാംബനിച്ചു നിൽക്കുക.",
    "ഇത് ഒരു അടിയന്തിര സന്ദേശമാണ്, സുരക്ഷിത സ്ഥലത്തേക്ക് പോകുക.",
    "ഭൂമികമ്പിന് ശേഷം ഈ പ്രദേശത്ത് പ്രവേശിക്കരുത്.",
    "ഇരുനൂറ് ആളുകൾ കുടിയേറ്റിരിക്കുന്നു.",
    "പതിനഞ്ച് മിനിറ്റിനുള്ളിൽ സഹായം എത്തിച്ചേരും.",
    "ഗ്രാമത്തിന്റെ പേര് രാമപ്പൂർ.",
    "ദയചെയുമായി ശാന്തത നിലിർക്കുക.",
    "വെള്ളി കാളിയാക്കി കുടിക്കുക.",
    "പ്രദർശനക്കാർക്ക് പ്രഥമ സഹായം നൽകുന്നു.",
    "അടുത്ത നിർദ്ദേശം വരുന്നതുവരെ കാത്തിരിക്കുക.",
]

TELUGU = [
    "వర్షపాటు హెచ్చరిక ఇవ్వబడింది, పైన అంతస్తునికి వెళ్ళండి.",
    "ఇది అత్యవసర సందేశం, క్షేమస్థలానికి వెళ్ళండి.",
    "భూకంపం తర్వాత ఈ ప్రాంతంలోకి ప్రవేశించవద్దు.",
    "రెండు వందల మంది ఇంట్లోకి వెళ్ళారు.",
    "పదిహేను నిమిషాల్లో సహాయం చేరుతుంది.",
    "గ్రామం పేరు రామపూర్.",
    "దయచేసి శాంతిగా ఉండండి.",
    "నీటిని మరిగించి తాగండి.",
    "గాయాల ప్రాథమిక చికిత్స చేస్తున్నారు.",
    "తదుపరి ఆర్డర్ వచ్చే వరకు వేచి ఉండండి.",
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

    print("Loading ASR + every bundled TTS voice ...", flush=True)
    asr = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(M, "model.int8.onnx"),
        tokens=os.path.join(M, "tokens.txt"),
        num_threads=8,
        provider="cpu",
        debug=False,
    )
    # One entry per bundled voice. The four added on 27 Sep 2026 (mr, bn, ml, te) were each
    # synthesised and listened to before being listed here; see eval/fetch_tts_voices.py.
    VOICES = {
        "hi": ("hi_IN-pratham-medium", HINDI),
        "en": ("en_US-lessac-medium", ENGLISH),
        "mr": ("mr_IN-google-medium", MARATHI),
        "bn": ("bn_BD-google-medium", BENGALI),
        "ml": ("ml_IN-arjun-medium", MALAYALAM),
        "te": ("te_IN-venkatesh-medium", TELUGU),
    }
    tts_engines = {}
    for tag, (voice, _sents) in VOICES.items():
        tts_engines[tag] = load_tts(f"{voice}.onnx", f"{voice}.tokens")

    rss_all_models = peak_mb()
    print(f"ASR + {len(tts_engines)} voices loaded — peak RSS {rss_all_models:.0f} MiB\n", flush=True)

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

    LANG_NAME = {"hi": "Hindi", "en": "English", "mr": "Marathi",
                 "bn": "Bengali", "ml": "Malayalam", "te": "Telugu"}
    for tag, (voice, sentences) in VOICES.items():
        lang = LANG_NAME[tag]
        tts = tts_engines[tag]
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

    # Tag the output so a run against a different model/voice set does not silently overwrite
    # the previous measurement.
    name = f"tts_intelligibility_{TAG}.json" if TAG else "tts_intelligibility.json"
    with open(os.path.join(BASE, name), "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    n_wavs = sum(v["n_sentences"] for v in report["languages"].values())
    print(f"\nWrote {name} + {n_wavs} wavs to {OUT}")
    print("Next step for the submission: have humans score these wavs.")


if __name__ == "__main__":
    main()
