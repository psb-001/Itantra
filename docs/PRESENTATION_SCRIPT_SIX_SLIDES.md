# iTantra — presentation script, 5 minutes

**Read this before we present.** Six slides, six speakers, **5.1 minutes**.

Each slide has:
- **ON SCREEN** — what the audience is looking at
- **SAY** — the words. Say these. This is the 5-minute script.
- **IF YOU HAVE TIME** — extra detail. Skip these unless you are running long.

**Timing at 150 words per minute**

| Slide | Speaker | Words | Time |
|---|---|---|---|
| 1 Title | Prathamesh | 108 | 43 s |
| 2 Idea | Prathamesh | 122 | 49 s |
| 3 Technical | Atharv | 101 | 40 s |
| 4 Feasibility | Aayush | 178 | 71 s |
| 5 Impact | Harshal | 116 | 46 s |
| 6 References | Aniket | 108 | 43 s |
| **Total** | | **733** | **4 min 53 s** |

Leave a few seconds of slack. Do not add anything — if you finish early, stop and let the silence sit
before the questions.

**Two rules for the whole presentation**
1. **Do not read the slide.** The slide is the evidence; you are the explanation.
2. **Never claim more than the slide claims.** The weakest moment in any defence is a presenter
   promising something the evidence does not support.

---

## Who says what

| Slide | Speaker | Why |
|---|---|---|
| 1 Title | **Prathamesh** (Lead) | Opens, sets the problem |
| 2 Idea | **Prathamesh** | The core argument needs the strongest voice |
| 3 Technical | **Atharv** | Built the pipeline |
| 4 Feasibility | **Aayush** | Owns the evaluation, so he defends the numbers |
| 5 Impact | **Harshal** | The human side of it |
| 6 References | **Aniket** | Licences and sources, closes cleanly |
| Demo / backup | **Tanishka** | Runs the two-phone demo if asked |

Fewer than six people? Prathamesh takes 1 + 2, Aayush takes 3 + 4.

---

# SLIDE 1 — Title

### ON SCREEN
Left: the word **iTantra**, tagline, three badges. Right: navy panel with SIH26173, theme,
category, team ID, team name.

### SAY
> "Good morning. We are team B.J.P, and our project is **iTantra**.
>
> In a disaster the network dies first, and people still need to talk. Voice is the natural way to
> talk — and voice is the one thing a damaged radio cannot carry.
>
> iTantra sends the **words instead of the sound**. Speech goes in on one phone, the words cross the
> link, speech comes out on the other. Voice at both ends. Almost nothing in the middle.
>
> No internet. All ten required languages. Seven spoken aloud. It all runs on the two phones
> themselves."

> **Do not mention accuracy yet.** If someone asks now, say: *"we measured it on two thousand real
> recorded sentences — I'll show you shortly."*

---

# SLIDE 2 — Idea

### ON SCREEN
Four labelled boxes. Top strip: the four-step flow. Bottom left: three-bar chart —
14 000 B / 222 000 B / 211 B. Bottom right: two boxes, `هello → ello` in red, `hello ✓` in green.

### SAY
> "The pipeline. You hold the button and speak. A small detector waits for you to stop and cuts out
> your sentence. A recognition model on the phone turns it into text. A filter throws away anything
> in the wrong script. The text crosses as about two hundred bytes. The other phone reads it
> aloud.
>
> That chart is the whole argument — the same sentence three ways. A normal voice call, about
> fourteen thousand bytes. The raw audio the microphone records, two hundred and twenty-two thousand. Our text, **two hundred and eleven**. Sixty-six times smaller than a voice call.
>
> And the part we care about most: the words arrive and are **spoken** on the far side, so the
> message reaches people who cannot read it."

### IF YOU HAVE TIME — the red box, the best 70 seconds in the deck
> "The red box is a real bug. The recogniser is character-by-character and is not told which
> language to use, so it swapped in letters that sound identical but belong to another script.
> 'Hello' came back as 'hello' in Arabic letters. Our filter deleted the odd letter and sent the
> rest, so a phone displayed 'ello' as if it were correct. In a rescue app that is worse than
> silence, because a person believes it.
>
> So we did not tune the model. We deleted the nine thousand two hundred and eighty-six output
> characters our ten languages never need, so now it **cannot** get it wrong. Wrong output went from
> two in every nineteen test phrases to zero, Hindi accuracy improved, and the other nine languages
> came out exactly the same. The fix was free."

