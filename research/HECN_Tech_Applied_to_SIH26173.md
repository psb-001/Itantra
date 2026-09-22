# HECN Tech Applied to SIH26173

HECN is reference material, not our submission. SIH26173's core requirement remains an Android app with offline speech-to-text (STT) and text-to-speech (TTS) across ten specified languages. Its description permits Wi-Fi/Bluetooth-connected embedded devices; LoRa, mesh and solar are optional design choices, not mandated features.

## Proposed pipeline
Phone A: offline STT → Bluetooth → radio node A → LoRa text packet → radio node B → Bluetooth → Phone B: offline TTS.

## 1. LoRa — transport text instead of audio
**Implementation:** Connect each Android phone to a microcontroller/radio node through Bluetooth. Send UTF-8 recognised text with a message ID, language tag and integrity check. Split and reassemble messages where necessary. Use modules and settings compliant with applicable Indian radio rules.
**Validation:** Compare sent and received text byte-for-byte, measure packet delivery and end-to-end speech latency, and record radio settings and test distance rather than promising 10 km.

## 2. Offline-first — retain messages during link loss
**Implementation:** Keep the speech models and message queue on-device. Persist outgoing messages, retry within limits, acknowledge receipt, suppress duplicates and expire stale messages. Display queued/received states separately; a receipt is not confirmation that a human heard the message.
**Validation:** Disconnect and restore the link, then check that unexpired messages arrive once and stale messages are not announced as current alerts.

## 3. Mesh relay — optional extension after direct communication works
**Implementation:** Add a third dedicated radio node to relay packets with message IDs, duplicate suppression and hop limits. Do not assume unmodified nearby phones automatically act as BLE repeaters. A fixed relay is a first experiment, not proof of a self-healing mesh.
**Validation:** Establish a test where direct delivery fails, introduce the relay, and measure the delivery improvement and added latency.

## 4. Battery/solar — optional support for radio-node power
**Implementation:** First power radio nodes with appropriately rated protected battery packs. Add compatible solar charging only after measuring consumption and confirming the budget; this does not power the phones unless their charging is explicitly included.
**Validation:** Measure runtime under a stated message workload and compare harvested daily energy with consumption before claiming solar autonomy.

## Scope decision
Build the two-phone speech loop first, then add two radio nodes and offline delivery handling. Defer mesh and solar until the core works. Hardware costs are not yet supplier-verified against the ₹8,000 cap. None of these additions replaces the required ten-language offline speech implementation or proves novelty or SIH selection probability.

Evidence basis: the HECN slide OCR and the SIH26173 description retrieved from the official portal on 17 September 2026. Validation steps above are proposed tests, not completed results.
