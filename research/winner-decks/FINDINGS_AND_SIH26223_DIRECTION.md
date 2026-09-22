# Verified deck findings and SIH26223 direction

Research date: 17 September 2026. Existing proposals were not edited. Web retrieval used Scrapling, repository discovery used GitHub API, source/deck downloads used HTTPS. No application or radio prototype was executed.

## Evidence standard and coverage

Three public project presentations were downloaded and text-extracted, with team/PS identity matched to official results. This does not authenticate the exact file/version presented to judges, establish judging reasons, or complete a five-year review. Winner and joint-winner are distinct statuses; SIH has PS-specific results, not one national first-place deck.

- **2022 Traditional Learners / Sangyan, AD976, Winner.** Official result: https://sih.gov.in/pdf/SIH-SOFTWARE-RESULT.pdf . Deck: https://github.com/SnehalSwadhin/SIH-2022---Sangyan-Winners-/blob/HEAD/Final%20PPT.pdf . Eight PDF pages extracted. Team name and PS appear on page 1; official leader Snehal Swadhin matches repository owner. Pages 5–6 report initial WER 32.3 and final WER 17.9. These are deck claims, not independently replicated scores, and the deck does not establish an identical test protocol. Pages 2–3 describe Sanskrit access research, a speech-to-search extension, synonyms and word-instance lookup. Lesson: a narrow user task plus a numerical model comparison is stronger evidence than listing AI frameworks. No proof these specific features caused the win.
- **2023 404:Found, SIH1415, Winner.** Official: https://sih.gov.in/sih2023-grand-finale-result . Deck: https://github.com/Ashwin-Baduni/video-based-motion-amplification-and-vibration-analysis-SIH-2023/blob/HEAD/SampleOutputs/Final%20PPT.pdf . Fifteen pages extracted; leader Swarnim Sawane and PS match the official result. Pages 5–6 and 13 describe heatmap-guided area selection, targeted magnification and per-region waveform/FFT analysis. Page 12 labels fault prediction as future work; page 14 acknowledges capture hardware dependence. Lesson: convert an algorithm into an operator decision workflow, distinguish current analysis from future diagnosis. No quantitative accuracy substantiation found in extracted text; graphical pages were not fully visually audited.
- **2025 Ourobonics / Kritrim, SIH25117, Joint Winner.** Official: https://sih.gov.in/sih2025/sih2025-grand-finale-result . Deck: https://github.com/Naveenmehta121/TEAM-OUROBONICS---SIH2025-WINNERS/blob/HEAD/ourobonics_SIH2025.pptx . Six slides extracted, embedded images additionally OCRed. PS/team match result; repo owner matches official leader Naveen kumar. Slides 2 and 5 emphasise snap-and-fit repair, interchangeable materials, MSME uses and lifecycle cost. Slide 3 lists Raspberry Pi/Arduino, servos, Python, Google speech/cloud and LiveKit. Lesson: hardware differentiation can be maintainability and deployment economics rather than a novel AI model. Claims of 70–80% savings and hazardous-environment suitability were not validated. Its cloud speech stack would not satisfy SIH26173's offline restrictions.

