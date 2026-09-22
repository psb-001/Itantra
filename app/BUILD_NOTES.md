# iTantra — what's in this build

**Built:** 21 September 2026, 22:15 IST · clean rebuild from scratch
**Debug:** `iTantra-debug.apk` (512 MB) · **Release:** `iTantra-release.apk` (510 MB, signed)

---

## Build verification

| Check | Result |
|---|---|
| `gradle clean` | ✅ |
| `assembleDebug` from clean | ✅ BUILD SUCCESSFUL |
| `assembleRelease` from clean | ✅ BUILD SUCCESSFUL |
| Release signature | ✅ verified — `CN=iTantra, OU=Team BJP, O=SIH26173, L=Pune, ST=Maharashtra, C=IN` |
| Release SHA-256 | `039522e5aeb9213009e364b935e0344456c39104417e24dcf32d86d0e27bf717` |
| `apksigner verify` | ✅ VERIFIED |

The `META-INF/... not protected by signature` warnings are normal — they refer to the v1 JAR
signature. The APK is signed with v2/v3, which covers the whole file.

---

## What changed — 20 bugs found, every one compiled cleanly

### Architecture
| # | Bug | Consequence |
|---|---|---|
| — | **One-way SPEAKER/LISTENER design** | A walkie-talkie is two-way. A reply required both users to swap roles. **Removed entirely** — every phone now loads VAD + ASR + TTS and can talk or receive. |

### Bluetooth — the connection never worked
| # | Bug | Consequence |
|---|---|---|
| 11 | Transports built with **no Context** | Reported "Bluetooth unavailable" on phones where Bluetooth worked perfectly |
| 12 | Wi-Fi Direct demanded an ungrantable permission | Unreachable on Android 13+ |
| 13 | Wi-Fi Direct failed **silently** on a null context | Nothing happened, no log at all |
| 14 | Removing the toggle made Wi-Fi Direct dead code | Fallback transport never constructed |
| 16 | `loadModels()` **leaked the native models** | Hundreds of MB leaked per role switch — the "lots of errors" |
| 17 | Wi-Fi Direct called `requestPeers()` before discovery ran | Always found nothing |
| 18 | Role button queued **two concurrent model loads** | App left in an inconsistent state |
| 19 | `connectTransport()` didn't stop the old transport | Server socket left open and accepting |
| **20** | **`startListening()` attempted once, never retried** | **Bluetooth off at launch → listening failed permanently. Turning Bluetooth on later did nothing.** |

### Speech
| # | Bug | Consequence |
|---|---|---|
| 7 | `stopCapture()` never flushed the VAD | **Speech-to-text produced literally nothing** |
| 8 | No leading silence before ASR | Hindi returned in Arabic/Tamil script, then filtered out |
| 9 | `maxSpeechDuration` default 5 s force-cut mid-sentence | Same wrong-script failure |
| 10 | `isFresh()` gated on sequence number | After the sender restarted, the receiver silently dropped everything |

### TTS
| # | Bug | Consequence |
|---|---|---|
| 1 | JNI library from the reference APK had **no TTS symbols** | Listener crashed on `UnsatisfiedLinkError` |
| 2 | TTS voice path pointed at the asset root | Native `abort()`, process died, no Java trace |
| 3 | `espeak-ng-data` read from assets (impossible) | Native `abort()` |

### Connection reliability — added this session
- **SDP fallback chain.** `createRfcommSocketToServiceRecord` does an SDP lookup, which fails
  instantly on many devices. Now tries: **secure SDP → insecure SDP → raw RFCOMM channel 1–15 via
  reflection.** Validated against BlueComm, an open-source app that does the same thing.
- **300 ms settle after `cancelDiscovery()`.** `cancelDiscovery()` is asynchronous; connecting while
  the stack is still winding down fails instantly.
- **Always listen, even while dialling.** Prevents the deadlock where both users tap each other's
  phone simultaneously and neither answers.
- **Bluetooth state watcher.** Re-opens the listening socket when Bluetooth is switched on.
- **Accept-loop watchdog.** Restarts the listener if it dies (Bluetooth stacks do this silently).
- **Every connect failure now logs the exception class and message**, so `0 ms` (refused) and
  `12 000 ms` (unreachable) can be told apart.

### UI
- **SETTINGS** screen: connection status, transport, device model, log path, live scrollable log,
  **Share log**, **Clear log**
- **Log panel is scrollable** — it used to clip silently
- **DEVICES** simplified to one job: list paired phones. Everything else moved to SETTINGS
- Log written to a file: `/sdcard/Android/data/com.bjp.itantra/files/logs/itantra.log`
- `pull_logs.sh` pulls it into `output/option_a_stt/logs/`

---

## Measured on the emulator (API 34, arm64)

| | |
|---|---|
| ASR RTF | **0.120–0.127** |
| TTS RTF | **0.055–0.058** |
| Memory, all engines | **536 MB PSS / 647 MB RSS** |
| Startup | ~3.9 s |
| VAD RTF | 0.004 |

---

## NOT verified — needs two real phones

| | Why |
|---|---|
| **The SDP fallback chain** | Emulator has no Bluetooth adapter |
| **The Bluetooth state watcher** | Same |
| **A two-way conversation** | Needs two devices |
| **Wi-Fi Direct group formation** | Discovery runs; no second device |
| **A real low-end phone** | All figures are from an emulator |

---

## How to test

1. Install `iTantra-debug.apk` on **both** phones
2. **Bluetooth ON, both phones** — before opening the app
3. Open on both. Both banners should read **"NOT CONNECTED — tap DEVICES to link"**
4. On **one** phone: **DEVICES** → tap the other phone
5. Hold the button, speak a **full sentence**, release

If it fails, **SETTINGS → Share log** on both and send the files. The log now names the exact reason:

```
  secure-SDP: IOException: Service discovery failed
  insecure-SDP: IOException: Service discovery failed
  raw channel 1: connected
```
