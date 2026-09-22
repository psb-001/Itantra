# Is bitchat useful to us? — a direct answer

**Date:** 21 September 2026
**Compared:** `bitchat/bitchat-android` (BLE mesh) vs `output/option_a_stt/android/iTantraApp` (RFCOMM point-to-point)

---

## Short answer

**No — not directly.** bitchat is **pure BLE GATT**. We use **Bluetooth Classic RFCOMM**. They are
different radios with different APIs, different connection models and different failure modes. There
is no code we can lift.

**But it is still worth reading**, because it told me two useful things: (1) it confirmed our
transport choice is right for this problem, and (2) it showed us a set of *engineering habits* worth
copying, and one thing it deliberately does **not** do that we should stop doing.

And separately — **I found our slow-connection cause, and it was ours, not Bluetooth's.** See §4.

---

## 1. They are not the same thing

| | bitchat | ours |
|---|---|---|
| Radio | **BLE (GATT)** | **Bluetooth Classic (RFCOMM)** |
| Roles | every device is **both** peripheral and central | one listens, one connects |
| Discovery | **continuous scanning** + advertising forever | `bondedDevices` only, no discovery |
| Pairing | none — they never bond | **required** (Android Settings, manual) |
| Connections | up to **8**, persistent | 1 |
| Payload | fragments up to 469 B, reassembled | one packet, 50–250 B |
| Topology | **mesh** — relays through other phones | **point-to-point** |
| Crypto | Noise protocol, Tor option | none |

A repo-wide search for `BluetoothSocket`, `listenUsingRfcomm`, `createRfcommSocket`,
`BluetoothServerSocket`, `SPP` and the UUID `00001101` returned **zero matches** in bitchat.
They have never written a line of Classic Bluetooth.

**So: nothing to port.** Adopting their approach would mean rewriting our transport from scratch.

---

## 2. What bitchat does that is genuinely good

These are habits, not code. Each is worth stealing.

### 2.1 Timeouts are constants, not magic numbers

Every timing value is a named constant at the top of a file:

```
MeshConnectionTracker.kt:22   CONNECTION_RETRY_DELAY    = 5_000L
MeshConnectionTracker.kt:23   MAX_CONNECTION_ATTEMPTS   = 3
MeshConnectionTracker.kt:24   CLEANUP_INTERVAL          = 30_000L
BluetoothGattClientManager.kt:36  SCAN_RETRY_BASE_MS       = 3_000L
BluetoothGattClientManager.kt:37  SCAN_MAX_RETRY_DELAY_MS  = 30_000L
BluetoothGattClientManager.kt:38  SCAN_WATCHDOG_INTERVAL_MS= 30_000L
```

Ours were inline literals scattered through the loop — `Thread.sleep(2000)` with no name and no
explanation. **Fixed today:** they are now named and commented.

### 2.2 Exponential backoff with a ceiling

`BluetoothGattClientManager.kt:303-313` — retry delay is `base * retryCount`, capped at 30 s.
Ours was a flat 2 s forever. Flat retries hammer the stack and make a failing connection take
*longer*, not shorter.

### 2.3 A watchdog for the scanner

`BluetoothGattClientManager.kt:320-348` — a periodic task notices the scanner has died and restarts
it. Bluetooth stacks do silently stop scanning. We have no equivalent: if our accept loop dies, the
listener is dead until the app restarts. **Worth adding.**

### 2.4 Serialised writes, one operation in flight per link

`BluetoothPacketBroadcaster.kt:511-604` — an `ArrayDeque` queue, one GATT operation outstanding at a
time, `MAX_PENDING_SENDS_PER_LINK = 256`, `SEND_RETRY_DELAY_MS = 15L`, `MAX_CALLBACK_RETRIES = 3`.

Their comment explains why: GATT permits only one outstanding operation per link, and firing more
silently drops them. **RFCOMM has the same hazard** — our `send()` is `@Synchronized` on the socket
but a write that fails mid-way is lost with no retry. For 50–250 B packets the risk is low, but a
small send queue with retry is the right shape.

### 2.5 Dedup keyed by content hash

`SecurityManager.kt:33` — `MAX_PROCESSED_MESSAGES = 10_000`, keyed by
`hash(type + sender + timestamp + payload)`.

