# Option A — SIH26173 speech-model fact sheet

Sources retrieved 17 September 2026. Web pages fetched with Scrapling; individual MMS metadata checked using Hugging Face API. No speech model was run and no Android measurement was performed.

## Requirement
Two Android phones: local ASR → recognised text and language tag → local Wi-Fi/Bluetooth → local TTS in the same language. No translation or general-purpose LLM is required. Languages: Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English. Published weights (40% accuracy, 20% efficiency, 20% latency) are scoring weights, not performance thresholds. They sum to 80%; remaining weight is unspecified in retrieved PS.

## Strongest ASR candidate discovered
Meta Omnilingual ASR CTC 300M, deployed with sherpa-onnx INT8. CTC is the non-LLM variant. Upstream supported-language file includes hin_Deva, guj_Gujr, mar_Deva, kan_Knda, mal_Mlym, tam_Taml, tel_Telu, ory_Orya, ben_Beng, eng_Latn. Code/models are Apache 2.0 according to upstream README. sherpa-onnx documents an INT8 ONNX model shown as 348M in ls output, Android APKs and Android source. This is meaningful implementation evidence, but not proof of competitive performance on low-/mid-range phones. Upstream A100 speed figures and its 7B LLM accuracy headline do not apply to this phone candidate.

### EXECUTED LOCALLY 18 Sep 2026 — see REAL_ASR_RESULTS.md
The model was actually downloaded and run (sherpa-onnx 1.13.8, CPU). Model 365,352,120 bytes, load 0.528 s, RTF 0.036–0.043 (~25x real time) on a Mac. Real FLEURS human speech, 6 clips/language: Odia WER 0.402/CER 0.083, Hindi WER 0.444/CER 0.239, Bengali WER 0.313/CER 0.089, Marathi WER 0.744/CER 0.229 (Marathi is weak). English on bundled wavs was perfect. Odia — the language stock Whisper lacks — works. Kannada, Malayalam, Tamil, Telugu, Gujarati and Android remain unmeasured. Mac, not phone.

### CORRECTION to source URL
The k2-fsa namespace repo returns HTTP 401 (gated). The public, downloadable copy is `csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`, Apache 2.0, verified by reading the LICENSE and running the model.

Sources:
- https://github.com/facebookresearch/omnilingual-asr
- https://github.com/facebookresearch/omnilingual-asr/blob/main/src/omnilingual_asr/models/wav2vec2_llama/lang_ids.py
- https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html
- https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/android.html

Other ASR: IndicConformer-600M-Multi (MIT) explicitly covers the nine Indian languages but its listed 22 languages do not include English. Requires an English path. Stock Whisper covers nine of the PS languages but not Odia in its standard list. Neither is a single complete coverage answer by itself.
https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual

## TTS candidates
1. Indic Parler-TTS: model card lists all ten required languages, Apache 2.0, approximately 0.9B parameters. Single-model coverage candidate; no verified low-/mid-range Android package/benchmark found in this review. FP32 weights-only estimate is roughly 3.6 GB from 0.9B × 4 bytes, excluding runtime/activations and auxiliary assets. CPU execution in a Python example does not imply acceptable phone speed. Its Native Speaker Scores are not transcription accuracy percentages; scores vary by language. Raw file access was gated, but public model card could be read.
   https://huggingface.co/ai4bharat/indic-parler-tts
2. MMS-TTS: separate VITS checkpoints exist for all ten (hin, guj, mar, kan, mal, tam, tel, ory, ben, eng). Approximately 36.3M parameters per checkpoint, roughly 145 MB FP32 parameter bytes each, not runtime RAM. Load one active voice instead of ten at once. Weights are CC-BY-NC-4.0, NOT unconditionally permissive open source. Get organiser approval for the PS's open-source requirement; do not claim compliance based on public downloads. sherpa-onnx documents conversion, but its shown converter explicitly rejects uroman-dependent models; validate preprocessing/export for every required language rather than assuming all convert directly.
   https://huggingface.co/facebook/mms-tts-ory
   https://huggingface.co/facebook/mms-tts-ben
   https://k2-fsa.github.io/sherpa/onnx/tts/mms.html
3. IndicF5: published language list covers nine of the required languages, not English. Its usage requires reference audio and its transcript. Not an out-of-box complete ten-language answer; no target-phone benchmarks established here.
   https://github.com/AI4Bharat/IndicF5
4. eSpeak NG: non-neural, local formant synthesiser with all ten listed (Odia labelled Oriya). Useful lightweight baseline; robotic quality and intelligibility require listening tests. Observe GPL licensing for integration/distribution. Not proof of high-quality neural TTS.
   https://github.com/espeak-ng/espeak-ng/blob/master/docs/languages.md

## Proposed decision
ASR: **confirmed executable** — Omnilingual CTC 300M INT8 ran locally with usable Odia/Hindi/Bengali accuracy and ~25x-real-time speed. The open question has moved from "does it work" to "does it work on a phone". Next: benchmark on an actual Android device, and measure the 5 untested languages. TTS: use eSpeak as a low-resource functional baseline; compare neural TTS only after the model licence, frontend and actual phone runtime are validated. Indic Parler is a coverage/quality research candidate, not yet a phone recommendation. MMS is a conditional experiment pending licensing clarification.

No single reviewed stack is verified to deliver all ten languages with competitive accuracy, memory and latency on the required phones. A larger model's reported quality must not be attributed to its smaller quantised sibling.

## Local smoke test performed (strictly limited)
Checked local caches/project: no ASR models/audio test samples/inference runtimes located. Created only output/offline_voice_smoke/.venv (no packages installed). Sent a fixed English JSON text message through localhost TCP on one Mac and synthesised the received text using installed Apple say/Samantha. Exact text preserved; say exit 0; AIFF file 178616 bytes; synthesis wall time 1.040 s in this one run. No microphone recording or ASR; no playback/listener validation; no Android; no network-isolation audit. Apple voice is proprietary and cannot substantiate SIH open-source compliance. This is plumbing evidence only, not the requested speech-model quality benchmark.

Artifacts: output/offline_voice_smoke/result.json and received.aiff. Existing project files unchanged.
