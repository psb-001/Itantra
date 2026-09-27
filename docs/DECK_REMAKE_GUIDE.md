# Deck remake guide — what changed and what every slide must now say

**Written:** 27 September 2026 · **For:** rebuilding `deck/iTantra_SIH26173_DRAFT.pptx`
**Supersedes:** `docs/PPT_CHANGES_TO_APPLY.md`

Every number below was read out of the result files, not from the old deck. Where the deck and
the data disagree, **the data wins** and the exact replacement wording is given.

Rubric: **Efficiency 20% · Accuracy 40% · Latency 20%** (= 80% published; the other 20% is
unpublished — do not invent it). Six slides maximum, PDF only.

---

# PART 1 — READ THIS FIRST: five claims in the current deck are now FALSE

These are not outdated numbers. They are statements that are no longer true, three of which
would be caught by anyone who read the source material. Fix these before anything else.

| # | Slide | Current text | Why it is wrong | Replacement |
|---|---|---|---|---|
| **1** | 4 | *"Every component is open-source and permissively licensed (Apache 2.0 / MIT)."* | **Factually wrong.** The ASR model is **CC-BY-NC-4.0** (non-commercial). The Hindi voice is **CC-BY-NC-SA-4.0** (non-commercial **and** share-alike). Marathi/Bengali are CC-BY-SA-4.0, Telugu CC-BY-4.0. | See slide 4 replacement below. **This is the highest-risk line in the deck.** |
| **2** | 4 | *"Kannada, Tamil, Odia use espeak-ng."* | **Wrong, and was never buildable.** espeak-ng is a *phonemiser*, not a voice — it cannot generate audio. It has no TTS config in sherpa-onnx (`master` or `v1.13.8`) and the symbol is absent from the shipped `.so`. Tamil now has a real neural voice. | *"Tamil now has a neural voice. Gujarati, Kannada and Odia receive text only — no usable permissively-licensed offline voice exists."* |
| **3** | 4 | *"Under noise, Hindi script retention falls to ~50% at 15 dB SNR."* | **Superseded.** That was the unpruned model. | *"Hindi holds 80% correct script at 15 dB SNR — up from 48% before vocabulary pruning."* |
| **4** | 4 | *"TTS round-trip: Hindi 0.339 (weak — open problem)."* | **Closed.** The cause was never the voice; it was the same wrong-script bug. | *"Hindi TTS round-trip 0.339 → 0.102."* |
| **5** | 3 | *"Meta Omnilingual ASR 300M CTC, INT8 — 348.4 MB, Apache 2.0"* | **Size and licence both wrong.** | *"340 MB, CC-BY-NC-4.0."* |

If you fix nothing else, fix these five. A judge who catches claim 1 loses trust in every other
number on the slide.

---

# PART 2 — The single biggest thing missing from the deck

## Vocabulary pruning — this is now the strongest technical result you have, and it is not in the deck at all

**The problem it solves.** The recognition model understands 1,600 languages and is **not told
which one to use**. It is character-level, so it substitutes *phonetically identical characters
from unrelated scripts*. On completely clean audio, with no noise at all:

```
you say "hello"          model wrote  هello      (Arabic heh for Latin h)
you say "help me"        model wrote  help مe     (Arabic meem for Latin m)
you say "are you okay"   model wrote  اr يوك     (Arabic alif, Arabic waw)
```

3 of 19 short English phrases were affected. Worse, the old filter then *deleted* the odd
character and transmitted the remainder — so a phone displayed **"ello"** and **"help e"** as if
they were correct. For an emergency app that is worse than silence, because a human acts on it.

**The fix.** Delete the 9,286 output characters the ten languages do not need — 10,288 → 1,002.
The model then **cannot** emit Arabic, CJK or Hangul at all. And the filter now drops any bad
segment whole instead of editing it.

**The measurements. This is what goes on the slide:**

| | Before | After |
|---|---|---|
| Out-of-script characters on clean audio | 2 of 19 test phrases | **0 — now impossible** |
| Mean character error rate, 2,000 clips | 0.0934 | **0.0918** |
| Other 9 languages' CER | — | **bit-identical** |
| Hindi CER | 0.113 | **0.096** |
| Speed (desktop RTF) | 0.0422 | **0.0352 — 16% faster** |
| Model size | 348.9 MB | **339.8 MB** |

**The number to lead with:** Hindi script retention under background noise at 15 dB SNR went from
**40% to 78%**. Clean-audio Hindi went from 94% to **100%**. This was the project's single
largest measured weakness and it is now closed.

