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
there is no honest expected script to enforce. With `null`, purity asks the same question
`LanguageFilter.filter(raw, null)` already asks — "is every letter inside one of the nine allowed
scripts" — which is the only accusation that can be substantiated on-device after the vocabulary
prune. Pinned by three new tests in `QualityGateTest`: the 0.0 measurement, English sendable, and
the measured Hinglish sentence sendable.

## Finding 2 — the receiver spoke fragments over each other (playback cut-off)

Every received packet spawned its own `tts` thread, and `Speaker.play()` starts with `stop()`.
A long hold produces a burst of segments. The second message's playback therefore cut the first
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
  **Fix.** `send()` now returns `Boolean`. A new `Outbox` (unit-tested, 6 cases) sends when the
  link is up and buffers *in order* when it is down (cap 64, oldest dropped and counted), and the
  transport's CONNECTED callback flushes it. Buffered packets keep their TTL, so a very old one is
  shown as text but never announced as live.
- The sender never re-dialled after a drop. **Fix.** Automatic reconnect with backoff
  (2 s → 5 s → 10 s → 15 s, reset on CONNECTED), dialling the peer last chosen in DEVICES. It is
  guarded against fighting a manual reconnect or a rebuilt transport.
- The Bluetooth **listener** accept loop exits after the first connection and was only restarted
  by the 15 s watchdog. **Fix.** The accept loop restarts the moment a session drops. The watchdog
  is now 5 s as backup, `startAcceptLoop` is idempotent, and the watchdog cannot be duplicated.
- Wi-Fi Direct: a dropped socket was terminal (and re-hosting would have failed with `EADDRINUSE`
  because the old server socket stayed bound). **Fix.** The socket owner closes the stale server
  socket and re-hosts. The client re-dials the host address it already knew. A group dissolve is
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
  has been quiet for 4 s each side sends a ping. Any inbound frame — data, ping or pong — is
  proof of life. After 12 s with no frame at all the link is declared dead, the socket is closed
  (which unblocks the reader *and* any write stuck in flow control), and the existing reconnect
  takes over. A live link costs two bytes every few seconds. A dead one is detected in ~12 s
  instead of "whenever the user next speaks". The ping's round-trip time is logged
  ("heartbeat pong — round trip N ms") — free link-quality telemetry for the demo.
- **Every connect attempt wasted ~5 s of pure sleeps.** `cancelDiscovery()` + a 300 ms settle was
  paid on all 17 connect paths per attempt (2 SDP + 15 raw channels) even though
  `cancelDiscovery()` is a no-op when discovery is not running — and this transport never starts
  discovery itself. **Fix.** The settle only happens when `isDiscovering` is actually true.
- **Reconnects forgot what worked.** The raw-channel fallback always swept from channel 1.
  **Fix.** The channel that last produced a connection is remembered and tried first. Channel 1
  and the rest of the sweep follow only if it fails.
- **Two sockets could race into one transport.** `attach()` could be reached twice in a
  crossed-connect race. The loser's reader would later flip `isConnected` off on a healthy link,
  and a failed write left its socket unclosed. **Fix.** Sockets are closed before being replaced,
  sessions are numbered so only the newest reader may touch connection state, a write failure
  closes its socket immediately, `stop()` during a connect no longer adopts a socket, and the
  accept loop retries a transient `accept()` failure a few times before the watchdog backstop
  restarts it.
- **A connect round against a phone that was off wasted minutes.** When the far end is switched
  off or out of range, each of the 17 connect methods burns the full RFCOMM page timeout
  (~10 s), and the round is tried 5 times — minutes of radio hammering and battery per round,
  all arriving at the same answer. **Fix.** A failed connect that took at least 4.5 s is a page
  timeout, not a refusal (a reachable phone refuses in ~0 ms): the sweep stops immediately,
  reports "looks off or out of range" honestly, and the 2/5/10/15 s reconnect schedule probes
  again cheaply. The full 17-method sweep is unchanged for every reachable case.
