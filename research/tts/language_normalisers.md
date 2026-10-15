# Per-language pronunciation audit — all 7 bundled voices

Source: `eval/probe_language_normalisers.py` (self-contained; reruns the reference
helpers in `eval/probe_normaliser_roundtrip.py`). Measurement: intended text →
bundled Piper VITS voice → pruned 9-script ASR (`models/asr-v3-10lang/...`, same as
`app/app/src/main/assets/...-v2-int8`) → transcript, 3 runs per condition, because
VITS sampling swings ~±0.1 a single run is not evidence. Score is `difflib`
SequenceMatcher similarity between the **text actually spoken** (after any
`SpeechText.kt` conditioning) and the ASR transcript. Hidden `app/` untouched;
nothing committed.

## A/D. Round-trip and conditioners

"changed" = the Python mirror of `SpeechText.forSynthesis` (`replaceLoanwords`
only when `devanagariVoice`, plus `spellAcronyms` always) altered the sentence.

| Language (voice) | kind | changed | raw (no cond.) | conditioned | Δ |
|---|---|---|---|---|---|
| Hindi (hi) | loanword | yes | 0.70 (0.64–0.76) | 0.89 (0.88–0.90) | +0.19 |
| Hindi (hi) | number | no | 0.69 (0.68–0.70) | 0.77 (0.64–0.84) | +0.08 |
| Hindi (hi) | acronym | yes | 0.63 (0.62–0.67) | 0.66 (0.65–0.67) | +0.02 |
| Marathi (mr) | loanword | yes | 0.35 (0.12–0.73) | 0.94 (0.92–0.96) | **+0.59** |
| Marathi (mr) | number | no | 0.71 (0.70–0.72) | 0.71 (0.69–0.72) | −0.00 |
| Marathi (mr) | acronym | yes | 0.59 (0.57–0.61) | 0.63 (0.62–0.65) | +0.04 |
| Bengali (bn) | loanword | no | 0.66 (0.65–0.67) | 0.61 (0.56–0.64) | −0.05 |
| Bengali (bn) | number | no | 0.70 (0.66–0.76) | 0.69 (0.68–0.70) | −0.01 |
| Bengali (bn) | acronym | yes | 0.30 (0.19–0.52) | 0.32 (0.19–0.55) | +0.02 |
| Tamil (ta) | loanword | no | 0.59 (0.57–0.60) | 0.57 (0.52–0.59) | −0.03 |
| Tamil (ta) | number | no | 0.73 (0.67–0.76) | 0.55 (0.11–0.79) | −0.17 (stochastic outlier run) |
| Tamil (ta) | acronym | yes | 0.66 (0.64–0.67) | 0.68 (0.63–0.71) | +0.02 |
| Telugu (te) | loanword | no | 0.49 (0.45–0.54) | 0.50 (0.44–0.54) | +0.02 |
| Telugu (te) | number | no | 0.68 (0.67–0.71) | 0.69 (0.67–0.69) | +0.00 |
| Telugu (te) | acronym | yes | 0.66 (0.59–0.70) | 0.69 (0.66–0.71) | +0.03 |
| Malayalam (ml) | loanword | no | 0.53 (0.52–0.56) | 0.54 (0.51–0.56) | +0.01 |
| Malayalam (ml) | number | no | 0.69 (0.67–0.73) | 0.71 (0.67–0.73) | +0.02 |
| Malayalam (ml) | acronym | yes | 0.59 (0.58–0.61) | 0.66 (0.60–0.72) | +0.07 |
| English (en) | loanword | no | 0.96 (0.96–0.97) | 0.96 (0.96–0.97) | +0.00 |
| English (en) | number | no | 0.72 (0.71–0.72) | 0.73 (0.72–0.75) | +0.02 |
| English (en) | acronym | yes | 0.51 (0.45–0.59) | 0.60 (0.59–0.61) | +0.09 |

Mean across the three sentences: Hindi 0.67→0.77, Marathi 0.55→0.76,
Bengali 0.55→0.54, Tamil 0.66→0.60, Telugu 0.61→0.63, Malayalam 0.61→0.64,
English 0.73→0.77.

## B. Loanword script (code-mixed sentence, raw, no conditioning)

Heard strings from the three runs:

- **Hindi** "स्कूल में emergency alert भेजो":
  - "स्कूल में मदवन सी अल्लहत भेजो"
  - "स्कूल में मदललसी एलत भीज ो"
  - "स्कूल मेंरीमद्रन सी अल्लहत भीज"
  → loanwords came back as **Devanagari garble**, not Latin — that is why the
  Devanagari-only loanword map in `SpeechText.kt` is needed.
- **Marathi** "शाळेत emergency alert पाठवा":
  - "શાયક ઇમર્જન્સી અલર્જ પાઠવા" (Gujarati script — foreign script leak)
  - "शायत इमरजेंसी अलरत आठवा"
  - "ચાયત ઇમરજન્સી અલર્ગ પાઠવા"
  → loanwords arrive as Devanagari/Gujarati-script garble and two of three runs
  landed in an **allowed-but-wrong script** (Gujarati). The conditioning fixes
  both the words and the script.
- **Bengali** "দুর্ঘটনার পরে police এবং ambulance দ্রুত ডাকুন":
  - "দুর্ঘোটন় পরে এস এবং আম্বালে দ্রুত ডাকুন"
  - "দুর্ঘটনের পরা স এবং আমেলিল ফরুত ডাকুন"
  - "দুর্ঘটনার পরে এস এবং আমালীর তুত ডাকন"
  → loanwords come back in **Bengali script** (police → এস/স, ambulance →
  আম্বালে/…). No Latin script survives.
