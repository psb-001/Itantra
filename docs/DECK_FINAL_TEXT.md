# iTantra SIH26173 — every word to write on each slide

**Use this instead of `DECK_FROM_SCRATCH.md` for writing.** That file has the reasoning. This one has
the final text, word for word, in the order you type it.

**Format:** exactly 6 slides including the title. Official SIH 2026 template. **PDF only.**
The template's italic prompt labels stay exactly as printed — type your text underneath them.

**Numbers used here, all machine-checked against the result files:**

| | |
|---|---|
| Sentences tested | 2,000 (200 per language, 6 h 44 m of real recorded speech) |
| Mean character error | 0.0918 — about 9 wrong letters in 100, so 1 in 11 |
| Best / worst language | Kannada 0.053 · Odia 0.134 |
| Hindi before → after our fix | 0.113 → 0.096 |
| Hindi under background noise, letters in the right script | 40% → 78% |
| Message size | 6.94 s of speech → **211 bytes** |
| Size against a 16 kbps voice codec | **~66× smaller** |
| Size against the raw audio the mic records | ~1,050× smaller |
| TTS voices | 7 speak aloud, 3 text only |
| Two real phones | **works** |

---

# SLIDE 1 — Title page

Leave the template's own fields. Fill in:

```
Idea Title:  iTantra
Team Name:   B.J.P (Bug Janta Party)
Problem Statement ID: SIH26173
Theme:       Smart Automation
PS Category: Software
```

**One line under the title, small:**

> Voice to text to speech. Works between two phones with no internet.

**One line at the very bottom, small:**

> Recognises 10 of 10 languages · speaks 7 aloud · needs no network · tested on two real phones

*Nothing else. Do not put numbers on the title slide.*

---

# SLIDE 2 — Idea

**Title, top left:** `iTantra — send the words, not the sound`

**`Proposed Solution (Describe your Idea/Solution/Prototype)`**
> An offline Android app. Hold the button and speak. This phone works out what you said, turns it
> into written words, and sends **only those words** over Bluetooth. The other phone reads them
> aloud. Your voice is never sent and never recorded.

**`Detailed explanation of the proposed solution`**
> Two phones, push to talk, like a walkie-talkie. Either phone can talk at any time.
> A small detector waits for you to stop speaking and cuts out just your sentence. A 340 MB
> recognition model turns it into text **on the handset**. A language filter throws away anything
> that came back in the wrong script. The text crosses the link at about 200 bytes. The receiving
> phone speaks it aloud — emergency alerts at full volume, repeating until acknowledged.

**`How it addresses the problem`**
> Speech is too big for a damaged link. In our own test, 6.94 seconds of speech became a
> **211-byte** message. A normal phone voice call would have sent roughly 14,000 bytes for the
> same sentence, so the words are about **66 times smaller**. A link that could never carry a
> voice can now carry a whole conversation.
>
> And because the words arrive and are **spoken** on the far side, the message still reaches
> people who cannot read. Nothing needs the network — so it works at the exact moment the network
> is what has broken.

**`Innovation and uniqueness of the solution`**
> We changed the model's **output layer**.
>
> The recogniser is character-by-character and is not told which language to use, so it swapped in
> letters that sound identical but belong to another script. "hello" came back as `هello` in
> Arabic letters, and our filter then sent the rest of it, so a phone displayed **"ello"** as if it
> were correct. In a rescue app that is worse than silence, because a person acts on it.
>
> We deleted the 9,286 output characters our ten languages never need, so the model **cannot**
> produce them. Wrong-script output went from 2 in every 19 test phrases to **zero**. Hindi
> accuracy in background noise improved from 0.113 to **0.096**, and the other nine languages came
> out **exactly the same** — so the fix was free.

---

# SLIDE 3 — Technical approach

**`Technologies to be used`**

```
Kotlin Android app  ·  k2-fsa sherpa-onnx  ·  ONNX Runtime

Recognition  Meta Omnilingual ASR CTC 300M INT8 · 340 MB · CC-BY-NC-4.0
             output vocabulary pruned 10,288 → 1,002 characters  ← our change
Synthesis    Piper VITS voices · 7 languages · 448 MB · MIT wrapper, mixed data terms
Sentence cut Silero VAD · 629 KB · runs only while idle
Transport    Bluetooth RFCOMM (main)  ·  Wi-Fi Direct (backup)
```

**`Methodology and process for implementation`** — three boxes with arrows:

| EITHER PHONE — TALKING | ACROSS THE LINK | EITHER PHONE — RECEIVING |
|---|---|---|
| Microphone, 16 kHz | Bluetooth RFCOMM | Piper voice, 7 languages |
| Silero VAD cuts your sentence | Wi-Fi Direct backup | Speaker at full volume |
| Omnilingual ASR → text | **words only, not sound** | Alerts cannot be interrupted |
| Language filter → message | **129–278 bytes** | Repeats until acknowledged |

