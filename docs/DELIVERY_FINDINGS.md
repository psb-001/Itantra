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

## Bluetooth deep-tune (the "it should not drop" follow-up)

Four more weaknesses in the link itself, found by re-reading the transports with reliability as
the only goal:

- **A silently dead link looked identical to an idle one.** If the other phone walked out of
  range or slept, `read()` just kept blocking, `isConnected` stayed true, and the next messages
  were written into a socket that reached nobody — while the screen said "Sent". **Fix.** A
  transport-level heartbeat: every frame now carries a 1-byte type (`Framing`), and when the link
  has been quiet for 4 s each side sends a ping; any inbound frame — data, ping or pong — is
  proof of life. After 12 s with no frame at all the link is declared dead, the socket is closed
  (which unblocks the reader *and* any write stuck in flow control), and the existing reconnect
  takes over. A live link costs two bytes every few seconds; a dead one is detected in ~12 s
  instead of "whenever the user next speaks". The ping's round-trip time is logged
  ("heartbeat pong — round trip N ms") — free link-quality telemetry for the demo.
- **Every connect attempt wasted ~5 s of pure sleeps.** `cancelDiscovery()` + a 300 ms settle was
  paid on all 17 connect paths per attempt (2 SDP + 15 raw channels) even though
  `cancelDiscovery()` is a no-op when discovery is not running — and this transport never starts
  discovery itself. **Fix.** The settle only happens when `isDiscovering` is actually true.
- **Reconnects forgot what worked.** The raw-channel fallback always swept from channel 1.
  **Fix.** The channel that last produced a connection is remembered and tried first; channel 1
  and the rest of the sweep follow only if it fails.
- **Two sockets could race into one transport.** `attach()` could be reached twice in a
  crossed-connect race; the loser's reader would later flip `isConnected` off on a healthy link,
  and a failed write left its socket unclosed. **Fix.** Sockets are closed before being replaced,
  sessions are numbered so only the newest reader may touch connection state, a write failure
  closes its socket immediately, `stop()` during a connect no longer adopts a socket, and the
  accept loop retries a transient `accept()` failure a few times before the watchdog backstop
  restarts it.

Wi-Fi Direct received the same framing and heartbeat, so both links now detect and recover the
same way.

## Delivery receipts — proof the words arrived

The transports can tell a dead link from an idle one, and the outbox covers a link that is
*known* to be down. Neither could prove that a packet written into a socket that was already
dying ever arrived — a write can "succeed" into the RFCOMM buffer while the radio is gone. That
last hole is closed:

- The receiving phone answers every message with a one-line receipt (`Message.Type.ACK`, carrying
  the sender's message id) **before** the duplicate/expiry checks and before playback — a receipt
  is about arrival, and even a duplicate is re-confirmed on purpose so a lost receipt cannot make
  the sender retry forever.
- The sender tracks every message (`DeliveryTracker`, 8 unit tests) and resends through the same
  ordered outbox until a receipt returns: after 3 s, then 6 s, 12 s, and every 15 s after that,
  up to 10 resends — never past the message's own TTL. Abandoned messages are logged loudly
  ("✗ no receipt for …"), never silently dropped. On reconnect, retries are held back one interval
  so they cannot race the outbox flush.
- The log now separates the two honest states: "sent N B" (handed to the link) and
  "✓ delivered … confirmed by the other phone" (a receipt came back — the only proof of arrival
  this app can have). Settings shows awaiting/confirmed/abandoned counts.

## What was deliberately NOT done

- **Wi-Fi Direct group re-formation** depends on the other phone re-inviting, and the vendor
  quirks documented in that class header still apply. Bluetooth remains the primary transport.

## Evidence

- Unit tests: 76 green (`cd app && ./build.sh testDebugUnitTest`) — 48 from before, 6 for
  `Outbox`, 3 for the gate fix, 6 for `Heartbeat` (fake-clock cadence, timeout, liveness cases),
  3 for `Framing` (pins the wire format), 8 for `DeliveryTracker` (schedule, receipts, deadlines,
  capacity), 2 for the ACK envelope. The debug variant compiles cleanly in the same run.
- Build: the next on-phone `app-debug.apk` will be rebuilt when the hand-over build is asked
  for; the previous APK predates the heartbeat work.

## Phone checklist (two phones, debug APK)

1. Pair once in Android Settings. Open the app on both; wait for "Ready — hold to talk".
2. One phone: DEVICES → tap the other. Both banners show "connected to …".
3. Short burst: one sentence → the other phone speaks it fully.
4. **Long speech (the reported bug):** hold ~20–30 s, 4–5 sentences with short pauses, release →
   the other phone must speak ALL sentences, in order, none cut off. Sender log shows one
   "queued for recognition" per segment.
5. **Link drop:** walk out of range while it plays. Sender log: "link IDLE — reconnect attempt N in
   …s", then "Buffered … B". Walk back: "link up — flushed N packet(s) buffered while down", and
   the buffered sentence arrives **in order**. With the heartbeat, a *silent* death (no FIN) shows
   as "heartbeat: no frame from the peer for 12 s — the link is dead" followed by the same
   reconnect line, about 12 s after the other phone goes away.
6. **English (Finding 1 regression):** say an English sentence → it must send (no "say it again"),
   and the other phone speaks it with the English voice.
7. Hinglish mixed sentence → travels; see Addenda 1–2 of `MODEL_AND_TTS_FINDINGS.md`.
8. Toggle Bluetooth off/on on one phone → the link returns without touching DEVICES.
9. Log lines to watch: "sent N B", "✓ delivered … confirmed by the other phone", "no receipt for
   … resend N/10", "✗ no receipt for … abandoned", "link down, buffered (Outbox…)", "flushed N
   packet(s)", "reconnect attempt N", "ALERT … preempts playback", "speech worker error",
   "ASR worker error", plus the heartbeat's "heartbeat pong — round trip N ms" on a healthy idle
   link, and "heartbeat: no frame from the peer for N s — the link is dead" for a silent death.
10. **Delivery receipt:** speak a sentence → sender log shows "✓ delivered <id> confirmed by the
    other phone". Then close the app on the *other* phone: speak two short sentences → "Buffered
    … link down"; reopen the other phone's app → "link up — flushed N packet(s)" followed by one
    "✓ delivered …" per sentence. A receipt that only arrives after retries is marked "(after N
    resend(s))", and an abandoned message shows "✗ no receipt for …" — nothing is ever dropped
    silently.
