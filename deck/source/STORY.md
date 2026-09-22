# STORY.md — iTantra, SIH26173

## ① Intent

**Audience & occasion.** Evaluators of Smart India Hackathon 2026, problem statement SIH26173 (Indian
Space Research Organisation, theme Smart Automation, category Software). This is an *idea
submission* reviewed on paper first and defended in a short pitch. Evaluators are technical — they
will read the numbers.

**Core goal.** After reading, an evaluator should believe three things and act on one.
Believe: (1) voice-over-low-bitrate is solved by moving speech intelligence to the device and
sending text, not audio; (2) the pipeline is *measured*, not asserted — 2,000 real-speech clips
across all ten mandated languages; (3) the team knows exactly what is still unproven.
Act: shortlist SIH26173 and let us defend the demo.

**Length.** Exactly **6 slides including the title slide.** This is a hard cap stated on the
template's own instruction page ("Kindly keep the maximum slides limit up to six (6). Including the
title slide"). The same page states the deck must use the provided template "without changing the
idea details pointers" — so the four italic prompt labels on slides 2–5 are kept verbatim and our
content sits under them.

**Visual tone.** Institutional, evidence-first, restrained. Official SIH template language: black
Times New Roman section titles, a #0070C0 footer rule, navy section chips, one orange accent. No
gradients, no stock photography, no decorative icons beyond the SIH mark and the team badge.

**Content boundaries.**
- **Must say:** the text-not-audio thesis; the measured payload; the v2 accuracy table across all
  ten languages; that the Android app has been executed; the honesty line about what is unmeasured.
- **Will not say:** "works" for the radio link; "field-ready"; "disaster-proven"; any figure not
  traceable to a file in `output/option_a_stt/`.
- **Forbidden:** the retired v1 accuracy numbers; the old 6-clip results; "everything is
  permissively licensed"; claims of lives saved.

**Structural deviation, declared.** The template forbids extra slides, so this deck has **no catalog
page and no section-divider pages**. The usual catalog↔section contract is therefore not applicable
— there is nothing to enumerate. The six mandated sections are the structure.

---

## ② Layout skeleton

| Page | Section (mandated label kept) | role | rhythm | layout | asymmetric? |
|---|---|---|---|---|---|
| 1 | Title slide | **hero** | peak | 全屏视觉+大标题 | no (cover) |
| 2 | Idea / Proposed Solution | supporting | peak | 非对称双栏 60:40 | **yes** |
| 3 | Technical Approach | supporting | valley | 左标题+右内容 32:68 | **yes** |
| 4 | Feasibility and Viability | **hero** | peak | 图表+洞察 | no |
| 5 | Impact and Benefits | supporting | valley | N卡片横排 | no |
| 6 | Research and References | supporting | valley | 上下分栏 | **yes** |

**Hero budget.** 2 of 6 = 33%. Page 1 is the cover; page 4 is the evidence page — it carries the
40%-weighted accuracy criterion, so it is the true centrepiece. They are not adjacent.

**Rhythm.** peak (1) → peak (2) → valley (3) → **peak (4)** → valley (5) → valley (6). No run of
three valleys.

**Layout constraints checked.** No two adjacent pages share a layout. `N卡片横排` used once (cap 2).
Asymmetric layouts = pages 2, 3, 6 = **50%** (floor 40%). `左大图+右侧文字` + `非对称双栏` = 1 page
(cap 40% of 6 = 2.4). Hero pages use no card-row layout.

---

## ③ Page outline

### Page 1 — Title
- **title:** SMART INDIA HACKATHON 2026
- **type:** cover · **role:** hero · **rhythm:** peak · **layout:** 全屏视觉+大标题
- **visual:** L3: SIH brain-bulb mark (top right, fixed all pages) · L2: hexagonal field motif
- **visual_role:** atmosphere
- **density:** 字数约 120 / 图 2 张 / 留白约 45%
- **anti_pattern:** 禁止把 PS 元数据排成装饰卡片；禁止在标题右侧加装饰小图；禁止改动模板的六个元数据字段
- **description:** PS ID **SIH26173**, title *iTantra — Indian Multilingual TTS & STT Aided Neural
  Transceiver Radio Access for low bitrate links*, theme Smart Automation, category Software,
  organisation ISRO. → The metadata is the compliance contract; every field is present and correct,
  so the evaluator can match this deck to the problem statement in one glance.

### Page 2 — Idea title and proposed solution
- **title:** iTantra — send voice as text, not as audio
- **type:** content · **role:** supporting · **rhythm:** peak · **layout:** 非对称双栏 60:40
- **visual:** L1: Diagram(voice→text→link→voice pipeline, occupies left 60%) · L2: payload comparison bar
- **visual_role:** anchor
- **density:** 字数约 190 / 图 2 张 / 留白约 22%
- **anti_pattern:** 禁止 50:50 等分双栏；禁止把 payload 数字塞进角落小字；禁止四卡片等宽横排
- **description:** The PS names the constraint — audio is too data-intensive for low-bitrate links,
  yet in distress scenarios audio is *more* inclusive than text. → Our resolution: recognise speech
  locally and transmit **only the text**. Measured payload **49–111 bytes** per sentence versus
  ~93 KB of raw PCM for the same three seconds — roughly **1,000× smaller**. Kept labels:
  *Proposed Solution*, *Detailed explanation*, *How it addresses the problem*, *Innovation and
  uniqueness*.

