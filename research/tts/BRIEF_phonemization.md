# BRIEF — espeak-ng phonemisation + text normalisation (research)

Repo: /Users/prathameshbhujbal/iTantra-SIH26173 — offline Android app, sherpa-onnx Piper VITS TTS.
Bundled voices: hi_IN-pratham, mr_IN-google, bn_BD-google, ml_IN-arjun, te_IN-venkatesh,
ta_IN-rasa, en_US-lessac (see `app/app/src/main/java/com/bjp/itantra/speech/SpeechEngine.kt`).
espeak-ng-data is trimmed to 10 language dictionaries by `eval/trim_espeak.py` (en, hi, mr, bn,
ml, te, ta, gu, kn, or are kept).

Answer with evidence (links, quotes, versions):

1. How does sherpa-onnx Piper TTS invoke espeak-ng, and is there a *supported* way to add a
   custom pronunciation lexicon / dictionary override per voice (for fixing words the model
   mispronounces)? Check sherpa-onnx docs + examples + issues.
2. What happens when Latin words appear inside Devanagari text, and vice versa — does espeak-ng
   switch dictionaries per word or phonemise the whole string with one language's rules? Known
   community fixes?
3. Offline text normalisation for TTS input: numbers, currency, dates, acronyms, units, emoji,
   punctuation. What is the smallest *correct* approach for English, and for English words inside
   Indic text? (rule lists? num2words-style? maybe it is already handled?) Keep it Android-light.
4. Check the actual voice metadata files in `tts/` (the repo root, not assets) — do the Piper
   `.onnx.json` files there carry an `espeak.voice` field, and does it match the voice language?
   Report what each bundled voice declares.

Write your report to: research/tts/phonemization.md
Do NOT modify any other file. Use `scrapling` (installed) or curl. Concise (<200 lines).
Last line of stdout: DONE research/tts/phonemization.md