---

# SLIDE 3 — Technical approach

**Speaker: Atharv**

### ON SCREEN
Top: six-row table of parts and licences. Middle: three boxes — Phone A → LINK → Phone B.
Bottom: four stat boxes.

### SAY
> "Quickly, the parts. A Kotlin Android app. For recognition, Meta's Omnilingual model, three hundred and forty megabytes, running fully on the handset. For speech, seven Piper voices. A small voice
> detector finds where your sentence ends. Transport is Bluetooth, with Wi-Fi Direct as a backup.
>
> Two things to notice. The **ASR row carries our fix** — ten thousand two hundred and eighty-eight
> characters pruned down to one thousand and two, and that is why Hindi improved.
>
> And the **licences are stated, not hidden**. The recognition model is CC-BY-NC-4.0: free to use and
> change, not for commercial use. It is on the next slide and in the app in plain language."

### IF YOU HAVE TIME
> "The flow diagram is the asymmetry. Phone A loads recognition, Phone B loads speech. A naive design
> loads both on both phones and doubles the memory for nothing.
>
> And the idle box. While nobody is speaking only six hundred and twenty-nine kilobytes are running.
> The big models are not even loaded until you actually speak, so idle battery is near zero **by
> design**, not by tuning."

**If asked "why not just stream the audio":**
> "Because the link is the problem. Audio does not compress enough to fit. Text is the only
> representation of language small enough to survive a damaged link."

**If asked about the 340 MB model:**
> "A trade. Meta's seven-billion model is two to three times more accurate, but it is twenty-five
> gigabytes and cannot run on a phone. Ours runs."

---

# SLIDE 4 — Feasibility and viability

**Speaker: Aayush — the slide that matters most**

### ON SCREEN
Left: five bullets. Right: ten-bar chart of character error per language, **Hindi in orange**.
Below: risk table on the left, strategies on the right.

### SAY
> "This is the evidence slide.
>
> First, the thing that matters most: **it works on two real phones.** We ran it on two physical
> Android phones on the twenty-seventh of September. One person holds the button, the words come out
> of the speaker on the other. No internet, no computer. That test had never been done before, so the
> demo is a result, not a plan.
>
> The accuracy. Two thousand real recorded sentences, six hours and forty-four minutes of people
> actually speaking, two hundred per language, all ten. The chart is character error, lower is
> better. Every language is under zero point one four, and the mean is
> zero point zero nine one eight — nine wrong letters in every hundred.
>
> Hindi is orange because our fix moved it, from zero point one one three down to
> zero point zero nine six. The other nine did not change."

### IF YOU HAVE TIME — the risks, which are what make the numbers believable
> "Now I want to be straight about the risks.
>
> **Nobody has listened to our voices and judged them.** Every number about how clear the speech is
> comes from a computer feeding its own audio back into itself. That is not the same as a frightened
> person understanding it.
>
> **Tamil and Odia are weak** — about one word in two is wrong — and we would rather say that than
> hide it in a footnote.
>
> **Three languages cannot speak aloud at all**: Gujarati, Kannada, Odia. Not because we failed to
> wire them. Because we could not find a free, good-quality, offline voice we were allowed to ship.
>
> **Our speed and memory numbers come from an emulator**, not a cheap handset, and a budget phone
> will be slower by an amount we have not measured.
>
> And **we have never tested this in a real disaster.** The noise tests are noise we added to clean
> recordings afterwards."

---

# SLIDE 5 — Impact and benefits

**Speaker: Harshal**

### ON SCREEN
Three audience cards. Three benefit cards — Social / Economic / Environmental. A
measured / not-yet-measured strip at the bottom.

