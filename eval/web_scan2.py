"""Fetch the pages that matter most for the build."""
import json
from scrapling import Fetcher

PAGES = {
    "omni_android": "https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/android.html",
    "omni_models": "https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html",
    "silero_vad": "https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html",
    "vits_all": "https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html",
    "mms_tts": "https://k2-fsa.github.io/sherpa/onnx/tts/mms.html",
    "kitten_tts": "https://k2-fsa.github.io/sherpa/onnx/tts/kitten.html",
    "tts_rtf": "https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/rtf.html",
    "piper": "https://k2-fsa.github.io/sherpa/onnx/tts/piper.html",
    "moonshine_android": "https://k2-fsa.github.io/sherpa/onnx/moonshine/android.html",
    "qnn_phone": "https://k2-fsa.github.io/sherpa/onnx/qnn/run-executables-on-your-phone.html",
}
out = {}
f = Fetcher()
for k, u in PAGES.items():
    try:
        p = f.get(u, timeout=45, impersonate="chrome")
        txt = p.get_all_text()
        out[k] = {"url": u, "status": p.status, "chars": len(txt), "text": txt}
        print(f"OK   {k:18} {p.status} {len(txt)}")
    except Exception as e:
        out[k] = {"url": u, "error": str(e)[:200]}
        print(f"FAIL {k:18} {str(e)[:80]}")
with open("output/option_a_stt/web_scan2.json", "w") as fh:
    json.dump(out, fh, ensure_ascii=False)