**Bottom line, one line:**

> On an Android phone: 6.94 seconds of speech became a **211-byte** message in **1.02 seconds**.
> Only 2 models are ever loaded on a phone, and while nobody is speaking only the 629 KB detector
> runs — so idle battery use stays near zero.

---

# SLIDE 4 — Feasibility and viability
### This is the slide that carries the 40% accuracy mark. Give it the most space.

**`Analysis of the feasibility of the idea`**
> **It works on two real phones.** On 27 September 2026 we ran this app on two physical Android
> phones: one person held the button and spoke, and the words came out of the speaker on the other
> phone. No internet, no computer, nothing in between.
>
> That test had never been done before. The Bluetooth connection had never once completed a
> conversation on this project until that day.
>
> Everything else is measured, not claimed:
> • All 10 required languages tested on **2,000 real recorded sentences** — 6 hours 44 minutes of
>   people actually speaking.
> • Mean error **0.0918** — about 9 wrong letters in every 100, or 1 in 11.
> • Best: Kannada 0.053. Worst: Odia 0.134. All ten under 0.14.
> • Runs with no network at any point. Nothing recorded, nothing uploaded.
>
> **Licences, said plainly:** the recognition model is **CC-BY-NC-4.0** and the Hindi voice is
> **CC-BY-NC-SA-4.0**. Free to use and change, **but not for commercial use**. The app says this
> in plain language under About → Open-source credits.

**`Potential challenges and risks`**
> • **No human has judged the voices.** Every "how clear is the speech" number comes from a
>   computer reading its own audio back. That is not the same as a person understanding it while
>   frightened.
> • **Tamil and Odia are weak** — about 1 word in 2 is wrong (0.50 and 0.51). We say so instead of
>   hiding it.
> • **3 of 10 languages cannot speak aloud** — Gujarati, Kannada and Odia show the words on screen
>   and stay silent. No free, high-quality, offline voice exists for them that we were allowed to
>   ship.
> • **All our speed and memory numbers come from an Android emulator.** A cheap real handset will
>   be slower and we have not measured by how much.
> • **We have not tested in a real disaster.** Background noise was added to clean recordings
>   afterwards. That measures sensitivity to noise, not real field accuracy.
> • **804 MB app.** Fine to install by hand, too large for an app store.

**`Strategies for overcoming these challenges`**
> • **Fix the whole failure, not the symptom.** We did not tune the recogniser — we removed the
>   characters it was getting wrong, so the mistake became impossible. Nine of ten languages were
>   unaffected.
> • **Drop a whole sentence rather than send a half-corrected one.** Silence can be retried. A
>   confident wrong message cannot.
> • **Demo in English and Hindi** — our two most accurate languages.
> • **Report letter errors and word errors both.** Word error alone flatters languages with long
>   written words.
> • **Bluetooth with Wi-Fi Direct as backup.** Never depend on one way of connecting.

**Accuracy table across the bottom** — two lines, sorted, Hindi bolded because it moved:

> Tested on 200 real recorded sentences in each of the 10 required languages
>
> **Kannada 0.053 · Malayalam 0.059 · English 0.067 · Gujarati 0.071 · Hindi 0.096**
> **Bengali 0.100 · Telugu 0.100 · Marathi 0.108 · Tamil 0.129 · Odia 0.134**
>
> Mean **0.0918** — 1 wrong letter in 11. Hindi improved from 0.113 after our fix.
> The other nine are unchanged, so the fix cost nothing.

---

# SLIDE 5 — Impact and benefits

**`Potential impact on the target audience`**
> For rescue workers, volunteers and ordinary people on a damaged or overloaded network: spoken
> instructions that arrive as **clear speech**, needing neither reading nor an internet connection.
> A rescuer in one camp and a family in another can hold a normal conversation on ordinary phones,
> with no equipment between them.
>
> Pairing also identifies the sender. An alert is traceable to a phone somebody deliberately
> connected — which matters when the alert is telling people to move.

**`Benefits of the solution (social, economic, environmental, etc.)`**

| SOCIAL | ECONOMIC | ENVIRONMENTAL |
|---|---|---|
| Voice in, **voice out** — reaches people who cannot read or type. The one group almost every other alert system leaves out. | No paid service, no cloud, no proprietary software. Runs on phones people already own, using only free open-source models. | A 211-byte message instead of ~14,000 bytes of voice — about **66× less data**, so less radio energy per message. Battery effect not yet measured. |

**Closing box, keep it — this is what makes the slide believable:**