We dedup on the message **UUID** only. That catches a replayed packet but not the *same text* sent
twice by a confused retry. Their content-hash approach is stronger.

### 2.6 Connection limits with a stated eviction policy

`BluetoothConnectionTracker.kt:228-268` — over the limit, evict **oldest first, clients preferred**.
Ours is one connection, so not applicable, but the principle — *state the policy, don't let it emerge* —
is the right one.

### 2.7 Generous, explicit delays around stack calls

They insert deliberate pauses: 200 ms before MTU negotiation, 200 ms before signalling connected,
500 ms before `gatt.close()`, 300 ms before advertising, `Thread.sleep(100)` before `openGattServer`.

That looks superstitious and it is not — the Android Bluetooth stack is asynchronous and racy, and
these pauses are scar tissue. We should be equally willing to add a documented delay where the stack
needs one.

---

## 3. What bitchat does NOT do — and this is the useful part

I asked specifically about connection-setup latency. The answer is uncomfortable but important:

> **They do not solve connect latency either. They minimise *discovery* latency and then accept
> whatever the stack's own connect timing is.**

There is **no `connectGatt` timeout** in their code — they rely on the Android default (~30 s).
Their speed comes from scanning aggressively and continuously, so a peer is *found* instantly; the
connection itself still takes as long as the stack takes.

They also have **no** workarounds for the classic GATT error codes (133 / 8 / 19), **no**
`gatt.refresh()`, and **no** `cancelDiscovery()`. So the folklore list of "Android Bluetooth fixes"
is largely absent from a shipping app — which suggests most of it is cargo cult.

**Implication for us:** switching to BLE would not have fixed our slow connection. Our problem was
different, and it was in our own code.

---

## 4. Our actual slow-connection cause — found and fixed

bitchat's code made me go and *measure* our startup order instead of assuming. Three real problems:

### 4.1 The race — models loaded before the transport started

`MainActivity.kt` ran:

```
load models (1.5–2 s)  →  then start listening
```

So the **listener phone was not accepting until ~2 s after launch**, while the speaker phone was
already dialling. Every attempt landing in that window blocked for the full RFCOMM connect timeout
before failing, then had to be retried. **This was the main cause.**

**Fix:** the transport now starts **before** model loading. It does not need the models — it needs a
socket. The listener is now accepting within ~100 ms of launch.

### 4.2 A 2-second sleep on top of a 12-second timeout

`BluetoothRfcommTransport.kt:248` had `Thread.sleep(2000)` between attempts. `connect()` already
blocks for its own timeout, so this was **pure dead time** — up to 10 s across five attempts.

**Fix:** sleep reduced to 300 ms, purely to avoid hammering the stack back-to-back.

### 4.3 Six attempts, each potentially 12 s

Worst case: `6 × (12 s + 2 s) = 84 seconds` before giving up.

**Fix:** five attempts, no long sleep, and **every attempt is now timed and reported**:

```
attempt 1/5 to Spike's Phone failed after 11840 ms: read failed, socket might closed
attempt 2/5 to Spike's Phone failed after 210 ms: ...
CONNECTED to Spike's Phone in 1840 ms (attempt 3, 14210 ms total)
```

That log is the real deliverable here. **Next time it is slow, we will know exactly which attempt
and how long — instead of guessing.**

---

## 5. Verdict

| Question | Answer |
|---|---|
| Can we reuse bitchat's code? | **No.** BLE GATT vs RFCOMM — different radios, nothing shared. |
| Should we switch to BLE? | **No.** It would not fix our latency (they don't fix it either) and would cost us pairing-based sender authentication, which matters for an emergency alert app. |
| Was reading it worth it? | **Yes.** Named timing constants, exponential backoff, a scanner watchdog, a serialised send queue, and content-hash dedup are all things we should adopt. |
| Did it solve the slowness? | **No — but it made me measure it, and the measurement found the cause in our own startup order.** |

### What I would take, in priority order

1. **A watchdog on the accept loop** — if the listener's `accept()` dies, nothing restarts it today.
2. **Named timing constants** — done for the retry loop today; the rest still has literals.
3. **A small send queue with retry** — a failed write is currently lost silently.
4. **Content-hash dedup** alongside the UUID check.
5. **Exponential backoff** instead of a flat retry interval.

None of these is a rewrite. All are small.
