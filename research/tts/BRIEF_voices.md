# BRIEF — offline English TTS voice options (research)

Repo: /Users/prathameshbhujbal/iTantra-SIH26173 — an offline Android emergency-messaging app.
Stack today: sherpa-onnx 1.13.8 (Android libs) + Piper VITS voices; the English voice is
`en_US-lessac-medium` (22.05 kHz mono), bundled at `app/app/src/main/assets/tts/`. espeak-ng is
the phonemiser. The device never has a network connection; anything you recommend must run fully
offline on Android.

Problem: listeners report the English speech "sometimes sounds broken / unnatural". We want the
best offline English quality that still fits the app's RAM and disk budget (phones ~2 GB RAM).

Find, with concrete evidence (links, versions, sizes in MB, licenses):

1. Other Piper English voices suitable for Indian-context English — e.g. `en_US-ryan-high`,
   `en_US-libritts_r-medium`, `en_US-hfc_female-medium`, `en_US-amy-medium`, `en_GB-alan`,
   `en_GB-cori`, etc. Which are considered most natural/clear? Quality tier vs size. Download
   URLs (rhasspy/piper-voices on HuggingFace, k2-fsa sherpa-onnx releases).
2. Kokoro TTS via sherpa-onnx (`kokoro-en-v0_19`, `kokoro-multi-lang-v1_0`, …): does sherpa-onnx
   support it, and through which Kotlin/Java API? Model size, license, English quality evidence,
   whether it also has Indic voices. Sources: k2-fsa.github.io/sherpa/onnx/tts/kokoro.html and
   the k2-fsa/sherpa-onnx releases.
3. Anything else offline that runs on Android via ONNX or sherpa-onnx with clearly better English
   than Piper.

End with a recommendation: first choice to try on Android, second choice — each with size, license,
and the exact URL. Mark anything you could not verify as "unverified".

Write your report to: research/tts/voices.md
Do NOT modify any other file in the repo. Use `scrapling` (installed globally) or curl. Be concise
(<200 lines). Last line of your stdout: DONE research/tts/voices.md