> **Measured:** works between two real phones · 10 of 10 languages on 2,000 real sentences ·
> 66× less data than voice · whole system works with no network.
>
> **Not yet claimed:** no human has judged the voices · no speed figures from a cheap phone ·
> battery life not measured · never tested in a real disaster.
>
> These benefits are proposed, not proven. **We make no claim of lives saved.**

---

# SLIDE 6 — Research and references

**`Details / Links of the reference and research work`**

| What we used | Where it is from | Licence |
|---|---|---|
| Omnilingual ASR paper | arxiv.org/abs/2511.09690 | — |
| Omnilingual ASR model (the one we use) | github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models | **CC-BY-NC-4.0** |
| Piper voice models | huggingface.co/rhasspy/piper-voices | MIT wrapper, mixed data |
| Silero VAD | github.com/snakers4/silero-vad | MIT |
| sherpa-onnx | github.com/k2-fsa/sherpa-onnx | Apache-2.0 |
| ONNX Runtime | github.com/microsoft/onnxruntime | MIT |
| espeak-ng | github.com/espeak-ng/espeak-ng | GPL-3.0 |
| Material Design 3 | developer.android.com/jetpack/androidx/releases/material | Apache-2.0 |
| FLEURS speech set (for testing only) | huggingface.co/datasets/google/fleurs | CC-BY-4.0 |

**One line under the table:**

> Every Creative Commons condition is written out in plain language inside the app, under
> About → Open-source credits — including what we are required to do and what we are not allowed
> to do. Not buried in a footnote.

*Check every link opens before you submit. A dead link in a references slide costs you more than
it looks like.*

---

# WHAT TO SAY OUT LOUD — 30 seconds

> "In a disaster the network dies first, and people still need to talk to each other. Voice is the
> natural way to talk — and voice is the one thing a damaged radio cannot carry.
>
> iTantra works out what you said **on the phone that heard you**, sends **only the words** —
> about 66 times smaller than sending the sound — and **speaks them on the phone at the other
> end.** Voice at both ends. Almost nothing in the middle.
>
> It needs no internet at any point, and it is measured, not claimed: 2,000 real recorded
> sentences across all ten required languages, one wrong letter in eleven.
>
> We also found and fixed a real bug. The recogniser was writing Hindi using Arabic letters,
> because those letters sound the same. Our filter then deleted the odd letter and sent the rest,
> so a phone displayed 'ello' as if it were right. In a rescue app that is worse than silence,
> because a person believes it. We fixed it by deleting the 9,286 output characters our languages
> never need, so it now **cannot** happen. Wrong-script output went from 2 in 19 to zero, and
> Hindi accuracy in background noise went from 40% to 78%.
>
> And it works between two real phones — we tested that on the 27th.
>
> The honest gap: no human has judged our voices yet, and Tamil and Odia are weak, about one word
> in two."

---

# THE FIVE THINGS TO FIX FIRST

Do these before anything else. Three of them are statements, not numbers, and an evaluator who
finds one has reason to doubt every other number on the slide.

1. **"Every component is permissively licensed (Apache 2.0 / MIT)"** → the recognition model is
   **CC-BY-NC-4.0** and the Hindi voice is **CC-BY-NC-SA-4.0**
2. **"Kannada, Tamil, Odia use espeak-ng"** → espeak-ng cannot make sound on its own. Tamil now
   has a real voice. Gujarati, Kannada and Odia are text-only
3. **"Hindi script retention falls to ~50% at 15 dB"** → it is **78%**, and was 40% before our fix
4. **"Hindi speech 0.339 — open problem"** → **0.102**, and we found the cause
5. **ASR model "348.4 MB, Apache 2.0"** → **340 MB, CC-BY-NC-4.0**

Then:

- [ ] Add the two-real-phones result to slide 4
- [ ] Add vocabulary pruning to slides 3 and 4
- [ ] Change "1,000× smaller" to **66×**, and say it is against a voice codec
- [ ] Change "536 MB memory" to **428 MB**, measured with the model loaded
- [ ] Say "7 speak aloud, 3 text only" on every slide that mentions voices
- [ ] Report letter **and** word errors together
- [ ] Label every speech-clarity number as a machine check
- [ ] Check every reference link opens
- [ ] 6 slides maximum, PDF, official template, prompt labels unchanged
- [ ] Slide 1 says **SIH26173**

---

# IF YOU ONLY HAVE FIVE MINUTES

Cut slides 2 and 3 down to their first two boxes each, and keep slides 1, 4, 5, 6 as they are.
Slide 4 is where the 40% accuracy mark lives, and slide 5 is where the impact mark lives. Those two
plus the title are worth more than the other four combined.
