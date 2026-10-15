# Model version comparison and TTS coverage

**Date:** 20 September 2026 · All measurements on macOS arm64 (Apple Silicon, 17 GB RAM) — **NOT a phone**

---

## Part 1 — v1 vs v2 model, 200 clips × 10 languages

Both models are the same size on disk. Same eval, same clips, same machine.

| | v1 | v2 |
|---|---|---|
| Artifact | `...300M-ctc-int8-2025-11-12` | `...300M-ctc-v2-int8-2026-02-05` |
| `model.int8.onnx` | 365,352,120 B | 365,841,453 B (+489 KB) |
| `tokens.txt` | 86,423 B | **90,630 B — different tokenizer** |

### Results (normalised, corpus-level, n=200 per language)

| Language | v1 CER | **v2 CER** | Δ | v1 WER | v2 WER | Δ |
|---|---|---|---|---|---|---|
| Kannada | 0.052 | 0.053 | +0.001 | 0.276 | 0.307 | +0.031 |
| Malayalam | 0.054 | 0.059 | +0.004 | 0.334 | 0.361 | +0.027 |
| English | 0.073 | **0.067** | −0.007 | 0.195 | **0.181** | −0.014 |
| Gujarati | 0.074 | **0.071** | −0.003 | 0.286 | 0.300 | +0.014 |
| Bengali | 0.094 | 0.100 | +0.005 | 0.337 | 0.359 | +0.022 |
| Telugu | 0.103 | **0.100** | −0.003 | 0.434 | **0.431** | −0.003 |
| Marathi | 0.108 | 0.108 | 0.000 | 0.415 | 0.416 | 0.000 |
| Hindi | 0.141 | **0.113** | **−0.028** | 0.305 | **0.281** | −0.025 |
| Odia | 0.143 | **0.134** | −0.009 | 0.504 | 0.509 | +0.006 |
| **Tamil** | 0.149 | **0.129** | **−0.020** | 0.532 | **0.501** | −0.031 |
| **Mean CER** | **0.0992** | **0.0934** | **−0.0058 (−5.9%)** | | | |

**v2 is better on 6 languages, worse on 2, unchanged on 2.**

### Verdict

**Switch to v2 — but describe it accurately.** It is a *modest* improvement, not a transformation:
- Mean CER improves **5.9% relative** (0.0992 → 0.0934)
- The **worst language improves most**: Tamil 0.149 → 0.129
- Hindi improves notably: 0.141 → 0.113
- But Bengali, Kannada and Malayalam get slightly **worse**

Same download size, same phone footprint, same integration work. There is no cost to switching.

**Do not overclaim it.** The honest line for the deck is: *"v2 of the model reduces mean character
error rate from 0.099 to 0.093 across the ten required languages, with the weakest language
improving most."*

### How to reproduce

```bash
cd output/option_a_stt
OMNI_DIR=omni300m            OMNI_TAG=v1 ./.venv/bin/python fleurs_eval_full.py 200
OMNI_DIR=omni300m_v2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05 \
  OMNI_TAG=v2 ./.venv/bin/python fleurs_eval_full.py 200
```

Results land in `SUMMARY_full.json` (v1) and `SUMMARY_full_v2.json` (v2).

---

## Part 2 — TTS coverage for the ten required languages

This was an **unexploded gap**: the deck says "TTS: Piper VITS voices" without stating coverage.
Piper covers only 6 of the 10. Full audit across every source we have:

| Language | Neural voice available | Source | Licence |
|---|---|---|---|
| Hindi | ✅ | Piper `hi_IN` pratham | **MIT** |
| Marathi | ✅ SHIPPED | Piper `mr_IN-google-medium` | MIT wrapper / **CC-BY-SA 4.0** data |
| Malayalam | ✅ SHIPPED | Piper `ml_IN-arjun-medium` | MIT wrapper / **unclear** data |
| Telugu | ✅ SHIPPED | Piper `te_IN-venkatesh-medium` | MIT wrapper / **CC-BY-4.0** data |
| Bengali | ✅ SHIPPED | Piper `bn_BD-google-medium` | MIT wrapper / **CC-BY-SA 4.0** data |
| English | ✅ SHIPPED | Piper `en_US` lessac | MIT wrapper / Blizzard 2013 |
| Hindi | ✅ SHIPPED | Piper `hi_IN-pratham-medium` | MIT wrapper / **CC-BY-NC-SA 4.0** |
| **Gujarati** | ❌ | Mimic3 is AGPL-3.0; IndicTTS needs a PyTorch port | — |
| **Kannada** | ❌ | MMS-TTS only (CC-BY-NC) | — |
| **Tamil** | ❌ | MMS-TTS only (CC-BY-NC) | — |
| **Odia** | ❌ | MMS-TTS only (CC-BY-NC) | — |

