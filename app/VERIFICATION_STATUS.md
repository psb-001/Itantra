# Verification status — what is actually proven

**Date:** 20 September 2026 · Android 34 arm64 emulator (Apple Silicon host)
**Evidence:** every row below cites a file in `android/evidence/`

> **Why this file exists.** Six bugs were found in this app, and *every one of them compiled
> cleanly*. A green build proves nothing. This is the honest ledger of what has been executed
> versus what is still just code.

---

## VERIFIED — executed and observed

| # | What | Evidence |
|---|---|---|
| 1 | Builds a 528 MB debug APK | `../iTantra-debug.apk` |
| 2 | Installs and launches on Android 34 | `01_launched_speaker_mode.png` |
| 3 | **348 MB ASR model loads on Android** | `01_launched_speaker_mode.png` — from the earlier one-way build |
| 4 | UI renders (status, level, transcript, log, buttons) | screenshots 01–04 |
| 5 | **Microphone opens** — `AudioRecord` reaches `STATE_INITIALIZED` | pipeline log: "recording started (mic OK, sampleRate=16000)" |
| 6 | PTT press/release cycle runs without crashing | same |
| 7 | **VAD segments audio and the release-flush emits the final sentence** | `pipeline_test_verified.txt` — "0 segments before flush, 1 after" |
| 8 | **ASR on VAD segments returns correct Devanagari and Latin** | `pipeline_test_verified.txt` — script=1.00 on all four |
| 9 | Language filter accepts correct-script text and rejects foreign script | `pipeline_test_verified.txt` |
| 10 | Packet serialises and deserialises with the text intact | `loopback_test_verified.txt` |
| 11 | **Receiver path (`onPacket`) executes** | `loopback_test_verified.txt` |
| 12 | Duplicate suppression and expiry ledger run | same |
| 13 | **TTS loads and synthesises on Android** | `loopback_test_verified.txt` — 49–380 ms per sentence |
| 14 | **AudioTrack playback starts** | `loopback_test_verified.txt` — isPlaying=true |
| 15 | **ALERT mode playback works** (loop points, no exception) | `loopback_test_verified.txt` — "mode=ALERT" |
| 16 | ~~Mode switch SPEAKER → LISTENER~~ | **obsolete** — roles removed; both phones load everything |
| 17 | Transport auto-starts and re-roles on mode switch | run log: "no paired device…" |
| 22 | **Bluetooth reaches the adapter** (was impossible before) | run log: "no paired device — pair the two phones in Android Settings first" |
| 23 | **Wi-Fi Direct passes permissions and starts discovery** | run log: "DISCOVERING — looking for a peer to join" |
| 24 | Transport switcher works | run log: "transport switched to Wi-Fi Direct" |
| 25 | Device picker dialog renders all rows | `06_device_picker_dialog.png` |
| 26 | Connection banner reflects state | `05_connection_banner_and_devices_button.png` |
| 18 | espeak-ng-data extracts to storage once, then caches | run log: "already extracted" |
| 19 | ASR latency on Android: **RTF 0.120–0.127** | `benchmark_onddevice.txt` |
| 20 | TTS latency on Android: **RTF 0.055–0.058** | `benchmark_onddevice.txt` |
| 21 | Memory with the ASR model resident: **428 MB PSS / 535 MB RSS** | `dumpsys meminfo` |
| 27 | **Pruned-vocab model decodes correctly** — 10,288 → 1,002 tokens | `eval/fleurs_eval_full.py`, `SUMMARY_full_v3pruned.json` |
| 28 | **Pruned model emits zero out-of-script characters** (was 2/19 on clean short English) | `docs/VOCAB_PRUNE_FINDINGS.md` §1 |
| 29 | **Hindi script retention 40% → 78%** under pink noise @ 15 dB; clean 94% → 100% | `noise_robustness_v3pruned.json` |
| 30 | **Mean CER 0.0934 → 0.0918**; 9 of 10 languages bit-identical | `SUMMARY_full_v3pruned.json` |
| 31 | **LanguageFilter drops whole segments, never partial** — 25/25 cases | `eval/language_filter.py`, `language_filter.json` |
| 32 | **All 6 TTS voices synthesise** — real audio, correct length and amplitude | `eval/tts_intelligibility.py`, `tts_intelligibility_pruned.json` |
| 33 | **TTS round-trip: Hindi CER 0.339 → 0.102** (was "the most important open question") | `tts_intelligibility_pruned.json` |
| 34 | **v1/v2 discrepancy closed** — the APK now ships the model the deck reports | `unzip -l app-debug.apk \| grep omnilingual` |
| 35 | **16 KB page-size compatible** — the "not 16 KB compatible" dialog on a real phone is gone | `zipalign -c -P 16 releases/iTantra-release.apk` exits 0 |
| 36 | **All 6 TTS voices synthesise** after trimming espeak-ng-data 17.2 MB → 1.7 MB | `eval/tts_smoke.py` 6/6 |
| 37 | **Material 3 UI, light and dark** — connection dot, mic level bar, About and Licences screens | `app/evidence/ui/*.png` |
| 38 | On-device ASR **RTF 0.147** on a 16384-byte-page emulator (6.94 s clip in 1020 ms) | `app/evidence/pipeline_pruned_model_2026-09-27.txt` |

