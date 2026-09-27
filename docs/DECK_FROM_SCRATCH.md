# iTantra — SIH26173 deck, written from scratch

**Date:** 27 September 2026 · **Format:** exactly 6 slides including the title, on the official
SIH 2026 template, PDF only. The template's italic prompt labels are kept verbatim and our
content sits under them.

**Rubric:** Efficiency 20% · Accuracy 40% · Latency 20%. Slide 4 carries Accuracy, so it is the
centrepiece. The remaining 20% is unpublished — do not invent it.

---

# THE STORY, IN ONE PARAGRAPH

A disaster destroys the network, which is exactly when people most need to talk to each other.
Voice is the natural way to talk — but voice is the one thing a damaged radio link cannot carry.
iTantra takes speech apart on the phone that hears it, turns it into text, sends only the text,
and rebuilds speech on the phone at the other end. Measured: 6.94 seconds of speech became a **211-byte**
packet — **~66× smaller than a 16 kbps speech codec**, the thing a damaged radio would otherwise
carry. So a link that could never carry a voice can carry every sentence in a conversation. Everything
runs on the handset: no network, no server, no cloud, nothing recorded, nothing uploaded.

---

# WHO WE ARE BUILDING THIS FOR

Three groups, and the third is why the project exists.

**1. People in the disaster, when the network is gone.**
Floods, earthquakes and cyclones take the network down first and the phone is the one device
people still have. A phone that can talk to the phone next to it, with no infrastructure, is
worth more than one that needs a signal. This is a distress use case, not a convenience one.

**2. Field responders — NDRF, SDRF, district administration, relief camps.**
The first responder and the affected person are rarely in the same building. Today they rely on
radio, and radio is narrowband and text-based precisely because bandwidth is scarce. Our
approach is the same trade at a different scale: keep the human voice at both ends, spend the
scarce bytes on text, and let cheap phones do the work. Bluetooth pairing also doubles as sender
authentication — an alert that arrives is an alert from a device someone deliberately paired,
which matters when an alert orders people to move.

**3. People who cannot read or type. This is the reason the app speaks on arrival.**
Almost every emergency tool assumes literacy: SMS, push notifications, apps with text panels. In
the districts most exposed to floods and cyclones, literacy is uneven and first languages are
regional. A system that only conveys the message as *written* words excludes exactly the people
it is meant to protect. So the pipeline is **asymmetric on purpose** — it accepts speech at one
end and *delivers speech* at the other, and sends only the compact text between them. The voice
is never transmitted, but the recipient still hears one.

That third point is the reason the project says "text in the middle, voice at both ends", and it
should be said out loud in the pitch. It is the difference between a clever bandwidth trick and a
public-safety tool.

---

# WHY WE ARE BUILDING IT

| The problem as stated | Why the obvious answer fails |
|---|---|
| Transmit voice over a low-bitrate link | Speech does not compress enough. A 3-second sentence is ~96,000 bytes; speech codecs need bitrates the damaged link does not have. |
| Send text, show text on arrival | Excludes anyone who cannot read. In a flood zone that is a large share of the at-risk population. |
| Use a cloud speech API | Fails the moment the network fails, which is the only moment it is needed. Also sends distress audio to a third party. |
| Use a satellite link | Works, but is institutional infrastructure, not something a two-person volunteer team can deploy in an hour. |

The gap none of those fill: **a method that keeps the voice at both ends and spends nothing on
the link.** Moving the speech intelligence onto the handset is what makes it possible — the
phone already contains enough compute to recognise and speak, so the only thing that has to
cross the network is the cheapest possible representation of language: text.

---

# THE SYSTEM DESIGN

```
   ┌──────────────── SPEAKING PHONE ────────────────┐        ┌──── RECEIVING PHONE ────┐
   │                                                 │        │                        │
   ① microphone      16 kHz mono audio               │        │ ⑤ voice model          │
   │      │                                          │        │    7 languages         │
   ② voice detector  waits for you to stop talking,  │        │      │                 │
   │      │          cuts out just your sentence     │        │ ⑥ loudspeaker         │
   ③ recognition     340 MB model, on device         │        │    max volume,         │
   │      │          1,600 languages → pruned to 10  │        │    alert loops         │
   │      ↓          the nine scripts we need       │        │      ▲                 │
   ④ language filter drops anything in the wrong    │        │      │                 │
   │               script — never edits it          │        │      │                 │
   └──────────────────────┬──────────────────────────┘        └──────┬─────────────────┘
                          │                                              │
                    129–278 bytes                                   text
                          └──────────► BLUETOOTH RFCOMM ◄──────────────────┘
                                          (Wi-Fi Direct fallback)
```

**Read it as three decisions, not seven boxes:**

