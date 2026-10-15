# BRIEF — trace every path where the wrong TTS voice can be chosen (repo audit)

Repo: /Users/prathameshbhujbal/iTantra-SIH26173. READ-ONLY audit: do not modify any file except
your own report.

Context: on the receiving phone the voice is picked by `TtsEngine.voiceFor(received.lang)`. On the
sending phone a `langTag` is set (the field initialises to `Lang.HINDI`) and is passed to
`LanguageFilter.filter(raw, langTag)`, which drops a segment whose characters are outside the
expected script.

Deliverable: `research/tts/code_audit.md` — concrete findings, each with `file:line` quotes,
answering:

1. Where exactly is `langTag` set, on every path — live capture, WAV/test file, benchmark,
   loopback? Is there any UI that lets a user indicate "I am speaking English"?
2. If live-carried text is Latin-script English: what happens in `LanguageFilter.filter` (sent or
   dropped?), and if sent, which voice speaks it on the receiving phone?
3. If live-carried text is Devanagari-transliterated English ("हैलो आई एम फाइन"): which voice
   speaks it, and does anything downstream treat it as Hindi only?
4. Any other path where a message can be spoken by a voice whose language does not match its text
   (e.g. no voice bundled for the tag, fallbacks, `LanguageFilter` null-tag defaults).
5. The exact `log(...)` lines the app prints in each of those cases — so a phone log can tell us
   which path a "broken English" report travelled.

End with a short section: "Most likely mechanism for broken English over the live path" — one
paragraph, with the file:line evidence you found, or an explicit statement that the code does not
explain it.

Write the report to: research/tts/code_audit.md
Last line of stdout: DONE research/tts/code_audit.md