Coverage gaps: no verified 2024 winner deck obtained (Pratishtha's joint-winner status was verified, but a README/dashboard is not a deck). No verified 2021 winner deck obtained; absence of an archive URL is not proof no event took place. A previously downloaded 2020 Naari deck is outside a strict 2021–2025 window and is not substituted silently. The Digital Druids repository PPT was inspected and proved to be a blank official template; excluded.

## Uniqueness rubric derived from these examples (analysis, not official causation)

1. Specific user and difficult operating condition.
2. Named baseline and an explicit shortcoming.
3. A concrete mechanism that addresses that shortcoming.
4. An observable outcome and fair comparison.
5. Deployment/repair/maintenance argument where hardware matters.
6. Honest separation of demonstrated features, claims and future work.

This is a reasoning rubric used in this conversation, not permanent model training. A sample of winners without matched losing entries cannot isolate why teams won or estimate selection probability.

## Practical feasibility findings

### OpenSuperWhisper
Repository: https://github.com/Starmel/OpenSuperWhisper . Readme.md, licence, TranscriptionService.swift, TranscriptionEngine.swift and WhisperEngine.swift inspected. MIT macOS Apple Silicon application; Whisper/whisper.cpp and Parakeet/FluidAudio engines. Whisper engine handles local speech segments and transcription; transcription service selects engines and returns text. Streaming remains a README TODO despite real-time marketing wording. This is ASR, not proof a general-purpose local LLM is necessary. It is not an Android app and does not itself implement the complete LoRa + receiving TTS pipeline.

whisper.cpp documents Android support and approximate memory requirements (tiny ~273 MB, base ~388 MB, small ~852 MB). These are upstream reference figures, not phone benchmarks here. Ordinary ESP32 radio nodes should route packets; phones or capable computers run speech inference. Stock Whisper tokenizer lists nine of SIH26173's required languages but not Odia/Oriya: https://github.com/openai/whisper/blob/main/whisper/tokenizer.py . This is a coverage gap requiring a separately verified path, not proof Odia ASR is impossible. TTS must independently cover all required languages.

### Prior art — speech over LoRa is not new
https://github.com/James-P-D/MeshSpeech is a desktop text-to/from-speech Meshtastic client. Source main.py includes SerialInterface, sendText, pyttsx3 speech output, and recognize_google for STT. Thus the pipeline has public implementation evidence, but this version is not an all-offline STT solution. No execution was performed here.

https://github.com/MelonPy/meshtastic-offline-stt explicitly proposes offline multilingual dictation over Meshtastic, but its current tree contains README and licence only and labels itself early-stage planning. It demonstrates prior concept, not a working implementation.

Meshtastic already documents LoRa forwarding, packet IDs, hop limits, acknowledgement/retry behaviour and duplicate handling: https://meshtastic.org/docs/overview/mesh-algo/ . Do not claim these as new inventions. Its broadcast implicit acknowledgement is not confirmation that every intended recipient received a message. Packet payload is limited (documentation lists maximum 237 bytes excluding protobuf overhead at the relevant layer); useful application text is smaller after metadata/security overhead. UTF-8 bytes are not equivalent to displayed characters.

### Bitchat and terminology
https://github.com/permissionlesstech/bitchat currently documents local BLE multi-hop messaging plus an internet-based Nostr transport. Offline BLE is not LoRa. Nostr does not work through a total internet outage. Automatic participation requires installed/running compatible software and operating-system permissions; arbitrary nearby phones do not relay.

“LLMesh” is ambiguous: GitHub search returned unrelated LLM orchestration and inference projects. No exact user repository link was supplied. Do not equate it with LoRa mesh or promise distributed LLM inference over LoRa. Proceed only with the explicit interpretation: local ASR at the endpoint and short text messages through the radio mesh.

## SIH26223 concept brief — draft, not tested submission

**Working title:** SetuRelay — voice-accessible disaster message relays with visible rescue handoff.

**Scope:** AICTE Student Innovation, Hardware, Disaster Management. Official portal retrieved again: https://sih.gov.in/sih2026PS . Description is an open disaster-management theme; it does not mandate ten languages or local LLMs. Count at retrieval was 44/500, not a measure of selection odds.

**User:** designated shelter volunteers communicating short requests to a local response desk when cellular connectivity fails. Not an assumed citywide civilian phone network.

**Initial topology:** Phone A (local ASR) → Bluetooth → radio A → radio B relay → radio C → Bluetooth → Phone C (local TTS). Three total radios; no speech model needed on B. Two radios test direct delivery, not a relay. Three in a chain cannot prove alternate-path self-healing; losing the only relay partitions the network.

**Core design:** implement the hardware relay and endpoint bridge first using an established mesh stack, with credits/licence compliance. Disable cloud gateways during offline testing. Add durable endpoint queues, expiration policy and explicit responder acceptance state. Maintain the distinction between transmitted, network received, speech played and human accepted. Follow platform audio restrictions; do not claim emergency-grade reliability.

**Candidate differentiator:** a complete request-to-responder handoff, tested under interruption and scarce channel capacity. Example fictional request: location, number of people and required resource are locally read back and confirmed; receiver explicitly accepts; old queued requests are visibly stale. Priority is set/confirmed by a human, not an unvalidated LLM triage model. Preserve meaning; do not silently summarise away negation or numbers.

**Hardware contribution:** measured relay placement, battery/runtime behaviour, portable protected power, visible node/link state, and repairable/interchangeable radio/power modules. These are proposed contributions, not novelty guarantees. If all distinctive engineering is an app on unchanged purchased nodes, the hardware-track differentiation is weak.

**Fair baselines:** typed Meshtastic, offline ASR + Meshtastic without the additional workflow, and the proposed system. Compare task completion time, critical-detail errors, channel bytes/airtime, remote receipt and human-acceptance delay, duplicate announcements, expiration correctness and runtime. Report devices, distances, placement, antenna, firmware, radio parameters, traffic workload and failure rate. Avoid universal range and latency promises.

**Proposed demo gate:** two phones and three radios; direct A→C delivery demonstrably unavailable under the declared placement; verify A→B→C delivery; interrupt B, show queued/not-delivered status, restore B and verify one unexpired delivery plus explicit receiver acceptance. This tests a relay and outage recovery, not a self-healing topology. Test text first, then add speech. Begin with one/two accurately supported languages for 26223 and declare scope; that would not complete 26173.

**Purchasing:** user has asked not to reject feasibility on the previous budget cap. Therefore no ₹8,000 feasibility assertion is made and no priced BOM is supplied. Budget relaxation does not solve language, latency, interference or topology constraints. India-compatible firmware settings are not a substitute for checking applicable radio law and hardware certification. No purchases or existing-file edits were made.

**Bottom line:** a small speech-to-text-to-LoRa-to-speech prototype is technically credible with available building blocks, but this exact integrated prototype has not been run. Basic combination is prior art. SIH26223 is a better match to the user's hardware focus than 26173; selection strength depends on measured disaster workflow and substantive hardware engineering, not the label “LLM mesh”.
