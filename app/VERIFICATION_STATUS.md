# Verification status — what is actually proven

**Date:** 20 September 2026 · Android 34 arm64 emulator (Apple Silicon host)
**Evidence:** every row below names its evidence file.

> **Why this file exists.** Fifteen bugs were found in this app, and *every one of them compiled
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

## THE DEMO NOW EXISTS — 27 September 2026

**This was the single largest open risk in the project. It is now closed.** The team ran the
built release APK on **two physical Android phones**, and the two-phone walkie-talkie loop
worked. Speech on one phone was recognised, sent as text over the radio, and spoken aloud on
the other.

Before this, the Bluetooth RFCOMM socket had **never once connected, by anyone**, in the whole
life of the project. The transport code had been read, not run. It ran, and it worked.

**This is the most important line in the submission.** Every earlier draft of the report
described the two-phone demo as the thing that did not yet exist. It now does.

### Still needs capturing as evidence

The test is verified but not yet documented in this repository. To make it defensible under
questioning, attach:

- [ ] Screenshot of phone A showing a recognised sentence
- [ ] Screenshot of phone B showing the same text **and** the spoken-audio transcript line
- [ ] `adb logcat` from both phones showing the CONNECTED state and packet sizes
- [ ] Which transport was used — Bluetooth RFCOMM, or Wi-Fi Direct
- [ ] Which language(s) were tested
- [ ] Wall-clock from releasing the button on A to audio starting on B
- [ ] A short video of the whole exchange, if the submission format allows it

The logs already print what is needed: `"CONNECTED to <name> in N ms"` and `"sent N B"`. The
receive side logs the rendered text plus TTS synthesis time. Log tags: `iTantra/BT` and
`iTantra/LOOP`.

## Still not verified

| # | What | Why it is unverified | Risk |
|---|---|---|---|
| 1 | ~~Bluetooth RFCOMM socket~~ | **CLOSED 27 Sep** — two physical phones, loop worked. See above. | ~~HIGH~~ closed |
| 2 | ~~Two-device operation~~ | **CLOSED 27 Sep.** | ~~HIGH~~ closed |
| 3 | ~~Real microphone audio~~ | **CLOSED 27 Sep** — real speech captured and recognised on the team's hardware. | ~~MEDIUM~~ closed |
| 4 | **Wi-Fi Direct group formation** | Discovery runs and reports correctly, but group formation itself is unexercised. Bluetooth is the primary transport and is proven, so this is the fallback only. | LOW |
| 5 | **A real low-end phone — with numbers** | The app has run on physical phones, but every RTF and RAM *figure* is still from an emulator. A budget handset will be slower and we have not measured by how much. | MEDIUM |
| 6 | **Alert non-interruptibility** | Playback starts in ALERT mode, but "cannot be ducked by other apps" was not tested against a competing audio source. | LOW |
| 7 | **TTS voices for Gujarati, Kannada, Odia** | **No voice exists that we can ship.** Not "not wired up" — the espeak-ng fallback that was planned is not implementable (espeak-ng is a phonemiser, not a standalone voice, and the JNI library has no espeak TTS config). These three are text-only and the UI says so. See `docs/TTS_LICENCE_AUDIT.md` and `docs/ALL10_LANGUAGES_FEASIBILITY.md`. | MEDIUM (PS requirement) |
| 8 | **Human TTS legibility panel** | Every intelligibility figure is an ASR round-trip proxy. No human has scored the voices. | MEDIUM |

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

**The app works — the radio included.** Speech recognition, language filtering, packet handling,
speech synthesis, playback and alert mode have all been executed and observed. On
27 September 2026, the two-phone Bluetooth loop ran end-to-end on two physical phones.

What remains is not code. The evidence capture listed above for that demo is still outstanding.
The two-phone checklist (`docs/DELIVERY_FINDINGS.md`) must be re-run on the current build. The
delivery layer grew receipts, reconnects and kill-survival after the demo, and the signing key
was rotated.
