# iTantra — SIH26173

**Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links.**

Organisation: **ISRO, Department of Space** · Category: Software · Theme: Smart Automation
Team: **B.J.P (Bug Janta Party)** · **Deadline: 30 September 2026**

---

## What this is

Voice is too data-heavy for low-bitrate links, but in a distress scenario you still need voice —
it reaches people regardless of literacy. This project ships an **Android app that runs offline
STT and TTS in 10 Indian languages on a low-power phone**, sends the recognised **text** over
Bluetooth / Wi-Fi Direct, and speaks it out on the receiving phone. Two phones behave like a
push-to-talk walkie-talkie.

Nothing leaves the device. No network, no cloud.

**Ten languages:** Hindi, Marathi, Bengali, Gujarati, Kannada, Malayalam, Odia, Tamil, Telugu, English.

---

## Folder map

| Path | What lives there |
|---|---|
| `docs/` | **Start here.** `DECISION_SIH26173.md` (why this PS), `HANDOVER.md` (latest state), `ARCHITECTURE.md`, `TECHNICAL_APPROACH.md`, `FULL_RESEARCH_DOSSIER.md`, plus results and deck-change notes. |
| `app/` | The Android app — Kotlin, Gradle, `setup-project.sh`, `build.sh`, plus `evidence/` (device screenshots + logs). |
| `models/` | `asr-v2/` (**current**), `asr-v1/` (superseded), `vad/` (Silero), `reference-apk/` (the prebuilt sherpa-onnx reference APK). |
| `tts/` | Piper voices (`en_US-lessac`, `hi_IN-pratham`), `espeak-ng-data/`, and the 40 intelligibility test wavs. |
| `eval/` | Every benchmark and eval script — ASR, TTS, VAD, RAM, noise robustness, language filter, FLEURS harness. |
| `results/` | All measured output as JSON. `SUMMARY_full_v2.json` is the current headline result. |
| `logs/` | Eval run logs + `device/` logcat captures. |
| `dataset/` | FLEURS evaluation audio (7.1 GB). Re-fetchable with `eval/fetch_missing_langs.sh`. |
| `deck/` | The 6-slide submission deck (`.pptx` / `.pdf`), the template, and `source/` — the editable slide project. |
| `releases/` | Built APKs: `iTantra-release.apk`, `iTantra-debug.apk`. |
| `research/` | Prior art (`comms-feasibility`), offline build facts, the model survey, 3 verified winning SIH decks, and the SIH 2026 problem-statement explorer. |

---

## Current status

### Proven

| Thing | Evidence |
|---|---|
| Model runs on Android | Loaded on device, **428 MB PSS / 535 MB RSS** with the 348 MiB model resident |
| App builds, installs, runs | 521 MB APK built and launched on Android 34; screenshots in `app/evidence/` |
| ASR accuracy | Mean CER **0.0934** on v2 across 200 clips × 10 languages (`results/SUMMARY_full_v2.json`) |
| On-device latency | ASR RTF 0.120–0.127, TTS RTF 0.055–0.058 → **~0.9–1.0 s mouth-to-ear** |
| VAD | 0 segments on silence, RTF 0.004 |
| Language filter | 21/21 cases — accepts the ten scripts, rejects everything else |

### Not yet proven

| Gap | Why it matters |
|---|---|
| **Real low-end phone** | Latency and RAM are emulator numbers, not a budget handset |
| **No two-phone test** | The actual demo does not exist yet |
| **Human TTS panel** | 40% of the marks are legibility; 40 wavs sit in `tts/intelligibility/` unscored |
| **Battery drain** | Never measured |
| **Hindi under noise** | Script retention drops to ~50% at 15 dB SNR — pick a demo language |

### Known risk

The model flips script under noise — Hindi at 30 dB SNR (inaudible noise) drops from 94% to 64%
script retention, writing the same sounds in Arabic/Urdu or Gujarati. English and Bengali hold
100% down to 10 dB. See `docs/NOISE_ROBUSTNESS_FINDINGS.md`.

---

## Building and running

```bash
cd app
./setup-project.sh     # vendors the sherpa-onnx API, native libs and models (copies only)
./build.sh             # builds the APK
```

Install on a device:

```bash
~/android-dev/android-sdk/platform-tools/adb install -r \
  app/app/build/outputs/apk/debug/app-debug.apk
```

On-device benchmark:

```bash
adb shell am start -n com.bjp.itantra/.MainActivity --ez benchmark true
```

Toolchain lives at `~/android-dev/`. **Pair the two phones in Android Settings first** — Bluetooth
pairing is manual by design; it is what authenticates the sender of an emergency alert.

---

## Notes on this layout

This folder was reorganised on 21 September 2026. The project previously sat scattered inside a
clone of the unrelated **Scrapling** Python library, mixed with ~1 GB of abandoned hackathon work.
Everything project-related was moved here and the rest was sent to the macOS Trash.

Two things may need attention:

- **`.venv/` was moved, so its shebangs still point at the old path.** Recreate it with
  `python3 -m venv .venv && .venv/bin/pip install -r ...`, or fix the paths in `.venv/bin/`.
- **`app/app/build/` holds 3.1 GB of stale Gradle output** carried over from the old path.
  It is regenerable — safe to delete, `./build.sh` rebuilds it.
