# Language routing for script-ambiguous text (Android, offline)

## Problem

The ASR is character-level CTC and occasionally writes Indian-accented English in
Devanagari: `हैलो आई एम फाइन` for "hello I am fine". Script/Unicode rules already
used by `LanguageFilter` cannot decide voice in that case, and speaking it with the Hindi
Piper voice yields broken English. Router input is the transcript plus a language tag;
the app loads **one** ~60 MB Piper voice at a time (`TtsEngine.voiceFor`), so switching
voices mid-message is not free.

## Routing strategies

### 1. Heuristic / common-word lexicon (cheapest)

Tokenise on whitespace/punctuation. For Devanagari text, compare each token against a
curated set of frequent English loanwords written in Devanagari (`हैलो`, `ओके`,
`प्लीज`, `थैंक्स`, `फाइन`, `गुड`, …, roughly 200–1000 entries). Route on token ratio
or absolute hits (unverified threshold; start at ~0.3 of tokens).

- **Cost:** ~a few KB list, O(n) string compares, no new dependency, no model load.
- **Pros:** works on the exact failure mode, deterministic, debuggable.
- **Cons:** misses novel words; spelling variants/regional transliteration; cannot
  distinguish "computer" pronounced as Hindi loanword vs English word without context.

### 2. Transliterate to Latin + dictionary (middle)

Convert Devanagari to a Latin guess (simple one-to-one mapping rules, ~KB), then match
against an English word list (10k–100k words). Better coverage than a hand list, but
schwa deletion and ambiguous mappings make the Latin form lossy. Transformer transliterators
exist (AI4Bharat IndicXlit, ~11M params) but are too heavy for this target.

### 3. Tiny on-device language-ID models

| Option | Size | Lic. | Langs | Notes |
|---|---|---|---|---|
| fastText `lid.176.ftz` (official) | 917 KB (.bin 126 MB) | CC-BY-SA 3.0 | 176 | Thousands docs/s on desktop (unverified on phone); model is monolingual. Devanagari English likely labelled `hi` by script prior (unverified — likely limitation). |
| Compact FastText (andriydruk) | ~2.6 MB src-embedded model data | unverified (weights CC-BY-SA 3.0) | 176 | Pure C, zero deps; same weights as official ftz. |
| CLD3 / CCLD3 (andriydruk) | ~1.4 MB src-embedded model data | Apache 2.0 (CLD3 original) | 109 | Pure C, 100% agreement with Google C++ on 3k samples. Author notes CLD3 is unreliable below ~150 chars (unverified accuracy on our short alerts). |
| Google CLD3 (original) | model embedded | Apache 2.0 | 109 | Archived, protobuf/Chromium dependency; impractical outside Chromium (unverified buildability on Android JNI). |
| ML Kit Language ID | ~900 KB bundled / ~200 KB dynamic | Google terms | 100+ | Works on-device; can be Play-Services-delivered. Docs explicitly state it does **not** identify multiple languages in one string. Devanagari English handling unknown (unverified). |
| Lingua (JVM) | ~30 MB (unverified, forum) | Apache 2.0 | 75+ | Pure Java, high accuracy, but memory-heavy for tight RAM. |
| langid.py | small (unverified) | BSD/MIT | 97 | Naive Bayes, pure Python → no Android runtime without Chaquopy/CPython; monolingual. |
| AI4Bharat IndicLID | 208 MB–1.4 GB | MIT | 47 (native + roman) | Indic-focused, romanised-text aware; best long-term reference, but far too large for on-device budget. |

Important caveat: every off-the-shelf LID above is **monolingual and script-prior driven**.
None is trained to label `हैलो आई एम फाइन` as English; they will almost certainly return
Hindi (unverified). That is exactly the ambiguity to solve, so off-the-shelf LID should
be measured (not assumed) before use.

### 4. Prior art: Hinglish / code-mixed TTS

Prior work treats code-mixed TTS as token-level language tagging plus per-token
phonemisation, rather than one language for the whole utterance
(Sitaram et al. 2016 "Speech Synthesis of Code-Mixed Text"; FonadaLabs 2026
code-mixed TTS guide):

- detect matrix vs. embedded language; Hindi usually matrix, English the embedded lexical items;
- switch G2P per token; blend prosody at boundaries (Hindi mora-timed vs English stress-timed);
- use different phoneme inventories (~44 vs ~52 for Hindi incl. retroflex/aspiration);
- distinguish borrowed words spoken with adapted phonology ("computer" -> "kampyootar") from
  words keeping English phonology.

Known failure modes:

- Dominant-language detection "steamrolls" the other language → mangled words.
- Intra-sentential mixing is far harder than inter-sentential (cannot split cleanly).
- Ambiguous graphemes/shared scripts need surrounding context; boundaries remain ambiguous.
- Prosody discontinuities at switch points sound robotic.
- Monolingual LIDs do not identify multiple languages in one string (ML Kit documents this).
- CLD3-type models are unreliable on short strings (<150 chars, author note).
- A single Piper voice cannot actually switch phoneme sets mid-utterance; doing it requires
  chunking, per-chunk voice load/unload on a 60 MB model, and PCM concatenation.

Espeak/Piper language switching inside one string via SSML `<lang>` was not verified
against sherpa-onnx Piper in this repo.

## Conclusion

- **Cheapest to implement now:** heuristic lexicon (~KB, O(n), deterministic) combined with
  a manual language override on the sender side. Fits tight RAM, no native integration.
  Expected accuracy: good on common words, poor on unseen vocabulary. Mark accuracy predictions
  as estimates, not measurements.

- **Most accurate realistic option (still on-device):** a small custom supervised fastText
  model (target ~1–3 MB, C++/JNI or ML Kit-style packaged) trained on Devanagari strings
  labelled by *intended* language, including transliterated English and mixed examples.
  IndicLID (MIT) is the right reference dataset/recipe, but its released models
  (208 MB–1.4 GB) are unusable here. Measure against a held-out set of real ASR transcripts
  before shipping.

- **Do not expect a fix from off-the-shelf LID** (fastText 917 KB, CLD3 1.4 MB, ML Kit 900 KB):
  their script prior will label Devanagari as Hindi. Verify with our own traces first.

- **Full code-mixed voice switching** is out of budget (60 MB per voice, one loaded at a
  time); for occasional English-in-Devanagari, chunked synthesis is the only faithful route
  and should be scoped as a separate task.

Unverified claims are marked "unverified" throughout.