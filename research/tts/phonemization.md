# espeak-ng phonemisation + text normalisation (research)

Repo: iTantra-SIH26173 (offline Android app, sherpa-onnx Piper VITS TTS). Scope is the TTS path only.

## 1. How sherpa-onnx Piper TTS invokes espeak-ng

Evidence (upstream sherpa-onnx `master`, fetched 2026-10-15):

- C API, `sherpa-onnx/c-api/c-api.h`:
  - `SherpaOnnxOfflineTtsVitsModelConfig.lexicon` is "Path to the lexicon file. **Ignored if data_dir is provided.**"
  - `data_dir` is "Optional path to espeak-ng-data."
- `sherpa-onnx/csrc/offline-tts-vits-impl.h`:
  - for Piper metadata (`is_piper` etc.) it constructs `PiperPhonemizeLexicon(mgr, tokens, data_dir, meta_data)`
  - then calls `frontend_->ConvertTextToTokenIds(text, meta_data.voice)` (`meta_data.voice` comes from the `.onnx.json` `espeak.voice` field).
- `sherpa-onnx/csrc/piper-phonemize-lexicon.cc`:
  - `InitEspeak(data_dir)` calls `espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data_dir.c_str(), 0)`.
  - `ConvertTextToTokenIdsVits` sets `config.voice = voice` and calls `piper::phonemize_eSpeak(text, config, ...)`.
  - `piper-phonemize/src/phonemize.cpp` sets `espeak_SetVoiceByName(voice.c_str())` and `espeak_TextToPhonemesWithTerminator(..., espeakCHARS_AUTO, 0x02, ...)`.

So invocation is: **one espeak-ng voice for the whole utterance**, set from model metadata; no per-word language tags survive phonemisation (default `keepLanguageFlags=false`, which filters `(lang)` switch flags).

### Custom pronunciation lexicon / dictionary override

**Not supported on the Piper path.** Because Piper models pass `data_dir` (espeak-ng-data), sherpa takes the `PiperPhonemizeLexicon` branch and ignores `lexicon`. The only knobs exposed by `OfflineTtsConfig` are `ruleFsts`/`ruleFars` — text normalisers (kaldifst), not pronunciation dictionaries. The `dictDir` field is explicitly marked "unused".

Options for fixing a mispronounced word, ranked:

1. **App-side substitution map** before `TtsEngine.synthesise()` (recommended for an offline emergency app).
2. **espeak-ng user data** hack (add entries to `<lang>_dict` and re-run `espeak-ng --compile`) — large, unverified, not exercised by `eval/tts_smoke.py`; avoid.
3. **Collect/replace the whole voice model** — outside scope.

There is no sherpa issue/PR referenced in-repo promising a fix; the C API comment above is the contract.

## 2. Latin inside Devanagari, and vice versa

espeak-ng phonemises the **entire string with the voice's single language rules**. Language switching inside a word is not automatic by script; it happens only when a dictionary rule uses the special `_^_<lang>` form (`espeak-ng` dictionary doc: "the translation is aborted and the word is re-translated using the specified different language"; e.g. `th (_    _^_EN`).

Our trimmed dictionaries contain **no** `_^\_EN` re-translation rules (checked with `grep -a` across all 10 kept `*_dict`). Therefore:

- Hindi text with embedded English words ("emergency alert") is read with Hindi phonology (or spelt letter-by-letter), not English.
- English text with embedded Indic words using the English voice is read with English phonology.

Community fixes seen (and consistent with the repo):

- **Pre-transliterate common foreign terms** to the target script before synthesis (e.g. "emergency" -> इमरजेंसी for hi_IN). Cheap lexicon of ~1–2 k loan words.
- Alternatively keep English terms but synthesise the English segment with `en_US-lessac` and stitch audio — breaks one-sentence prosody but guarantees pronunciation. This app loads only one voice at a time (TtsEngine comment), so switching has load cost.
- Do **not** rely on SSML `xml:lang` through sherpa: the phonemiser's `keepLanguageFlags=false` strips those markers and sherpa gives no voice override per utterance.