> **SUPERSEDED 27 September 2026 — two claims in this section were wrong.**
>
> **1. The "MIT" column above was never accurate for any Indic voice.** Piper's wrapper is MIT;
> the *training data* is not. `hi_IN-pratham-medium`, which has shipped in the APK all along, is
> **CC-BY-NC-SA 4.0 — non-commercial and share-alike**. The claim elsewhere in this project that
> the stack contains "no CC-BY-NC anywhere" was incorrect. See `TTS_LICENCE_AUDIT.md`.
>
> **2. espeak-ng cannot be a standalone voice, so the fallback below was never implementable.**
> espeak-ng is a *phonemiser* used inside a VITS model, not a synthesiser you can call directly.
> Neither `master` nor the `v1.13.8` tag of sherpa-onnx's Kotlin API has an espeak TTS config
> class, and the `OfflineTtsEspeakModelConfig` symbol is absent from the shipped
> `libsherpa-onnx-jni.so` — verified by inspecting the library. The 17.2 MB of espeak
> dictionaries in the APK phonemises text *for the Piper voices*; it does not generate audio on
> its own. The "quality is robotic but intelligible" trade-off described below therefore never
> arose.
>
> **What actually shipped:** six neural Piper voices (hi, mr, bn, ml, te, en), all four newly
> added ones CC-licensed and disclosed. Gujarati, Kannada, Tamil and Odia are **text-only**, and
> the app says so on screen rather than failing silently. The remaining route to all ten is
> AI4Bharat Indic-TTS (BSD-2, all 13 languages), which is FastPitch+HiFi-GAN in PyTorch and
> needs an NDK cross-compile of sherpa-onnx or a Kotlin reimplementation. Post-deadline project.

### Options considered for the four remaining languages

**Option A — MMS-TTS (Meta).** Has all four, good neural quality. **CC-BY-NC 4.0.** Rejected to
stay consistent with the team's own licence rule, though it is arguably compliant for a
non-commercial hackathon — the restriction bans proprietary, closed-source and *commercial*
voice SDKs, and CC-BY-NC is none of those three.

**Option B — AI4Bharat Indic-TTS.** **BSD-2**, covers 13 languages with male and female speakers.
Licence-clean and technically the right answer. Blocked on integration: the prebuilt JNI library
implements only `Vits, Matcha, Kokoro, Zipvoice, Kitten, Pocket, Supertonic`, so adding FastPitch
means rebuilding sherpa-onnx for Android with the NDK (neither NDK nor cmake is installed here)
or writing the model in Kotlin from scratch. Weeks, not days.

**Option C — text-only, stated openly.** What we did. Four languages display correct text and
are labelled TEXT-ONLY in the UI.

Then say this plainly in the submission:

> *"Six of the ten required languages have neural TTS voices, all MIT-licensed Piper weights
> (four carry Creative Commons training data, disclosed in TTS_LICENCE_AUDIT.md). Speech
> recognition works in all ten. Gujarati, Kannada, Tamil and Odia currently receive text without
> audio; a BSD-2 Indic-TTS port is the identified next step and is scoped in
> TTS_LICENCE_AUDIT.md."*

That is a stronger position than pretending all ten are equal, and stronger than silently shipping
## Part 3 — what this changes in the submission

