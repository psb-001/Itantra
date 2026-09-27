# TTS licence audit — what we actually ship, and what we cannot

**Date:** 27 September 2026 · **Reason:** an audit requested while adding four more voices
**Supersedes:** the compliance claims in `DECISION_SIH26173.md` and `MODEL_AND_TTS_FINDINGS.md`

---

## 1. The claim we made, and why it was wrong

`DECISION_SIH26173.md` records the decision to use espeak-ng for Kannada, Tamil and Odia, on
these grounds:

> *"It keeps the entire shipped stack at MIT / Apache-2.0 / GPL — **no CC-BY-NC anywhere**. That is
> a clean, checkable compliance story, and the dossier already flags espeak-ng as a known GPL
> dependency."*

**That was never true, and the reason it went unnoticed is that nobody checked the voices that
were already in the app.** Piper's wrapper code and weights format are MIT. The *training data*
behind each voice is not uniformly permissive, and a voice trained on a CC dataset inherits
those terms in most readings.

## 2. What is actually in the shipped APK

| Language | Voice | Weights wrapper | Training dataset | Terms |
|---|---|---|---|---|
| Hindi | `hi_IN-pratham-medium` | MIT (Piper) | AI4Bharat `indicnlp_corpus` | **CC-BY-NC-SA 4.0** |
| English | `en_US-lessac-medium` | MIT (Piper) | Blizzard 2013 | custom, non-transferable |
| Marathi | `mr_IN-google-medium` | MIT (Piper) | OpenSLR 64 | **CC-BY-SA 4.0** |
| Bengali | `bn_BD-google-medium` | MIT (Piper) | OpenSLR 37 + CMU Indic | **CC-BY-SA 4.0** + CMU |
| Malayalam | `ml_IN-arjun-medium` | MIT (Piper) | Kaggle `indic-tts-malayalam` | **unclear** |
| Telugu | `te_IN-venkatesh-medium` | MIT (Piper) | `ai4bharat/indicvoices_r` | **CC-BY-4.0** |

`hi_IN-pratham-medium` is **non-commercial and share-alike**. It has been in the APK since
before this audit. A submission claiming "no CC-BY-NC anywhere" is inaccurate on its face.

## 3. Why this is disclosed rather than resolved

The alternative to a CC voice is silence. For an emergency-alert app whose stated purpose is
reaching people who cannot read, a robotic-but-intelligible voice in a regional language beats
correct text with no audio. We therefore ship the CC voices, attribute them properly, and say so
plainly. We do not claim a licence purity we do not have.

### Required attribution

If any of this ships, the submission must carry something equivalent to:

> Text-to-speech voices from the Piper project (MIT). `hi_IN-pratham-medium` trained on
> AI4Bharat indicnlp_corpus (CC-BY-NC-SA 4.0); `mr_IN-google-medium` on OpenSLR 64
> (CC-BY-SA 4.0); `bn_BD-google-medium` on OpenSLR 37 and CMU Indic (CC-BY-SA 4.0);
> `te_IN-venkatesh-medium` on ai4bharat/indicvoices_r (CC-BY-4.0);
> `en_US-lessac-medium` on the Blizzard 2013 corpus. Speech recognition uses Meta's Omnilingual
> ASR (CC-BY-NC 4.0) with its output vocabulary pruned to ten Indian languages.

## 4. The four languages with no voice

Gujarati, Kannada, Tamil and Odia receive **correct text and no audio**. They are shown
explicitly in the UI rather than failing silently, because a text-only language that *looks*
like it is speaking is the worst outcome in a live demo.

| Option | Why not |
|---|---|
| **MMS-TTS** (Meta) — has all four, good quality | CC-BY-NC 4.0. Rejected by the team's own rule, though defensible for a non-commercial hackathon. |
| **AI4Bharat Indic-TTS** — BSD-2, all 13 languages, male+female | FastPitch + HiFi-GAN in **PyTorch**. The prebuilt `libsherpa-onnx-jni.so` only implements `Vits, Matcha, Kokoro, Zipvoice, Kitten, Pocket, Supertonic` — verified by inspecting the library's strings. Porting needs either an Android NDK cross-compile of sherpa-onnx (no NDK or cmake installed) or a from-scratch FastPitch+HiFi-GAN implementation in Kotlin. Neither is a weekend job. |
| **Mimic3** (Mycroft) | AGPL-3.0 engine; HuggingFace mirrors are CC-BY-SA 4.0. Stronger copyleft than our rule allows. |
| **espeak-ng** as a standalone voice — the plan in the old docs | **Not implementable.** espeak-ng is a *phonemiser frontend* for VITS models, not a voice. Neither `master` nor the `v1.13.8` tag of sherpa-onnx's Kotlin API has an espeak TTS config class, and the `OfflineTtsEspeakModelConfig` string is absent from the shipped `.so`. The 17.2 MB of espeak dictionaries in the APK is used only to phonemise text for the Piper voices. |