**Why it is worth a slide.** It is a genuine, non-obvious engineering result: a structural fix
that costs nothing, cannot regress nine of ten languages, and makes an entire failure *class*
impossible. Judges reward that far more than another accuracy table.

---

# PART 3 — Slide-by-slide replacement text

## Slide 1 — Title page

Unchanged, except confirm the PS number.

```
iTantra
B.J.P (Bug Janta Party)
Speech to text to speech, fully offline, over low-bitrate links.
Problem Statement ID – SIH26173
Theme – Smart Automation
PS Category – Software
```

Add a one-line **version/date stamp** so judges know which build they are looking at:
`v0.1.0 · 27 September 2026 · 7 of 10 languages spoken aloud, 10 of 10 recognised`

---

## Slide 2 — Idea title / problem

Keep the structure. The one upgrade: state the number that makes the idea obvious.

**Proposed solution**

> Hold to talk. This phone turns your speech into text **on-device** and sends only the text.
> The other phone reads it aloud. Your voice is never transmitted.
>
> 3 seconds of speech is ~96,000 bytes. The same sentence as text is ~200 bytes — **480× smaller**.
> A weak or damaged radio link can carry a sentence when it could never carry a voice.
>
> **No internet. No server. No cloud. Nothing leaves the phone.**

**How it addresses the problem**

- 10 required languages recognised, measured on real recorded human speech
- Emergency alerts speak at maximum volume and loop until acknowledged
- Bluetooth pairing doubles as sender authentication — you know who an alert is from
- Works with no network at all, which is the situation the problem describes

---

## Slide 3 — Technical approach

This slide needs the most work. Current text is wrong (see Part 1, claim 5) and omits the best
result.

**Pipeline diagram** — keep the existing one, it is good:

```
SPEAK → voice detector → recognition model → LANGUAGE FILTER → text (~200 B) → Bluetooth
                                                                              ↓
                                          speaker ← playback ← voice model ← text
```

**Models and runtime** — replace with:

| Component | What it does | Size | Licence |
|---|---|---|---|
| Meta Omnilingual ASR CTC 300M, INT8 | speech → text, on device | 340 MB | **CC-BY-NC-4.0** |
| ↳ **output vocabulary pruned 10,288 → 1,002** | removes 9,286 characters so wrong scripts are impossible | −9 MB | our modification |
| Piper voices × 7 | text → speech | 448 MB | MIT wrapper, mixed data terms |
| Silero VAD | detects when you stop talking | 0.6 MB | MIT |
| k2-fsa sherpa-onnx v1.13.8 | runs the models on Android | 26 MB | Apache-2.0 |
| ONNX Runtime | the maths underneath | (bundled) | MIT |
| espeak-ng | pronunciation rules only — **generates no audio** | 1.7 MB | GPL-3.0 |

**Innovation and uniqueness** — replace with the pruning result (Part 2 above). Suggested wording:

> **We modified the model's output layer.** It is character-level and takes no language
> argument, so it substituted phonetically identical characters from other scripts — "hello"
> became `هello` in Arabic letters, and our filter then transmitted "ello" as a valid message.
> We deleted the 9,286 output characters the ten languages don't need. The model now **cannot**
> emit them: out-of-script output went from 2-in-19 test phrases to zero, and Hindi script
> retention under background noise rose from 40% to 78%. Nine of ten languages scored
> bit-identical accuracy, so the fix cost nothing.

---

## Slide 4 — Feasibility and viability

**Panel 1 — feasibility.** Replace line 1 (the licence claim is false):

> **Measured, not projected:**
> • All 10 required languages scored on 2,000 real recorded sentences (6.7 h, FLEURS)
> • 9.2% of characters correct-but-for-one-error on average — about 1 wrong character in 11
> • Recognition RTF 0.032–0.039 on desktop, 0.147 measured on Android
> • Whole pipeline runs offline; no network used at any point
>
> **Licences — stated plainly:** the recognition model is CC-BY-NC-4.0 and the Hindi voice is
> CC-BY-NC-SA-4.0. Both are free to use and modify but **not for commercial use**. Full
> attribution is in the app under About → Open-source credits.

**Panel 2 — challenges and risks.** Replace with the *current* honest position:

> • **Two phones have not yet been tested against each other.** The Bluetooth link has never
>   completed a real conversation. This is the main thing still to prove.
> • No human has judged the voices. Every intelligibility figure is a machine proxy.
> • Field accuracy in a real disaster is unmeasured; noise was added to clean speech afterwards.
> • Tamil (0.501) and Odia (0.509) have roughly one word in two wrong. Demo English or Hindi.
> • 3 of 10 languages are text-only for speech output.
> • 804 MB APK — fine for sideloading, over a store's size limit.