### Page 3 — Technical approach
- **title:** TECHNICAL APPROACH
- **type:** content · **role:** supporting · **rhythm:** valley · **layout:** 左标题+右内容 32:68
- **visual:** L1: Diagram(6-stage pipeline, speaker → transport → listener) · L2: stack table
- **visual_role:** evidence
- **density:** 字数约 240 / 图 1 张 / 表 1 个 / 留白约 18%
- **anti_pattern:** 禁止把技术栈写成无尺寸的裸名词列表；禁止把 VAD 画成主视觉；禁止相邻页复用双栏
- **description:** Six stages, all on-device. Speaker: mic 16 kHz → **Silero VAD** (0.6 MB,
  RTF 0.004) → release-flush → **Omnilingual ASR 300M CTC INT8** (348.4 MB, Apache 2.0) →
  language filter → JSON packet with a 4-byte length prefix. Link: **Bluetooth Classic RFCOMM
  primary**, Wi-Fi Direct fallback. Listener: **Piper VITS** voices (hi_IN pratham 60.6 MB, en_US
  lessac 60.3 MB, MIT) → AudioTrack 22 050 Hz on STREAM_ALARM. → The load-bearing decision is
  **one heavy model per phone**: speaker carries VAD+ASR, listener carries VAD+TTS, so a listener
  needs ~100 MB rather than a duplicate 348 MB, and idle listening runs only the VAD. Kept labels:
  *Technologies to be used*, *Methodology and process for implementation*.

### Page 4 — Feasibility and viability
- **title:** FEASIBILITY AND VIABILITY
- **type:** content · **role:** **hero** · **rhythm:** peak · **layout:** 图表+洞察
- **visual:** L1: Table(10 languages × WER/CER/RTF, occupies left 60%) · L3: three insight callouts
- **visual_role:** anchor
- **density:** 字数约 260 / 表 1 个 / 留白约 16%
- **anti_pattern:** 禁止只摆数字不给判断；禁止隐藏弱势语言；禁止把 200 段样本量写小字；禁止引用 v1 数字
- **description:** **2,000 clips, 200 per language, 6.72 hours, all ten mandated languages** on
  Google FLEURS real human speech. Mean normalised CER **0.093**; every language under **0.14**;
  best Kannada 0.053, weakest Odia 0.134. RTF median 0.039–0.043. → Context that makes it land:
  Meta's 7B model is 2–3× better but is 25 GiB and cannot run on a phone; ours is 348 MB and does,
  and on Malayalam we are within 1.1×. Weakest languages are stated openly. Then the risk block:
  the radio has never connected, figures are from an emulator, Hindi CER degrades 0.133→0.424 at
  30 dB SNR, and Kannada/Tamil/Odia TTS voices are not yet wired. Kept labels: *Analysis of the
  feasibility of the idea*, *Potential challenges and risks*, *Strategies for overcoming these
  challenges*.

### Page 5 — Impact and benefits
- **title:** IMPACT AND BENEFITS
- **type:** content · **role:** supporting · **rhythm:** valley · **layout:** N卡片横排
- **visual:** L2: audience strip · FAIcon list for the benefit cards
- **visual_role:** evidence
- **density:** 字数约 210 / 图 0 张 / 留白约 25%
- **anti_pattern:** 禁止把"已测量"和"未测量"混在一张卡里；禁止宣称救人；禁止把效益写成已实现
- **description:** Three audiences — field responders on constrained links, disaster/shelter teams
  when internet is gone, remote and industrial sites in bandwidth-limited environments. Benefits:
  **social** (voice in, voice out — usable without literacy), **economic** (no paid API, no cloud,
  Apache 2.0 / MIT only), **environmental** (text instead of audio lowers transmitted energy, but
  battery runtime is *not yet measured*). → Closes with the honesty line: accuracy, latency and
  memory are measured; field accuracy in noise, battery runtime and human TTS legibility are not.
  Benefits are proposed outcomes, not measured results. Kept labels: *Potential impact on the target
  audience*, *Benefits of the solution (social, economic, environmental, etc.)*.

### Page 6 — Research and references
- **title:** RESEARCH AND REFERENCES
- **type:** content · **role:** supporting · **rhythm:** valley · **layout:** 上下分栏
- **visual:** L3: source chips per group
- **visual_role:** evidence
- **density:** 字数约 200 / 图 0 张 / 留白约 30%
- **anti_pattern:** 禁止用模糊免责声明替代逐项许可；禁止留空占位链接；禁止把 espeak-ng 的 GPL 藏起来
- **description:** Upper band — models and runtime: Omnilingual ASR 300M INT8 (Apache 2.0, 348.4 MB),
  rhasspy/piper-voices hi_IN pratham + en_US lessac (MIT), k2-fsa/sherpa-onnx v1.13.8 (Apache 2.0),
  Silero VAD (MIT). Lower band — evaluation and platform: google/fleurs (CC-BY-4.0, 2,000 real
  speech clips), developer.android.com for Bluetooth RFCOMM and Wi-Fi Direct. → Licensing stated
  component by component, including the one exception: **espeak-ng-data is GPL**, flagged for
  redistribution review. No CC-BY-NC component ships. Kept label: *Details / Links of the reference
  and research work*.

---

## Checklist

- Hero pages: 2 / 6 = 33% — within 20–35%, and non-adjacent.
- No run of three consecutive supporting+valley pages (peak sits at page 4).
- `N卡片横排` used once.
- Asymmetric layouts 50% ≥ 40%.
- No two adjacent pages share a layout.
- Every page carries `role` / `rhythm` / `visual_role` / `anti_pattern`.
- Every page with a number also carries a judgment (see the "→" clause in each description).
- Catalog↔section contract: not applicable — the template caps the deck at six slides and provides
  no catalog or divider pages. Declared above.
- Every figure traces to a file in `output/option_a_stt/`. The v1 accuracy table is retired and
  does not appear anywhere in this deck.
