# English TTS voice samples - LISTEN AND DECIDE BY EAR

Generated entirely in `/tmp`. The project repo was not modified.

## The question this set exists to answer

The project ships Piper `en_US-lessac-medium` and the team says its English
*"pronunciation is not good enough"*. An ASR round-trip CER test could not
confirm any candidate is more intelligible - every candidate scored 0.007-0.049
CER on 20 sentences, which is noise. **CER cannot hear naturalness, accent, or
sibilance.** So this set is for a human to listen to. Pick the voice that sounds
like a person telling you to get out of a building.

## Start here

**`COMPARE_sentence1__lessac-medium__kokoro-bf_emma__amy-medium__kristin-medium__cori-medium.wav`** - sentence 1, all 5 voices back-to-back, 0.7 s of silence
between each. This is the single A/B file. Voice start offsets:

| offset | voice |
|---|---|
|  0.00 s | Piper `en_US-lessac-medium` |
|  4.37 s | Kokoro `v0.19` int8, speaker `bf_emma` |
|  9.15 s | Piper `en_US-amy-medium` |
| 14.33 s | Piper `en_US-kristin-medium` |
| 19.17 s | Piper `en_GB-cori-medium` (British) |

Then work down the per-sentence `compare_0N_*.wav` files, especially s4 and s6.

## What to listen for

1. **Number reading - the thing CER hides.** In s4 the correct reading of
   *"Call one one two on the emergency line"* is **one - one - two** (three separate
   digits, the Indian emergency number). Listen for a voice that instead says
   *"one hundred twelve"*, or collapses it to *"one twelve"*, or slurs it into a
   single token. In s6, *"Two hundred"* must be two words with a stressed HUN-
   dred - not *toohun-dred* run together. **A voice that gets a phone number wrong
   in a distress app is disqualified regardless of how pleasant it sounds.**
2. **Regional word fit - is *lift* and *elevator* natural?** s2 says *lift* (British),
   s5 says *elevator* (American). A British voice reading *elevator* is fine but
   often slightly formal; an American voice reading *lift* can land as a spelling-out
   or a wrong-accented word. Listen for either word being pronounced as if the model
   had never heard it in context. This is a naturalness tell that CER cannot see,
   because ASR transcribes it correctly either way.
3. **Sibilance - the *s* and *sh* test.** Sentences 1, 2 and 5 are full of *safe*,
   *stairs*, *is*, *use*, *push*-adjacent stops. Listen on a phone speaker with bass
   rolled off, not headphones. A harsh voice that gets fatiguing after three
   repetitions is worse in this app than a slightly dull one, because users may hear
   the same alert many times a day. Piper *medium* models commonly have a lisping or
   watery *s*; that is the single most common complaint about this voice family.
4. **Newsreader or robot?** Judge which one it is: a calm human **newsreader** (a
   steady, slightly authoritative delivery - the target for emergency messaging), a
   **chatty assistant** (pitched up, over-bright, sounds like a phone navigation
   voice), or a **robot** (flat pitch, no sentence-final fall, every sentence identical
   in shape). Check s3 alone (*Water is rising. Move to the upper floor now.*) - it is
   short, and you will hear immediately whether the pitch actually falls at the end.
   Also check the **junctions**: Piper models often put a small audible seam or a
   breath between the two sentences of s1 and s5.

## The voices

| # | voice | role | sid | sample rate | model size |
|---|---|---|---|---|---|
| 1 | Piper `en_US-lessac-medium` | CURRENT BASELINE | 0 | 22050 Hz | 60.3 MB |
| 2 | Kokoro `v0.19` int8, speaker `bf_emma` | BEST CANDIDATE | 7 | 24000 Hz | 128 MB |
| 3 | Piper `en_US-amy-medium` | alternative | 0 | 22050 Hz | 60.3 MB |
| 4 | Piper `en_US-kristin-medium` | alternative | 0 | 22050 Hz | 60.3 MB |
| 5 | Piper `en_GB-cori-medium` (British) | alternative | 0 | 22050 Hz | 60.3 MB |

Kokoro speaker id that worked: **7** (`bf_emma`).
Kokoro exposes 11 speakers (`num_speakers=11`, ids 0-10); id 7 loaded and
synthesised audibly on the first try, so no fallback id was needed.

## Speed - READ THIS BEFORE CHOOSING KOKORO

RTF = real-time factor = seconds of wall time per second of audio. **RTF above 1.0
means it cannot keep up with playback in real time.** Measured on this machine
(MacBook Pro 18,1, 10 cores, M1 Pro, 16 GB) after a warm-up call, averaged over all
6 sentences.

| voice | RTF @4 threads | RTF @1 thread | worst single sentence @1 thread | model load |
|---|---|---|---|---|
| Piper `en_US-lessac-medium` | 0.033 | 0.086 | 0.089 | 0.41 s |
| Kokoro `v0.19` int8, speaker `bf_emma` | 0.762 | 0.987 | 1.067 | 0.48 s |
| Piper `en_US-amy-medium` | 0.032 | 0.085 | 0.087 | 0.50 s |
| Piper `en_US-kristin-medium` | 0.032 | 0.085 | 0.091 | 0.56 s |
| Piper `en_GB-cori-medium` (British) | 0.034 | 0.087 | 0.090 | 0.56 s |

