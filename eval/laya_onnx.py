"""
Export the Laya multilingual checkpoint to split ONNX, then exercise it on real text.

WHY THIS EXISTS

Laya (https://github.com/NandhaKishorM/laya, Apache-2.0) is a non-autoregressive decision
engine: it reads text and returns typed decisions -- a choice from labelled options, a score, or
a yes/no with a probability -- in one forward pass, in 100+ languages. iTantra's receiving phone
already has the decoded text, so the decisions we want need no new audio path:

    is this an emergency?      -> escalate to ALERT (max volume, repeat until acknowledged)
    which language is it?      -> pick the right Piper voice
    what is being asked?       -> route

Measured 29 Sep 2026, results at the bottom of docs/LAYA_INTEGRATION_FINDINGS.md.

Run:
    pip install torch --index-url https://download.pytorch.org/whl/cpu
    pip install laya onnx onnxruntime onnxscript safetensors huggingface_hub
    python eval/laya_onnx.py export --out /tmp/laya_onnx
    python eval/laya_onnx.py bench  --model /tmp/laya_onnx

The export is Laya's own `laya-ts/scripts/export_onnx.py`, vendored here as `export_onnx.py`
upstream, because it verifies torch against ONNX to 1e-4 before writing anything. Do not replace
it with a bare torch.onnx.export and skip the check: the head graph is easy to get subtly wrong.
"""

import argparse
import json
import os
import sys
import time

import numpy as np

CKPT_REPO = "convaiinnovations/laya"
CKPT_SUBFOLDER = "multilingual"

# Special token ids in Laya's tokenizer. They are NOT [CLS]/[SEP]/[MASK]; it is a mmBERT vocab
# and getting these wrong produces a head that returns a perfectly uniform distribution with no
# error anywhere -- which is exactly what the first version of this did.
BOS, EOS, MASK, PAD = 2, 1, 4, 0


# --------------------------------------------------------------------------- export
def cmd_export(args):
    from huggingface_hub import snapshot_download

    print(f"downloading {CKPT_REPO}/{CKPT_SUBFOLDER} (about 647 MB)...", flush=True)
    root = snapshot_download(
        CKPT_REPO,
        allow_patterns=[
            f"{CKPT_SUBFOLDER}/rl_agent_config.json",
            f"{CKPT_SUBFOLDER}/model.safetensors",
            f"{CKPT_SUBFOLDER}/tokenizer/tokenizer.json",
            f"{CKPT_SUBFOLDER}/tokenizer/tokenizer_config.json",
            f"{CKPT_SUBFOLDER}/encoder/config.json",
        ],
    )
    ckpt = os.path.join(root, CKPT_SUBFOLDER)

    here = os.path.dirname(os.path.abspath(__file__))
    export_py = os.path.join(here, "laya_export_onnx_upstream.py")
    if not os.path.exists(export_py):
        sys.exit(
            f"missing {export_py}. Fetch it from Laya:\n"
            f"  curl -sL https://raw.githubusercontent.com/NandhaKishorM/laya/main/"
            f"laya-ts/scripts/export_onnx.py -o {export_py}"
        )

    import subprocess

    print("\nexporting to split ONNX (encoder + head), verifying torch vs onnx to 1e-4...", flush=True)
    cmd = [sys.executable, export_py, "--model-dir", ckpt, "--out-dir", args.out,
           "--seq-len", "16", "--verify-len", "512"]
    if args.no_verify:
        cmd.append("--no-verify")
    return subprocess.call(cmd)


# --------------------------------------------------------------------------- inference
class LayaOnnx:
    """Split-ONNX Laya: encoder.onnx then head.onnx.

    Input assembly mirrors laya/common.py:

        <bos> question <eos> <mask>opt1 <mask>opt2 ... <eos> state <eos>

    and the decision head reads the hidden state at each <mask> position, one per option. One
    option is one marker, so the marker count must equal the option count.
    """

    def __init__(self, model_dir, providers=None):
        import onnxruntime as ort
        from tokenizers import Tokenizer

        prov = providers or ["CPUExecutionProvider"]
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2       # match the app's ASR thread budget
        self.enc = ort.InferenceSession(os.path.join(model_dir, "encoder.onnx"), opts, providers=prov)
        self.head = ort.InferenceSession(os.path.join(model_dir, "head.onnx"), opts, providers=prov)
        self.tok = Tokenizer.from_file(os.path.join(model_dir, "tokenizer.json"))
        self.cfg = json.load(open(os.path.join(model_dir, "rl_agent_config.json")))
        self.max_len = int(self.cfg.get("max_len", 1024))

    def _ids(self, text):
        return self.tok.encode(text, add_special_tokens=False).ids

    def build(self, state, question, options):
        ids = [BOS] + self._ids(question) + [EOS]
        markers = []
        for o in options:
            markers.append(len(ids))
            ids.append(MASK)
            ids += self._ids(o)
        ids.append(EOS)
        ids += self._ids(state)
        ids.append(EOS)
        return ids[: self.max_len], [m for m in markers if m < self.max_len]

    def decide(self, state, question, options, qtype=0):
        """Return (option, probability, encoder_ms, head_ms, n_tokens)."""
        ids, markers = self.build(state, question, options)
        if len(markers) < len(options):
            return None, 0.0, 0.0, 0.0, len(ids)
        n = len(ids)
        ii = np.array([ids], dtype=np.int64)
        am = np.ones((1, n), dtype=np.int64)

        t0 = time.perf_counter()
        (h,) = self.enc.run(None, {"input_ids": ii, "attention_mask": am})
        t1 = time.perf_counter()

        mp = np.array([markers], dtype=np.int64)
        mm = np.ones((1, len(markers)), dtype=bool)
        qt = np.array([[qtype]], dtype=np.int64)
        logits, _ = self.head.run(
            None,
            {"hidden_states": h.astype(np.float32), "marker_pos": mp, "marker_mask": mm,
             "qtype": qt, "attention_mask": am},
        )
        t2 = time.perf_counter()

        p = np.exp(logits - logits.max(-1, keepdims=True))
        p /= p.sum(-1, keepdims=True)
        return options[int(p[0].argmax())], float(p[0].max()), (t1 - t0) * 1e3, (t2 - t1) * 1e3, n


