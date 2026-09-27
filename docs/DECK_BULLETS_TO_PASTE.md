# iTantra SIH26173 — paste-ready bullets, label by label

**Points only. No paragraphs.** Per the official template's own instruction: *"Try to avoid
paragraphs and post your idea in points /diagrams / Infographics /pictures."*

**Before you start — 4 things the template demands**
1. **6 slides including the title.** The file has 7. Slide 7 is the instructions page — **delete it**.
2. **PDF only.** *"You need to save the file in PDF and upload the same on portal. No PPT, Word Doc or any other format."*
3. **Do not edit the italic labels.** They are the "idea details pointers".
4. **Team ID and Team Name come from the portal**, not from this document.

**How to lay out each slide**
- Leave the label box alone. Add your content in **new text boxes below it**.
- Usable content area: about **10.26 in wide × 4.5 in tall**.
- Suggested font sizes: bullets **16–18 pt**, table text **12–14 pt**, big numbers **28–36 pt**.

---

# SLIDE 1 — TITLE PAGE

Fill the template's own fields:

```
Problem Statement ID     SIH26173
Problem Statement Title  (copy the portal wording exactly)
Theme                    Smart Automation
PS Category              Software
Team ID                  (from portal)
Team Name                B.J.P (Bug Janta Party)
```

Then add, in the blank area:

**Big title**
```
iTantra
```

**One-line tagline**
```
Speak on one phone. Words cross the link. Speech comes out the other end.
```

**Three badges — put these in three small boxes in a row**
```
Works offline between two real phones
10 of 10 languages · 7 spoken aloud
1 wrong letter in 11
```

---

# SLIDE 2 — IDEA TITLE

4 labels. Give each label **one content box**. 2 columns × 2 rows.

### Label 1 → `Proposed Solution (Describe your Idea/Solution/Prototype)`

```
• Offline Android app
• Speak → text on the phone → 129–278 bytes → spoken aloud on the other phone
• Your voice is never sent, never recorded
• No internet, no cloud, no server
• Tested: works between two real phones
```

**Visual:** 4-box arrow strip across the top of this quadrant
`🎤 Mic → 📝 ASR → 📶 129–278 B → 🔊 TTS`

### Label 2 → `Detailed explanation of the proposed solution`

```
• Hold to talk; a 629 KB voice detector waits for your pause
• 340 MB recognition model runs on the handset → text
• Language filter drops anything in the wrong script
• Receiving phone speaks it: 22 050 Hz, full volume, alert repeats
• Either phone can talk at any time
```

### Label 3 → `How it addresses the problem`

```
• Measured: 6.94 s of speech → a 211-byte message
• Same sentence as 16 kbps voice ≈ 14 000 bytes → 66× smaller
• Text is spoken on arrival → reaches people who cannot read
• Needs no network → works when the network is what broke
```

**Visual:** 3-bar comparison chart
```
16 kbps voice   ████████████████████  14 000 B
Raw mic audio   ██████████████████████████████  222 000 B
iTantra text    ▏211 B
```

### Label 4 → `Innovation and uniqueness of the solution`

```
• Bug: recogniser wrote Hindi in Arabic letters (same sound) — "hello" → هello
• Our filter then deleted the odd letter and sent "ello" as if correct
• In a rescue app that is worse than silence — a person believes it
• Fix: deleted the 9,286 output characters our 10 languages never need
• Vocabulary 10,288 → 1,002, so the model cannot emit them
• Wrong-script output: 2 in 19 → 0
• Hindi CER 0.113 → 0.096 · script retention in noise 40% → 78%
• 9 of 10 languages bit-identical → the fix was free
```

**Visual:** two boxes side by side — `BEFORE: هello → ello (sent)` vs `AFTER: hello ✓`

---

# SLIDE 3 — TECHNICAL APPROACH

2 labels. This slide is mostly table + diagram.

### Label 1 → `Technologies to be used (e.g. programming languages, frameworks, hardware)`

Make this a **6-row table**:

