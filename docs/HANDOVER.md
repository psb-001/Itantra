# HANDOVER — what happened while you were away

**Session:** 19–20 September 2026 · **Deadline:** 30 September 2026
**Nothing was deleted.** Every operation was copy/create/edit only.

---

## Read this first — the three things that changed

**1. There is a v2 of the model, and it is better.** Same size — **348.9 MiB on disk** vs 348.4 MiB
for v1 (365,841,453 vs 365,352,120 bytes) — mean CER 0.0992 → **0.0934**.
Better on 6 of 10 languages. Downloaded and measured. The deck uses v2 numbers.
**But the app still bundles v1 — see the discrepancy section below before the demo.**

**2. Your TTS only covers 7 of 10 languages, and that was never written down anywhere.**
Piper gives you Hindi, Marathi, Malayalam, Telugu, Bengali, English. Gujarati comes from Mimic3.
**Kannada, Tamil and Odia have no permissively-licensed neural voice.** The deck now states this
in the risk panel rather than hiding it.

**3. The Android app BUILDS, INSTALLS AND RUNS.** `android/iTantraApp/` — Kotlin, Bluetooth +
Wi-Fi Direct transports, TTS playback, the language filter. Toolchain at `~/android-dev/`.
A **521 MB APK** was built, installed on an Android 34 emulator, launched, and **the 348 MB model
loaded**. **428 MB PSS / 535 MB RSS** with the model resident. Screenshots in `android/evidence/`.
**Phone-class CPU latency is still unmeasured.**

---

## ⚠ OPEN DISCREPANCY — the app bundles v1, the deck reports v2

Found 22 Sep 2026 while preparing the GitHub release. **Not fixed.**

`SpeechEngine.kt` pins the model by index:

```kotlin
/** Matches the reference app: model type 44 selects omnilingual. */
const val MODEL_TYPE_OMNILINGUAL = 44
```

and `getOfflineModelConfig(44)` in `app/app/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt:799`
resolves that to:

```kotlin
val modelDir = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"
```

That is the **v1** directory. Verified inside the shipped APK rather than inferred:

```
$ unzip -l releases/iTantra-release.apk | grep omnilingual
365352120  assets/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/model.int8.onnx
    86423  assets/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/tokens.txt
```

365,352,120 bytes = the v1 file, byte-identical to `models/asr-v1/model.int8.onnx`.
The v2 model (`models/asr-v2/...-ctc-v2-int8-2026-02-05/`, 365,841,453 bytes) is **not referenced
anywhere under `app/`**.

**Why it matters.** `ACCURACY_RESULTS.md` and the deck headline **CER 0.0934**, which is v2's
number. The APK that would run on stage contains v1, whose measured CER is **0.0992**. Nobody
built a wrong model — the v2 switch was made in the docs and the eval harness, but never in
`SpeechEngine.kt` or the bundled assets.

**Two ways to close it:**

1. **Re-bundle v2** (the honest fix, and what the deck already claims). Vendor
   `models/asr-v2/...-ctc-v2-int8-2026-02-05/` into `app/app/src/main/assets/` under the directory
   name the Kotlin expects, or add a v2 index and point `MODEL_TYPE_OMNILINGUAL` at it, then
   `./build.sh`. The eval numbers were all produced with v2, so the deck needs no edit.
2. **Report v1's numbers instead** (0.0992), and drop the v2 claims from the deck. Cheaper, but
   throws away a real 5.9% relative accuracy improvement.

Option 1 is the right one. It is a copy plus a rebuild, not new work.

---

## Stage 0 is answered: the model runs on a phone

You confirmed it on your 6 GB device. That was the biggest unknown in the whole project and it is
now closed. **The model runs on Android.**

The APK is at `android/sherpa-onnx-1.13.8-arm64-v8a-vad_asr-1600-omnilingual_asr_300M_ctc_int8.apk`
(297,969,404 bytes, verified against your original dossier claim — which was correct all along).

