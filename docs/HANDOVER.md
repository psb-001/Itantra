# HANDOVER — what happened while you were away

**Session:** 19–20 September 2026 · **Deadline:** 30 September 2026
**Nothing was deleted.** Every operation was copy/create/edit only.

---

## Read this first — the three things that changed

**1. There is a v2 of the model, and it is better.** Same 279 MiB, mean CER 0.0992 → **0.0934**.
Better on 6 of 10 languages. Already downloaded and measured. The deck now uses v2 numbers.

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

---

## Corrections made to earlier claims

Found and fixed while you were away:

| Claim | Was | Actually |
|---|---|---|
| Silero VAD size | "208 KB, MEASURED" | **628.8 KB** — and the file was never on disk at all |
| Model version | v1 only | **v2 exists and is better** |
| TTS coverage | implied 10 languages | **7 of 10** have a neural voice |
| Tamil / Odia CER | 0.149 / 0.143 | **0.129 / 0.134** on v2 |

The Odia and Marathi corrections from 19 Sep still stand (Odia was optimistic at 6 clips; Marathi
was wrongly written off).