- **A crossed dial could still fight over the link.** If both phones dialled at once, the
  acceptor's winner could be joined — and then replaced — by the dialer's sweep a moment later.
  **Fix.** The sweep checks `isConnected` before every method and abandons itself the moment a
  link exists, reporting nothing: the newest link wins and the loser stops cleanly.

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

One order edge this does not chase: if a write succeeds into a socket that is already dying and
the *next* sentence does reach the far side, the first sentence's retry can land after the
second. A holdback ("do not send sentence N+1 until N is confirmed") would delay every sentence
on every link to cover a case that needs loss and recovery to happen inside a 3 s window — the
wrong trade here. The outbox still guarantees order whenever the link is known down, which is
the common case.

## Playback hardening (same night's pass)

- **An ALERT now jumps the head of the speech queue.** Stopping the current track was never the
  whole story: the queue could already hold several unspoken sentences, and the distress message
  waited behind every one of them. The queue is a deque now. An explicit ALERT is `offerFirst`ed,
  speaks next, and the queued sentences continue in their original order afterwards.
- **An alert that arrives while the message before it is still SYNTHESISING goes first too.**
  Stopping playback covered an alert that lands mid-audio. Voice loading and synthesis take
  seconds with nothing audible — and there the alert had no way in: the worker would play the
  message it was preparing IN FULL first, then the alert. Alerts enter at the head and the worker
  is the only consumer, so an alert found waiting ahead right after a synthesis must have arrived
  during it. The in-flight message — nothing of it heard yet — re-queues directly behind the
  alerts, ahead of every later normal message, and the alert speaks next. `SpeechQueueOrder`,
  6 unit tests.
- **Long clips can no longer fail to play.** A 30 s VAD segment at 22 kHz float is ~2.6 MB, and
  one `MODE_STATIC` AudioTrack buffer that large can fail to build on a low-RAM phone — the
  failure mode being a message shown but never heard. Clips above 240 k samples (~1 MB) are now
  played as back-to-back pieces on the same worker. A preempting ALERT still ends the sequence
  mid-way: `Speaker` keeps a stop-generation counter that the worker checks between pieces.
- **Link RTT is on screen.** The heartbeat's measured round-trip now appears in Settings
  ("Link RTT: N ms (last heartbeat)") — the same number as the log line, no logcat needed.

## Lint sweep and edge cases (same "squeeze" pass)

Android Lint over the whole app: **0 errors now (was 32)**. Every fix below is behavior, not style:

- **API floor raised to 23.** The app's own calls — `AudioTrack.Builder` (all speech playback),
  `Context.getColor`, `TextView.setTextAppearance(int)`, `getSystemService(Class)` — are API 23.
  with the old `minSdk = 21` they would crash on Android 5.x. Compatibility shims for 0.1% of
  devices would be untested paths, so the floor moved to where the code actually runs.
- **`ACCESS_COARSE_LOCATION` added** (manifest, same `maxSdkVersion="32"` cap) and requested as a
  pair with FINE on Android 10+. Android 12+ ignores a request for FINE location alone — the
  Wi-Fi Direct discovery path would have silently never received it.
- **Permission guards made explicit.** The Bluetooth accept loop and `AudioRecord` creation carry
  `@SuppressLint("MissingPermission")` with the in-place check documented. The Wi-Fi Direct
  broadcast receiver and connect calls now *catch* `SecurityException` — permission revoked
  mid-session by the privacy dashboard used to be an uncaught crash from a receiver.
- **Backup rules for Android 12+.** `dataExtractionRules` + `fullBackupContent="false"`: nothing
  this app stores ships anywhere, cloud or device-to-device.