---

## What I built

### New files

| File | What it is |
|---|---|
| `MODEL_AND_TTS_FINDINGS.md` | **v1-vs-v2 comparison + the full TTS coverage audit.** Read this. |
| `HANDOVER.md` | this file |
| `language_filter.py` | restricts ASR output to the ten scripts. **21/21 test cases pass.** |
| `vad_test.py` | proves the VAD detects speech and ignores silence. **RTF 0.004.** |
| `prove_local_run.py` | proves the results were computed locally, not downloaded |
| `android/LanguageFilter.kt` | the Kotlin version of the filter |
| `android/iTantraApp/**` | **the complete Android app** (see below) |

### The Android app — `android/iTantraApp/`

```
app/src/main/java/com/bjp/itantra/
├── MainActivity.kt                    push-to-talk, permissions, the full pipeline
├── LanguageFilter.kt                  ten-script output filter
├── Message.kt                         packet format + delivery states + dedupe/expiry
├── speech/SpeechEngine.kt             VadEngine, AsrEngine, TtsEngine, Speaker
└── transport/
    ├── Transport.kt                   the interface + runtime picker
    ├── BluetoothRfcommTransport.kt    primary
    └── WifiDirectTransport.kt         fallback
```

Plus Gradle files, manifest, layout, strings, an app icon, `setup-project.sh` (vendors the
sherpa-onnx API, native libs and models — **copies only, deletes nothing**), and a README.

**Verified before writing the code** (so the paths are right, not guessed):
- `getOfflineModelConfig(44)` expects `assets/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/{model.int8.onnx,tokens.txt}` ✅ matches
- `getVadModelConfig(0)` expects `assets/silero_vad.onnx` ✅ matches
- Every API symbol the code calls exists in the vendored Kotlin files ✅

### New measurements

**v2 model, 200 clips × 10 languages** (`SUMMARY_full_v2.json`):

| | v1 | v2 |
|---|---|---|
| Mean CER | 0.0992 | **0.0934** |
| Best language | Kannada 0.052 | Kannada 0.053 |
| Worst language | Tamil 0.149 | **Odia 0.134** |
| Better on | — | 6 of 10 |

**Silero VAD** — extracted from the prebuilt APK (it was never on disk; the dossier's "208 KB" was
wrong — the real file is 628.8 KB). Tested: 0 segments on silence, 1–3 on real speech, **RTF 0.004**.

**Language filter** — 21/21 cases. Accepts all ten languages; rejects Chinese, Japanese, Korean,
Arabic, Russian, Thai, Greek, empty and digits-only. Cleans stray foreign characters out of
otherwise-valid text.

---

## What you need to do, in order

### 1. ~~Build the Android app~~ — DONE, it builds

The toolchain is installed and the APK exists. **Skip straight to installing it:**

```bash
cd output/option_a_stt/android/iTantraApp
~/android-dev/android-sdk/platform-tools/adb install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app/build/outputs/apk/debug/app-debug.apk` (521 MB) to the phone and tap it.
To rebuild after a code change: `./build.sh`.

### 2. Get two phones talking

Pair them in Android Settings first (Bluetooth pairing is manual by design — it is what
authenticates the sender of an emergency alert). Then: open the app on both, tap DEVICES on one,
hold the button, speak.

### 3. The human TTS listening panel

40 wavs are already generated at `option_a_stt/tts/intelligibility/`. Have 2–3 Hindi and 2–3
English speakers score intelligibility. That converts a proxy into a real number for the
40%-weighted criterion. **This is the cheapest remaining win.**

### 4. Decide on Kannada / Tamil / Odia voices

Either ship espeak-ng (recommended — no licence questions) or ask the organisers about
CC-BY-NC for MMS-TTS. See `MODEL_AND_TTS_FINDINGS.md` §2.

### 5. Fill in `[PORTAL TEAM ID]` on slide 1

Still a placeholder. It is the only literal blank left in the deck.

---