### Flag: Kokoro is borderline real-time at 1 thread, and 2x the model size

- **Kokoro is ~23x slower than every Piper
  voice.** The four Piper models are interchangeable on speed (RTF 0.03 @4 threads,
  0.09 @1 thread) - they are all the same architecture at the same tier. Kokoro is
  the only one where speed is a real variable.
- **At 1 thread, Kokoro averaged RTF 0.987 - and its worst single sentence hit
  1.067, i.e. it actually crossed into non-real-time on at least one of these
  six sentences.** Repeated 3x for stability: 0.990, 0.985, 0.988. So it is genuinely
  marginal, not a noisy measurement.
- **This machine is not a weak phone.** An M1 Pro core is several times faster than a
  budget ARM core, and this test used the FASTEST of its 10 cores. A mid/low-range
  Android device at 1 thread should be assumed to land **well above RTF 1.0** for
  Kokoro. On a mid-range phone a distress alert would start speaking several seconds
  late. **If you pick Kokoro, budget for 4 threads on a fast device, or pre-generate
  audio, and re-measure on real hardware - this number does not transfer.**
- **Kokoro also costs 128 MB vs 60.3 MB**, a 68 MB increase to the APK for English
  alone, in exchange for quality that **no measurement in this repo has confirmed**.
  That is the real trade: certain speed/size cost, unproven benefit.
- Practical consequence: **the four Piper voices are a free upgrade** on speed - they
  change nothing about the compute budget. Switching lessac -> amy, kristin or cori is
  a pure listen-and-choose decision. Switching to Kokoro is not; it is a
  performance-and-size decision that happens to include a listen-and-choose part.

## Files

All 30 per-voice WAVs are **16-bit mono PCM** at each model's native sample
rate, unnormalised (levels are as the model emits them). Compare files are
**48 kHz** (all voices upsampled to a common rate so they can be concatenated without
resampling artefacts) with each segment peak-normalised to -1 dBFS, so a loud voice
does not sound like a better one.

### Compare files (one sentence, every voice, 0.7 s silence between)

| file | sentence | length |
|---|---|---|
| `COMPARE_sentence1__lessac-medium__kokoro-bf_emma__amy-medium__kristin-medium__cori-medium.wav` | s1 | 23.2 s |
| `compare_02_third-floor-lift__lessac-medium__kokoro-bf_emma__amy-medium__kristin-medium__cori-medium.wav` | s2 | 22.8 s |
| `compare_03_water-is-rising__lessac-medium__kokoro-bf_emma__amy-medium__kristin-medium__cori-medium.wav` | s3 | 17.8 s |
| `compare_04_one-one-two__lessac-medium__kokoro-bf_emma__amy-medium__kristin-medium__cori-medium.wav` | s4 | 15.5 s |
| `compare_05_do-not-use-elevator__lessac-medium__kokoro-bf_emma__amy-medium__kristin-medium__cori-medium.wav` | s5 | 18.7 s |
| `compare_06_two-hundred-station__lessac-medium__kokoro-bf_emma__amy-medium__kristin-medium__cori-medium.wav` | s6 | 16.9 s |

### Per-voice WAVs, `NN-voice__sN.wav` - 16-bit mono PCM, native sample rate

