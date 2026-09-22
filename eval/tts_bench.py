"""Full TTS benchmark for SIH26173: Hindi + English Piper voices via sherpa-onnx."""
import json, time, resource, wave
import numpy as np
import sherpa_onnx

D = "output/option_a_stt/tts"

def peak_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0

MODELS = {
    "Hindi": ("hi_IN-pratham-medium.onnx", "hi_IN-pratham-medium.tokens",
              ["नमस्ते, यह एक आपातकालीन संदेश है।",
               "तुम्हें तुरंत सुरक्षित स्थान पर जाना होगा।",
               "बाढ़ की चेतावनी जारी की गई है, ऊपरी मंजिल पर रहें।"]),
    "English": ("en_US-lessac-medium.onnx", "en_US-lessac-medium.tokens",
                ["Emergency alert. Move to a safe location immediately.",
                 "Flood warning issued. Stay on the upper floor.",
                 "This is a test of the emergency broadcast system."]),
}

results = []
for lang, (model, tokens, texts) in MODELS.items():
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=f"{D}/{model}", lexicon="", tokens=f"{D}/{tokens}",
                data_dir=f"{D}/espeak-ng-data"),
            num_threads=4, debug=False, provider="cpu"))
    t0 = time.time()
    tts = sherpa_onnx.OfflineTts(cfg)
    load = time.time() - t0
    for i, txt in enumerate(texts):
        t1 = time.time()
        audio = tts.generate(text=txt, sid=0, speed=1.0)
        gen = time.time() - t1
        sr = audio.sample_rate
        dur = len(audio.samples) / sr
        wav = np.array(audio.samples, dtype=np.float32)
        with wave.open(f"{D}/{lang}_{i}.wav", "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
            w.writeframes((wav * 32767).astype(np.int16).tobytes())
        results.append({"lang": lang, "model": model, "model_bytes": 63516050 if lang == "Hindi" else 63201294,
                        "load_s": round(load, 3), "gen_s": round(gen, 3), "audio_s": round(dur, 3),
                        "rtf": round(gen / dur, 3), "sr": sr, "peak_rss_mb": round(peak_mb(), 1),
                        "text": txt})
        print(f"{lang:8} RTF={gen/dur:6.3f} gen={gen:5.2f}s audio={dur:5.2f}s  {txt[:50]}")

json.dump(results, open("output/option_a_stt/tts_result.json", "w"), indent=1, ensure_ascii=False)
import statistics
for lang in MODELS:
    rs = [r for r in results if r["lang"] == lang]
    print(f"\n{lang}: mean RTF = {statistics.mean(r['rtf'] for r in rs):.3f}  "
          f"load = {rs[0]['load_s']:.2f}s  model = {rs[0]['model_bytes']/1048576:.1f} MiB")