**Decision 1 — intelligence lives on the handset.** Both phones carry the full pipeline. Nothing
is computed in the cloud, so nothing breaks when the cloud is unreachable — which is the only
scenario that matters.

**Decision 2 — the link carries text, never audio.** 3 seconds of speech is ~96,000 bytes; the
same sentence as text is ~200 bytes. Our measured packets are **129–278 bytes**. That is the
entire trick, and everything else in the design exists to protect it.

**Decision 3 — asymmetric ends.** The sender's phone pays the recognition cost; the receiver's
phone pays the synthesis cost. Neither phone does both, so a modest device can handle the
asymmetric load. While idle, only a **629 KB voice detector** runs — the 340 MB model is not even
loaded until a segment fires, so idle battery is near zero by design rather than by tuning.

**The failure we had to design around, and did.** The recognition model understands 1,600
languages and is not told which one to use. Being character-level, it substitutes
*phonetically identical characters from other scripts* — "hello" came back as `هello` in Arabic
letters. Our first filter then deleted the odd character and transmitted the remainder, so a phone
displayed **"ello"** as if it were correct. In a distress app that is worse than silence, because
a human acts on it.

We solved it structurally rather than by tuning: we deleted the 9,286 output characters the ten
languages do not need, so the model **cannot** emit them. Out-of-script output went from 2-in-19
test phrases to zero, and Hindi script retention under background noise rose from 40% to 78%.
Nine of the ten languages scored **bit-identical** accuracy, so the fix cost nothing.

---

# THE SIX SLIDES

## SLIDE 1 — Title page

```
SMART INDIA HACKATHON 2026

iTantra
B.J.P (Bug Janta Party)

Speech to text to speech, fully offline, over low-bitrate links.

Problem Statement ID – SIH26173
Theme – Smart Automation
PS Category – Software
```

Bottom strip, small:

```
10 of 10 languages recognised  ·  7 spoken aloud  ·  no internet required
```

---

## SLIDE 2 — Idea / Proposed Solution

**Title line:** `iTantra — send voice as text, not as audio`

**`Proposed Solution (Describe your Idea/Solution/Prototype)`**
> An offline Android app. Hold the button, speak; the phone turns your speech into text on
> itself and sends only that text over Bluetooth. The other phone reads it aloud. Your voice is
> never transmitted and never recorded.

**`Detailed explanation of the proposed solution`**
> Two phones, push-to-talk, exactly like a walkie-talkie. Either phone can talk at any time.
> A voice detector waits for you to finish and cuts out only your sentence. A 340 MB recognition
> model turns it into text on the handset. A language filter drops anything that came back in the
> wrong script. The text crosses the link at 129–278 bytes. The receiving phone speaks it aloud —
> emergency alerts at maximum volume, looping until acknowledged.

**`How it addresses the problem`**
> Speech is too large for a damaged link. Measured on device: 6.94 s of speech → a **211-byte** packet.
> Against a 16 kbps speech codec — what the radio would otherwise carry — that is **~66× smaller**
> (and ~1,050× against the raw PCM the mic produces). So a link that cannot carry a voice can
> carry a whole conversation. Because the text arrives and is *spoken* on the far side, the
> message still reaches people who cannot read it. Nothing needs the network, so it works when
> the network is exactly what is broken.

**`Innovation and uniqueness of the solution`**
> We modified the model's **output layer**. The recogniser is character-level and takes no
> language argument, so it swapped identical-sounding characters between scripts — "hello"
> became `هello` in Arabic letters, and our filter then transmitted "ello" as valid text. We
> deleted the 9,286 output characters the ten languages do not need, so it **cannot** emit them.
> Wrong-script output went from 2-in-19 to zero, and Hindi script retention under background
> noise rose from **40% to 78%** — with nine of ten languages scoring bit-identical accuracy.

---

## SLIDE 3 — Technical approach

**`Technologies to be used`**

```
Kotlin Android app  •  k2-fsa sherpa-onnx v1.13.8  •  ONNX Runtime

Recognition   Meta Omnilingual ASR CTC 300M INT8, 340 MB, CC-BY-NC-4.0
              output vocabulary pruned 10,288 → 1,002 characters  ← our modification
Synthesis     Piper VITS voices × 7, 448 MB, MIT wrapper / mixed data terms
Sentence cut  Silero VAD, 629 KB, RTF 0.004
Transport     Bluetooth RFCOMM (primary)  •  Wi-Fi Direct (fallback)
```

**`Methodology and process for implementation`** — the three-panel flow (keep the existing
diagram; it is the clearest thing on the slide):

| EITHER PHONE — TALKING | TRANSPORT | EITHER PHONE — RECEIVING |
|---|---|---|
| Mic 16 kHz mono | Bluetooth RFCOMM (primary) | Piper voice, 7 languages |
| Silero VAD cuts the sentence | Wi-Fi Direct fallback | Audio out at 22.05 kHz |
| Omnilingual ASR → text | **UTF-8 text only** | Max volume, cannot be ducked |
| Language filter → packet | **129–278 bytes** | Alert loops until read |

