# iTantra — SIH26173 · Presentation Script

**Deck:** `sih 173.pptx` (6 slides) · **Target:** ~5 minutes · **Team:** B.J.P (Bug Janta Party)
**Product:** iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access

> **Read this first.** The script below uses the **corrected v2 accuracy numbers** and fills the
> gaps the deck is missing (efficiency, TTS legibility, end-to-end latency). If you present the
> deck as it stands today, slide 4's table and slide 2's byte range will contradict what you say.
> Either patch the slides first or use the numbers exactly as written here — do not mix the two.

**Timing:** S1 0:20 · S2 1:00 · S3 1:00 · S4 1:40 · S5 0:50 · S6 0:15 → **5:05**

---

## Slide 1 — Title

**Time: 20 s**

**SAY:**

> Good morning. We're team B.J.P — Bug Janta Party — and we're presenting for problem statement
> SIH26173, from ISRO: *iTantra — Indian Multilingual TTS and STT Aided Neural Transceiver Radio
> Access for low bitrate links.*
>
> In one line: **we turn voice into text at the speaker's phone, send only the text, and turn it
> back into voice at the listener's phone — completely offline.** No cloud, no internet, no
> network infrastructure.

**Slide fix before you present:** the Problem Statement Title and Team ID fields are blank on this
slide. Fill both in.

**If asked "why the name iTantra":** it's the product name — say it once here so the judges attach
a name to the idea. Right now the deck never names it.

---

## Slide 2 — The problem and our solution

**Time: 1:00**

**SAY:**

> The problem statement is specific: audio is data-intensive, so it's hard to push through a low
> data rate link. And in an alert or distress situation, **audio beats text** — because audio
> reaches everyone, whether or not they can read.
>
> So we have a conflict. We need voice, but voice is too big to send.
>
> Three things go wrong with the obvious solution of just streaming audio. It needs continuous
> bandwidth. It needs a cloud speech API, which needs internet. And continuous audio transmission
> burns data and battery.
>
> Our answer is to **move the intelligence to the edge and send text instead of audio.**
>
> At the speaker's phone, speech is recognised locally and becomes a short text message. Only that
> text crosses the link. At the listener's phone, that text is synthesised back into speech.
>
> **[Point at the byte figure]** The measured payload is **49 to 111 bytes** of UTF-8 per sentence.
> Raw PCM for a three-second sentence at 16 kHz mono is about 93 kilobytes. That's roughly
> **a thousand times smaller** — which is why voice now fits where it couldn't before.

**Correction to make on the slide:** it currently says "46 – 111 bytes". Your `end_to_end.json`
says **49–111**. Change it.

**Honesty note — say this if you have time:** the 49–111 figure is the bare text payload. With our
JSON envelope on the wire it's roughly **150–250 bytes** — still 400 to 600× smaller than raw
audio. The slide's "excluding packet overhead" footnote is correct — just be ready to give the
real number.

**If asked "why only 4 utterances":** it's 4 test utterances from `end_to_end.json`. Say so
plainly, and offer that the accuracy benchmark below is 2,000 clips.

---

## Slide 3 — Technical approach

**Time: 1:00**

**SAY:**

> Here's the pipeline end to end, and every stage runs on the device.
>
> On the **sender**: push-to-talk. Silero VAD detects speech and cuts the sentence at natural
> pauses. Then Meta's Omnilingual ASR — a 300-million parameter CTC model, quantised to INT8,
> running through sherpa-onnx — converts it to text locally. We tag the text with its language.
>
> Over the **link**: Wi-Fi Direct as primary, Bluetooth RFCOMM as fallback. UTF-8 text only.
>
> On the **receiver**: Piper TTS in Hindi and English turns the text back into speech, played
> through the phone speaker. Alert messages play at maximum volume and loop until acknowledged.
>
> The key architectural choice: **the speaker's phone carries the ASR cost, the listener's phone
> carries the TTS cost.** When idle, both phones run only the voice activity detector, so idle CPU
> stays near zero by design.

**Numbers to add to this slide — they belong here and they're missing:**

- **ASR model: 348 MiB on disk, 279 MiB download, INT8.** This is the single most important
  efficiency number and it isn't on the slide.
- **Silero VAD: 629 KB, RTF 0.004.** You list VAD but give no size.
- **TTS voices: ~60 MiB each** (measured 60.6 and 60.3 MiB). This one *is* on the slide — good.
- **Debug APK: ~540 MB** (540,164,991 bytes — `iTantra-debug.apk`, current build). Be ready for
  this one. It's a debug build and the 348 MiB model ships inside it. Note your
  `VERIFICATION_STATUS.md` says 528 MB — that's the earlier build, so quote the current figure or
  say "about half a gigabyte."

**Correction:** the slide says "Sherpa—ONNX" with an em dash and "meta omnilingual ASR" in
lowercase. Make it **Sherpa-ONNX** and **Meta Omnilingual ASR**.

---

## Slide 4 — Feasibility and viability

**Time: 1:40 — this is the slide that decides your score**

**SAY:**