**Panel 3 — strategies.** Keep, and add:

> • Vocabulary pruning removes a whole failure class rather than patching symptoms
> • Drop a segment whole rather than transmit a partially-corrected message
> • Demo in English and Hindi — the two most robust languages
> • Ship Bluetooth with Wi-Fi Direct fallback; never depend on one transport

**Accuracy strip at the bottom** — replace with the current, re-sorted numbers:

> Measured accuracy — all 10 required languages, 200 real-speech clips each (FLEURS), v2 model with pruned vocabulary
>
> **Kannada 0.053 · Malayalam 0.059 · English 0.067 · Gujarati 0.071 · Hindi 0.096**
> **Bengali 0.100 · Telugu 0.100 · Marathi 0.108 · Tamil 0.129 · Odia 0.134**
>
> Mean **0.0918**. Hindi improved from 0.113. The other nine are unchanged from the unpruned model.

Note Hindi has moved **up the table** — that is a real change from the old deck, which listed
Hindi at 0.113.

---

## Slide 5 — Impact and benefits

Mostly keep. Two corrections:

- Remove any claim that voice quality is uniform. **7 of 10 languages speak; 3 are text-only.**
- The reach argument is the real one:

> Voice reaches people who cannot read. That is the entire reason this sends text but speaks
> it aloud on arrival. In a distress scenario, literacy cannot be assumed.

Keep the social/economic/environmental panels as they are — they are fine and not data claims.

---

## Slide 6 — Research and references

**Must add the licences as first-class entries**, since two are Creative Commons:

| Reference | Link | Licence |
|---|---|---|
| Omnilingual ASR paper | arxiv.org/abs/2511.09690 | — |
| Omnilingual ASR model weights (what we used) | github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models | **CC-BY-NC-4.0** |
| Piper voices | huggingface.co/rhasspy/piper-voices | MIT wrapper, mixed data |
| Silero VAD | github.com/snakers4/silero-vad | MIT |
| sherpa-onnx | github.com/k2-fsa/sherpa-onnx | Apache-2.0 |
| ONNX Runtime | github.com/microsoft/onnxruntime | MIT |
| espeak-ng | github.com/espeak-ng/espeak-ng | GPL-3.0 |
| Material 3 | developer.android.com/jetpack/androidx/releases/material | Apache-2.0 |
| FLEURS (evaluation only) | huggingface.co/datasets/google/fleurs | CC-BY-4.0 |

Every URL above was HTTP-verified. Do not add a link from memory — a dead link in a references
slide costs credibility.

---

# PART 4 — Data tables you can paste straight in

## Recognition accuracy — 2,000 clips, 6.7 hours, 200 clips per language

| Language | Character error | Word error | Plain reading |
|---|---|---|---|
| Kannada | 0.053 | 0.307 | 1 wrong character in 19 |
| Malayalam | 0.059 | 0.361 | 1 in 17 |
| English | 0.067 | **0.181** | **best words — 1 in 6** |
| Gujarati | 0.071 | 0.300 | 1 in 14 |
| **Hindi** | **0.096** | **0.269** | **1 in 10 / 1 in 4** |
| Bengali | 0.100 | 0.359 | 1 in 10 |
| Telugu | 0.100 | 0.431 | 1 in 10 |
| Marathi | 0.108 | 0.416 | 1 in 9 |
| Tamil | 0.129 | 0.501 | weak — 1 word in 2 |
| Odia | 0.134 | 0.509 | weakest |
| **Mean** | **0.0918** | 0.363 | 1 wrong character in 11 |

Report **both** character and word error. Character error flatters complex scripts; quoting only
the flattering one is the kind of shortcut that reads as dishonest under questioning.

## Script retention under background noise — % of outputs in the correct alphabet

This is the metric that decides whether a recipient can **read** the message.

| Condition | Hindi before | **Hindi now** | Tamil | English | Bengali |
|---|---|---|---|---|---|
| clean | 94 | **100** | 100 | 100 | 100 |
| white 30 dB | 64 | **80** | 94 | 100 | 100 |
| white 20 dB | 56 | **80** | 92 | 100 | 100 |
| white 15 dB | 48 | **80** | 92 | 100 | 100 |
| white 10 dB | 54 | **80** | 88 | 100 | 96 |
| white 5 dB | 64 | **92** | 78 | 100 | 96 |
| white 0 dB | 60 | **84** | 44 | 100 | 92 |
| pink 15 dB | 40 | **78** | 90 | 100 | 100 |
| pink 10 dB | 40 | **74** | 90 | 100 | 98 |
| pink 0 dB | 64 | **88** | 60 | 100 | 90 |
| **mean gain** | | **+25.5** | 0 | +0.2 | 0 |