| Part | What | Licence |
|---|---|---|
| App | Kotlin, Android minSdk 21, targetSdk 34, arm64 | — |
| Recognition | Meta Omnilingual 300M CTC INT8 · 340 MB · vocab pruned to 1,002 | **CC-BY-NC-4.0** |
| Speech out | Piper VITS · **7 voices** · 448 MB | MIT wrapper, mixed data terms |
| Sentence cut | Silero VAD · 629 KB · RTF 0.004 | MIT |
| Runtime | sherpa-onnx 1.13.8 + ONNX Runtime | Apache-2.0 |
| Link | Bluetooth RFCOMM (main) · Wi-Fi Direct (backup) | — |

**Fix while you are here:** the old deck says `348.4 MB · Apache 2.0` for the ASR model.
It is **340 MB** and **CC-BY-NC-4.0**. That is a licence claim — the most checkable error in the deck.

### Label 2 → `Methodology and process for implementation`

**This is where the flow chart goes.** 3 columns with arrows:

```
┌─ PHONE A · SPEAKER ────┐   ┌──── LINK ────┐   ┌─ PHONE B · LISTENER ──┐
│ Mic 16 kHz mono        │   │              │   │ Dedupe + expiry       │
│ Silero VAD finds pause │ → │  UTF-8 text  │ → │ Piper TTS             │
│ Omnilingual ASR → text │   │  129–278 B   │   │ 22 050 Hz out         │
│ Language filter        │   │ JSON + 4 B   │   │ ALERT, repeats        │
└────────────────────────┘   └──────────────┘   └───────────────────────┘
```

**Stat strip along the bottom, 4 boxes:**
```
6.94 s speech          ASR RTF 0.032–0.039        Idle = 629 KB only        428 MB PSS
→ 211 B in 1.02 s       (desktop)                  (models not loaded)       (ASR resident)
```

---

# SLIDE 4 — FEASIBILITY AND VIABILITY

3 labels. **This slide carries the 40% accuracy mark — give it the most space.**

### Label 1 → `Analysis of the feasibility of the idea`

```
• Works on two physical phones, 27 Sep 2026 — never connected before
• 2,000 real recorded sentences · 6.72 h · all 10 languages
• Mean CER 0.0918 → 1 wrong letter in 11 · mean WER 0.363
• Best Kannada 0.053 · worst Odia 0.134 · all under 0.14
• Zero network use. Nothing recorded, nothing uploaded
• Licences: ASR CC-BY-NC-4.0, Hindi voice CC-BY-NC-SA-4.0 → non-commercial, disclosed in-app
```

**Visual:** horizontal bar chart, 10 bars, CER per language — **Hindi highlighted** since it moved
```
Kannada    0.053  ████
Malayalam  0.059  ████
English    0.067  █████
Gujarati   0.071  █████
Hindi      0.096  ███████   ← 0.113 before our fix
Bengali    0.100  ███████
Telugu     0.100  ███████
Marathi    0.108  ████████
Tamil      0.129  █████████
Odia       0.134  ██████████
```

### Label 2 → `Potential challenges and risks`

Make this a **2-column table — risk | what we actually know**. Short lines.

| Risk | Status |
|---|---|
| No human judged the voices | All clarity numbers are machine round-trip proxies |
| Tamil & Odia weak | 0.501 / 0.509 word error — ~1 word in 2 |
| 3 of 10 text-only | gu, kn, or — no shippable offline voice exists |
| Speed & memory from emulator | Not a budget handset; slower by an unmeasured amount |
| Noise is synthetic | Added to clean speech, not recorded in a disaster |
| 804 MB APK | Sideload only, over a store limit |

### Label 3 → `Strategies for overcoming these challenges`

```
• Fixed the failure class, not the symptom — removed the wrong characters
• Drop a whole sentence rather than send a half-corrected one
• Demo in English + Hindi, our two most accurate
• Report letter error AND word error — word error flatters long written words
• Bluetooth primary, Wi-Fi Direct backup — never depend on one link
```

---

# SLIDE 5 — IMPACT AND BENEFITS

2 labels.

### Label 1 → `Potential impact on the target audience`

**3 icon cards in a row:**

| 🛡️ Field responders | ⛺ Disaster & shelter teams | 🔊 People who cannot read |
|---|---|---|
| NDRF / SDRF, district admin, camp volunteers | Works when cellular and internet are down | Every other alert system needs reading — ours **speaks on arrival** |
| Responder and affected person are rarely in the same place | Runs on phones already in their pocket | Regional first languages, uneven literacy |
| Bluetooth pairing identifies the sender of an alert | No infrastructure to deploy | The group almost every alert system leaves out |