> The rubric weights accuracy at 40 percent, so this slide is measured data, not claims.
>
> We evaluated all ten languages the problem statement requires, on **Google FLEURS — real
> recorded human speech** — **200 clips per language, 2,000 clips, 6.7 hours of audio.**
>
> **[Point at the table]** These are normalised character error rates. Lower is better.
>
> Kannada 0.053, Malayalam 0.059, English 0.067, Gujarati 0.071, Bengali and Telugu 0.100,
> Marathi 0.108, Hindi 0.113, Tamil 0.129, Odia 0.134.
>
> **Every one of the ten required languages is under 0.14. Mean CER is 0.093.**
>
> On speed: real-time factor stays between **0.039 and 0.043** across all ten languages. A
> three-second sentence costs about **0.12 seconds** of ASR compute.
>
> And here's the context that matters. Meta published CER for their **7-billion parameter** model
> on the same languages. We're roughly 2 to 3 times worse — but that model is 25 GiB and cannot run
> on a phone. Ours is 348 MiB and does. On Malayalam we're within **1.1×** of it, on Telugu **1.5×**.
>
> I'll be straight about the weak end: **Odia at 0.134 and Tamil at 0.129 are our weakest.** Stock
> Whisper cannot transcribe Odia at all, so this model doing it at 0.134 is still a real capability
> — but it's the honest limit of a 300M model on-device.

**CRITICAL — the slide table must be replaced.** Every number currently on slide 4 is from the
**superseded v1** model. Your own `ACCURACY_RESULTS.md` says *"MODEL VERSION: use v2."* Corrected
table:

| Language | WER norm | CER norm | RTF median |
|---|---|---|---|
| Kannada | 0.307 | 0.053 | 0.041 |
| Malayalam | 0.361 | 0.059 | 0.041 |
| English | 0.181 | 0.067 | 0.040 |
| Gujarati | 0.300 | 0.071 | 0.041 |
| Bengali | 0.359 | 0.100 | 0.040 |
| Telugu | 0.431 | 0.100 | 0.039 |
| Marathi | 0.416 | 0.108 | 0.040 |
| Hindi | 0.281 | 0.113 | 0.040 |
| Tamil | 0.501 | 0.129 | 0.040 |
| Odia | 0.509 | 0.134 | 0.043 |

Also fix: "RTF values remain around 0.038 – 0.042" → **0.039 – 0.043**. And sort the table by CER,
not by RTF — sorting by RTF looks like you're hiding the weak languages.

**Say the raw-vs-normalised disclosure.** The model is CTC greedy decode, so it emits no
punctuation or capitals. Your `ACCURACY_RESULTS.md` rule is *"Report both. Never only the
flattering one."* One line: *"These are normalised scores — normalisation strips punctuation and
case, because our decoder doesn't emit them. Raw WER is roughly 1.3× higher."*

---

## Slide 5 — Impact and benefits

**Time: 0:50**

**SAY:**

> Who this is for: field responders, disaster and shelter teams, and remote or industrial sites —
> anywhere the network is degraded, saturated, or simply gone.
>
> The benefits follow directly from the architecture.
>
> **Low data** — only compact text crosses the network.
> **Offline** — all speech processing happens on the device. No cloud, no per-request cost.
> **Voice-first** — you speak instead of typing, so it works for users who can't read or type.
> **Accessible** — it runs on Android phones people already own.
>
> Socially, it's inclusive of non-literate users — alerts are heard, not read.
> Operationally, it removes dependence on internet-hosted speech services.
> Economically, there's no paid API and no proprietary SDK.

**Important framing — add this line, don't skip it:**

> These benefits are **proposed outcomes, not measured results.** We have measured the accuracy,
> latency and memory of the speech stages. We have not measured field performance in noise, battery
> runtime, or human legibility of the TTS. We're not claiming lives saved.

That single sentence buys you enormous credibility with a judging panel, and it's true.

---

## Slide 6 — Research and references

**Time: 0:15**

**SAY:**

> Everything we've built on is open source. The ASR model is Meta Omnilingual ASR, Apache 2.0. The
> runtime is sherpa-onnx. The TTS voices are Piper, MIT licensed. Evaluation audio is Google FLEURS
> under CC-BY-4.0. Transport follows the Android Wi-Fi Direct and Bluetooth documentation.
>
> All of it is reproducible from the scripts in our submission.

**MUST FIX — this slide currently ends with an internal note:**

> *"Do not claim 'everything is permissively licensed.'"*

That's a reminder to yourselves, not content for judges. It reads as a self-contradiction and it
invites a licence question you don't want. Replace it with a clean statement:

> **Licensing:** ASR model Apache 2.0, Piper voices MIT, sherpa-onnx Apache 2.0. The Kannada, Tamil
> and Odia TTS path uses eSpeak-ng, which is GPL — redistribution obligations for that component
> are under review.

Being upfront about the one GPL component is far stronger than a vague disclaimer. Also fix the
double period in "Piper voice repository..".

---

## Q&A preparation — the five questions that will actually be asked

