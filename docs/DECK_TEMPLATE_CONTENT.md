# iTantra SIH26173 — deep content for every template slot

Written against the real template (`deck/source/iTantra_SIH26173.pptx`, 6 slides, 13.33 × 7.5 in).
Every labelled box in the template is addressed below, in template order, with text you can paste.

**Rules for the template itself**
- Do **not** add, delete or reorder slides. The template is 6 slides and the instruction page caps it at 6.
- Keep every italic prompt label exactly as printed. Type your text underneath, never over it.
- Fill `[PORTAL TEAM ID]` with the real value from the SIH portal. It is still a placeholder.
- Export **PDF only**.
- Nothing on a slide may exceed what the result files support. Every number here is traceable.

---

# SLIDE 1 — Title

### `Problem Statement ID`
```
SIH26173
```

### `Problem Statement Title`
Keep the portal's official wording, unchanged. Ours currently reads:
```
iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links
```

### `Theme` / `PS Category` / `Team ID` / `Team Name`
```
Theme         Smart Automation
PS Category   Software
Team ID       [PORTAL TEAM ID]      ← replace with the real portal value
Team Name     B.J.P (Bug Janta Party)
```

### `iTantra` — the big title line
```
iTantra
```

### `Speech in. Text across the link. Speech out.` — the tagline
Keep as is. It is the thesis in five words and it is already correct.

### The one-line summary under the tagline
```
An offline Android app that recognises speech on the sending phone, transmits only compact
text over Bluetooth or Wi-Fi Direct, and speaks it aloud on the receiving phone.
```

### The three badges at the bottom — **replace all three**
The current badges say "Fully offline · 10 languages · No cloud speech API". Add the result and
the real-phone test, which are stronger than a feature list.

```
Fully offline, works between two real phones
10 of 10 languages recognised · 7 spoken aloud
1 wrong letter in 11 · measured on 2,000 real sentences
```

**Do not** put an accuracy number on the title slide. Accuracy belongs on slide 4 where it can be
explained. A number with no context on slide 1 invites a question you have no room to answer.

---

# SLIDE 2 — Idea Title · Proposed Solution

The template gives you a 4-step strip, a two-way comparison, and 4 labelled boxes. Fill all of it.

### The 4-step strip

| Step | Label | Text |
|---|---|---|
| 1 | VOICE | Mic, 16 kHz — Silero VAD waits for your pause |
| 2 | TEXT | Omnilingual ASR, on the device → 340 MB model |
| 3 | TRANSMIT | **129–278 bytes** of UTF-8 text |
| 4 | VOICE | Piper TTS on the device → speaker, 22 050 Hz |

### `Same sentence, two ways` — the comparison block

| | Bytes |
|---|---|
| Raw PCM audio, 3 s | ≈ 96 000 |
| A 16 kbps voice call for the same sentence | ≈ 7 500 |
| **iTantra text payload** | **≈ 200** |

Caption under it:
```
Measured on device: 6.94 s of speech → a 211-byte packet. That is ~66× smaller than a
16 kbps voice call would send, and ~1,050× smaller than the raw audio the mic records.
```

**Why this is worded the way it is.** The old caption said "roughly 1,000× smaller than the
equivalent audio." That is only true against uncompressed audio, and an evaluator who knows
codecs will spot it immediately. Naming the 16 kbps baseline gives you 66×, which is still
decisive, and it is a claim you can defend.

### `Proposed solution`
> An offline Android app: you hold the button and speak, this phone works out what you said and
> turns it into text on itself, sends **only that text** over Bluetooth, and the other phone reads
> it aloud. Your voice is never transmitted and never recorded.

### `Detailed explanation`
> Two phones, push-to-talk, exactly like a walkie-talkie, and either phone can talk at any time.
> A 629 KB voice detector waits for you to stop speaking and cuts out just your sentence. A
> 340 MB recognition model turns it into text on the handset. A language filter throws away
> anything that came back in the wrong script. The text crosses the link at 129–278 bytes. The
> receiving phone speaks it — emergency alerts at full volume, repeating until acknowledged.