| sentence | text | what it probes |
|---|---|---|
| s1 | This is an emergency. Please move to a safe location immediately. | neutral opener, no numbers |
| s2 | The fire is on the third floor. Use the stairs, not the lift. | ordinal **third floor** + British **lift** |
| s3 | Water is rising. Move to the upper floor now. | short - tests pace and final-falling |
| s4 | Call one one two on the emergency line. | **DIGITS read separately**: one one two, or wrongly one hundred twelve? |
| s5 | The building is on fire. Do not use the elevator. | American **elevator** (contrast s2's lift) |
| s6 | Two hundred people are waiting outside the station. | **WORD-NUMBER** Two hundred + place name **the station** |

| file | voice | s1 | s2 | s3 | s4 | s5 | s6 | total |
|---|---|---|---|---|---|---|---|---|
| `01-lessac-medium__s1.wav` … `_s6.wav` | Piper `en_US-lessac-medium` | 3.67 | 3.46 | 2.82 | 2.30 | 2.82 | 2.61 | **17.68** |
| `02-kokoro-bf_emma__s1.wav` … `_s6.wav` | Kokoro `v0.19` int8, speaker `bf_emma` | 4.08 | 3.71 | 2.89 | 2.42 | 3.14 | 2.65 | **18.89** |
| `03-amy-medium__s1.wav` … `_s6.wav` | Piper `en_US-amy-medium` | 4.48 | 4.40 | 3.41 | 2.75 | 3.81 | 3.15 | **22.00** |
| `04-kristin-medium__s1.wav` … `_s6.wav` | Piper `en_US-kristin-medium` | 4.14 | 4.06 | 3.16 | 2.83 | 3.19 | 2.75 | **20.14** |
| `05-cori-medium__s1.wav` … `_s6.wav` | Piper `en_GB-cori-medium` (British) | 3.99 | 4.36 | 2.71 | 2.36 | 2.97 | 2.92 | **19.30** |

Durations are seconds of audio. Read them with the repeatability caveat below -
the Piper numbers move by several percent between takes.

Duration is itself a signal. *amy* is the slowest voice on most sentences (22.00 s for the six vs lessac's 17.68 s, a 24% gap), which usually means a slower, more deliberate delivery. If you
want urgency rather than gravity, that cuts against it.

## How these were made

`make_samples.py` drives `_gen_child.py`, one subprocess per (voice, num_threads),
following the convention in `eval/tts_smoke.py` - sherpa-onnx aborts the whole
interpreter natively on a bad model, so sharing a process would report only the
first failure. Every voice ran against the **same trimmed espeak-ng data dir the app
ships** (`app/app/src/main/assets/tts/espeak-ng-data`, 17 entries), so phonemisation is
identical across voices and any difference you hear is the model, not the front end.
Kokoro phonemises fine off that trimmed dir - `en_dict` is present - so it needs no
espeak change.

Every WAV was checked for audible output, not merely absence of an exception: all
30 passed an RMS > 0.01 and duration > 0.5 s gate. 0 silent.

### These samples faithfully represent the shipped baseline

The app ships `en_US-lessac-medium.onnx` at IR 8 / opset 15; the upstream sherpa-onnx
release is IR 9 / opset 17, and the two files have different SHA-256 despite identical
size. So they are *not* the same bytes, and I checked whether that matters rather than
assuming: **all 396 shared weight tensors are bit-identical** (max absolute difference
exactly 0.0). The app copy carries 5 extra initializers, all of them folded constants
(0.5, sqrt(2), 1e-05, two int64 shape arrays) from `onnxsim`-style constant folding -
no weights. **Same voice, different export.** Sample `01-lessac-medium__sN.wav` is
therefore an accurate listen-to-what-we-ship-today file.

## One caveat that matters: Piper output is not reproducible

Regenerating the *same* sentence from the *same* model twice does not give the same
audio. Piper VITS samples a stochastic duration/noise prediction per call, so each
take differs in waveform and in length:

| voice | 3 identical takes of sentence 1 (s) | spread |
|---|---|---|
| Piper `en_US-lessac-medium` | 3.60 , 3.37 , 3.35 | 0.24 s (7.1%) |
| Kokoro `v0.19` int8, speaker `bf_emma` | 4.07 , 4.08 , 4.08 | 0.00 s (0.1%) |
| Piper `en_US-amy-medium` | 4.64 , 4.42 , 4.51 | 0.23 s (5.0%) |
| Piper `en_US-kristin-medium` | 4.24 , 4.00 , 4.17 | 0.23 s (5.6%) |
| Piper `en_GB-cori-medium` (British) | 4.17 , 4.02 , 4.03 | 0.15 s (3.8%) |

Three consequences for how you use this set:

- **Do not over-read small duration differences between the Piper voices.** A gap under
  ~7% is noise. amy at 22.00 s vs lessac at 17.68 s over the six sentences is a 24% gap,
  3x the jitter, so *that* one is real.
- **Kokoro is deterministic** (0.1% spread) - repeated calls reproduce the same audio
  byte-for-byte. That is a genuine engineering advantage over Piper: output is
  cacheable, and a bug report is reproducible. A model instance seeds its RNG at load,
  so a fresh instance re-rolls it, but one instance always gives the same take.
- **Nobody can re-roll to get a 'better take'.** If a Piper sample here happens to
  have a stumble, that stumble is not in the voice - re-synthesise and it changes.
  Judge the voice, not the take. Kokoro is the exception: what you hear is exactly
  what you will get.

## Loudness differs a lot, and the compare files hide it

Measured peak amplitude of sentence 1, as each model emits it:

| voice | peak | note |
|---|---|---|
| Piper `en_US-lessac-medium` | 0.746 | loudest |
| Piper `en_US-amy-medium` | 0.638 |  |
| Kokoro `v0.19` int8, speaker `bf_emma` | 0.515 |  |
| Piper `en_US-kristin-medium` | 0.489 |  |
| Piper `en_GB-cori-medium` (British) | 0.354 |  |

The compare files are peak-normalised to -1 dBFS, so this is invisible there *by
design* - otherwise the loudest voice would always sound like the best one. But it is
real: on a phone speaker in a noisy street, cori is the one most likely to be missed,
and that is a safety property, not a quality one. If you pick cori or kokoro, budget
for gain in the app.

## Reproduce with:

```
cd /tmp/voice_samples && /Users/prathameshbhujbal/iTantra-SIH26173/.venv/bin/python make_samples.py
/Users/prathameshbhujbal/iTantra-SIH26173/.venv/bin/python make_listen_md.py
```