## 3. Offline text normalisation

Nothing normalises today: `TtsEngine.synthesise(text)` calls `tts.generate(text, ...)` with raw text; `OfflineTtsConfig.ruleFsts`/`ruleFars` are empty in `SpeechEngine.kt`. espeak-ng itself handles digits/punctuation tolerably, but currency, dates, acronyms and emoji are unreliable and depend on dictionary completeness.

### English — smallest correct approach (Android-light)

Do not ship a general NLP/`num2words` port. Add a ~150-line **deterministic preprocessor** invoked right before synthesis:

1. Unicode NFKC normalise + collapse whitespace.
2. Delete/replace emoji with words ("⚠️" -> "warning") via a small codepoint table.
3. Currency: regex `[$₹€£]\s?([\d,]+(?:\.\d+)?)` -> "<amount> <currency>" (`₹500` -> "500 rupees").
4. Numbers: recursive expander for 0–999,999,999 (integer -> words); decimals split on ".". Use `java.text.NumberFormat`? avoid; pure Kotlin integer recursion is ~40 lines and allocation-free.
5. Dates: regex `(\d{1,2})[/\-](\d{1,2})[/\-](\d{2,4})` -> spoken form; if unambiguous, leave as digits (espeak reads "12 slash 3").
6. Acronyms: run of uppercase ≥2 letters with no vowel -> letter-spaced (`SOS` -> `S O S`); mixed case left alone so espeak's capital handling stands.
7. Units: small map (`km` -> "kilometers", `°C` -> "degrees Celsius", `km/h` -> "kilometers per hour").
8. Punctuation: keep `. , ? ! : ; ( )` — espeak's clause terminators need them; strip others.

Because messages are internal emergency alerts, a closed-domain expander beats a general tokeniser.

### English words inside Indic text

Keep the per-language expander above for digits/currency. For English loanwords, **curate a replacement map per language** (e.g. `{"SOS": "एस ओ एस", "emergency": "इमरजेंसी"}`) rather than running an English normaliser or a full transliterator; full Indic transliteration libraries are too heavy and error-prone for a few hundred known terms. Apply loanword replacement after number/currency normalisation so numerals inside English acronyms are untouched.

## 4. Voice metadata (`tts/*.onnx.json`)

Bundled claims (root `tts/` ships only hi and en; the other five live under `app/.../assets/tts/` and are identical in layout):

| voice file (stem) | location | `espeak.voice` | `language.code` | match? |
|---|---|---|---|---|
| en_US-lessac-medium | `tts/` | `en-us` | `en_US` | yes |
| hi_IN-pratham-medium | `tts/` | `hi` | `hi_IN` | yes |
| mr_IN-google-medium | assets/tts | `mr` | `mr_IN` | yes |
| bn_BD-google-medium | assets/tts | `bn` | `bn_BD` | yes |
| ml_IN-arjun-medium | assets/tts | `ml` | `ml_IN` | yes |
| te_IN-venkatesh-medium | assets/tts | `te` | `te_IN` | yes |
| ta_IN-rasa_female-medium | assets/tts | `ta` | `ta` | voice ok, but `language.code` drops the `_IN` region |

All `phoneme_type = "espeak"`, `piper_version = "1.0.0"` (where present). The Tamil metadata's `language.code` is the only one that does not carry a region tag; harmless at runtime because sherpa only reads `espeak.voice`, but worth fixing at the source in `eval/fetch_tts_voices.py` for consistency. (hi/en jsons omit `dataset`? hi has `dataset: pratham`; en `lessac`.) 

## Recommendation

- Keep espeak voice names exactly as declared; they already match espeak's identifiers.
- Do not plan a custom TTS pronunciation lexicon via sherpa-onnx for these Piper voices; instead add a small, closed-domain text normaliser/loanword mapper upstream of `TtsEngine.synthesise()`.
- Add curated English-loanword maps for Hindi/Tamil/etc. to fix foreign-term pronunciation without retraining.