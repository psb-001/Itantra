# BRIEF — language routing for script-ambiguous text (research)

Repo: /Users/prathameshbhujbal/iTantra-SIH26173 — offline Android app. A character-level
multilingual CTC ASR (sherpa-onnx OmnilingualASR, pruned to 10 languages) transcribes speech; a
language tag decides which Piper TTS voice speaks it on the receiving phone.

Hypothesis to research: Indian-accented English is sometimes transcribed into Devanagari script
(e.g. "हैलो आई एम फाइन" for "hello I am fine"), so a *script*-based language decision cannot tell
English from Hindi, and the Hindi voice reading those English words produces "broken English".

Research (offline / on-device only; target Android, tight RAM):

1. Ways to route text to the right voice when script is ambiguous: detecting English words written
   in Devanagari (transliteration tables, common-word lists?), heuristics over Indic text with
   Latin borrowings, or tiny language-ID models that run on device.
2. Survey tiny offline language-ID options with size/license/speed: fastText lid.176 (is there a
   quantised/compressed version suitable for Android?), CLD3, langid.py, anything else practical;
   include actual file sizes if available.
3. Prior art: how do other Indic apps / libraries decide TTS voice per message or per word for
   Hinglish (code-mixed) text, and what are the known failure modes?

Add your own conclusion: cheapest-to-implement option vs most-accurate option, with sizes and CPU
costs. Mark unverified claims as "unverified".

Write your report to: research/tts/routing.md
Do NOT modify any other file. Use `scrapling` (installed) or curl. Concise (<200 lines).
Last line of stdout: DONE research/tts/routing.md