## 5. What the app now does

```kotlin
fun voiceFor(langTag: String): String? = when (langTag) {
    Lang.HINDI     -> "hi_IN-pratham-medium"
    Lang.MARATHI   -> "mr_IN-google-medium"     // was borrowing the Hindi voice
    Lang.BENGALI   -> "bn_BD-google-medium"
    Lang.MALAYALAM -> "ml_IN-arjun-medium"
    Lang.TELUGU    -> "te_IN-venkatesh-medium"
    Lang.ENGLISH   -> "en_US-lessac-medium"
    else -> null                                // Gujarati, Kannada, Tamil, Odia
}
```

Marathi previously fell back to the Hindi voice. Both use Devanagari, but the pronunciation is
wrong, and reading an emergency message with the wrong phonetics is immediately obvious to a
native speaker. It now has its own voice.

## 6. Getting these voices onto sherpa-onnx

`eval/fetch_tts_voices.py` does the work reproducibly. Three problems, each verified:

1. **No `.tokens` file on HuggingFace.** Piper ships only `.onnx` and `.onnx.json`. The phoneme
   table is inside the config as `phoneme_id_map`, so we regenerate it. Checked byte-equivalent
   against both hand-bundled voices: `hi_IN` 157 symbols, `en_US` 154 symbols, both exact.
2. **Raw Piper models have zero `metadata_props`.** sherpa-onnx then aborts *natively* with
   `'sample_rate' does not exist in the metadata` — a process abort with no Python traceback, so
   it looks like a crash rather than a config problem. The script patches the 7 properties
   k2-fsa's converter adds.
3. **The Marathi and Bengali maps contain multi-codepoint IPA diphthongs** (`aɪ aʊ ɔɪ eɪ oʊ`).
   `piper-phonemize-lexicon.cc:ReadTokens` rejects them (`size: 2`). We drop them, which is what
   k2-fsa's converter does — confirmed by the one Indian voice they actually package,
   `vits-piper-ml_IN-arjun-medium`, whose shipped `tokens.txt` has 161 single-codepoint symbols
   and matches our output exactly.

## 7. Intelligibility, ASR round-trip proxy

Measured with `eval/tts_intelligibility.py`, 10 emergency sentences per new language, 20 for
Hindi and English. **This is a proxy, not a human panel.**

| Language | n | exact | WER norm | CER norm |
|---|---|---|---|---|
| Hindi | 20 | 4/20 | 0.248 | **0.102** |
| English | 20 | 7/20 | 0.094 | 0.043 |
| Marathi | 10 | 0/10 | 0.440 | 0.139 |
| Bengali | 10 | 1/10 | 0.309 | 0.106 |
| Malayalam | 10 | 1/10 | 0.326 | 0.048 |
| Telugu | 10 | 2/10 | 0.477 | 0.132 |

**Hindi TTS round-trip improved from CER 0.339 to 0.102.** That was previously logged as "the
most important open accuracy question" in `ACCURACY_RESULTS.md`. The cause was not the voice:
it was the ASR emitting out-of-script characters on synthetic speech, which pruning fixed. See
`VOCAB_PRUNE_FINDINGS.md`.

Still to do: a human listening panel. The 80 wavs are in `tts/intelligibility/`.

## 8. Honest limitations

1. **No human panel.** Every intelligibility number above is an ASR round-trip proxy. The rubric
   weights legibility heavily, and a proxy is not a substitute.
2. **`ml_IN-arjun-medium`'s licence is genuinely unclear** — the MODEL_CARD says "See URL" and
   points at a Kaggle notebook, not a licence. Treat as unknown until AI4Bharat clarifies.
3. **Round-trip CER is not a listening test.** It measures whether the ASR can decode the audio,
   which correlates with intelligibility but does not measure naturalness, prosody, or whether a
   human parses it in a stressed situation.
4. **Four of ten languages have no audio.** The problem statement asks for TTS in ten languages.
   We deliver speech *recognition* in ten and speech *output* in six.
