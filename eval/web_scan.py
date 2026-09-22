"""Deep scan for SIH26173 build plan: streaming ASR, VAD, TTS, Android P2P transport."""
import json, traceback
from scrapling import Fetcher

PAGES = {
    # Streaming / online recognition (the PS demands pause detection + sentence forming)
    "streaming": "https://k2-fsa.github.io/sherpa/onnx/online-recognition/index.html",
    "streaming_models": "https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-asr/index.html",
    "vad": "https://k2-fsa.github.io/sherpa/onnx/vad/index.html",
    "vad_models": "https://k2-fsa.github.io/sherpa/onnx/vad/models.html",
    # Android runtime
    "android_offline": "https://k2-fsa.github.io/sherpa/onnx/android/index.html",
    "android_online": "https://k2-fsa.github.io/sherpa/onnx/android-online-recognition/index.html",
    "android_tts": "https://k2-fsa.github.io/sherpa/onnx/tts/android.html",
    # TTS models that could serve Hindi/English
    "tts_models": "https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html",
    "mms_tts": "https://k2-fsa.github.io/sherpa/onnx/tts/mms.html",
    # P2P transport, no internet
    "wifidirect": "https://developer.android.com/develop/connectivity/wifi/wifi-direct",
    "bluetooth_le": "https://developer.android.com/develop/connectivity/bluetooth/ble-overview",
    "nearby": "https://developers.google.com/nearby/connections/overview",
    "socket": "https://developer.android.com/reference/android/net/SocketKeepalive",
}

out = {}
f = Fetcher()
for k, u in PAGES.items():
    try:
        p = f.get(u, timeout=45, impersonate="chrome")
        txt = p.get_all_text() if hasattr(p, "get_all_text") else p.text
        out[k] = {"url": u, "status": p.status, "chars": len(txt), "text": txt}
        print(f"OK   {k:18} {p.status} {len(txt)} chars")
    except Exception as e:
        out[k] = {"url": u, "error": str(e)[:200]}
        print(f"FAIL {k:18} {str(e)[:100]}")

with open("output/option_a_stt/web_scan.json", "w") as fh:
    json.dump(out, fh, ensure_ascii=False)
print("saved", sum(len(v.get("text", "")) for v in out.values()), "chars total")