### `How it addresses the problem`
> Speech is too large for a damaged link. Our 211-byte message replaces roughly 14 000 bytes of
> voice, so a link that could never carry a voice can carry a whole conversation.
>
> Because the text is *spoken* on the far side, the message still reaches people who cannot read.
> And nothing needs the network, so it works at the exact moment the network is what has broken.

### `Innovation and uniqueness`
> **We changed the model's output layer.** The recogniser is character-by-character and is not
> told which language to use, so it substituted letters that sound identical but belong to another
> script — "hello" came back as `هello` in Arabic letters, and our filter then sent the remainder,
> so a phone displayed **"ello"** as if it were correct. In a rescue app that is worse than
> silence, because a person acts on it.
>
> We deleted the 9,286 output characters our ten languages never need, so the model **cannot**
> emit them. Wrong-script output went from 2 in 19 test phrases to **zero**, Hindi CER improved
> 0.113 → **0.096**, Hindi script retention under background noise went **40% → 78%**, and the
> other nine languages came out **bit-identical**. The fix was free.

**If the four boxes overflow, cut in this order:** the comparison caption, then the third
paragraph of Innovation. Never cut the wrong-script story — it is the strongest thing you have.

---

# SLIDE 3 — Technical Approach

### `Technologies to be used` — the 6-row table

| Row | Text |
|---|---|
| **Android app** | Kotlin · minSdk 23 · targetSdk 34 · AudioRecord / AudioTrack / BluetoothSocket / WifiP2pManager |
| **ASR** | Meta Omnilingual 300M CTC INT8 · **340 MB** · **CC-BY-NC-4.0** · output vocabulary pruned 10,288 → 1,002 characters |
| **TTS** | Piper VITS · **7 voices** (hi, mr, bn, ml, te, ta, en) · 448 MB · MIT wrapper, mixed data terms |
| **VAD** | Silero · 629 KB · MIT · RTF 0.004 |
| **Runtime** | sherpa-onnx v1.13.8 · Apache-2.0 · ONNX Runtime · arm64-v8a |
| **Transport** | Bluetooth Classic RFCOMM (primary) · Wi-Fi Direct (fallback) |

**Three rows are wrong in the current deck and must be edited:**

| Row | Now | Correct to |
|---|---|---|
| ASR | `348.4 MB · Apache 2.0` | `340 MB · CC-BY-NC-4.0` |
| TTS | `hi_IN pratham + en_US lessac · MIT` | `7 voices · MIT wrapper, mixed data terms` |
| VAD | `0.6 MB` | `629 KB` — same number, keep it consistent |

The ASR licence is the important one. **CC-BY-NC-4.0 is non-commercial.** Writing "Apache 2.0"
next to it is the single most damaging error in the deck, because it is a licence claim and an
evaluator can verify it in one click.

### `Methodology and process for implementation` — 3 panels

| PHONE A — SPEAKER<br>loads VAD + ASR | LINK | PHONE B — LISTENER<br>loads VAD + TTS |
|---|---|---|
| Mic · 16 kHz mono | Bluetooth RFCOMM | Message dedupe + expiry |
| Silero VAD finds the pause | Wi-Fi Direct fallback | Piper TTS · text → speech |
| Omnilingual ASR · speech → text | **UTF-8 text only** | Audio out · 22 050 Hz · ALERT loop |
| Language filter enforces script | **129–278 bytes** | Repeats until acknowledged |

Under the LINK panel, add the framing detail, which is the kind of specificity that reads as real work:
```
JSON envelope · 4-byte length prefix · 128–275 bytes framed
```

### The `One heavy model per phone` block — keep the argument, fix the numbers
The reasoning in this box is genuinely good and should stay. The numbers need updating:

> One heavy model per phone. The speaker loads VAD + ASR; the listener loads VAD + TTS. A naive
> design loads both models on both phones and doubles the memory for no benefit. While nobody is
> speaking, only the 629 KB detector runs — the heavy models are not loaded until a segment fires.
> **428 MB PSS / 535 MB RSS** measured on Android with the ASR model resident; the listener needs
> roughly 100 MB.