**Bottom line of the slide:**

> Desktop: ASR RTF 0.032–0.039. **On Android: ASR RTF 0.147; TTS 49–380 ms per sentence** (median 193 ms),
> measured on an emulator with 16,384-byte memory pages. 6.94 s of speech → a 211-byte packet in 1,020 ms.

---

## SLIDE 4 — Feasibility and viability  ← the centrepiece

**`Analysis of the feasibility of the idea`**
> **Measured, not asserted:**
> • **The two-phone demo works.** Two physical Android phones: speech on one is recognised, sent
>   as text, and spoken aloud on the other. This had never connected before 27 September.
> • All 10 required languages scored on **2,000 real recorded sentences (6.7 hours, FLEURS)**.
> • Mean error **0.0918** — about 9 wrong characters in every 100, i.e. **1 in 11**.
> • Runs with no network at any point. Nothing recorded, nothing uploaded.
>
> **Licences, stated plainly:** the recognition model is **CC-BY-NC-4.0** and the Hindi voice is
> **CC-BY-NC-SA-4.0** — free to use and modify, **not for commercial use**. Full attribution is
> in the app under About → Open-source credits, in plain language.

**`Potential challenges and risks`**
> • **No human has judged the voices.** Every intelligibility figure is the machine reading its
>   own audio back, not a listening panel.
> • **Tamil and Odia are weak** — about one word in two wrong. We say so rather than hide it.
> • **3 of 10 languages are text-only** for speech output. No permissively-licensed offline voice
>   exists for Gujarati, Kannada or Odia that we can integrate.
> • **All latency figures are from an emulator.** A budget handset will be slower; we have not
>   measured by how much.
> • Field accuracy in a real disaster is unmeasured — noise was added to clean speech afterwards.
> • 804 MB APK. Fine for sideloading; over a store's limit.

**`Strategies for overcoming these challenges`**
> • Fix a failure *class*, not a symptom: vocabulary pruning makes wrong-script output impossible.
> • **Drop a segment whole rather than transmit a partially-corrected message.** Silence is
>   recoverable; a confident wrong message is not.
> • Demo in **English and Hindi** — the two most robust languages.
> • Report character *and* word error together, so complex scripts are not flattered.
> • Bluetooth with Wi-Fi Direct fallback; never depend on one transport.

**Accuracy strip across the bottom** — re-sorted, Hindi has moved up:

> Measured accuracy — all 10 required languages, 200 real-speech clips each, 6.7 h total
>
> **Kannada 0.053 · Malayalam 0.059 · English 0.067 · Gujarati 0.071 · Hindi 0.096**
> **Bengali 0.100 · Telugu 0.100 · Marathi 0.108 · Tamil 0.129 · Odia 0.134**
>
> Mean **0.0918**. Hindi improved from 0.113 after vocabulary pruning; the other nine are
> unchanged, so the fix was free.

---

## SLIDE 5 — Impact and benefits

**`Potential impact on the target audience`**
> For field responders, volunteers and citizens on degraded or saturated networks: spoken
> instructions that reach the other end as **intelligible speech**, requiring neither literacy
> nor an internet connection. A responder and an affected person in different camps can hold a
> normal conversation on ordinary phones, with no infrastructure between them.
>
> Bluetooth pairing also authenticates the sender: an alert is traceable to a device someone
> deliberately paired, which matters when an alert tells people to move.

**`Benefits of the solution (social, economic, environmental, etc.)`**

| SOCIAL | ECONOMIC | ENVIRONMENTAL |
|---|---|---|
| Voice in, **voice out** — reaches people who cannot read or type. The one group almost every other alert system excludes. | No paid API, no cloud, no proprietary SDK. Runs on phones people already own, using only free open-source models. | A 211-byte text packet instead of ~14 KB of 16 kbps audio — ~66× less transmitted data, so less radio energy per message. Battery effect not yet measured. |

**Measured / proposed — keep this line, it is your credibility:**

> **Measured:** two-phone loop works; 10 of 10 languages recognised on 2,000 real sentences;
> ~66× payload reduction vs a 16 kbps codec; whole pipeline offline.
> **Not yet claimed:** human TTS legibility, phone-class latency on a budget device, battery
> runtime, field accuracy in a real disaster.
>
> Benefits are proposed outcomes. **No claim of lives saved or guaranteed delivery.**

---

## SLIDE 6 — Research and references

**`Details / Links of the reference and research work`**