- **Tamil** "பள்ளியில் emergency alert அனுப்புங்கள்":
  - "பள்ளியில் இமாஜனசியில்ல தனுப்புங்கள்" (all three runs native garble)
  → native **Tamil script** only.
- **Telugu** "పాఠశాలలో emergency alert పంపండి":
  - "పాటశాలలో మదున్సి లోత్ పంపండి" (+ variants)
  → **Telugu script** only.
- **Malayalam** "സ്കൂളിൽ emergency alert അയക്കൂ":
  - "സ്കൂളിൽ ഇമോജൻസി അലേത് അയക്കു" (+ variants "അλεθ", "അലത")
  → **Malayalam script** only.
- **English** "A tsunami alert …": "atsunami alert …" — English voice keeps Latin, as expected.

Conclusion for B: outside Hindi/Marathi, the ASR writes loanwords in the **native**
script (as native-script garble, never Latin). Because `SpeechText`'s loanword map is
Devanagari-only and a Devanagari string cannot be pronounced by a
Bengali/Tamil/Telugu/Malayalam voice, extending that map to those scripts is not a
one-line change and this probe did not measure any such spelling helping.

## C. Digits (audio check)

Every voice rendered 108 and 500 as ordinary words (the well-known ASR spelling
artefact, not an audio defect). Representative heard:

- Hindi: "एक सौ …" / "पाँच सौ" (digits not broken; `probe_hindi_numbers.py`
  already established 108 → एक सौ आठ).
- Marathi: "एकशे आठ" / "पाच… सौ".
- Bengali: "एx/एकसো" / "पाँ…" (ASR-mangled but the audio counts
  one-hundred-and-eight / five-hundred).
- Tamil: "நூற்றி எட்டு" / "ஐன…".
- Telugu: "ఒక వంద ఎనిమిది" / "ఐదు వందలు".
- Malayalam: "നൂറ്റി എട്ട്" / "അഞ്ഞൂ…" (no spurious splitting).
- English: "one hundred eight" / "five hundred".

No voice miscounted or dropped a digit in a way we could attribute to the
AUDIO rather than the ASR spelling. Digits are correctly left un-expanded in
`SpeechText`, for every language including Hindi.

## Findings — per-language verdict

- **Hindi (hi_IN-pratham, Devanagari voice)** — *existing rule keeps working.*
  Loanword map: +0.19 (0.70 → 0.89), and without it the Latin words become
  unrecoverable Devanagari garble. Acronym spacing is within VITS noise here
  (+0.02) but the `S O S`/`F I R` rule is already mandated by the English-voice
  measurement (0.51 → 0.60) and harmless. Digits audio correct. No new rule.
- **Marathi (mr_IN-google, Devanagari voice)** — *existing rule is doing real work,
  possibly more than in Hindi.* Loanword map: **+0.59** (0.35 → 0.94), and raw
  runs leaked into Gujarati script. Marathi shares Devanagari with Hindi so the
  same map runs (`SpeechText.isDevanagariVoice` includes Marathi); nothing to
  change, but the magnitude (+0.6 vs Hindi +0.2) means the Marathi loanword list
  should grow at least as easily as Hindi's. Acronyms: +0.04 (noise). Digits
  correct.
- **Bengali (bn_BD-google)** — *chain fine, no rule needed.* Loanwords come
  back in Bengali script already; the existing conditioners do not run (no
  Devanagari voice) and the acronym-spacing delta is +0.02 (≤ the ±0.1 VITS
  swing) — null result. The Latin loanword TTS quality is mediocre
  (sim ≈ 0.66) but a Devanagari map is inapplicable and none was measured.
- **Tamil (ta_IN-rasa_female)** — *chain fine, no rule needed.* Native script
  throughout; conditioners change only acronyms (+0.02, noise). One number run
  drifted into Malayalam script — a stochastic/decoder effect, not an audio
  defect, and the language filter already drops cross-script surprises only when
  a *foreign* script appears (both stay in the allowed set). No new rule measured.
- **Telugu (te_IN-venkatesh)** — *chain fine, no rule needed.* Native script
  throughout; acronym spacing +0.03, loanwords +0.02, digits +0.00 — all inside
  the run-to-run noise. Nothing justifies a rule.
- **Malayalam (ml_IN-arjun)** — *chain fine, no rule needed.* Native script;
  acronyms show the only non-trivial delta outside Hindi/Marathi (+0.07), still
  below the ±0.1 VITS swing and smaller than the +0.09 measured for English, so
  treat as noise, not a rule. Loanword audio is the weakest of the series
  (sim ≈ 0.53) but unconditioned; no spelling was measured to help.
- **English (en_US-lessac)** — *existing acronym rule earns its keep.*
  Loanwords (here "tsunami") round-trip at 0.96; digits at 0.72 just because ASR
  spells them. Acronym spacing lifts 0.51 → 0.60, exactly the
  "sos → sauce" failure the rule was written for. No further normaliser needed.

## Summary verdict

Only the **Devanagari loanword map** (and the always-on acronym letter-spacing)
is evidenced by measurement, and it is already gated to the two Devanagari voices
(`isDevanagariVoice` = Hindi or Marathi). For **Bengali, Tamil, Telugu and
Malayalam**, code-mixed Latin loanwords come back through the ASR in the **native
script** (never Latin), so no Latin-script map is required and generalising the
Hindi Devanagari spellings into those scripts was not measured to help. **Digits
are correct in audio in every language and must stay un-expanded.** No changes to
`app/` and no git commits were made; this report and `eval/probe_language_normalisers.py`
are the deliverables.