## Decisions I made, and why

**Switch to model v2.** Same size, better average, better worst case. No downside.

**espeak-ng over MMS-TTS for the three missing voices.** MMS is better quality but
`cc-by-nc-4.0`. espeak-ng keeps the shipped stack at MIT / Apache-2.0 / GPL with no licence
argument to have. It is robotic and we say so.

**Bluetooth Classic as the primary transport, Wi-Fi Direct as fallback.** The payload is 50–250
bytes; we need ~1 kbit/s and Bluetooth gives 2 Mbit/s. Wi-Fi Direct's bandwidth is worthless here,
and `WifiP2pManager` behaves differently across vendors — that is how demos fail. Bluetooth pairing
also gives sender authentication for free.

**Vendored the sherpa-onnx API rather than building the AAR.** Fewer moving parts, and the native
`.so` files were already sitting in the decompiled APK. One project, no two-stage build.

**Did not fine-tune.** Technically possible — Meta publishes the PyTorch checkpoints and training
recipes, both Apache 2.0 — but it is weeks of work and it would **not** fix the "detects other
languages" problem, because fine-tuning biases the model rather than guaranteeing it. The output
filter guarantees it. Fine-tuning is now a *future work* line on the deck.

---

## New finding — Hindi flips script under noise

`NOISE_ROBUSTNESS_FINDINGS.md`. Measured with controlled white/pink noise:

- **Hindi at 30 dB SNR** (noise 1000x weaker than signal, inaudible): CER 0.133 → **0.424**, and
  **script retention drops 94% → 64%**. At 15 dB it is 48%.
- The model writes the same sounds **in a different alphabet** — Arabic/Urdu, or Gujarati.
- **English and Bengali are solid** — 100% script retention down to 10 dB.

This is the mechanism behind the original "it detects other languages" complaint. It also forced
a fix: `LanguageFilter` now enforces the **expected** script for the selected language, not just
"one of the nine". Otherwise a Hindi→Gujarati flip would be transmitted as unreadable text.

**Decision to make:** demonstrate in English + Bengali (100% script retention), or keep Hindi and
add a read-back confirmation step so the speaker sees the transcript before it sends.

## On-device benchmark — and three bugs it caught

```
adb shell am start -n com.bjp.itantra/.MainActivity --ez benchmark true
```
Runs ASR and TTS on bundled WAVs and logs RTF, latency and memory.
Evidence: `android/evidence/benchmark_onddevice.txt`.

| Stage | Android | Desktop (Mac) |
|---|---|---|
| ASR RTF | **0.120–0.127** | 0.039–0.043 |
| TTS RTF | **0.055–0.058** | 0.032–0.044 |
| ASR model load | 1.4–2.0 s | 0.53 s |
| TTS voice load | 0.6–0.8 s | 0.53–0.64 s |

Transcripts were correct for both English and Hindi. A 3-second sentence implies ~0.55 s of
compute, putting mouth-to-ear at roughly **0.9–1.0 s** — at the 1-second target.

### The three bugs — all three would have crashed the demo on stage

**1. The JNI library copied from the reference APK has no TTS.**
`UnsatisfiedLinkError: No implementation found for ... OfflineTts.newFromAsset(...)`.
That APK is speech-to-**text** only, so its `libsherpa-onnx-jni.so` was built without TTS —
**0 OfflineTts symbols** vs **58** in the official one. `setup-project.sh` now fetches the
official `sherpa-onnx-v1.13.8-android.tar.bz2` and *verifies* the symbols are present.

**2. Wrong TTS asset path.** The voices are in `assets/tts/` but were referenced at the asset
root. The native layer prints `F sherpa-onnx: Load 'en_US-lessac-medium.onnx' failed` and calls
`abort()` — the process dies with **no Java stack trace**.

**3. `espeak-ng-data` cannot be read from assets.** The native phonemiser opens real files by
path. `TtsEngine` now copies it to app storage on first use (186 ms, cached) and passes the
filesystem path.