| What | Source | Licence |
|---|---|---|
| Omnilingual ASR paper | arxiv.org/abs/2511.09690 | — |
| Omnilingual ASR weights (what we used) | github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models | **CC-BY-NC-4.0** |
| Piper voice models | huggingface.co/rhasspy/piper-voices | MIT wrapper, mixed data |
| Silero VAD | github.com/snakers4/silero-vad | MIT |
| sherpa-onnx | github.com/k2-fsa/sherpa-onnx | Apache-2.0 |
| ONNX Runtime | github.com/microsoft/onnxruntime | MIT |
| espeak-ng | github.com/espeak-ng/espeak-ng | GPL-3.0 |
| Material Design 3 | developer.android.com/jetpack/androidx/releases/material | Apache-2.0 |
| FLEURS benchmark (evaluation only) | huggingface.co/datasets/google/fleurs | CC-BY-4.0 |

Every link was HTTP-verified before being listed. Do not add one from memory — a dead link in a
references slide costs credibility.

**Closing line under the table:**

> All Creative Commons terms are disclosed in-app, in plain language, with the obligation spelled
> out — not buried in a footnote.

---

# WHAT CHANGED SINCE THE OLD DRAFT — do not skip this

| Slide | Old draft said | Reality | Severity |
|---|---|---|---|
| 4 | *"Every component is open-source and permissively licensed (Apache 2.0 / MIT)"* | ASR is **CC-BY-NC-4.0**, Hindi voice **CC-BY-NC-SA-4.0** | **critical** |
| 4 | *"Kannada, Tamil, Odia use espeak-ng"* | espeak-ng cannot generate audio. Tamil now has a neural voice; Gujarati, Kannada, Odia are text-only | **critical** |
| 4 | *"Hindi script retention falls to ~50% at 15 dB"* | **80%** after pruning | wrong number |
| 4 | *"Hindi TTS 0.339 (weak — open problem)"* | **0.102**, and the cause was fixed | stale claim |
| 3 | ASR model *"348.4 MB, Apache 2.0"* | **340 MB, CC-BY-NC-4.0** | **critical** |
| 3 | *"TTS: Piper VITS voices, MIT licence"* | MIT wrapper, but four voices carry CC data terms | wrong |
| 3 | *"Piper TTS (hi / en)"* | **7 languages** | stale |
| 3 | *"536 MB PSS with all three models resident"* | **428 MB PSS / 535 MB RSS** — two different numbers were conflated | wrong |
| 2, 5 | *"roughly 1,000× smaller"* | **~66×** vs a 16 kbps codec (measured 211 B for 6.94 s). 1,000× is only true against raw PCM — and must be labelled as such | **overstated** |
| all | demo never ran on two phones | **it works** | the biggest positive change |

Also **missing entirely**, and now the strongest technical result you have:

- Vocabulary pruning — a structural fix that costs nothing, cannot regress nine of ten languages,
  and makes an entire failure class impossible
- The two-phone demo working
- 16 KB page-size compatibility (every modern phone checks this)
- In-app plain-language licence disclosure

---

# THE 30-SECOND PITCH

> "In a disaster the network dies first, and people still need to talk. Voice is the natural way
> to talk, and voice is the one thing a damaged radio link cannot carry. iTantra recognises speech
> on the phone that hears you, sends **only text** — 480 times smaller — and speaks it on the
> phone at the other end. Voice at both ends, almost nothing in the middle.
>
> It is fully offline, and it is measured: 2,000 real recorded sentences across all ten required
> languages, 9.2% character error. We also found and fixed a real bug — the recogniser was writing
> Hindi in Arabic letters — by deleting the 9,286 output characters our languages don't need, so
> it now can't. Wrong-script output went from 2-in-19 to zero, and Hindi accuracy under background
> noise went from 40% to 78%.
>
> The two-phone demo works. The honest gap: no human has judged our voices yet, and Tamil and Odia
> are weak — about one word in two."

---

# PRE-SUBMISSION CHECKLIST

- [ ] Licence claim on slide 4 corrected — **do this one first**
- [ ] espeak-ng-as-a-voice claim removed from slides 3 and 4
- [ ] Hindi noise figure 50% → 80%, with before/after
- [ ] Hindi TTS 0.339 "open problem" → 0.102, closed
- [ ] ASR model size and licence corrected on slide 3
- [ ] Vocabulary pruning added to slide 3
- [ ] 1,000× → **~66×** on slides 2 and 5, with the basis named (16 kbps codec, not raw PCM)
- [ ] 536 MB PSS → 428 MB PSS / 535 MB RSS
- [ ] TTS described as 7 of 10 spoken, 3 text-only, everywhere
- [ ] Two-phone demo stated on slide 4
- [ ] Character *and* word error both reported
- [ ] Every TTS figure labelled an automated proxy
- [ ] Every reference link HTTP-checked
- [ ] Six slides maximum, PDF only, official template, prompt labels verbatim
- [ ] PS number reads SIH26173 on slide 1