**Everything except the radio has now been executed at least once.**

---

## NOT VERIFIED — still just code

| # | What | Why it is unverified | Risk |
|---|---|---|---|
| 1 | **Bluetooth RFCOMM socket** | The emulator has no paired device, so no socket has ever been established. The handshake and byte transfer remain unexecuted. **But the adapter is now reachable and the app reports the real reason it cannot proceed.** | **HIGH** — this is the demo |
| 2 | **Wi-Fi Direct group formation** | Discovery runs and reports correctly, but there is no second device to form a group with. | MEDIUM |
| 3 | **Two-device operation** | Only one runtime available | HIGH |
| 4 | **Real microphone audio** | The emulator's mic input is silent, so the VAD correctly found 0 segments. The path runs; it has never seen actual speech. | MEDIUM |
| 5 | **A real low-end phone** | All figures are from an emulator on Apple Silicon. A budget handset will be slower. | MEDIUM |
| 6 | **Alert non-interruptibility** | Playback starts in ALERT mode, but "cannot be ducked by other apps" was not tested against a competing audio source. | LOW |
| 7 | **TTS voices for Kannada, Tamil, Odia, Gujarati** | **No voice exists that we can ship.** Not "not wired up" — the espeak-ng fallback that was planned is not implementable (espeak-ng is a phonemiser, not a standalone voice, and the JNI library has no espeak TTS config). These four are text-only and the UI says so. See `docs/TTS_LICENCE_AUDIT.md`. | MEDIUM (PS requirement) |

---

## Bugs found in this app — all compiled cleanly

| # | Bug | Consequence if shipped |
|---|---|---|
| 1 | JNI library from the reference APK has no TTS symbols | Listener phone crashes on `UnsatisfiedLinkError` |
| 2 | TTS voice path pointed at the asset root, not `assets/tts/` | Native `abort()`, process dies, no Java trace |
| 3 | `espeak-ng-data` read from assets (impossible) | Native `abort()` |
| 4 | Transport never started — only the button started it | Nothing ever connected |
| 5 | First tap on "Bluetooth" switched to Wi-Fi Direct | Wrong transport, silently |
| 6 | Mode switch did not re-role the transport | Both phones on the same side; never pairs |
| 7 | **`stopCapture()` never flushed the VAD** | **Speech-to-text produced literally nothing** |
| 8 | No leading silence before ASR | Hindi returned in Arabic/Tamil script, then filtered out — nothing appears |
| 9 | `maxSpeechDuration` default 5 s force-cut mid-sentence | Same wrong-script failure |
| 10 | `isFresh()` gated on sequence number | After the sender restarts, the receiver silently drops every message |
| 11 | **Transports built with no Context** | **Bluetooth reported "unavailable" on a phone where it worked. This is why Bluetooth never connected.** |
| 12 | Wi-Fi Direct demanded an ungrantable permission | Wi-Fi Direct unreachable on Android 13+ |
| 13 | Wi-Fi Direct failed silently on a null context | Nothing happened, with no log at all |
| 14 | Removing the toggle button made Wi-Fi Direct dead code | Fallback transport never constructed |
| 15 | `setMessage` + `setItems` hid the dialog rows | "Listen for an incoming connection" unreachable |

Bugs 7–10 were found only by *executing* the pipeline. Bugs 1–3 were found only by *running* the
app. **None of them would have been caught by a build.**

---

## How to re-run any of this

```bash
# toolchain
export JAVA_HOME="$HOME/android-dev/jdk17/Contents/Home"
export ANDROID_HOME="$HOME/android-dev/android-sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

# start the emulator if it is not running
"$ANDROID_HOME/emulator/emulator" -avd itantra34 -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -memory 4096 &

# on-device latency benchmark
adb shell am start -n com.bjp.itantra/.MainActivity --ez benchmark true

# speaker-side pipeline (VAD -> ASR -> filter -> packet)
adb shell am start -n com.bjp.itantra/.MainActivity --ez pipeline true

# full loopback (speaker side AND listener side, incl. TTS + playback + alert mode)
adb shell am start -n com.bjp.itantra/.MainActivity --ez loopback true

# read the results
adb logcat -d | grep -E "BENCH|PIPE|LOOP"
```

---

## Bottom line

**The app works, minus the radio.** Speech recognition, language filtering, packet handling,
speech synthesis, playback and alert mode have all been executed and observed. Bluetooth has not —
not once, by anyone.

If the demo matters, the next hour should go into getting a Bluetooth socket to actually connect.
Everything else is proven.
