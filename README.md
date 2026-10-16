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
| `docs/` | **Start here.** `DECISION_SIH26173.md` (why this PS), `HANDOVER.md` (latest state), `VOCAB_PRUNE_FINDINGS.md` (the wrong-script fix), `TTS_LICENCE_AUDIT.md` (voice licences, incl. the CC-BY-NC-SA Hindi voice), plus results and deck-change notes. |
| `app/` | The Android app — Kotlin, Gradle, `setup-project.sh`, `build.sh`, plus `evidence/` (device screenshots + logs). |
| `models/` | `asr-v3-10lang/` (**current** — v2 with its output vocabulary pruned), `asr-v2/` (unpruned baseline, kept for comparison), `asr-v1/` (superseded), `vad/` (Silero), `reference-apk/`. |
| `tts/` | Piper voices (`en_US-lessac`, `hi_IN-pratham`), `espeak-ng-data/`, and the 40 intelligibility test wavs. |
| `eval/` | Every benchmark and eval script — ASR, TTS, VAD, RAM, noise robustness, language filter, FLEURS harness, plus `prune_vocab.py` (builds the pruned model) and `fetch_tts_voices.py` (downloads and prepares the Piper voices). |
| `results/` | All measured output as JSON. `SUMMARY_full_v2.json` is the current headline result. |
| `logs/` | Eval run logs + `device/` logcat captures. |
| `dataset/` | FLEURS evaluation audio (7.1 GB). Re-fetchable with `eval/fetch_missing_langs.sh`. |
| `deck/` | The 6-slide submission deck (`.pptx` / `.pdf`), the template, and `source/` — the editable slide project. |
| `releases/` | Built APKs, gitignored: the current `iTantra-1.0.0-release.apk` + `SHA256SUMS.txt`, and the older Sep builds kept beside it. Build instructions: `docs/RELEASE.md`. |
| `research/` | Prior art (`comms-feasibility`), offline build facts, the model survey, 3 verified winning SIH decks, and the SIH 2026 problem-statement explorer. |

---

## Current status

### Proven

| Thing | Evidence |
|---|---|
| Model runs on Android | Loaded on device, **428 MB PSS / 535 MB RSS** with the model resident |
| App builds, installs, runs | **Signed release APK v1.0.0** (993 MB, sha256 `0b46aeff…`), sideloadable; this build launched and ran on Android 34 (emulator smoke test: every screen, pipeline test, loopback playback), signature/alignment/badging-verified (`docs/RELEASE.md`); screenshots in `app/evidence/ui/` |
| ASR accuracy | Mean CER **0.0918** across 200 clips × 10 languages, 2,000 clips, 6.7 h of real speech (`eval/SUMMARY_full_v3pruned.json`) |
| **Out-of-script output** | **Zero, and structurally impossible.** The model is shipped with its CTC vocabulary pruned 10,288 → 1,002 tokens, so it cannot emit Arabic, CJK or Hangul. Previously "hello" came back as `هello` and was transmitted as `ello`. See `docs/VOCAB_PRUNE_FINDINGS.md` |
| **Hindi under noise** | Script retention 40% → **78%** at pink 15 dB SNR; clean 94% → **100%**. Previously the project's single largest weakness |
| Language filter | **25/25** cases — drops any segment containing a foreign-script letter, never partially edits one |
| TTS voices | **6 of 10** languages speak aloud (hi, mr, bn, ml, te, en), all verified by synthesis |
| TTS intelligibility | Hindi round-trip CER **0.339 → 0.102** (was the top open accuracy question) |
| On-device latency | ASR RTF 0.120–0.127, TTS RTF 0.055–0.058 → **~0.9–1.0 s mouth-to-ear** |
| VAD | 0 segments on silence, RTF 0.004 |

### Not yet proven

| Gap | Why it matters |
|---|---|
| **No two-phone test** | **The demo still does not exist.** The Bluetooth RFCOMM link has never once connected |
| **Real low-end phone** | Latency and RAM are emulator numbers, not a budget handset |
| **Human TTS panel** | 40% of the marks are legibility. Every intelligibility number is an ASR round-trip proxy; 80 wavs sit in `tts/intelligibility/` unscored by a human |
| **4 of 10 languages are text-only** | Gujarati, Kannada, Tamil and Odia display correct text and stay silent. The UI labels them. See `docs/TTS_LICENCE_AUDIT.md` |
| **Battery drain** | Never measured |

### Known risks

**16 KB page size — fixed 27 Sep, worth knowing why.** Real phones showed an "This app isn't
16 KB compatible" dialog. The libraries were never the problem: every `PT_LOAD` segment in both
`.so` files is already aligned to 16384 bytes. The fault was their *position inside the APK zip*
— with `useLegacyPackaging = false` they were stored uncompressed, and an uncompressed library
must itself begin on a 16 KB boundary in the archive. Fixed by compressing the native libraries
(`zipalign -c -P 16` now exits 0), and the APK got 16 MB smaller as a side effect. This is the
kind of thing a judge with a modern phone would have hit on first launch, so it is recorded
here rather than left in the build log.

**Licences are CC, and we say so.** The Hindi voice in the APK is **CC-BY-NC-SA 4.0**
(non-commercial, share-alike) and three of the four newly added voices are CC-BY-SA or CC-BY.
An earlier version of this project claimed "no CC-BY-NC anywhere"; that was wrong.
`docs/TTS_LICENCE_AUDIT.md` has the full table and the attribution text to ship.

**Tamil still collapses at 0 dB** — 44% script retention. Pruning changed nothing there because
Tamil's failure mode is dropping syllables, not switching script.

**Wrong language, right script, still passes.** Hindi cannot be told from Marathi, nor Bengali
from Assamese. Fixing that needs a language-ID model, which is out of scope.

**Noise is synthetic.** White and pink noise measure *sensitivity*, not field accuracy in a
disaster zone. Still unmeasured.

---

## Building and running

```bash
cd app
./setup-project.sh          # vendors the sherpa-onnx API, native libs and models (copies only)
./build.sh assembleRelease  # the sideloadable release APK, signed (see docs/RELEASE.md)
./build.sh                  # debug APK, installs as com.bjp.itantra.debug
```

The release build lands at `app/app/build/outputs/apk/release/app-release.apk`; built
artifacts are copied to `releases/` with a versioned name
(`releases/iTantra-1.0.0-release.apk`) for handing to a phone.

Install on a device:

```bash
~/android-dev/android-sdk/platform-tools/adb install -r \
  releases/iTantra-1.0.0-release.apk
```

An install over an older iTantra only works if the signature matches. The release key was
rotated on 16 Oct 2026 (see `docs/RELEASE.md`), so a phone carrying anything older needs
the old app **uninstalled first** — Android reports the mismatch as "App not installed".
Release and debug builds can then coexist: different packages, separate logs. The About
and Settings screens always name the version and build kind you are looking at.

On-device benchmark (release package; append `.debug` for a debug build):

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