**All three compiled cleanly.** A successful build tells you nothing about whether the app runs.

## Still not done

| Item | Why it matters |
|---|---|
| **Real low-end phone** | Latency and RAM are measured on an emulator, not a budget handset. |
| **The noise finding needs a decision** | Hindi script retention ~50% at 15 dB. Pick a demo language. |
| **No two-phone test** | The demo does not exist yet. |
| **Human TTS panel** | 40% of the marks depend on legibility. |
| **Phone-class latency/RAM** | Still TARGET, not MEASURED, in the deck. |
| **Battery drain** | Never measured. |
| **Noisy-audio robustness** | FLEURS is clean studio speech. |

> **Update, 16 Oct 2026.** The two-phone test now exists: on 27 Sep 2026 the release APK ran on
> two physical phones and the loop worked (`app/VERIFICATION_STATUS.md`). The current v1.0.0
> build still needs its own pass.

---

## Corrections made to earlier claims

Found and fixed while you were away:

| Claim | Was | Actually |
|---|---|---|
| Silero VAD size | "208 KB, MEASURED" | **628.8 KB** — and the file was never on disk at all |
| Model version | v1 only | **v2 exists and is better** |
| TTS coverage | implied 10 languages | **7 of 10** have a neural voice |
| Tamil / Odia CER | 0.149 / 0.143 | **0.129 / 0.134** on v2 |
| ASR model size | "279 MiB" (6 places, 5 docs) | **Unverified.** Nothing in-repo or upstream supports it, and the upstream HF repo (`csukuangfj2/…-ctc-int8-v2-2026-02-05`) now contains only `.gitattributes`. What *is* verifiable is the shipped file: **348.9 MiB** (365,841,453 bytes) for v2, **348.4 MiB** for v1. State 348 MiB, or state nothing. |
| Doc paths | `output/option_a_stt/android/iTantraApp/`, `android/evidence/`, `option_a_stt/tts/` | **24 references across 6 docs still point at the pre-21-Sep layout.** Live now: app is `app/`, evidence is `app/evidence/`, TTS wavs are `tts/intelligibility/`, deck is `deck/`. Not yet rewritten — some may be deliberate historical narrative in `DECISION_SIH26173.md`. |

The Odia and Marathi corrections from 19 Sep still stand (Odia was optimistic at 6 clips; Marathi
was wrongly written off).

---

## Session record — 16 October 2026 (release day)

**Purpose.** Publish v1.0.0 to GitHub and leave the repository clean.

**Decisions.**

1. Release identity: `versionName 1.0.0`, `versionCode 2`. Debug builds carry the `.debug`
   package suffix.
2. Key rotation: the first key was committed to git with its password, so it counts as
   compromised. We rotated it on 16 Oct 2026. Older installs must be uninstalled first.
3. History cleanup, not a merge: four local deck commits duplicated content already on
   `origin/main`. We replayed the 30 app commits onto `origin/main` with `git rebase --onto` and
   dropped the duplicates. The keystore file was parked outside the repository during the rebase.
4. Distribution stays sideload-only. No Google Play.

**Completed work.**

1. Fixed the launch crash. Rebuilt and re-signed the release APK: 993,362,447 bytes, sha256
   `0b46aeffa5a39e506340632c16d125c6787a4cc13cbfbb10d79b35f0cae26b48`.
2. Ran the release APK on an Android 34 emulator (arm64). Every screen opened. Three self-tests
   passed. No crashes.
3. Corrected the two-phone claims in 7 files. The 27 Sep 2026 demo ran on the earlier build.
4. Rebased 30 commits onto `origin/main` (zero conflicts). The tree stayed byte-identical.
5. Pushed `main` and tag `v1.0.0`. Published GitHub Release `v1.0.0` with 43 assets.
6. Verified the release APK byte-for-byte: GitHub's digest matches the local SHA-256 exactly.
7. Added the full team (6 members) to the README, with LinkedIn links.
8. Backed up the signing files to `~/itantra-signing-backup/`: the new key, the superseded key,
   `keystore.properties`, and restore instructions.
