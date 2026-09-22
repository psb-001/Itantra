# SIH26173 Submission Draft
## SetuVoice — Offline Speech Messaging for Low-Bitrate Links

**Status:** Final output of the proposal-drafting task, not a validated product or submission-ready claim of compliance.
**Prepared:** 17 September 2026
**Organisation:** Indian Space Research Organisation (ISRO)
**Category:** Software
**Official title:** iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links
**Source:** https://sih.gov.in/sih2026PS (description fetched on 17 September 2026)

---

## 1. Problem and proposed solution

In distress and alert scenarios, people may need to communicate verbally over links that cannot efficiently carry audio. Internet-hosted speech services are unavailable when connectivity fails. Users also need to know whether an urgent message was recognised correctly and delivered—not merely whether they pressed Send.

We propose an Android application that recognises speech locally, transmits the resulting compact text over a local communication link, and reconstructs intelligible speech on the receiving phone. Speech models, message storage and playback operate without internet access after installation and provisioning.

**Core workflow:**

Speaker → on-device STT → text message → Wi-Fi/Bluetooth link → on-device TTS → listener.

An optional pair of Bluetooth-connected radio nodes would extend the transport to a low-bitrate radio link. Radio hardware is not required for the first prototype: the official PS explicitly permits two phones connected through Wi-Fi or Bluetooth.

**Proposed distinction to test:** meaning-preserving, delivery-aware voice messaging—not simply STT and TTS connected to a radio. This is a design hypothesis, not a verified novelty claim.

## 2. Required scope and compliance

| Official requirement | Proposed implementation | Current status |
|---|---|---|
| Android on low/mid-range phones | Native Android app; benchmark on the team's actual devices | Not implemented or benchmarked |
| Ten languages | Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English; explicit sender/receiver language selection | Model coverage unverified for every language |
| Local STT and TTS | Packaged or pre-provisioned open-source/open-weight components with licence review | No model selected yet |
| Fully offline speech pipeline | No cloud STT/TTS, LLM or translation dependency | Architectural commitment; network audit required |
| Pause-aware sentence formation | Local voice-activity detection and endpointing | Needs testing for premature sentence cuts |
| Text transmission through Wi-Fi/Bluetooth or connected embedded device | Transport interface with direct-phone implementation first | Not implemented |
| Push-to-talk and non-PTT operation | PTT first; separate hands-free listening mode with echo/feedback handling | Hands-free mode remains a substantive requirement |
| Received voice notes | Local TTS playback and message history | Not implemented |
| High-volume non-interruptible alert announcement | Distinct alert type and controlled playback flow | Android audio/DND policies may limit literal compliance; clarify and test |

A one-language PTT demonstration is a feasibility milestone, **not completion of the PS**. Supporting ten languages is not equivalent to translating between them. Cross-language translation is outside this proposal unless separately required and validated.

## 3. Architecture and technology stack

### Android client
- Kotlin and Jetpack Compose for the interface.
- Android audio APIs for recording and playback.
- Room/SQLite for durable message storage and delivery state.
- Android Bluetooth or local Wi-Fi APIs for transport. Choose the first transport after device compatibility testing.
- Local inference through a runtime supported by the selected speech models, such as ONNX Runtime Mobile or LiteRT/TensorFlow Lite. Runtime selection does not establish model compatibility.
- A user-visible foreground service where necessary for an active communication session; do not assume unlimited background execution.

### Model selection gate
For **each of the ten languages**, record STT model, TTS model, weight/runtime licences, redistribution permission, download size, installed size, memory use, device compatibility and measured quality. Do not assume the installed Android speech recogniser or TTS engine satisfies offline and open-source requirements.

Language packs may be provisioned before offline use. Load only the active models where technically feasible. The application must clearly indicate which languages are installed and ready. All required packs must be available during a complete evaluation.

### Message and transport layer
Transmit UTF-8 text with message ID, language, message type, creation time, sequence information and expiry policy. Fragment and reassemble where the transport payload requires it.

Use distinct states:
**Recorded → Recognised → Queued → Transmitted → Received → Played**, with **Responder acknowledged** only after explicit human action.

Receipt does not prove comprehension or rescue dispatch. A timed-out acknowledgement is not proof of non-delivery. Duplicate suppression must prevent retransmissions from repeatedly announcing an alert.

