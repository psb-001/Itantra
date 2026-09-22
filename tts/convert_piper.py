#!/usr/bin/env python3
"""Convert piper voices to sherpa-onnx format (adds metadata + tokens.txt)."""
import json, os, sys
from typing import Any, Dict
import onnx


def add_meta_data(filename: str, meta_data: Dict[str, Any]):
    model = onnx.load(filename)
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)
    onnx.save(model, filename)


def load_config(model):
    with open(f"{model}.json", "r") as file:
        return json.load(file)


def generate_tokens(config, out="tokens.txt"):
    # sherpa-onnx expects the espeak word-boundary markers to appear first.
    id_map = dict(config["phoneme_id_map"])
    ordered = {}
    for special in ("_", "^", "$"):
        if special in id_map:
            ordered[special] = id_map.pop(special)
    ordered.update(id_map)
    with open(out, "w", encoding="utf-8") as f:
        for s, i in ordered.items():
            f.write(f"{s} {i[0]}\n")
    print(f"Generated {out} ({len(ordered)} tokens)")


def main(fn):
    config = load_config(fn)
    base = fn[:-5]
    generate_tokens(config, f"{base}.tokens")
    meta = {
        "model_type": "vits",
        "comment": "piper",
        "language": config["language"]["name_english"],
        "voice": config["espeak"]["voice"],
        "has_espeak": 1,
        "n_speakers": config["num_speakers"],
        "sample_rate": config["audio"]["sample_rate"],
    }
    print("meta:", meta)
    add_meta_data(fn, meta)
    print("converted", fn)


if __name__ == "__main__":
    main(sys.argv[1])