# A rescue vocabulary. These English criteria are what Laya scores against; the STATE is the
# multilingual text, which is the point -- the model is zero-shot over 100+ languages.
QUESTION = "What kind of message is this?"
URGENCY = ["routine message", "safety warning", "evacuation order", "medical emergency"]

CASES = [
    ("hi", "बाढ़ आ गई है, पुलिस को तुरंत सूचित करें", "evacuation order"),
    ("hi", "कल बैठक है, कृपया उपस्थित रहें", "routine message"),
    ("ta", "வெள்ளை நீரில் மூழ்கும் அபாயம், உடனே அகற்றுங்கள்", "evacuation order"),
    ("te", "గండ్రి నీరు పెరుగుతోంది, వెంటనే విడిచండి", "evacuation order"),
    ("mr", "पूर आहे, सर्वांना लगेच बाहेर जा", "evacuation order"),
    ("bn", "পদ্মা নদী বাড়ছে, সবাই এখনই সরে যান", "evacuation order"),
    ("gu", "નદી ભરાઈ ગઈ છે, તરત ખાલી કરો", "evacuation order"),
    ("ml", "പുഴയിൽ വെള്ളം ഉയർന്നു, ഉടൻ മാറിക്കുക", "evacuation order"),
    ("or", "ବିଆଗ ବଢ଼ିଛି, ସମେଲେ ବାହାରକୁ ଯାଆନ୍ତୁ", "evacuation order"),
    ("kn", "ನದಿ ತುಂಬಿದೆ, ಎಲ್ಲರೂ ಈಗ ಹೊರಡಿ", "evacuation order"),
    ("en", "the river has flooded, evacuate the camp immediately", "evacuation order"),
    ("en", "lunch is at one, see you there", "routine message"),
]


def cmd_bench(args):
    m = LayaOnnx(args.model)
    print(f"encoder {m.enc.get_inputs()[0].name}  |  options are scored at <mask> positions\n")
    print(f"{'lang':<5}{'expected':<20}{'got':<20}{'ok':<3}{'conf':>7}{'enc':>7}{'head':>7}{'tok':>5}")
    print("-" * 76)
    ok = 0
    enc_ms, head_ms = [], []
    for lg, text, expected in CASES:
        got, conf, e, h, n = m.decide(text, QUESTION, URGENCY)
        good = got == expected
        ok += good
        enc_ms.append(e)
        head_ms.append(h)
        print(f"{lg:<5}{expected:<20}{str(got):<20}{'Y' if good else 'N':<3}"
              f"{conf:>7.3f}{e:>7.0f}{h:>7.0f}{n:>5}")
    print(f"\n{ok}/{len(CASES)} correct")
    print(f"encoder median {np.median(enc_ms):.0f} ms · head median {np.median(head_ms):.0f} ms "
          f"· total median {np.median(enc_ms) + np.median(head_ms):.0f} ms")
    print("\nA phone is typically 3-5x slower than this desktop CPU. The honest expectation is "
          "100-200 ms per decision, which is fine for a message that arrived as a 129-byte packet.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("export")
    e.add_argument("--out", default="/tmp/laya_onnx")
    e.add_argument("--no-verify", action="store_true")
    e.set_defaults(fn=cmd_export)
    b = sub.add_parser("bench")
    b.add_argument("--model", default="/tmp/laya_onnx")
    b.set_defaults(fn=cmd_bench)
    args = ap.parse_args()
    sys.exit(args.fn(args) or 0)


if __name__ == "__main__":
    main()