Use established platform pairing/security mechanisms where applicable. A packet checksum only detects accidental corruption; it does not authenticate a sender. Radio-path authentication and key provisioning remain design work before making security claims.

### Optional hardware transport
Phone A → Bluetooth → radio node A → LoRa text packet → radio node B → Bluetooth → Phone B.

Borrowed from the HECN reference: phone-to-node connectivity, low-bitrate text transport and intermittent-link handling. Deferred: civilian-phone mesh, solar deployment and streetlight infrastructure. Nearby stock phones do not automatically relay messages.

Use radio modules, frequencies and operating parameters compliant with applicable Indian rules. Do not assume every SX1278 module is suitable. Range is a measured outcome, not a promised 10 km specification.

### Web stack role
React and Python can support a local test dashboard and evaluation scripts. Postgres and a cloud backend are unnecessary for the core two-phone prototype. The team must learn Android and mobile inference; web experience alone is insufficient.

## 4. Candidate differentiators

### A. Protect critical details without inventing corrections
Names, locations, numbers and negation can change an emergency message's meaning. Offer local read-back and a quick repeat/correction step for user-selected or flagged critical details. Use model confidence only if available and shown to be useful; confidence is not a guarantee.

Keep the original local transcript and user-confirmed version distinct. Do not use an LLM to silently rewrite emergencies. Read-back can help users who cannot comfortably read text, but adds time; measure that trade-off.

**Test:** compare the basic STT pipeline with the confirmation flow for critical-detail errors and total time-to-delivery.

### B. Honest delivery during outages
Persist unsent messages, retry within bounds, acknowledge receipt, suppress duplicate playback and expire stale alerts. Clearly distinguish delayed delivery from a live conversation.

**Test:** deliberately disconnect and reconnect the local link; measure recovery, loss, duplicates and stale-alert playback.

### C. Measured language/device readiness
Display whether a selected offline language pack is ready. Maintain per-language, per-device quality and latency results rather than claiming one universal accuracy figure.

**Test:** evaluate speakers not used during tuning and report results separately for every language and phone.

These features are proposed improvements. They have not been compared comprehensively against existing products or demonstrated to cause SIH selection.

## 5. Evaluation plan mapped to the published PS

The retrieved PS lists **Efficiency 20%, Accuracy 40%, Latency 20%**. These total **80%, not 100%**. Do not invent the remaining weight; seek organiser clarification. General SIH guidelines additionally mention novelty, complexity, clarity, feasibility, practicability, sustainability, impact, user experience and future progression; these are not replacement numerical weights.

### Accuracy — published weight 40%
- Measure STT word error rate: (substitutions + deletions + insertions) / reference word count.
- State text normalisation and tokenisation, including treatment of numerals and punctuation.
- Report per language, speaker group and noise condition; do not hide weak languages inside an overall average.
- Measure critical-detail errors separately: locations, quantities, person names and negation.
- Ask consenting listeners fluent in each language to transcribe or explain received TTS messages; record intelligibility and naturalness separately.
- Separate STT-only, TTS-only and end-to-end failures.

### Efficiency — published weight 20%
- Installed application and language-pack sizes.
- Peak memory, idle-listening CPU and active inference CPU.
- Battery drain over a declared workload, with measurement method and device details.
- Compare cold start with warm operation; record throttling or out-of-memory failures.

### Latency — published weight 20%
Measure speech endpoint detection, STT completion, queue delay, transfer, TTS time-to-first-audio and overall sentence-end-to-remote-playback delay. Report median and tail latency, not just the fastest run.

Define real-time factor as processing time / audio duration and state the stage to which it applies. Use a shared timing reference or independently observed recording for cross-device measurements; unsynchronised phone clocks can invalidate results.

Evaluate direct Wi-Fi/Bluetooth and optional radio separately. Outage recovery times must not be mixed with normal-link latency without explanation.

### Additional engineering checks — not claimed official weights
- Compare recorded-audio bytes with actual text-protocol bytes, including headers, acknowledgements and retransmissions, for the same utterances. State the audio encoding used.
- Record packet delivery ratio, retransmissions, duplicate playback and expired-message handling.
- Disable internet access while leaving the chosen local radio enabled; audit that no speech data or inference requests leave for external services.
- Test echo, interruptions, screen locking and audio focus changes.
- Test sender pairing and rejection of malformed messages on our own devices only.