**1. "You claim 49–111 bytes. What's the real on-wire size?"**
> "The bare text payload is 49–111 bytes. With our JSON envelope it's about 150–250 bytes. Either
> way it's roughly 375 to 1,900 times smaller than the raw PCM for the same sentence, which is
> about 93 kilobytes for three seconds."

**2. "Have you tested the two-phone link?"**
> "Not yet. We've executed everything on the device — the model loads on Android, the microphone
> path runs, VAD segments, ASR returns correct script, the packet serialises and deserialises, TTS
> synthesises and plays, and alert mode loops. **The radio is the one thing we have not executed** —
> our emulator has no Bluetooth adapter. Getting a real RFCOMM socket connected is our next step,
> and we're not going to pretend otherwise."

Do not bluff this one. `android/VERIFICATION_STATUS.md` documents 21 verified items and lists
Bluetooth, Wi-Fi Direct and two-device operation as unverified. If you claim the link works and
they probe, you lose the room.

**3. "Odia is at 0.509 WER — half the words wrong. How is that usable?"**
> "Two things. First, on a low-resource language the useful metric is character error rate, because
> the failure mode is spelling and spacing, not meaning — Odia is 0.134 CER, so a reader recovers
> the message. Second, the alternative on-device is nothing: stock Whisper cannot transcribe Odia
> at all. And third, we'd scope a production demonstrator to Hindi and English first, then extend."

**4. "What happens in noise? This is for disaster zones."**
> "We measured it, and it's our biggest known weakness. On clean audio Hindi is 0.133 CER. At 30 dB
> SNR — noise a human can't even hear — Hindi jumps to **0.424**. English and Bengali degrade
> gracefully; Hindi does not, and we don't yet know why. We're investigating whether it's the
> language-ID head or the CTC decoder. It is on our risk list and we're not going to hide it."

**5. "What about RAM and CPU on a cheap phone?"**
> "On an Android 34 emulator with the ASR model resident we measure **428 MB PSS / 535 MB RSS**.
> ASR real-time factor on the emulator is 0.120 to 0.127 — about three times slower than our
> desktop number. TTS is 0.055 to 0.058. Mouth-to-ear is around 0.9 to 1.0 seconds. **These are
> emulator figures on Apple Silicon, not a budget handset.** A real low-end phone will be slower,
> and benchmarking on the cheapest device we can buy is our next priority."

---

## Numbers cheat sheet — memorise these

| What | Value | Source |
|---|---|---|
| Languages measured | 10 of 10 required, 200 clips each | `SUMMARY_full_v2.json` |
| Total evaluation | 2,000 clips, 6.72 h audio | `SUMMARY_full_v2.json` |
| Mean CER (norm) | **0.0934** | `SUMMARY_full_v2.json` |
| CER range | 0.053 (Kannada) – 0.134 (Odia) | `SUMMARY_full_v2.json` |
| WER range | 0.181 (English) – 0.509 (Odia) | `SUMMARY_full_v2.json` |
| ASR RTF median | 0.039 – 0.043 (desktop) | `SUMMARY_full_v2.json` |
| ASR RTF (Android emu) | 0.120 – 0.127 | `android/evidence/benchmark_onddevice.txt` |
| TTS RTF (Android emu) | 0.055 – 0.058 | same |
| Mouth-to-ear | ~0.9 – 1.0 s (emulator) | same |
| Payload | 49 – 111 B text · ~150–250 B on wire | `end_to_end.json` |
| vs 16 kbps speech codec | 13,880 B → 211 B = **~66×** | derived from measured packet |
| vs raw PCM (16 kHz 16-bit) | 222 KB → 211 B = **~1,050×** | derived — name the basis when quoting |
| ASR model | 348 MiB disk / 279 MiB download, INT8 | `ARCHITECTURE.md` |
| Debug APK | ~540 MB (model ships inside) | `iTantra-debug.apk` |
| TTS voice size | ~60 MiB each | `tts_result.json` |
| VAD | Silero, 629 KB, RTF 0.004 | `vad_test.json` |
| RAM (Android emu) | 428 MB PSS / 535 MB RSS | `dumpsys meminfo` |
| RAM (desktop, all models) | 838 – 889 MiB | `RAM_measure_all_models.json` |
| TTS round-trip CER | English **0.030** · Hindi **0.339** | `ACCURACY_RESULTS.md` §4 |
| Noise @ 30 dB, Hindi | CER 0.133 → **0.424** | `NOISE_ROBUSTNESS_FINDINGS.md` |
| Bluetooth / Wi-Fi Direct | **never executed** | `android/VERIFICATION_STATUS.md` |

## Three things to say that most teams won't

1. **"We measured it and it's worse than we hoped."** The Hindi noise collapse, the 0.509 Odia WER,
   the unverified radio. Every one of these is in your own files. Volunteering them makes the other
   19 numbers believable.
2. **"This is an emulator, not a phone."** Say it before they ask. It converts a weakness into
   evidence that you know what a valid measurement looks like.
3. **"Our accuracy is 2–3× worse than a 7B model that can't run on a phone."** This is the most
   persuasive sentence available to you — it reframes the comparison around the constraint that
   actually matters, which is 348 MiB and offline.
