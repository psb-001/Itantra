"""Peak RAM with every model loaded at once — SIH26173.

Fixes a unit bug in the earlier scripts. On macOS, `resource.getrusage().ru_maxrss`
is in BYTES; on Linux it is in KILOBYTES. The existing scripts divided by 1024
unconditionally and labelled the result "MB", which is wrong on macOS by a factor
of 1024.

Also measures the thing the dossier claims (~1.1 GiB with all models) but never
backed with a file: ASR + both TTS voices resident simultaneously, and the
incremental cost of loading TTS on top of ASR.

Run from the repo root:
    output/option_a_stt/.venv/bin/python output/option_a_stt/measure_ram_all.py
"""
import json
import os
import platform
import resource
import sys
import time

import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
M = os.path.join(BASE, "omni300m")
D = os.path.join(BASE, "tts")

MIB = 1024 * 1024


def peak_bytes():
    """ru_maxrss normalised to bytes across platforms."""
    raw = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return raw if sys.platform == "darwin" else raw * 1024


def peak_mib():
    return peak_bytes() / MIB


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
    out = {
        "machine": f"{platform.system()} {platform.machine()}",
        "note": (
            "macOS ru_maxrss is in bytes, Linux in kilobytes. Earlier scripts "
            "assumed KB and mislabelled the result as MB."
        ),
        "models": {
            "asr": "omnilingual-asr-300M-ctc-int8 (348 MiB on disk)",
            "tts_hi": "hi_IN-pratham-medium (60.6 MiB on disk)",
            "tts_en": "en_US-lessac-medium (60.3 MiB on disk)",
        },
    }

    out["baseline_mib"] = round(peak_mib(), 1)

    t0 = time.perf_counter()
    asr = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=os.path.join(M, "model.int8.onnx"),
        tokens=os.path.join(M, "tokens.txt"),
        num_threads=8,
        provider="cpu",
        debug=False,
    )
    out["asr_load_s"] = round(time.perf_counter() - t0, 3)
    out["peak_after_asr_mib"] = round(peak_mib(), 1)

    t0 = time.perf_counter()
    hi = load_tts("hi_IN-pratham-medium.onnx", "hi_IN-pratham-medium.tokens")
    out["tts_hi_load_s"] = round(time.perf_counter() - t0, 3)
    out["peak_after_asr_plus_hi_mib"] = round(peak_mib(), 1)

    t0 = time.perf_counter()
    en = load_tts("en_US-lessac-medium.onnx", "en_US-lessac-medium.tokens")
    out["tts_en_load_s"] = round(time.perf_counter() - t0, 3)
    out["peak_all_models_mib"] = round(peak_mib(), 1)

    # Prove the models are actually usable at this footprint, not just loaded.
    hi_out = hi.generate(text="यह एक आपातकालीन संदेश है।", sid=0, speed=1.0)
    stream = asr.create_stream()
    stream.accept_waveform(16000, hi_out.samples[:16000])
    asr.decode_stream(stream)
    out["smoke_asr_text"] = stream.result.text.strip()
    en_out = en.generate(text="Emergency alert.", sid=0, speed=1.0)
    out["smoke_tts_samples"] = len(en_out.samples)

    out["peak_after_use_mib"] = round(peak_mib(), 1)

    with open(os.path.join(BASE, "RAM_measure_all_models.json"), "w") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    print(json.dumps(out, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