- **Push-to-talk reports `performClick()`** for screen readers (lint's accessibility check), and
  the field type now matches the layout (`MaterialButton`).
- **Wi-Fi Direct edge cases:** the client retry loop no longer dies silently if interrupted, and
  a host socket closed by a newer host no longer emits a phantom ERROR that set the app-level
  reconnect chasing nothing.
- **The banner shows the receipt**: a confirmed delivery flips the status line to "✓ Delivered"
  (with "(after N resend(s))" when retries were needed) — the proof is on screen, not just in
  the log.
- Dead resources removed: one drawable, 7 strings, 2 `md_scrim` definitions, 1 style.

The 16 remaining lint warnings are all deliberate: the English-only dev-log strip
(`SetTextI18n`), pinned dependency versions for offline builds (`GradleDependency`),
`targetSdk 33` and the portrait PTT UI (`OldTargetApi`, `LockedOrientationActivity`).

## The repetition-loop check goes live (same pass)

QualityGate's "stuck decoder" rule — a 1-3 token n-gram tiled four or more times back to back —
was implemented and unit-tested but INERT in the app: `AsrEngine.transcribe` returned only text,
and the live call passed `tokens = emptyList()`, which by construction can never tile.
Measurement first (`eval/probe_loopcheck_tokens.py` — sherpa-onnx 1.13.8, the exact shipped
model, FLEURS clips, the Kotlin algorithm ported exactly):

- **First sweep, 90 clean clips: two rejections.** One was a genuinely degenerate decode —
  `"ख      0 f 1"` — which TODAY is transmitted and spoken by the receiving phone as garbage.
  The other was a false positive: a correct Bengali sentence containing "100000" (five adjacent
  zero tokens). The exemption below was born from that.
- **Final sweep, 225 clean clips: one rejection, again degenerate** (`"ं   ो    ख"`, a three-glyph
  spacing artifact). No ordinary sentence is rejected.

The rule is now live (`AsrEngine.transcribeWithTokens` → `QualityGate.evaluate(tokens = …)`) with
one measured exemption: a SINGLE digit token never counts as a loop at the 1-token level, so
numbers pass. Two-token digit runs are still counted ("10 10 10 10" is a stuck decoder). The
accepted cost is that a number with eight consecutive zeros reaches the threshold — vanishingly
rare, and the sender is merely asked to repeat. `QualityGateTest` pins both halves. The probe and
its JSON are committed under `eval/`.

## Production pass — survive a kill, speak without gaps, mind the room, yield memory

A deliberate audit of "what still breaks a demo" produced the changes below, all inside the APK:

- **Pending work now survives the process being killed.** The outbox, the receipt tracker and the
  heard-ids ledger lived only in RAM, so Android killing the app in the background (low RAM, a
  swipe, a crash) erased sentences the sender had already been shown as "Sent" or "Buffered" —
  the last silent-drop path. `PendingStore` writes all three to one small JSON file on every
  change (hex packets, temp-file+rename — a kill mid-write cannot tear it) and
  `restorePending()` puts it back at startup. Nothing is sent from the restore — the packets wait
  in the outbox and flush, in order, when a link comes up. Deadlines convert honestly (RAM runs
  on the monotonic clock, disk stores wall-clock epochs), so a restart cannot grant a fresh TTL.
  A restored message whose time ran out is abandoned LOUDLY on the first tick, exactly as if the
  app had stayed alive. Log: "restored from the last session: N buffered packet(s), M awaiting
  receipt, K id(s) remembered".
- **A long hold no longer has a synthesis-shaped pause between its sentences.** While a message
  is being heard, the worker synthesises the next queued one and plays it straight away when its
  turn comes. One slot, keyed by message id and voice, worker-thread only. An ALERT is never
  delayed for it, never prefetched, and a message that is no longer next is dropped, not spoken.
  Log: "speech: pre-synthesised <id> while the previous message plays" / "speech <id>: already
  synthesised — playing immediately".
- **The speaker now asks for audio focus** — what the `Speaker` class comment always claimed.
  A normal announcement requests `TRANSIENT_MAY_DUCK` (music lowers itself for the sentence and
  comes back after). An ALERT requests `TRANSIENT` without the duck (an emergency is heard over
  music, not mixed under it). Focus is handed back when a normal message has been heard to its
  end, which also releases the AudioTrack buffer. A refused or failed request changes nothing
  about whether the message plays.
- **An ALERT buzzes when it lands** — two pulses and a long one. Wire-typed ALERTs buzz at
  receive time, after the duplicate and freshness gates (a resend of something already felt does
  not buzz again) and before the speaker (a phone in a pocket feels it while other audio is
  still talking). A message that TRIAGE escalates buzzes when it starts speaking as an alert.
  With no ALERT-send UI on the phone, the escalation path is the alert a real user produces —
  both are covered. VIBRATE permission added. Any failure is logged and changes nothing else.
- **Low memory is given back, never at the cost of work in flight.** `onTrimMemory` at
  RUNNING_LOW releases the idle TTS voice (~60 MB, ~1 s to reload). At RUNNING_CRITICAL it also
  releases the ASR recogniser (~340 MB, rebuilt on demand). Recording, playback and both queues
  are checked first, and a new `asrLock` — a decode holds it for its whole duration, trims
  `tryLock` — closes the use-after-free that a release under a running decode would otherwise be.
- **The last silent discard is gone.** A segment that arrived while the recogniser was not
  resident used to be dropped with a log line. It now triggers a reload and decodes it, and if
  the reload fails the transcript says what was lost and that nothing was sent.
- **Smaller things:** the capture thread runs at `THREAD_PRIORITY_URGENT_AUDIO` (a starved
  `AudioRecord` loses words before any gate can see them), and the status line shows
  "Transcribing N more…" while segments wait, so the last words of a long hold never look
  abandoned.

## Release pass — the app gets its name, number, and key (16 Oct)

This pass is not about the message pipeline. It is about the thing you hand to a phone.

- **Version 1.0.0 (versionCode 2)**, up from the never-released 0.1.0 line. About and Settings
  read the version from the installed package instead of a hardcoded string, so what they show
  can never disagree with the APK the phone is running.
- **A real launcher icon.** The old one was a placeholder vector, not an adaptive icon. It is
  now a microphone with transmission waves: adaptive icon (API 26+), monochrome layer for
  Android 13 themed icons, pre-rendered PNGs for API 23–25. Source in `app/branding/`.
- **Debug and release coexist.** Debug builds carry the `.debug` package suffix
  (`com.bjp.itantra.debug`), so they no longer fight the release over one package name.
  `pull_logs.sh` pulls from whichever is installed.
- **Signing done properly.** The first keystore was committed together with its password. A
  key whose secrets are in git history is not a key. It is retired and replaced by a fresh
  RSA-4096 key (CN `iTantra`, OU `Team BJP`, valid to 2054), credentials in an untracked
  `keystore.properties`. Two files must be backed up. Bumping `versionCode` on every
  release is not optional. Both rules, plus the verification commands, are `docs/RELEASE.md`.
- **The artifact is a signed release**: `releases/iTantra-1.0.0-release.apk`,
  993,362,447 B, sha256 `0b46aeffa5a39e506340632c16d125c6787a4cc13cbfbb10d79b35f0cae26b48`,
  verified for signature, page-alignment and badging. Any older iTantra on a phone must be
  **uninstalled before installing it** — the key rotation means the signatures differ, and the
  installer reports the mismatch as "App not installed". (That hash is the launch-crash fix
  below, not the first build of this pass.)

## The first device run — a launch crash the emulator caught (16 Oct)

The release APK crashed on launch the first time it met a real Android runtime:

    FATAL EXCEPTION: main
    Unable to instantiate activity com.bjp.itantra.MainActivity:
    NullPointerException: ... Context.getApplicationContext() on a null object reference

**Cause.** The audio-focus pass added `private val speaker = Speaker(applicationContext)` to
MainActivity's property list. Property initializers run inside the activity's constructor, and
Android attaches the context (`attachBaseContext`) *after* the constructor — so
`applicationContext` was still null when `Speaker` asked for the AudioManager. One line.

**Why nothing caught it earlier.** It is invisible to unit tests (they never construct an
Activity) and to lint. It only fires on a device, and this build's two-phone test had not been
run yet —
the pass that introduced it had been compiled, never launched. It was caught on the Android 34
emulator before either phone saw it.

**Fix.** `private val speaker by lazy { Speaker(applicationContext) }` with the reason written
next to it, so the eager form cannot come back. Nothing touches the speaker before `onCreate`.

**The fixed release was then run end to end on the emulator**: launched clean and stayed up,
every screen opened (About shows "1.0.0 (release)", Settings, Devices, credits), the pipeline
test and the loopback test both passed (numbers in Evidence below), and the fixed APK replaced
the first one in `releases/` under the same version number.

## What was deliberately NOT done

- **Persisting the SPEECH queue** (received, not yet spoken) alongside the pending work: the
  window is a process kill between "✓ Delivered" and the sentence being heard, the queue drains
  in seconds in normal use, and replaying a stored queue on launch would announce yesterday's
  messages. The pullable log on the listener shows every received message, so the gap is
  reconstructable from evidence — nothing about it is invisible.
- **Wi-Fi Direct group re-formation** depends on the other phone re-inviting, and the vendor
  quirks documented in that class header still apply. Bluetooth remains the primary transport.

## Evidence

- Unit tests: 96 green (`cd app && ./build.sh testDebugUnitTest`) — 48 from before, 6 for
  `Outbox` (+2 snapshot/restore: order preserved, capacity honoured), 3 for the gate fix, 6 for
  `Heartbeat` (fake-clock cadence, timeout, liveness cases), 3 for `Framing` (pins the wire
  format), 8 for `DeliveryTracker` (+3: schedule carry-over across a restart, expired restore
  abandoned not re-sent, counters), 2 for the ACK envelope, 6 for `SpeechQueueOrder` (alert-yield
  order), 2 for the now-live loop check, 5 for `PendingStore` (byte-exact round trip including
  binary-unsafe bytes, missing/torn/future-version files, no temp file left behind, concurrent
  saves never leave an unreadable file), 2 for `MessageLedger` (a heard id cannot replay after a
  restart, and the restore bound holds). The debug variant compiles cleanly in the same run.
- Lint: 0 errors / 16 accepted warnings, unchanged by this pass (the SDK-gated focus and vibrator
  calls raised no new API warnings).
- Build: the runnable artifact is now the **signed release**
  `releases/iTantra-1.0.0-release.apk` (16 Oct, 993,362,447 B, sha256
  `0b46aeffa5a39e506340632c16d125c6787a4cc13cbfbb10d79b35f0cae26b48`. The 16 Oct 09:57 build
  under the old hash crashed at launch and is superseded). The release pass above
  replaced the debug APK as the phone build. The debug variant still compiles cleanly and now
  installs alongside as `com.bjp.itantra.debug`. **Both phones must be reinstalled with the
  release build** — the wire format and receipt envelope changed earlier in the project and
  everything in these passes lives inside the APK — and any older iTantra must be uninstalled
  first (the key rotation changed the signature — see the release pass above).
- **First run on a device runtime** (Android 34 emulator, arm64-v8a, the fixed release APK):
  launched clean and stayed up. Every screen opened with no fault — About (version line
  "1.0.0 (release)", read from the installed package), Settings with live counters, Devices,
  open-source credits. The app's own test modes, each a full pass:
  - *Pipeline* (`--ez pipeline true`): English clip → 3 segments, all sent, script 1.00,
    packets 129–211 B. Hindi clip → **0 segments before flush, 1 after** — the VAD-flush fix
    working, the exact bug this test exists to catch. ASR compute 143–1134 ms per segment.
  - *Loopback* (`--ez loopback true`): every segment SENT → TTS → played → COMPLETE.
    Synthesis 50–566 ms per sentence at 22050 Hz. Playback exercised both NORMAL and ALERT
    modes of the audio-focus `Speaker` — the class from the crash fix — with no fault.
  - *Benchmark* (`--ez benchmark true`): ASR load 2215 ms, ASR RTF **0.156** over 29.9 s of
    audio. TTS voice load 924 ms, TTS RTF **0.078**. Native heap 6 → 24 MB. Emulator numbers.
    A real phone is still what the checklist below measures.
  - *Triage toggle*: switching Laya on copied its assets (143 MB, first use) and reported
    "triage ready — vocab 12454, merges 30829". Switching back off was clean.

## Phone checklist (two phones, release APK)

1. Pair once in Android Settings. Open the app on both. Wait for "Ready — hold to talk".
2. One phone: DEVICES → tap the other. Both banners show "connected to …".
3. Short burst: one sentence → the other phone speaks it fully.
4. **Long speech (the reported bug):** hold ~20–30 s, 4–5 sentences with short pauses, release →
   the other phone must speak ALL sentences, in order, none cut off. Sender log shows one
   "queued for recognition" per segment. The listener log shows "pre-synthesised … while the
   previous message plays" and the sentences should run on with no silence between them — that
   is the prefetch (see the production pass above).
5. **Link drop:** walk out of range while it plays. Sender log: "link IDLE — reconnect attempt N in
   …s", then "Buffered … B". Walk back: "link up — flushed N packet(s) buffered while down", and
   the buffered sentence arrives **in order**. With the heartbeat, a *silent* death (no FIN) shows
   as "heartbeat: no frame from the peer for 12 s — the link is dead" followed by the same
   reconnect line, about 12 s after the other phone goes away.
6. **English (Finding 1 regression):** say an English sentence → it must send (no "say it again"),
   and the other phone speaks it with the English voice.
7. Hinglish mixed sentence → travels. See Addenda 1–2 of `MODEL_AND_TTS_FINDINGS.md`.
8. Toggle Bluetooth off/on on one phone → the link returns without touching DEVICES.
9. Log lines to watch: "sent N B", "✓ delivered … confirmed by the other phone", "no receipt for
   … resend N/10", "✗ no receipt for … abandoned", "link down, buffered (Outbox…)", "flushed N
   packet(s)", "reconnect attempt N", "ALERT … preempts playback", "alert arrived during
   synthesis — speaking it first", "REPEAT (repetition-loop)", "speech <id>: already
   synthesised — playing immediately", "pre-synthesised … while the previous message plays",
   "restored from the last session: …", "memory pressure (low): released the TTS voice",
   "memory critical: released the ASR recogniser", "recogniser not resident — reloading",
   "N segment(s) still queued for recognition", "speech worker error", "ASR worker error", plus
   the heartbeat's "heartbeat pong — round trip N ms" on a healthy idle link, and "heartbeat: no
   frame from the peer for N s — the link is dead" for a silent death.
10. **Delivery receipt:** speak a sentence → sender log shows "✓ delivered <id> confirmed by the
    other phone", and the banner flips to "✓ Delivered". Then close the app on the *other*
    phone: speak two short sentences → "Buffered
    … link down". Reopen the other phone's app → "link up — flushed N packet(s)" followed by one
    "✓ delivered …" per sentence. A receipt that only arrives after retries is marked "(after N
    resend(s))", and an abandoned message shows "✗ no receipt for …" — nothing is ever dropped
    silently.
11. **Process-kill survival (new):** close the app on the receiving phone. On the sender, speak a
    sentence → "link down, buffered". Swipe the sender's app away from recents. Reopen it →
    "restored from the last session: 1 buffered packet(s), N awaiting receipt…". Reopen the other
    phone's app → "link up — flushed 1 packet(s) buffered while down", the sentence is spoken,
    and "✓ delivered … confirmed by the other phone" appears. Nobody killed the sentence. Same
    test after speaking TWO sentences while down: both arrive in order.
12. **Manners (new):** play music on the receiving phone, then send a normal sentence → the music
    should dip while the sentence speaks and come back after it (and should not un-dip between
    the sentences of one long message). For an ALERT: turn on "Laya triage" in Settings on the
    receiving phone and speak a destructive sentence from the other phone ("the bridge has
    collapsed, please send help to the school") → the listener shows 🔴, buzzes twice and then
    long, is heard at emergency volume over the music, and loops until the next message replaces
    it. (No phone UI SENDS a wire-typed ALERT — the triage escalation is a real user's alert
    path. The receive-time buzz covers a wire ALERT if a test harness ever sends one.)
