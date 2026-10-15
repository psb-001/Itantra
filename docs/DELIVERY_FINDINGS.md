# Delivery findings — why "speak a lot" did not arrive, and what was fixed

15 Oct 2026. Two questions from the team's review of the real-phone tests:

> "When I speak a lot and send it over, it does not travel — only one to two lines. It should go
> on the other phone, but it's broke."
> "Fine-tune Bluetooth and Wi-Fi — it should not drop."

Four defects were found by reading the live path end to end. None was in the transports' framing
(both already length-prefix every packet and read them back with `readFully`, capped at 64 KB), and
none was in the TTS text handling (a desktop experiment refuted the `maxNumSentences` truncation
idea: sherpa synthesises the whole multi-sentence string with the default config, 5.7 s of audio
for the 120-char test sentence, byte-for-byte the same as with `max_num_sentences=-1`).

## Finding 1 — English was never sent at all (sender gate)

The live sender called `QualityGate.evaluate(...)` with `langTag = langTag`, and `langTag` is the
phone's hardcoded default, `hin_Deva`. The gate's script-purity check is:

    expectedScriptPurity("the bridge has collapsed please send help to the school", "hin_Deva") = 0.0

0.0 < 0.75 → `REPEAT`, reason "mixed-script" → the app answers *"I could not read that clearly"*
and sends nothing. Every Latin transcript was rejected this way, so English speech — and any
mostly-English Hinglish — could not be transmitted at all.

**Fix.** The live path passes `langTag = null`. There is no language selector in this build, so
there is no honest expected script to enforce; with `null`, purity asks the same question
`LanguageFilter.filter(raw, null)` already asks — "is every letter inside one of the nine allowed
scripts" — which is the only accusation that can be substantiated on-device after the vocabulary
prune. Pinned by three new tests in `QualityGateTest`: the 0.0 measurement, English sendable, and
the measured Hinglish sentence sendable.

## Finding 2 — the receiver spoke fragments over each other (playback cut-off)

Every received packet spawned its own `tts` thread, and `Speaker.play()` starts with `stop()`.
A long hold produces a burst of segments; the second message's playback therefore cut the first
one off mid-word, and the *order* of playback depended on which thread won the synthesis lock.
On the receiving phone, "I spoke a lot" was heard as fragments in unstable order.

**Fix.** One speech queue and one worker (`speechQueue` / `ensureSpeechWorker` / `speakOne`).
Messages are synthesised and played in arrival order, and a NORMAL message is heard to its end
before the next one starts (the worker waits on `Speaker.isPlaying()` with a clip-length + 2 s
cap). Only an explicit ALERT may cut in — exactly the rule the app always claimed — and the
preemption is logged. `Speaker` is now `@Synchronized` because play/stop/isPlaying genuinely run
on different threads now. Retransmission cannot double-speak: the `MessageLedger` still suppresses
duplicate UUIDs.

## Finding 3 — the sender's packets could arrive shuffled

Each VAD segment spawned its own `asr` thread. Several decodes ran concurrently on the same
recogniser and finished in scheduler order, so the packets went out in that order — a five-sentence
monologue could reach the other phone shuffled. (It also made the phone run several int8 CTC
decodes at once, which is the wrong thing to do to a phone's SoC.)

**Fix.** One decode queue and one worker. Decodes and packets follow the order the sentences were
spoken.

## Finding 4 — a dropped link was terminal ("it should not drop")

- `Transport.send()` while disconnected logged a warning and **silently dropped** the packet — the
  speaker's screen still said "Sent".
  **Fix.** `send()` now returns `Boolean`; a new `Outbox` (unit-tested, 6 cases) sends when the
  link is up and buffers *in order* when it is down (cap 64, oldest dropped and counted), and the
  transport's CONNECTED callback flushes it. Buffered packets keep their TTL, so a very old one is
  shown as text but never announced as live.
- The sender never re-dialled after a drop. **Fix.** Automatic reconnect with backoff
  (2 s → 5 s → 10 s → 15 s, reset on CONNECTED), dialling the peer last chosen in DEVICES; it is
  guarded against fighting a manual reconnect or a rebuilt transport.
- The Bluetooth **listener** accept loop exits after the first connection and was only restarted
  by the 15 s watchdog. **Fix.** The accept loop restarts the moment a session drops; the watchdog
  is now 5 s as backup, `startAcceptLoop` is idempotent, and the watchdog cannot be duplicated.
- Wi-Fi Direct: a dropped socket was terminal (and re-hosting would have failed with `EADDRINUSE`
  because the old server socket stayed bound). **Fix.** The socket owner closes the stale server
  socket and re-hosts; the client re-dials the host address it already knew; a group dissolve is
  reported and the dialer side re-invites through the automatic reconnect.
- Bluetooth switched off→on re-opened only the listening side. **Fix.** It now also re-dials the
  last peer.

## What was deliberately NOT done

- **No application-level ACK/retransmit.** The outbox covers "link went down while speaking"; it
  cannot *prove* the far end received a flushed packet. The UUID dedupe in `MessageLedger` already
  makes a future ACK/retry safe to add — that is the natural next step if delivery receipts are
  wanted.
- **No RFCOMM keepalive.** A silently dead link is noticed on the next read/write failure (link
  supervision, typically seconds), not instantly.
- **Wi-Fi Direct group re-formation** depends on the other phone re-inviting, and the vendor
  quirks documented in that class header still apply. Bluetooth remains the primary transport.

## Evidence

- Unit tests: 57 green (`cd app && ./build.sh testDebugUnitTest`) — 48 from before, 6 for
  `Outbox`, 3 for the gate fix.
- Build: `app-debug.apk` rebuilt from this tree.

## Phone checklist (two phones, debug APK)

1. Pair once in Android Settings. Open the app on both; wait for "Ready — hold to talk".
2. One phone: DEVICES → tap the other. Both banners show "connected to …".
3. Short burst: one sentence → the other phone speaks it fully.
4. **Long speech (the reported bug):** hold ~20–30 s, 4–5 sentences with short pauses, release →
   the other phone must speak ALL sentences, in order, none cut off. Sender log shows one
   "queued for recognition" per segment.
5. **Link drop:** walk out of range while it plays. Sender log: "link IDLE — reconnect attempt N in
   …s", then "Buffered … B". Walk back: "link up — flushed N packet(s) buffered while down", and
   the buffered sentence arrives **in order**.
6. **English (Finding 1 regression):** say an English sentence → it must send (no "say it again"),
   and the other phone speaks it with the English voice.
7. Hinglish mixed sentence → travels; see Addenda 1–2 of `MODEL_AND_TTS_FINDINGS.md`.
8. Toggle Bluetooth off/on on one phone → the link returns without touching DEVICES.
9. Log lines to watch: "sent N B", "link down, buffered (Outbox…)", "flushed N packet(s)",
   "reconnect attempt N", "ALERT … preempts playback", "speech worker error", "ASR worker error".
