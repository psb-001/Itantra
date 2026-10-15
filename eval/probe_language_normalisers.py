"""
Per-language pronunciation audit: all 7 bundled voices.
Run: .venv/bin/python eval/probe_language_normalisers.py
"""
import difflib, json, os, re
import numpy as np
import sherpa_onnx

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TTS_DIR = os.path.join(REPO, "tts")
ASSET_TTS_DIR = os.path.join(REPO, "app", "app", "src", "main", "assets", "tts")
MODEL_DIR = os.path.join(REPO, "models", "asr-v3-10lang", "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8")
OUT_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "probe_language_normalisers.json")
RUNS = 3

LOANWORDS = {"emergency":"इमरजेंसी","urgent":"अर्जेंट","alert":"अलर्ट","help":"हेल्प","police":"पुलिस","hospital":"हॉस्पिटल","ambulance":"एम्बुलेंस","doctor":"डॉक्टर","medical":"मेडिकल","school":"स्कूल","village":"विलेज","shelter":"शेल्टर","water":"वाटर","food":"फूड","fire":"फायर","flood":"फ्लड","bridge":"ब्रिज","road":"रोड","border":"बॉर्डर","message":"मैसेज","report":"रिपोर्ट","call":"कॉल","phone":"फोन","mobile":"मोबाइल","battery":"बैटरी","network":"नेटवर्क","signal":"सिग्नल","safe":"सेफ","danger":"डेंजर","rescue":"रेस्क्यू","team":"टीम","officer":"ऑफिसर","sir":"सर","madam":"मैडम","please":"प्लीज़","thanks":"थैंक्स","ok":"ओके","minute":"मिनट","minutes":"मिनट"}
ACRONYMS = {"sos","fir","ndrf","sdrf","cbi"}

def condition(text, devanagari_voice):
    out = text.strip()
    if devanagari_voice:
        out = re.sub(r"\b[A-Za-z]+\b", lambda m: LOANWORDS.get(m.group(0).lower(), m.group(0)), out)
    def spell(m):
        low = m.group(0).lower()
        if low in ACRONYMS:
            return " ".join(list(low.upper()))
        return m.group(0)
    out = re.sub(r"\b[A-Za-z]+\b", spell, out)
    out = re.sub(r"\s{2,}", " ", out).strip()
    return out

LANGS = [
 ("Hindi","hi_IN-pratham-medium",True,[("loanword","स्कूल में emergency alert भेजो"),("number","108 पर कॉल करो, 500 लोग फंसे हैं"),("acronym","पुलिस को SOS भेजो, FIR दर्ज करो और NDRF बुलाओ")]),
 ("Marathi","mr_IN-google-medium",True,[("loanword","शाळेत emergency alert पाठवा"),("number","108 वर कॉल करा, 500 लोक फसले आहेत"),("acronym","NDRF ला बोलावा, FIR नोंदवा आणि SOS पाठवा")]),
 ("Bengali","bn_BD-google-medium",False,[("loanword","দুর্ঘটনার পরে police এবং ambulance দ্রুত ডাকুন"),("number","108 নম্বরে কল করো, 500 লোক আটকে আছে"),("acronym","NDRF কে ডাকো, FIR দায়ের করো")]),
 ("Tamil","ta_IN-rasa_female-medium",False,[("loanword","பள்ளியில் emergency alert அனுப்புங்கள்"),("number","108 க்கு அழைக்கவும், 500 பேர் சிக்கியுள்ளனர்"),("acronym","NDRF ஐ அழைக்கவும், FIR பதிவு செய்யவும்")]),
 ("Telugu","te_IN-venkatesh-medium",False,[("loanword","పాఠశాలలో emergency alert పంపండి"),("number","108 కు కాల్ చేయండి, 500 మంది చిక్కుకున్నారు"),("acronym","NDRF కు సమాచారం ఇవ్వండి, FIR నమోదు చేయండి")]),
 ("Malayalam","ml_IN-arjun-medium",False,[("loanword","സ്കൂളിൽ emergency alert അയക്കൂ"),("number","108 ലേക്ക് വിളിക്കുക, 500 ആളുകൾ കുടുങ്ങി"),("acronym","NDRF നെ വിളിക്കുക, FIR രജിസ്റ്റർ ചെയ്യുക")]),
 ("English","en_US-lessac-medium",False,[("loanword","A tsunami alert was issued for the coastal district"),("number","Call 108 and report 500 people trapped in the building"),("acronym","Send SOS to NDRF and file an FIR with CBI")]),
]