### SAY
> "Who is this for?
>
> **Field responders** — NDRF, SDRF, district administration, camp volunteers. The first responder
> and the affected person are almost never in the same building. Today they rely on radio, which is
> narrowband and text-based for exactly this reason.
>
> **Shelter teams** — useful at the moment cellular and internet are down, on phones already in
> their pocket.
>
> **And the people no other alert system reaches.** Every other system needs reading. In the districts
> most exposed to floods, literacy is uneven and the first language is regional, so those systems
> leave out exactly the people they mean to protect. Ours speaks on arrival.
>
> **Benefits.** Social: voice in, voice out. Economic: no paid API, no cloud. Environmental:
> sixty-six times less data per message.
>
> The bottom strip is what we measured versus what we have not. We are not claiming to have saved
> any lives. We are claiming a working system, measured honestly."

### IF YOU HAVE TIME
> "One more on the economics, since licences get asked about. The recognition model is non-commercial.
> That is not a restriction for an NGO or responder deployment. For a commercial product we would
> need a model with a commercial licence, and we would have to do that work. We would rather state
> the limit than pretend it is not there."

**If asked about the non-commercial licence blocking deployment:**
> "It restricts commercial use, which is not a restriction for a responder or NGO deployment. For a
> commercial product we would need a commercially-licensed model. We would rather say that than
> guess."

---

# SLIDE 6 — Research and references

**Speaker: Aniket — close cleanly**

### ON SCREEN
Ten-row table of sources and licences. Below: a reproducibility box and a licences box.

### SAY
> "Our sources, and every licence.
>
> The recognition model is Meta's Omnilingual ASR, CC-BY-NC-4.0, three hundred and forty megabytes.
> Our voices are Piper, seven of them. The voice detector is Silero, MIT. The runtime is sherpa-onnx
> and ONNX Runtime. We tested on Google's FLEURS corpus, evaluation only.
>
> **Non-commercial components do ship.** The Hindi voice is CC-BY-NC-SA-4.0. We met the attribution
> and share-alike conditions and state them in plain language in the app.
>
> Every figure here comes from a file in our repository, so anyone can check our work.
>
> We are team B.J.P. Thank you — we would be happy to run the two-phone demo."

### IF YOU HAVE TIME
> "And one trade we made deliberately. Meta's larger model is two to three times more accurate than
> ours, but it is twenty-five gigabytes and cannot run on a phone. Ours is three hundred and forty
> megabytes and runs. We chose a model that fits on the handset, and we are telling you the cost of
> that choice rather than quoting the headline accuracy of something we cannot ship."

---

# THE THREE QUESTIONS YOU WILL BE ASKED

### "Your model is CC-BY-NC. Isn't that a problem for real deployment?"
> "It restricts commercial use, and we are clear about that. For a responder or NGO deployment it is
> not a restriction. If this became a commercial product we would need a commercially-licensed model
> and would have to do that work. We would rather state the limitation than pretend it is not there."

### "Three of your ten languages have no voice. Why is that a solution?"
> "Because recognition works for all ten, so the text on screen is correct for all ten — that already
> beats a system needing one language pre-installed. Speech output is seven of ten. We could not find
> a free, high-quality offline voice for the other three that we were allowed to ship, and we would
> not fake it with a robot voice and pretend that was fine."

### "How do you know it works? Isn't an emulator different from a real phone?"
> "The pipeline ran on two real physical phones, so the radio link and the microphones are real. What
> is still from the emulator is the timing and memory measurement. That is a fair challenge and it is
> on the risk slide. A budget handset will be slower; we have not measured by how much, and we would
> rather say that than guess."

---

# IF YOU ARE CUT TO 2 MINUTES

Keep slides **1, 4, 5**. Say 2, 3 and 6 in one line each:
- **Slide 2:** "Speech in, words across, speech out. Two hundred bytes instead of fourteen thousand."
- **Slide 3:** "All on-device, two hundred bytes over Bluetooth. We publish every licence, including
  the non-commercial one."
- **Slide 6:** "Every figure is reproducible from files in our repo, and every licence is in the app."

**Never cut slide 4.** It carries the accuracy mark, and the risks on it are what make the rest
believable.