**Fix:** the current deck says "536 MB PSS" in one place and "428 MB PSS" in another. Two different
numbers for the same thing. Use 428 MB PSS / 535 MB RSS everywhere, and say which models were loaded.

---

# SLIDE 4 — Feasibility and Viability
### This slide carries the 40% accuracy weighting. It is the most important slide in the deck.

### The accuracy table — all 10 rows, updated

| Language | WER (norm) | CER (norm) | RTF median |
|---|---|---|---|
| Kannada | 0.307 | 0.053 | 0.033 |
| Malayalam | 0.361 | 0.059 | 0.036 |
| English | **0.181** | 0.067 | 0.032 |
| Gujarati | 0.300 | 0.071 | 0.032 |
| **Hindi** | **0.269** | **0.096** | 0.037 |
| Bengali | 0.359 | 0.100 | 0.038 |
| Telugu | 0.431 | 0.100 | 0.036 |
| Marathi | 0.416 | 0.108 | 0.035 |
| Tamil | 0.501 | 0.129 | 0.035 |
| Odia | 0.509 | 0.134 | 0.039 |

Caption beneath the table:
```
Pruned model · 200 clips per language · 2,000 clips · 6.72 h of real recorded speech ·
mean CER 0.0918 · mean WER 0.363 · every language under 0.14.
```

**Note what changed in the table:** English WER 0.181 is the best in the set, and English CER
0.067 is third. The old deck had these right. **Hindi moved 0.113 → 0.096** and its WER
0.281 → 0.269 — that is our fix, visible in the table, which is exactly where you want it.

### The four stat cards — rewrite all four

**Card 1 — Offline ASR on real human speech**
```
2,000 real recorded sentences · 6.72 hours · all 10 languages
Mean CER 0.0918 — about 9 wrong letters in 100, or 1 in 11
```

**Card 2 — The demo works** ← this replaces the old "Fast local processing" card and is now your best card
```
Two physical Android phones, 27 September 2026.
Speech on one is recognised, sent as text, and spoken aloud on the other.
The Bluetooth link had never connected before that day.
```

**Card 3 — Fast local processing**
```
RTF 0.032–0.039 across all ten languages. A 3-second sentence costs ≈0.12 s of compute.
On Android: 6.94 s of speech → a 211-byte packet in 1.02 s.
```

**Card 4 — Against a far larger model** — this argument is strong, keep it
```
Meta's 7B LLM-ASR is 2–3× better, but it is 25 GiB and cannot run on a phone.
Ours is 340 MB and does. Malayalam within 1.1×, Telugu 1.5×.
```

### The `Challenge / Risk` table — **all four rows are now wrong or incomplete**

| Current deck says | Replace with |
|---|---|
| *"The radio link has never connected — RFCOMM socket and Wi-Fi Direct group formation are unexecuted."* | **The radio link is now proven.** Two physical phones, 27 Sep 2026. Remaining: Wi-Fi Direct group formation is still unexercised, so Bluetooth is primary and Wi-Fi Direct stays a fallback. |
| *"Every figure is from an API 34 emulator, not a low-end handset."* | Keep, and add what you did about it: benchmarked on real physical phones; **timings and memory still come from the emulator**, and a budget handset will be slower by an unmeasured amount. |
| *"Hindi degrades sharply in noise — CER 0.133 → 0.424 at 30 dB SNR."* | **Now 40% → 78% script retention.** Hindi noise CER 0.6577 → 0.3935 at 15 dB pink noise after pruning. The remaining degradation is real and is disclosed on slide 3 as a risk. |
| *"Kannada, Tamil and Odia TTS voices are not yet wired. Route through espeak-ng."* | **Tamil is now a neural voice** — 7 of 10 speak aloud. **The espeak-ng plan was not implementable**: espeak-ng is a phonemiser and cannot generate audio, and the JNI library ships no espeak TTS config. Gujarati, Kannada and Odia are text-only, and no permissively-licensed offline voice exists for them that we can ship. |

