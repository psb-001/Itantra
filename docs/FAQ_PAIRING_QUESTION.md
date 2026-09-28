# The pairing question, and how to answer it

> *"You need a pre-installed app and it sends over Bluetooth. But in that situation nobody's
> brain is active enough to pair two phones. How do they even connect?"*

This is the best counter-question anyone has asked us, and **they are right.** The current app
asks for three manual steps before anything happens. Do not dodge it. Concede it, then show that
you have thought further than the question.

---

## What the app actually does today — the honest version

`BluetoothRfcommTransport.availablePeers()` lists **only `adapter.bondedDevices`** — phones
already paired in Android Settings. The current on-screen instruction is:

```
1.  Turn Bluetooth on, on both phones.
2.  Pair them in Android Settings → Bluetooth.
3.  Come back here and tap the other phone.
```

So yes: **three steps, one of them inside system Settings.** A frightened civilian in a flooded
street will not do that. The question is correct.

**But be precise about where the weakness is.** It is not the radio. Once a socket opens, the link
works — that is what the two-phone test proved. The weakness is **onboarding and discovery**.

We already handle connection robustness well: `openSocket()` tries **secure SDP → insecure SDP →
raw channel** in order, because Android's service discovery is unreliable across vendors. That
is connection *resilience*. It does nothing for *discovery*, which is exactly what the judge hit.

---

# The 20-second answer for the pitch

> "That is the right question, and it is a real limitation today. Three manual steps is too many
> for someone in a flood — we are not claiming otherwise.
>
> But pairing is a **setup** activity, not an emergency activity. A radio is charged before the
> call. So the deployment model is that a **responder's phone is set up beforehand** and is the hub.
>
> The second half of our design already handles the stranger case: **Wi-Fi Direct needs no pairing
> at all** — it is a two-tap OS flow, the same one you use to cast to a TV. No pre-pairing, no
> Settings. That is our zero-setup path.
>
> And getting to one tap is a known, bounded piece of work — it is our next milestone, and I can
> tell you exactly which Android APIs it uses."

**Why this works:** you did not defend the app. You agreed with the judge, showed you understand
the deployment reality, and demonstrated you know the fix by name.

---

# The longer answer, if they push

## 1. The deployment model is hub-and-spoke, not peer-to-peer

The problem statement asks for a low-bitrate **transceiver** — infrastructure, not a consumer app.
Nobody installs anything during a cyclone.

| Role | Who | Setup |
|---|---|---|
| **Hub** | NDRF/SDRF responder, camp coordinator | Phone set up **before deployment**, paired to known peers |
| **Peer** | Affected person at a camp | One-tap join, or receives as text on any phone |

The responder had time. That is the entire point of the asymmetry in our pipeline: the
responder's phone carries the 340 MB recognition model, the receiver's phone carries only voices.
**One phone in the pair is expensive. The other is light.**

## 2. The size objection has a real answer: not every phone needs the full app

804 MB is our **worst case** — both models on one device. That is not the deployment shape.

| Build | Contains | Size | Who |
|---|---|---|---|
| Full | ASR + TTS, both sides | 804 MB | Responder hub, pre-provisioned |
| **Talker-only** | ASR + VAD, no voices | ~400 MB | Responder, sends only |
| **Listener-only** | 7 TTS + VAD, no ASR | ~450 MB | Camp device that reads alerts aloud |
| **Relay** | Neither model — forwards text | **~1 MB** | Anyone. Sideloads in seconds |

The relay build is the one to lead with. **A 1 MB app can be pushed over Bluetooth in a second.**
So the "pre-install" objection largely dissolves: distributing one small file to a camp is not a
logistics problem, and a 1 MB APK sideloads with no store, no account, no network.

## 3. The actual engineering fix, by name

If they ask "so what do you build?", give specifics — this is where a technical judge decides
whether you know your own product:

| Fix | Android API | Removes | Effort |
|---|---|---|---|
| **In-app pairing** | `startDiscovery()` + `ACTION_FOUND` + `createBond()` + `ACTION_BOND_STATE_CHANGED` | The "go to Settings" step. Civilian taps one name in our own list. | ~1 day |
| **6-digit invite** | Same, with a short code to disambiguate | Ambiguity in a crowd of 40 phones | ~half day on top |
| **QR / NFC invite** | `NfcAdapter` + NDEF carrying the SPP UUID | Reading a code instead of typing it. Good for "responder hands the phone over" | ~1 day |
| **Hub hotspot join** | Responder runs a fixed-SSID hotspot; peer joins it | Pairing entirely. One tap. Works with strangers. | ~1 day |
| **Zero-setup default** | Make Wi-Fi Direct the default, Bluetooth the fallback | The whole problem, for the stranger case | config change |

**The honest ordering:** the hub-hotspot join is the highest value per hour, because it removes
pairing completely and is what a real deployment would use. In-app `createBond` is the smallest
change that visibly answers the judge's question.

## 4. Why "no internet" is a non-issue for pairing

Worth saying, because the question conjoins two things. **Bluetooth pairing and Wi-Fi Direct group
formation are entirely local.** No server, no internet, no cloud, no account. The moment the two
phones have each other, our system is offline forever. The internet is irrelevant to setup *and*
to use.

---

# What we should say we have NOT solved

State it before they find it:

- **Nobody has tested pairing with strangers.** Our two-phone test used phones we set up
  beforehand. That is the exact gap the judge spotted.
- **In-app `createBond` is not implemented.** Discovery lists bonded devices only.
- **Wi-Fi Direct group formation is still unexercised** — discovery works, the two-device group
  has not been formed.

---

# The one thing to change in the deck

Add a line to the **challenges and risks** box, so the judge finds our answer before asking:

> **Setting two phones up takes three manual steps, including one inside Android Settings.** That
> is too many for someone in a flood. Our answer: pairing happens at responder setup, not during
> the emergency; Wi-Fi Direct removes pairing entirely for strangers; and we are building in-app
> pairing so it becomes one tap.

Putting the weakness on the slide, with the fix next to it, is worth more than being asked and
defending it. It is the same move as the licence disclosure — and it worked there.

---

# If the judge is being unfair

One legitimate pushback, used once and politely:

> "We should be careful what we are asking for. Every emergency tool has a setup cost — a radio is
> charged, a satellite phone is carried, a district office has a pre-built contact list. The
> question is not whether setup is required, it is whether setup can happen **before** the
> emergency. Ours can, and we are removing even that."

Then stop. Do not argue further. You have made the point.
