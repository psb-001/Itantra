# Meshtastic Offline STT

**Secure, offline, multilingual speech-to-text for Meshtastic, running on the Radxa ZERO 3W.**

> Status: early stage — project plan and benchmark design.

## Goal

[Meshtastic](https://meshtastic.org) lets people exchange text messages over LoRa without internet or cell coverage. LoRa's bandwidth is far too low to carry voice, so users have to type on a phone or a tiny keypad.

This project lets a user **dictate a message, transcribe it locally on the device with no internet connection, and send it as text over the mesh**. French comes first, followed by other languages.

## Design principles

- **Fully offline:** no cloud API, no network needed for recognition.
- **Privacy by design:** audio never leaves the device and is never stored by default.
- **Secure embedded system:** hardened Linux image and protected data and models, so a lost or captured node exposes as little as possible.
- **Open and reproducible:** every benchmark comes with scripts, model versions and settings.

## Architecture (planned)

```
 ┌──────────────┐   audio   ┌──────────────────────────┐   text   ┌────────────────┐   LoRa
 │  Microphone  │ ────────► │  Radxa ZERO 3W (RK3566)  │ ───────► │ Meshtastic node│ ~~~~~~► mesh
 │ (USB / I2S)  │           │  VAD → STT → text cleanup│  serial  │ (USB or SPI    │
 └──────────────┘           └──────────────────────────┘          │  LoRa module)  │
                                                                  └────────────────┘
```

The ZERO 3W handles audio capture, voice activity detection and speech recognition. The text is passed to Meshtastic, either through a Meshtastic node connected over USB serial or through a LoRa module wired to the board.

## Hardware

- Radxa ZERO 3W — RK3566, 4 GB LPDDR4, 32 GB eMMC (RS107-D4E32H1W15)
- Microphone (USB or I2S)
- Meshtastic-compatible LoRa node or module

## Speech models to benchmark

| Engine | Why |
|---|---|
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | Strong multilingual accuracy; tiny / base / small models |
| [Vosk](https://alphacephei.com/vosk/) | Lightweight, streaming, small per-language models |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Streaming models, ONNX runtime, good ARM support |
| [Moonshine](https://github.com/usefulsensors/moonshine) | Designed for low-latency on-device recognition |

**Languages:** French first, then English, then additional languages.

**Metrics:**
- Accuracy: word error rate (WER), or character error rate (CER) for languages without spaces
- Speed: real-time factor and end-to-end latency (end of speech → text ready)
- Resources: peak RAM, model size on eMMC, CPU load, power draw

**Test data:** public multilingual speech datasets (e.g. Mozilla Common Voice, FLEURS), plus short dictated messages typical of mesh use.

## Security and hardening (planned)

- Minimal Linux image with unused services removed
- Encrypted storage for user data and configuration
- Integrity protection for models and binaries
- Locked-down remote access and firewall
- A documented threat model covering a lost or captured node

Each measure will be documented with how to reproduce it and its cost in performance or power.

## Roadmap

This is an exploration project developed alongside engineering studies and an
apprenticeship, so it advances in phases rather than on fixed dates.

**Phase 1 — Benchmarks (first)**
Set the board up, run the four engines on French and English samples, and publish
accuracy, latency, memory and power figures with the scripts used to produce them.

**Phase 2 — Hardening**
Document the threat model and the hardening measures, with their cost in
performance and power.

**Phase 3 — Mesh integration (from January 2027)**
Wire the transcription output into a mesh transport — Meshtastic, and possibly
MeshCore — for a first end-to-end prototype.

Results are shared on the Radxa forum as each phase completes.

## License

MIT — see [LICENSE](LICENSE).