**Add this third card if you only keep one.** It is what separates this from a bandwidth trick.

### Label 2 → `Benefits of the solution (social, economic, environmental, etc.)`

**3 cards in a row:**

| Social | Economic | Environmental |
|---|---|---|
| Voice in, **voice out** | No paid API, no cloud, no proprietary SDK | 211 B instead of ~14 000 B → **66× less data** |
| Reaches people who cannot read or type | Runs on phones people already own | Less radio energy per message |
| Inclusive by construction | ⚠️ Non-commercial licence — fine for responder use, not a commercial product | Battery effect not yet measured |

**Then a 2-box strip at the bottom — this is what makes the slide believable:**

| ✅ Measured | ❌ Not yet measured |
|---|---|
| Two real phones | Human legibility of the voices |
| 2,000 real sentences, 10 languages | Speed on a budget handset |
| 66× less data than voice | Battery runtime |
| Fully offline | Accuracy in a real disaster |

Small line under it:
```
Proposed outcomes, not proven results. We claim no lives saved.
```

---

# SLIDE 6 — RESEARCH AND REFERENCES

1 label. The box is small, so use a **table**, not bullets.

### Label → `Details / Links of the reference and research work`

| What | Source | Licence |
|---|---|---|
| Omnilingual ASR paper | arxiv.org/abs/2511.09690 | — |
| ASR model (the one we ship) | github.com/k2-fsa/sherpa-onnx → releases/asr-models | **CC-BY-NC-4.0** · 340 MB |
| Piper voices × 7 | huggingface.co/rhasspy/piper-voices | MIT wrapper, mixed data terms |
| Silero VAD | github.com/snakers4/silero-vad | MIT |
| sherpa-onnx runtime | github.com/k2-fsa/sherpa-onnx | Apache-2.0 |
| ONNX Runtime | github.com/microsoft/onnxruntime | MIT |
| espeak-ng | github.com/espeak-ng/espeak-ng | GPL-3.0 — dictionaries only, not used for synthesis |
| Material Design 3 | developer.android.com/jetpack/androidx/releases/material | Apache-2.0 |
| FLEURS (testing only) | huggingface.co/datasets/google/fleurs | CC-BY-4.0 — evaluation only, not shipped |

**Line under the table:**
```
Non-commercial components do ship. CC-BY-NC-4.0 (ASR) and CC-BY-NC-SA-4.0 (Hindi voice)
permit use and modification for non-commercial use. We met the attribution and share-alike
conditions and state them in plain language in-app.
```

**Check every link opens before you submit.**

---

# THE 6 EDITS THAT MATTER MOST

Old deck says → change to:

| # | Slide | Wrong | Right |
|---|---|---|---|
| 1 | 6 | *"No CC-BY-NC component ships in the product"* | **It does.** ASR is CC-BY-NC-4.0. Owning it is stronger than denying it |
| 2 | 3 | ASR `348.4 MB · Apache 2.0` | `340 MB · CC-BY-NC-4.0` |
| 3 | 2 | *"roughly 1,000× smaller than audio"* | **66×** vs a 16 kbps voice call. 1,000× only holds against raw mic audio |
| 4 | 4 | *"the radio link has never connected"* | **It does** — two physical phones, 27 Sep 2026 |
| 5 | 4 | *"route missing voices through espeak-ng"* | espeak-ng cannot make sound. 7 voices now, 3 text-only |
| 6 | 4 | *"Hindi falls to ~50% in noise"* | **78%** now; was 40% before our fix |

Do #1 and #4 first. #1 is a false licence claim directly under a table of licences, and #4
understates the single best thing you have done.

---

# BEFORE YOU UPLOAD

- [ ] Slide 7 (instructions) **deleted** → 6 slides remain
- [ ] Exported as **PDF**
- [ ] Italic labels **unchanged**
- [ ] `Team ID` and `Team Name` match the portal
- [ ] Slide 1 shows **SIH26173**
- [ ] Every link in slide 6 opens
- [ ] No paragraphs — bullets, diagrams, charts only
- [ ] Slide 4 has the 10-language accuracy chart
- [ ] Slide 3 has the flow diagram
- [ ] Licence claims on slides 3, 5, 6 all agree with each other