## 6. Test data and ground truth

Initial engineering set: short scripted messages covering ordinary conversation and fictional distress/resource requests. Include names, numerals, place names, negation and similar-sounding phrases. Use synthetic identifiers; no real emergency calls or sensitive victim data.

Recruit consenting speakers and listeners for each language. Keep test speakers separate from those used in development. Public speech datasets may supplement this only after verifying language coverage, access and licences. **No dataset has yet been selected or downloaded for this proposal.**

A small pilot might use 30 utterances per language from several speakers, with quiet and controlled background-noise conditions. That would be an engineering pilot, not a representative population benchmark or validated emergency system.

## 7. Budget and purchasing gate

**Hard cap: ₹8,000 total incremental hardware expenditure.** Existing phones and laptop are assumed available and excluded; confirm that assumption before committing.

| Item | Purchase condition |
|---|---|
| Two existing Android phones | Verify model support and performance first; buying replacements may invalidate the budget |
| Two Bluetooth-capable microcontroller/radio assemblies | Obtain compatible supplier-priced BOM before purchase |
| Antennas and connectors | Match actual radio band; include in BOM |
| Protected power supplies, cables and enclosures | Include in complete cost rather than quoting board-only prices |
| Shipping and spare/replacement allowance | Reserve budget before buying optional parts |
| Solar and third relay node | Deferred; not necessary for the official direct-phone demo |

No current supplier quotes have been verified. This document does not claim the optional radio configuration is already costed below ₹8,000.

## 8. Development gates, not promised dates

1. **Two-day feasibility spike:** attempt one offline language end-to-end on two actual phones. Measure latency and memory. Two days is a decision checkpoint, not a delivery guarantee.
2. **Ten-language gate:** establish an installable, legally usable STT/TTS path for every required language. Missing coverage is a blocking issue, not an optional enhancement.
3. **Direct transport baseline:** demonstrate two-phone communication with PTT, durable messages and accurate state reporting.
4. **Robustness:** test noise, critical-detail confirmation, reconnection, duplication, stale alerts and hands-free audio feedback.
5. **Optional radio:** buy only after speech feasibility and the full BOM pass; repeat latency and integrity tests.
6. **Evaluation and submission:** publish reproducible per-language/per-device results, clearly labelled limitations and a live offline demonstration.

**Stop/reconsider criteria:** models do not run on available phones, required languages lack usable offline components, memory/latency remain impractical, or the team cannot support Android development. Do not compensate with a polished dashboard or extra radio hardware.

## 9. Proposed demonstration

1. Show installed language packs and internet-disabled operation.
2. Speak a fictional request with a location, quantity and negation.
3. Show or play back the recognised content and correct a deliberately difficult detail.
4. Transmit and hear the reconstructed speech remotely.
5. Show received/played states without claiming human acknowledgement automatically.
6. Interrupt the link, queue a second message, reconnect and demonstrate single delivery.
7. Show an expired message withheld from fresh-alert playback.
8. Present measured latency, resources, accuracy and radio conditions—not staged estimates.

## 10. Suggested submission slide structure

1. Problem, target users and the offline speech-to-text-to-speech solution.
2. Architecture and explicit ten-language compliance plan.
3. Critical-detail protection and delivery-aware messaging, with baseline comparison.
4. Feasibility: actual phone measurements, verified models, optional hardware BOM and risks.
5. Evaluation: official metrics, test data, measured results and limitations.
6. Impact, next steps, source citations and what remains unvalidated.

Follow the actual SIH presentation template when preparing the deck; this outline is not a claim about its required slide count.

## Decision

This is a coherent SIH26173 proposal with a limited, optional reuse of HECN's radio architecture. It is **high-risk for a beginner web team until Android inference and ten-language coverage are demonstrated**. Its strongest proposed improvement is preserving useful message content and honest delivery status under constrained connectivity—not adding AI, mesh or solar for presentation value.

Winner research is incomplete and is not used as proof that this design will win. This remains a prototype proposal, not an operational emergency communication system.
