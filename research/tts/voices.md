# Offline English TTS voice options

Repo: iTantra-SIH26173, sherpa-onnx 1.13.8, Piper VITS today (`en_US-lessac-medium`, 22.05 kHz mono).
Baseline voice size (HF blob): 63.2 MB. All Piper voices below are MIT-licensed
(`rhasspy/piper-voices`, model card license: mit). Download URLs are the v1.0.0
release tarballs from `rhasspy/piper-voices` (sizes measured from HuggingFace).

## 1. Piper English voices (drop-in, no API change)

Current baseline: `en_US-lessac-medium` — 63.2 MB.

| Voice | Tier | Size | Notes (unverified community opinion) |
|---|---|---|---|
| `en_US-lessac-high` | high | 114 MB | Same speaker as baseline; clearer, slightly more natural prosody. |
| `en_US-ryan-high` | high | 121 MB | Male, neutral US; often cited for clarity in voice assistants. |
| `en_US-libritts_r-medium` | medium | 78.6 MB | Multi-speaker corpus; robust but less distinctive. |
| `en_US-hfc_female-medium` | medium | 63.2 MB | Light, pleasant female; similar cost to baseline. |
| `en_US-amy-medium` | medium | 63.2 MB | Older single-speaker; smaller but less natural. |
| `en_GB-alan-medium` | medium | 63.2 MB | Male British; good articulation. |
| `en_GB-cori-high` | high | 114 MB | Female British; strong naturalness for a Piper voice. |
| `en_US-kusal-medium` | medium | 63.2 MB | Indian-origin speaker name; suitability for Indian English **unverified**. |
| `en_US-reza_ibrahim-medium` | medium | 63.5 MB | Unverified accent suitability. |

Every Piper voice URL follows:
`https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/<locale>/<name>/<tier>/<voice>-<tier>.onnx?download=true`
e.g. `en_US-ryan-high`:
`https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/ryan/high/en_US-ryan-high.onnx?download=true`

No official Piper quality ranking exists; treat the "notes" column as unverified.

## 2. Kokoro TTS via sherpa-onnx

sherpa-onnx supports Kokoro through the same `OfflineTts` API used for Piper, but with
`OfflineTtsModelConfig.kokoro: OfflineTtsKokoroModelConfig` (fields: `model`, `voices`,
`tokens`, `dataDir`, `lexicon`, `lang`, `lengthScale`) — see `Tts.kt` in this repo's
sherpa bindings. `lang` selects the Kokoro language/accent (English US/GB among others).

Model: `csukuangfj/kokoro-int8-multi-lang-v1_0` (Apache-2.0).
Files needed: `model.int8.onnx` (114 MB) + `voices.bin` (28.2 MB) ≈ 142 MB installed,
plus espeak-ng-data and lexicon files from the same repo. fp32 variant `kokoro-multi-lang-v1_0`
is 326 MB + 28.2 MB. sherpa Kokoro voice table (v1.0) has 54 speakers.

Indic coverage: Kokoro's speaker set is US/GB English, French, Spanish, Italian, Portuguese,
Japanese, Mandarin (and possibly Hindi `h` in the spec); no other Indic languages are
documented in the sherpa voice table (unverified). License Apache-2.0.

Download (int8, multi-lang):
- https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/main/model.int8.onnx
- https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/main/voices.bin
- Source repo: https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0

The `kokoro-en-v0_19` English-only int8 artifacts referenced by sherpa-onnx Flutter demos
were not found as a standalone model repo at research time (unverified).

## 3. Other offline (ONNX / sherpa-onnx) candidates

- Supertonic 3 (`Supertone/supertonic-3`) — offline ONNX, highly natural English, but license
  and Android deployment size not verified here (unverified).
- PocketTTS and Supertonic are exposed by sherpa-onnx 1.13.8 (`OfflineTtsPocket…`,
  `OfflineTtsSupertonic…`) but their sizes/licenses were not measured (unverified).
- Matcha/Kitten TTS: lighter than Kokoro but community quality is below Kokoro (unverified).
- sherpa-onnx MMS VITS voices: available for English but reported less natural than Piper high
  in TTS benchmarks (unverified).

## Recommendation

First choice to try on Android: **Kokoro int8 (multi-lang v1.0)** — clearly higher naturalness
than Piper, ~142 MB (114 MB + 28.2 MB), Apache-2.0, exact download URLs above. Verify
on-device CPU latency/RAM on a 2 GB phone before shipping (unverified).

Second choice (drop-in, no API change): **`en_US-ryan-high`** — 121 MB, MIT,
`https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/ryan/high/en_US-ryan-high.onnx?download=true`.
Fallback of equal size/cost: `en_US-lessac-high` (114 MB, same URL pattern). Mark anything
above about measured sizes/links as unverified where noted.