That last row is the one to get right. The current deck promises a fix that **cannot exist**. An
evaluator who knows espeak-ng will mark it down for proposing a technical impossibility.

### Add the licence honesty line under the table

```
Licences, stated plainly: the ASR model is CC-BY-NC-4.0 and the Hindi voice is
CC-BY-NC-SA-4.0 — free to use and modify, NOT for commercial use. Full attribution
ships in-app under About → Open-source credits, in plain language.
```

---

# SLIDE 5 — Impact and Benefits

### `Potential impact on the target audience` — keep the 3 cards, sharpen them

| Card | Text |
|---|---|
| **Field responders** | NDRF and SDRF teams, district administration, camp volunteers. The first responder and the affected person are rarely in the same building; today they rely on radio, which is narrowband and text-based for exactly this reason. We keep the voice at both ends and spend the scarce bytes on words. Bluetooth pairing also identifies the sender — an alert is traceable to a device somebody deliberately connected, which matters when the alert tells people to move. |
| **Disaster / shelter teams** | Useful precisely when cellular and internet are down — the moment the network stops being the thing you can depend on. Runs on phones people are already carrying; no infrastructure to deploy. |
| **People who cannot read or type** | **This is the group almost every alert system leaves out.** SMS, push notifications and app panels all require reading. In the districts most exposed to floods and cyclones, literacy is uneven and first languages are regional. Because our pipeline speaks on arrival, the message reaches them as sound, not as text. |

Adding that third card is the strongest single improvement available to this slide. It is what
separates this from a bandwidth trick.

### `Benefits of the solution` — 3 cards

| | |
|---|---|
| **Social** | Voice in, **voice out**. Reaches people who cannot read or type, because the alert is *heard*, not read. Inclusive by construction rather than as an add-on. |
| **Economic** | No paid API, no cloud subscription, no proprietary SDK. Runs on phones people already own, on only free open-source models. **Licence caveat: CC-BY-NC and CC-BY-NC-SA components mean non-commercial use** — fine for a responder deployment, not for a commercial product without a different model. |
| **Environmental** | A 211-byte message instead of ~14 000 bytes of voice — about **66× less transmitted data**, so less radio energy per message. Battery runtime effect not yet measured. |

The Economic card **must** carry the non-commercial caveat. Leaving it out contradicts slide 4 and
converts a strength into a trap.

### `What is measured and what is not.` — rewrite

> **Measured:** works between two physical phones · accuracy on 2,000 real recorded sentences
> across all 10 languages · Android pipeline latency and memory with the model resident · ~66×
> payload reduction.
>
> **Not yet measured:** human legibility of the voices · timings on a budget handset · battery
> runtime · accuracy on speech recorded in a real disaster.
>
> These are proposed outcomes, not measured results. **We claim no lives saved and no guaranteed
> delivery.**

---

# SLIDE 6 — Research and References

### `Models and runtime` table

| Item | Source | Licence and size |
|---|---|---|
| ASR model | `csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12` | **CC-BY-NC-4.0** · 340 MB · INT8 · vocabulary pruned to 1,002 |
| TTS voices | `rhasspy/piper-voices` — hi_IN pratham, mr_IN google, bn_IN payas, ml_IN arjun, te_IN, ta_IN, en_US lessac | MIT wrapper · **mixed data terms** · 448 MB · VITS |
| Runtime | `k2-fsa/sherpa-onnx` v1.13.8 + Android native libs | Apache-2.0 · arm64-v8a |
| Voice activity detection | Silero VAD | MIT · 629 KB · RTF 0.004 |
| Speech corpus | `google/fleurs` — real recorded human speech, all ten PS languages | CC-BY-4.0 · 200 clips/language, 2,000 clips, 6.72 h · **evaluation only, not shipped** |
| Transport docs | `developer.android.com` — Bluetooth RFCOMM and Wi-Fi Direct | Platform documentation |
| Audio APIs | AudioRecord 16 kHz · AudioTrack 22 050 Hz · AudioManager STREAM_ALARM | Android platform, API 23+ |

