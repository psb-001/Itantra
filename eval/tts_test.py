"""Real Piper TTS test via sherpa-onnx for Hindi + English."""
import json, time, resource
import sherpa_onnx
import numpy as np

D = "output/option_a_stt/tts"

def peak_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0

def tts(model, tokens, data_dir, text, sid=0):
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=model, lexicon="", tokens=tokens, data_dir=data_dir),
            num_threads=4, debug=False, provider="cpu"))
    t = sherpa_onnx.OfflineTts(cfg)
    audio = t.generate(text=text, sid=sid, speed=1.0)
    return t, audio

TESTS = [
    ("Hindi", f"{D}/hi_IN-pratham-medium.onnx", f"{D}/hi_IN-pratham-medium.onnx.tokens",
     f"{D}/espeak-ng-data", "नमस्ते, यह एक आपातकालीन संदेश है।"),
    ("English", f"{D}/en_US-lessac-medium.onnx", f"{D}/en_US-lessac-medium.tokens",
     f"{D}/espeak-ng-data", "Emergency alert. Move to a safe location immediately."),
]

results = []
for name, model, tokens, data, text in TESTS:
    try:
        t0 = time.time()
        tt, audio = tts(model, tokens, data, text)
        load = time.time() - t0
        samples = audio.samples
        sr = audio.sample_rate
        dur = len(samples) / sr
        t1 = time.time()
        audio2 = tt.generate(text=text, sid=0, speed=1.0)
        gen = time.time() - t1
        wav = np.array(samples, dtype=np.float32)
        import wave
        with wave.open(f"output/option_a_stt/tts/{name}.wav", "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
            w.writeframes((wav * 32767).astype(np.int16).tobytes())
        results.append({"lang": name, "load_s": round(load, 3), "gen_s": round(gen, 3),
                        "audio_s": round(dur, 3), "rtf": round(gen / dur, 3), "sr": sr,
                        "samples": len(samples), "peak_rss_mb": round(peak_mb(), 1)})
        print(f"OK {name}: load={load:.2f}s gen={gen:.2f}s audio={dur:.2f}s RTF={gen/dur:.3f}")
    except Exception as e:
        results.append({"lang": name, "error": str(e)[:300]})
        print(f"FAIL {name}: {str(e)[:200]}")

json.dump(results, open("output/option_a_stt/tts_result.json", "w"), indent=1)
