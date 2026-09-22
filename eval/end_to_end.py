"""END-TO-END SIH26173 LOOP: TTS-synthesised speech -> ASR -> text -> TTS -> audio out.
This is the walkie-talkie round-trip on one machine. Measures the real mouth-to-ear delay.
"""
import json, time, wave, resource
import numpy as np
import sherpa_onnx

M = "output/option_a_stt/omni300m"
D = "output/option_a_stt/tts"
SR = 16000

def peak_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0

# ---- load ASR + VAD-less (we use offline CTC directly) ----
asr = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
    model=f"{M}/model.int8.onnx", tokens=f"{M}/tokens.txt",
    num_threads=8, provider="cpu", debug=False)

# ---- load Hindi + English TTS ----
def load_tts(model, tokens):
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=f"{D}/{model}", lexicon="", tokens=f"{D}/{tokens}",
                data_dir=f"{D}/espeak-ng-data"),
            num_threads=4, debug=False, provider="cpu"))
    return sherpa_onnx.OfflineTts(cfg)

hi_tts = load_tts("hi_IN-pratham-medium.onnx", "hi_IN-pratham-medium.tokens")
en_tts = load_tts("en_US-lessac-medium.onnx", "en_US-lessac-medium.tokens")
print(f"all models loaded, peak RSS {peak_mb():.0f} MiB")

CASES = [
    ("Hindi", "नमस्ते, यह एक आपातकालीन संदेश है।", hi_tts, "hin_Deva"),
    ("Hindi", "तुम्हें तुरंत सुरक्षित स्थान पर जाना होगा।", hi_tts, "hin_Deva"),
    ("English", "Emergency alert. Move to a safe location immediately.", en_tts, "eng_Latn"),
    ("English", "This is a test of the emergency broadcast system.", en_tts, "eng_Latn"),
]

def resample(a, from_sr, to_sr):
    if from_sr == to_sr:
        return a
    n = int(len(a) * to_sr / from_sr)
    idx = np.linspace(0, len(a) - 1, n)
    return np.interp(idx, np.arange(len(a)), a).astype(np.float32)

results = []
for lang, text, tts, want in CASES:
    r = {"lang": lang, "text": text}
    # --- PHONE A: speak (synthesise the "speaker") ---
    t0 = time.time()
    audio = tts.generate(text=text, sid=0, speed=1.0)
    spoken = np.array(audio.samples, dtype=np.float32)
    speak_s = time.time() - t0
    r["speak_audio_s"] = round(len(spoken) / audio.sample_rate, 3)
    r["tts_a_s"] = round(speak_s, 3)

    # --- PHONE A: STT on that speech ---
    pcm16 = resample(spoken, audio.sample_rate, SR)
    t1 = time.time()
    stream = asr.create_stream()
    stream.accept_waveform(SR, pcm16)
    asr.decode_stream(stream)
    hyp = stream.result.text
    asr_s = time.time() - t1
    r["asr_s"] = round(asr_s, 3)
    r["asr_rtf"] = round(asr_s / (len(pcm16) / SR), 3)
    r["hyp"] = hyp

    # --- transport: negligible on-device, but record the text size sent ---
    r["payload_bytes"] = len(hyp.encode("utf-8"))
    r["payload_bytes_utf16"] = len(hyp) * 2

    # --- PHONE B: TTS the received text back out ---
    t2 = time.time()
    out = tts.generate(text=hyp, sid=0, speed=1.0)
    out_s = time.time() - t2
    r["tts_b_s"] = round(out_s, 3)
    r["tts_b_rtf"] = round(out_s / (len(out.samples) / out.sample_rate), 3)

    # mouth-to-ear compute (excluding audio playback time and radio latency)
    r["loop_compute_s"] = round(speak_s + asr_s + out_s, 3)
    results.append(r)
    print(f"\n[{lang}] {text[:40]}")
    print(f"  ASR  {asr_s:.2f}s (RTF {r['asr_rtf']})  -> {hyp[:60]}")
    print(f"  TTS  {out_s:.2f}s (RTF {r['tts_b_rtf']})  payload {r['payload_bytes']} B")
    print(f"  compute total {r['loop_compute_s']}s")

json.dump(results, open("output/option_a_stt/end_to_end.json", "w"), indent=1, ensure_ascii=False)
print(f"\npeak RSS {peak_mb():.0f} MiB")