| Document | Change needed |
|---|---|
| `FULL_RESEARCH_DOSSIER.md` | §3.3 TTS table — add coverage column and the 3-language caveat; §4 stack table |
| `ACCURACY_RESULTS.md` | add the v1/v2 comparison section |
| `build_deck.py` | slide 4 accuracy strip → v2 numbers; slide 6 → name the TTS gap |
| `ARCHITECTURE.md` | §7 known coverage gap — update with the resolution |
| `DECISION_SIH26173.md` | record the v2 decision and the TTS resolution |

---

## Addendum — 15 October 2026: the "broken English" cause, measured and fixed

**Symptom.** "When the user speaks English, sometimes the English on the other phone is broken."

**Cause — two faults, either one sufficient.** The live path had no language at all:

1. The sender fixed `langTag = hin_Deva` (`MainActivity.kt:140`, no UI ever changes it) and
   filtered every transcript against Hindi. That did **not** drop English: `rangesFor("hin_Deva")`
   adds Latin "for numerals and loanwords", so whole Latin-English sentences passed the filter and
   travelled tagged `hin_Deva`.
2. The receiver's live path never consulted the message's `lang` field: `onPacket` spoke with
   whatever voice was loaded at startup — the phone's own default, Hindi. The only place
   `received.lang` was used was the unused loopback path.

**Measured with the shipped models** (`eval/probe_en_script.py`, `eval/probe_voice_mismatch.py`,
macOS arm64; scripts committed):

| Probe | Result |
|---|---|
| English speech → script: `bench_00_en`, `bench_01_en`, 20×FLEURS en_us | **Latin 22/22** |
| Hindi speech → script: `bench_02_hi`, 5×FLEURS hi_in | **Devanagari 6/6** |
| Hindi voice reads "hello i am fine how are you" | ASR hears "halua yam fine how a yu" — similarity 0.76 |
| English voice reads the same | similarity 0.96 |
| Hindi voice reads "the bridge has collapsed please send help to the school" | ASR hears "अबिद्ज हज कलापस तपली संडहल्पतर सकूल" — unrecognisable |
| English voice reads the same | similarity **1.00** |
| Hindi voice reads Devanagari-transliterated English ("हैलो आई एम फाइन") | ASR hears "hluim pin" |

The script is a reliable signal for the spoken language here, and the cost of getting the voice
wrong is not subtle: a whole sentence dissolves.

**Fix (same day).**

- `LanguageFilter.tagForScript` — the language the text's own characters say it is (Latin →
  `eng_Latn`, Devanagari → `hin_Deva`, each Indic script its own tag; ties go to Latin). Sender
  now infers `spoken` from the transcript, sends it as `lang`, and runs `filter(raw, null)` (the
  nine-script check).
- `LanguageFilter.voiceTagFor` — receiver picks the voice from the message: keeps the sender's tag
  when the text agrees (a `mar_Deva` tag stays Marathi), follows the text's script when it
  contradicts. `onPacket` loads the needed voice on demand (`ensureVoiceLocked`; one voice
  resident, load+synthesis under one lock) and shows text-only when there is no voice.
- 15 JVM tests in `LanguageRoutingTest` + the Python twin in `eval/language_filter.py` pin it.

**Deliberate trade-off, recorded.** Enforcing one expected alphabet protected against noisy
Indic→Indic script flips (Hindi coming back in Gujarati — the case `LanguageFilter`'s own doc
comment was written for). Without a language selector there is no honest expected alphabet, so the
sender now drops only letters outside all nine scripts. A flipped segment therefore travels tagged
with the script it came back in — visible to the receiver as wrong-script text or text-only, never
silently mis-pronounced. If field logs show this happening, the next step is a language selector
(the `LanguageFilter` doc comment already assumed one exists) or a per-push-to-talk lock.

**On-phone check (two phones, 2 minutes).**

1. Phone A — speak the English bench sentences. Log must show
   `language hin_Deva -> eng_Latn (inferred from the transcript)` then `packet … B -> transport`,
   with no `DROPPED whole segment` line.
2. Phone B — first English message must log
   `voice hin_Deva -> eng_Latn (from the message script)` and `TTS voice -> en_US-lessac-medium`
   (one-time reload; ~0.2–0.5 s before that message speaks), then plain playback. Speech must be
   English, not the old Hindi-voice reading.