def voice_dir(voice):
    for d in (TTS_DIR, ASSET_TTS_DIR):
        if os.path.exists(os.path.join(d, f"{voice}.onnx")):
            return d
    raise SystemExit(f"no .onnx for {voice}")

def make_asr():
    return sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(MODEL_DIR, "model.int8.onnx"),
        tokens=os.path.join(MODEL_DIR, "tokens.txt"),
        num_threads=4, provider="cpu")

def make_tts(voice):
    d = voice_dir(voice)
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=f"{d}/{voice}.onnx", tokens=f"{d}/{voice}.tokens",
                data_dir=f"{TTS_DIR}/espeak-ng-data"),
            num_threads=2, provider="cpu")))

def hear(asr, tts, text):
    audio = tts.generate(text, sid=0, speed=1.0)
    samples = np.asarray(audio.samples, dtype=np.float32)
    s = asr.create_stream()
    s.accept_waveform(audio.sample_rate, samples)
    asr.decode_stream(s)
    return s.result.text.strip()

def sim(a,b): return difflib.SequenceMatcher(None,a,b).ratio() if (a or b) else 0.0

def main():
    asr = make_asr()
    engines = {}
    results = []
    print("="*90)
    print("PER-LANGUAGE PRONUNCIATION AUDIT - all 7 bundled voices, 3 runs each")
    print("="*90)
    for lang, voice, dev_voice, sents in LANGS:
        if voice not in engines:
            engines[voice] = make_tts(voice)
        tts = engines[voice]
        print()
        print("#"*90)
        print(f"# {lang}  voice={voice}  devanagari_voice={dev_voice}")
        print("#"*90)
        for kind, intended in sents:
            cond_text = condition(intended, dev_voice)
            raw_heards, cond_heards = [], []
            for _ in range(RUNS):
                raw_heards.append(hear(asr, tts, intended))
                cond_heards.append(hear(asr, tts, cond_text))
            raw_scores = [sim(cond_text, h) for h in raw_heards]
            cond_scores = [sim(cond_text, h) for h in cond_heards]
            raw_mean = sum(raw_scores)/RUNS
            cond_mean = sum(cond_scores)/RUNS
            raw_min, raw_max = min(raw_scores), max(raw_scores)
            cond_min, cond_max = min(cond_scores), max(cond_scores)
            changed = cond_text != intended
            print()
            print(f"  [{kind}]")
            print(f"    intended : {intended}")
            print(f"    cond     : {cond_text}  ({'CHANGED' if changed else 'unchanged'})")
            print("    RAW (no conditioning):")
            for b,sc in zip(raw_heards,raw_scores):
                print(f"        run heard : {b}   (sim {sc:.2f})")
            print(f"        mean {raw_mean:.2f}  min {raw_min:.2f}  max {raw_max:.2f}")
            print("    CONDITIONED:")
            for b,sc in zip(cond_heards,cond_scores):
                print(f"        run heard : {b}   (sim {sc:.2f})")
            print(f"        mean {cond_mean:.2f}  min {cond_min:.2f}  max {cond_max:.2f}")
            print(f"    delta conditioned-raw: {cond_mean-raw_mean:+.2f}")
            results.append({"lang":lang,"voice":voice,"devanagari_voice":dev_voice,"kind":kind,"intended":intended,"conditioned":cond_text,"changed":changed,"raw_heards":raw_heards,"raw_scores":raw_scores,"raw_mean":raw_mean,"raw_min":raw_min,"raw_max":raw_max,"cond_heards":cond_heards,"cond_scores":cond_scores,"cond_mean":cond_mean,"cond_min":cond_min,"cond_max":cond_max,"delta":cond_mean-raw_mean})
    with open(OUT_JSON,"w") as f:
        json.dump(results,f,indent=2,ensure_ascii=False)
    print()
    print(f"Wrote {OUT_JSON}")
    print("DONE")

if __name__=="__main__":
    main()