Add the ASR paper, which the current deck omits:
```
Omnilingual ASR — arxiv.org/abs/2511.09690
```

### `Reproducibility`
```
eval/SUMMARY_full_v3pruned.json      accuracy, all 10 languages
eval/noise_robustness_v3pruned.json  script retention under noise
eval/tts_intelligibility_pruned.json speech-output legibility proxy
eval/prune_vocab.py                  the 10,288 → 1,002 vocabulary prune
app/evidence/                        on-device logs, incl. the 211-byte packet
Every figure in this deck is reproducible from these files.
```

### `Licensing, component by component` — **the most important fix in the deck**

| Component | Current deck says | Must say |
|---|---|---|
| ASR model | Apache 2.0 | **CC-BY-NC-4.0 — non-commercial** |
| Piper voices | MIT | MIT wrapper; individual voices carry their own terms, incl. **CC-BY-NC-SA-4.0 (Hindi)** |
| Silero VAD | MIT | MIT |
| sherpa-onnx runtime | Apache 2.0 | Apache-2.0 |
| AndroidX / Material | Apache 2.0 | Apache-2.0 |
| espeak-ng-data | GPL, "redistribution obligation flagged" | GPL-3.0 — dictionaries only, **not used for synthesis** |
| FLEURS | not listed | CC-BY-4.0 — evaluation only, not shipped |

### The closing claim — delete it

The current deck ends slide 6 with:
> "No CC-BY-NC component ships in the product; MMS-TTS was evaluated and rejected on that basis."

**This is false.** The ASR model in the shipped APK *is* CC-BY-NC-4.0, and the Hindi voice *is*
CC-BY-NC-SA-4.0. A false licensing claim on the references slide, immediately after a table of
licences, is the easiest thing on the deck for an evaluator to catch and the worst thing they can
catch you making.

Replace it with:
```
Non-commercial components do ship. CC-BY-NC-4.0 (ASR) and CC-BY-NC-SA-4.0 (Hindi voice)
permit use and modification for non-commercial purposes; we have complied with the
attribution and share-alike conditions and state them in plain language in-app.
```

This is stronger, not weaker. Acknowledging a licence limitation and complying with it
demonstrates exactly the rigour the rest of the deck is claiming. Getting caught claiming
permissive licensing destroys the argument instead.

---

# THE ORDER TO WORK IN

1. **Slide 6 licences** — the false "no CC-BY-NC ships" claim, and the ASR licence row
2. **Slide 4** — the demo-works card, and all four challenge/risk rows
3. **Slide 3** — ASR licence + size, TTS voice count
4. **Slide 2** — the 1,000× → 66× caption, and the innovation box
5. **Slide 5** — the non-commercial caveat, the third impact card
6. **Slide 1** — new badges, real Team ID
7. Export PDF, then check every link in slide 6 opens

Do 1 and 2 first. Between them they contain the two things an evaluator is most likely to catch:
a false licensing claim, and a demo described as unbuilt that you have since built.

---

# ONE-PAGE REFERENCE

| | |
|---|---|
| Sentences tested | 2,000 · 200/language · 6.72 h |
| Mean CER / WER | 0.0918 / 0.363 |
| Best / worst CER | Kannada 0.053 / Odia 0.134 |
| Hindi CER before → after | 0.113 → 0.096 |
| Hindi script retention, 15 dB pink | 40% → 78% |
| Wrong-script output | 2 in 19 → 0 |
| Vocabulary prune | 10,288 → 1,002 characters |
| Packet size | 129–278 bytes · 211 for 6.94 s |
| Reduction vs 16 kbps codec / raw PCM | **66×** / 1,050× |
| RTF desktop / Android | 0.032–0.039 / 0.147 |
| TTS per sentence on Android | 49–380 ms, median 193 |
| Memory with ASR resident | 428 MB PSS / 535 MB RSS |
| Voices | 7 spoken · 3 text-only |
| APK | 804 MB · arm64 · 16 KB clean |
| Two real phones | **works**, 27 Sep 2026 |