3. Speak Hindi from phone A — no `voice …` swap line; the Hindi voice is already resident.

---

## Addendum 2 — 15 October 2026: Hinglish routing, speech conditioning, one rule removed by measurement

Addendum 1 fixed **which voice** speaks a message. This one fixes **what it reads** when the text
mixes scripts, and re-measures what the voices actually mispronounce — with repeats, after a
single-run reading nearly shipped a rule that does nothing.

### 1. Hinglish goes to the Devanagari voice when the sentence is really Hindi

`dominantScript` counted letters, so "स्कूल में emergency alert भेजो" (14 Latin vs 6 Devanagari
letters) routed to the **English** voice, which dissolves the Hindi words (`eval/probe_hinglish_routing.py`,
each sentence through each candidate voice, back through our own ASR):

| Text | English voice | Devanagari voice |
|---|---|---|
| "स्कूल में emergency alert भेजो" — 3 Devanagari words | "woolman wil emergency aleart be able" — Hindi words gone | "स्कूल में मजजोन सी इल्लवत भीजो" (0.71 raw → **0.93** with the loanword map) |
| "the police went to स्कूल" — ONE Devanagari word | "the police went to school" — correct | "pplis వంt to స్కూల్" — destroyed, even the script |

**Rule:** two or more Devanagari words → Devanagari wins, even against a Latin letter majority
(`LanguageFilter.dominantScript`; its KDoc carries both measured cases). One Devanagari word does
not flip an English sentence. The sender's inferred tag and the receiver's voice choice both
inherit it; the Python twin matches across the parity cases.

### 2. SpeechText — speech-only conditioning at the TTS call site

