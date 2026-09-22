"""Measure peak RSS of the real ASR pipeline + run Piper-style TTS if available."""
import os, resource, time, json
import sherpa_onnx

M = "output/option_a_stt/omni300m"
r = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
    model=f"{M}/model.int8.onnx", tokens=f"{M}/tokens.txt",
    num_threads=8, provider="cpu", debug=False)

def peak_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0

base = peak_mb()
import numpy as np, wave
with wave.open(f"{M}/test_wavs/en.wav", "rb") as w:
    a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
t0 = time.time()
s = r.create_stream(); s.accept_waveform(16000, a)
r.decode_stream(s)
txt = s.result.text
el = time.time() - t0
res = {"peak_rss_after_load_mb": round(base, 1),
       "peak_rss_after_decode_mb": round(peak_mb(), 1),
       "audio_s": round(len(a) / 16000, 3), "decode_s": round(el, 3),
       "rtf": round(el / (len(a) / 16000), 3), "text": txt}
print(json.dumps(res, indent=1))
json.dump(res, open("output/option_a_stt/RAM_measure.json", "w"), indent=1)