Hindi gained 25.5 points on average. English and Bengali were already at ceiling. **Tamil did not
improve** — its failure mode is dropping syllables, not switching script. Say so if asked.

## Speech output — 7 voices, ASR round-trip proxy

| Language | Voice | CER | Notes |
|---|---|---|---|
| Malayalam | `ml_IN-arjun` | 0.048 | best |
| English | `en_US-lessac` | 0.043 | 7/20 exact matches |
| Hindi | `hi_IN-pratham` | 0.102 | **was 0.339 — fixed** |
| Bengali | `bn_BD-google` | 0.106 | |
| Telugu | `te_IN-venkatesh` | 0.132 | |
| Marathi | `mr_IN-google` | 0.139 | now has its own voice, was borrowing Hindi |
| Tamil | `ta_IN-rasa_female` | not yet scored | newest, CC-BY-4.0 |
| Gujarati, Kannada, Odia | — | — | **text only** |

⚠ **Every figure in this table is a proxy** — the machine reading the machine's own audio back. It
measures intelligibility, not naturalness. **No human has judged the voices.** Do not describe
these as human legibility scores.

## Latency

| Stage | Value | Where measured |
|---|---|---|
| ASR real-time factor | 0.032–0.039 | desktop, 10-core |
| ASR RTF | **0.147** | **Android emulator, 16384-byte pages** |
| TTS synthesis | 45–306 ms per sentence | Android, on device |
| 6.94 s of speech → 211 bytes of text | 1020 ms end to end | Android |
| Packet size | 129–278 bytes | Android |

## APK composition — 804 MB

| Component | Size | Share |
|---|---|---|
| 7 TTS voice models | 448.5 MB | 54.2% |
| 1 ASR model | 340.4 MB | 41.1% |
| Native speech engine | 25.8 MB | 3.1% |
| **Our own code + UI** | **11.6 MB** | **1.4%** |
| Pronunciation dictionaries | 1.7 MB | 0.2% |

> **98.6% of the download is other people's model weights.** Our code is 1.4%.

That is a good line for the feasibility panel — it shows the work is integration and measurement,
not a wrapper around a downloaded model.

Per voice: Marathi 73.2 · Bengali 73.2 · Telugu 60.6 · Tamil 60.6 · Hindi 60.6 · English 60.3 ·
Malayalam 60.0 MB.

---

# PART 5 — Things worth adding that the deck has no room for

If you can spare one line anywhere, these are the highest-value additions:

1. **"In-app open-source credits, in plain language, with every licence spelled out as an
   obligation."** Judges ask about licensing. Answering it inside the app, unprompted, is strong.
2. **"16 KB page-size compatible, verified."** Every modern phone checks this. Ours did not, then
   did. Worth one line under feasibility.
3. **"1.4% of the app is our code; 98.6% is model weights we integrated, modified and measured."**
4. **"Built and verified on a device with 16,384-byte memory pages."**

---

# PART 6 — Final checklist before you submit

- [ ] Claim 1 (licence) corrected on slide 4 — **highest risk item**
- [ ] Claim 2 (espeak-ng as a voice) removed from slides 3 and 4
- [ ] Claim 3 (Hindi noise 50%) replaced with 80% and the before/after
- [ ] Claim 4 (Hindi TTS "open problem") replaced with 0.339 → 0.102
- [ ] Claim 5 (ASR model size + licence) corrected on slide 3
- [ ] Vocabulary pruning added to slide 3 — it is the best result you have
- [ ] Accuracy strip re-sorted with **Hindi at 0.096**, mean **0.0918**
- [ ] TTS described as **7 of 10 spoken, 3 text-only** everywhere it appears
- [ ] Both **character and word** error reported on the same slide
- [ ] Every reference link HTTP-checked
- [ ] All TTS figures labelled as an **automated proxy**, not human legibility
- [ ] "Two phones untested" stated on the risks panel
- [ ] Six slides maximum, PDF only, on the official template
- [ ] PS number reads **SIH26173** on slide 1

## Three things to say if asked

- *"Why 804 MB?"* — 98.6% is model weights. Deleting a voice is a one-line change; we chose
  coverage. It sideloads fine, and a store upload would need asset delivery.
- *"Is it offline?"* — yes, completely. No network call at any point; verified on an emulator
  with no network configuration.
- *"Which languages should we judge on?"* — English and Hindi. Tamil and Odia have about one
  word in two wrong, and three languages are text-only. We would rather tell you that than have
  you find it.