New `SpeechText.kt`, applied inside `TtsEngine.synthesise`, never to the displayed message, gated
on the **speaking voice** (the map's output is Devanagari; only Hindi/Marathi voices receive it).
Two rules survive repeats, 3 runs each, `eval/probe_normaliser_roundtrip.py`:

| Rule | Raw | Conditioned | Heard (conditioned) |
|---|---|---|---|
| Latin words in Devanagari text → Devanagari (closed 39-entry emergency map) | 0.77 | **0.93** | "स्कूल में इमेर्जेंसी अलर्ट भीजो" |
| lowercase acronyms → letter-spaced (sos, fir, ndrf, sdrf, cbi) | 0.79 | **0.90** | "send sauce" → "sends os" (the letters) |

Controls (identical text on both sides) bounce ≤0.03, so the table is signal, not noise.

### 3. A rule that measurement removed — number-to-words

A single early run made Hindi digits look broken ("500" heard as "5ा00"). On that reading a full
expander was built — Hindi 0–99 words, lakh/crore, "9+ digits read one by one", ~100 lines and 10
tests. Then `eval/probe_hindi_numbers.py` re-ran with repeats: the Hindi voice reads "108"
correctly ("ek so art" = एक सौ आठ) and an identical-text control scored 0.94 vs 0.85. The expander
solved a non-problem and was **deleted before commit**; the probe stays as the record. Lesson:
single-run VITS sampling plus a digit-happy ASR is not evidence.

### 4. Digits through every voiced language — nothing to fix

Raw "108 और 500" spoken by each voice, ASR back: hi "एक सौ आठ और पांच" · mr "એકશે આઠ અर પાશે" ·
te "ఒకవంద ఎనిమిది, అిదువందలు" · ml "നൂറ്റി എട്ത്, അഞ്യൂര്" · ta "നൂറ്ചി െട്ടു, ഐനൂര്" ·
bn "axo aodpaco". Hindi and Marathi come back clean; the other four are loose, sometimes
cross-script renderings of their own number words. espeak converts digits natively in every
shipped voice, so digits are left alone everywhere.

### 5. Phone check (extends Addendum 1's)

1. Phone A — say "स्कूल में emergency alert भेजो". The sender infers Hindi (two Devanagari words);
   phone B keeps the Hindi voice (no swap line) and **speaks** "स्कूल में इमरजेंसी अलर्ट भेजो"
   while the screen still shows "emergency alert". That difference is the feature.
2. Phone A — say an English sentence containing one Hindi word. Phone B logs
   `voice … -> eng_Latn (from the message script)` and speaks English.
3. Say a number ("500 लोग", "108"). The app does not touch digits; if one sounds wrong it is voice
   quality, not conditioning — re-check with `.venv/bin/python eval/probe_normaliser_roundtrip.py`.

---

## Addendum 3 — 15 October 2026: English voice audition — tied on intelligibility, not on cost

The team's complaint ("English pronunciation is not good enough") doesn't name a voice, so three
candidate upgrades were downloaded and auditioned against the shipped voice on a 4-sentence English
bench, 2 runs each, ASR-back through the same model the app ships (`eval/probe_voice_audition.py`;
macOS arm64, CPU, 2 threads — phone numbers will differ, the ranking is the point).

| voice | mean sim | synth s | RTF | size MB | load s |
|---|---|---|---|---|---|
| lessac-medium (shipped) | 0.920 | 0.14 | **0.05** | 63 | 0.5 |
| lessac-high | 0.919 | 1.06 | 0.39 | 114 | 0.7 |
| ryan-high | **0.929** | 0.94 | 0.36 | 121 | 0.4 |
| Kokoro v1.0 int8 (af_alloy) | 0.917 | 2.31 | 0.73 | 142 (+6 lexicon) | 0.7 |

What the table says, and does not:

- **Intelligibility is a tie** (0.92 ± 0.01). The 0.72-0.80 digit rows are an artifact: our ASR
  writes numbers as words ("one hundred eight") while the target carries digits ("108"). Every
  voice read them correctly.
- **Cost is not a tie.** High-tier Piper is ~8x the compute of medium here (RTF 0.36-0.39 vs
  0.05), Kokoro ~15x (0.73). RTF is synth wall-time per second of audio; a phone core is several
  times slower than an M1 Pro core, so none of the three should be packaged before the on-device
  BENCH run (it logs TTS timings; `MainActivity.kt`, BENCH TTS section).
- Measured sizes (HF byte counts): ryan-high is **121 MB** (an earlier note said 89 MB — that was
  a truncated download, not this voice), lessac-high 114 MB, Kokoro 114 (model) + 28.2 (voices,
  all languages in one file) + 6 (en lexicon) ≈ 148 MB.
- Kokoro bundles **Hindi** voices. Single-run probe, the app's mapped sentence
  "स्कूल में इमरजेंसी अलर्ट भेजो": sids 31/32 → 0.98/0.95 (pratham measured 0.93 over 3 runs after
  the same map). Indicative only — single runs are leads — but if Kokoro is chosen for English,
  usable Hindi appears to come free. Kokoro's other languages (fr/es/it/pt/ja/zh) do not cover our
  remaining Indic set; those stay Piper regardless.
- Kokoro's English speaker trial (6 sids, one sentence): every English sid scored 0.96-0.99. The
  v1.0 English sids behave as the model card order says (0-3 female af_*, 11/16 male am_*).

**The ear decides.** Same sentence, loudness as emitted: `docs/voice_samples/r2-lessac-medium__bridge.wav`
→ `r2-lessac-high__bridge.wav` → `r2-ryan-high__bridge.wav` → `r2-kokoro-v1.0-af_alloy__bridge.wav`
(or `...-am_michael__bridge.wav`). The 27 September set (`docs/voice_samples/README.md`) still
covers amy/kristin/cori and the v0.19 Kokoro.

Working through it:

1. Listen first. A 0.01 similarity gap decides nothing; the complaint is perceptual.
2. If a Piper high wins: the app change is one asset + one constant (same VITS path), then BENCH on
   the target phone. RTF ≥ 1 on-device means the alert starts speaking late — a safety cost.
3. If Kokoro wins: same swap plus a config branch (`OfflineTtsKokoroModelConfig`, already vendored
   in `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt`, and present in the shipped
   `libsherpa-onnx-jni.so`), ~148 MB, ~15x Piper-medium compute, Hindi likely included.
4. If nothing beats lessac-medium by ear, keep 63 MB / 0.05 RTF — and the "not good enough" is then
   a text or phonemisation problem (which this batch of work has already been fixing), not a voice
   problem.