9. Rewrote the README and the current-facing docs in simpler English: shorter sentences, active
   voice, no semicolons.

**Open items.**

1. **WARNING: the signing backup sits on one machine only.** Copy `~/itantra-signing-backup/` off
   the Mac. A disk failure would end the update path.
2. Re-run the 12-item two-phone checklist on v1.0.0 (`docs/DELIVERY_FINDINGS.md`). Install the
   new APK fresh on both phones first.
3. Decisions pending: foreground service for long holds, Laya voice assets, APK size trimming,
   and the English voice verdict.

---

## Session record — 18 October 2026 (Team YOLO rename — release v1.0.1)

**Purpose.** The team's registered name changed to **Team YOLO**. Rename it in every
current-state surface — the app, the README, the current docs — rebuild and republish the APK,
and upgrade the AVISHKAR convention poster (1 m × 1 m) with QR evidence codes and the real
reference stack.

**Decisions.**

1. Rename *current-state* surfaces only: app source (`ProjectInfo.kt`), README,
   `docs/ARCHITECTURE.md`, `docs/TECHNICAL_APPROACH.md`,
   `app/TECH_STACK_AND_ARCHITECTURE.md`, both posters. Dated records (deck sources,
   presentation scripts, decision log, dossier, `technical_approach.html`) keep the old name
   as history.
2. The signing certificate's `OU=Team BJP` stays — it is the literal certificate identity;
   changing it would need a new key and a forced uninstall on every phone. The package name
   `com.bjp.itantra` stays too.
3. Release identity: `versionName 1.0.1`, `versionCode 3`. Same key as v1.0.0, so an
   update-install over v1.0.0 works.
4. Poster references list the *actual* stack (Omnilingual ASR, Silero VAD, Piper voices,
   sherpa-onnx, ONNX Runtime, espeak-ng, FLEURS), and the QR codes point at the repo, release
   `v1.0.1`, `DELIVERY_FINDINGS.md`, and a scan page with everything on it
   (`psb-001.github.io/Itantra/`). The poster keeps the exact 1 m × 1 m square.

**Completed work.**

1. Renamed the team in the app (About), README, and the three current docs. Also corrected the
   Piper credit line from "six" to **seven** voices — Tamil ships and was missing.
2. Built, verified, and installed v1.0.1: 993,362,519 bytes, sha256
   `00b16eb216f1985a859bde2c8fe751785523429b2ef09937c693d813dd0db40e`, same certificate
   (`D9:C0:B3:…:58:B9`), badging `versionCode 3` / `versionName 1.0.1`, zip-aligned.
3. Recaptured release screenshots 06–11 from the v1.0.1 build on the Android 34 emulator;
   README, `app/evidence/ui/`, and the posters all use the same frames.
4. Poster upgrades: signal-path strip, QR evidence panel (repo / release / findings), a
   scan page QR in the reference band, real references, Team YOLO in the header, a
   simple-English rewrite of every poster text, and zero em dashes. Rebuilt `poster/out/`
   PDFs and PNGs, and rendered a flex print master (`itantra-poster-1x1m-print.png` and
   `.jpg`, 8000 px, 203 dpi at 1 m).
5. Published GitHub Release `v1.0.1` with the signed APK as its asset.

**Open items.**

1. **WARNING: the signing backup still sits on one machine only.** Copy
   `~/itantra-signing-backup/` off the Mac.
2. Re-run the 12-item two-phone checklist on v1.0.1 (`docs/DELIVERY_FINDINGS.md`). Uninstall
   pre-16-Oct builds first; v1.0.0 updates in place.
3. The repository is public now (18 Oct), and GitHub Pages serves the scan page that the
   poster QR codes open.
4. Decisions pending: foreground service for long holds, Laya voice assets, APK size trimming,
   and the English voice verdict.
