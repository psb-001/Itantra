# TTS voice selection — code audit (read-only)

Scope: `app/app/src/main/java/com/bjp/itantra/`. Audit only; no source files modified.  
Companion (older, not shipped) implementation: `app/LanguageFilter.kt`.

## 1. Where `langTag` is set, and whether any UI lets the user say "English"

`langTag` exists only as a field on `MainActivity`, initialised once and never reassigned in any interactive or capture flow:

- `app/app/src/main/java/com/bjp/itantra/MainActivity.kt:137-140`
  ```kotlin
  /** SPEAKER = ... LISTENER = ... */
  @Volatile
  // No speaker/listener role. Both phones load both models and can talk or receive.
  private var langTag = com.bjp.itantra.speech.Lang.HINDI
  ```
  Default value is `Lang.HINDI` (`"hin_Deva"`, `app/app/src/main/java/com/bjp/itantra/speech/SpeechEngine.kt:49`).

- There is **no UI that changes it**. `showSettings()` (`MainActivity.kt:930`) only shows connection/transport/device/log state and the Laya triage toggle. Searching the layouts and `strings.xml` finds settings for Bluetooth devices and triage, but no language spinner/selector for “I am speaking English”. Every fresh install therefore stays on Hindi for the life of the process.

`langTag` is *shadowed* by a local variable in two test-only code paths; those are the only places a non-default tag is derived:

- **Pipeline test ("WAV/test file" path)** — `MainActivity.kt:352-408`, tag derived at `:361`:
  ```kotlin
  val langTag = if (name.contains("_hi")) Lang.HINDI else Lang.ENGLISH
  ```
  used at `:387` (`LanguageFilter.filter(raw, langTag)`) and `:389` (`lang = langTag`).

- **Loopback test** — `MainActivity.kt:420-517`, same derivation at `:430`, used at `:444` and `:452`, and on the receive side the voice is chosen per packet at `:480`:
  ```kotlin
  val voice = TtsEngine.voiceFor(received.lang)
  ```

- **Benchmark** — `MainActivity.kt:260-341` loads clips (`bench_00_en.wav`, `bench_01_en.wav`, `bench_02_hi.wav`, `:263`), transcribes with ASR, but performs **no `LanguageFilter` step and uses a hard-coded English TTS voice** (`:309` `TtsEngine(this, TtsEngine.ENGLISH, ...)`). It does not exercise voice matching against the decoded text.

- **Live capture / mic path** — uses the outer field `langTag` (`:814` QualityGate, `:839` filter, `:857` packet `lang`), i.e. **Hindi for every user, including a user who is speaking English**, because there is no selector.

## 2. Live-carried Latin-script English: `LanguageFilter.filter` and the speaking voice

With the default `langTag = "hin_Deva"`, `rangesFor(langTag)` (`LanguageFilter.kt:118-121`) returns the Devanagari ranges **plus** `LATIN_RANGES` (line 90-92):

```kotlin
private fun rangesFor(langTag: String): Array<IntRange>? {
    val expected = EXPECTED[langTag] ?: return null
    return expected + LATIN_RANGES
}
```

- `LanguageFilter.kt:104-115`: `EXPECTED["hin_Deva"]` is the Devanagari range.
- `LanguageFilter.kt:280-298`: `filter()` computes `foreignCount(text, langTag)`. Latin letters are in range, so an all-Latin English utterance has `removed = 0` and returns:
  ```kotlin
  Result(send = true, text = tidy(text), fraction = 1f, removed = 0)
  ```
  **The segment is SENT**, not dropped. Latin loanword allowance (intended for numerals/loanwords) becomes a hole through which whole English sentences pass under a Hindi tag.

- The packet is built at `MainActivity.kt:856-861` with `lang = langTag` = `"hin_Deva"`, even though the text is Latin English.

- On the **receiving** phone, `onPacket` (`MainActivity.kt:873-918`) does **not** re-select a voice from `received.lang`. It uses the one TTS already loaded:
  - `MainActivity.kt:632` `val voice = TtsEngine.voiceFor(langTag)` (local default → Hindi),
  - `MainActivity.kt:647` `tts = TtsEngine(this@MainActivity, voice, ...)`,
  - `MainActivity.kt:883` `val engine = tts`,
  - `MainActivity.kt:904` `val samples = engine.synthesise(msg.text)`.

  So Latin English text is spoken by the **Hindi Piper voice** (`hi_IN-pratham-medium`). This is the text-vs-voice mismatch for the live path.

## 3. Devanagari-transliterated English ("हैलो आई एम फाइन")

- Under `langTag = "hin_Deva"`, `LanguageFilter.filter` sees only Devanagari→passes (`foreignCount = 0`, `send = true`, `MainActivity.kt:839-854`). The packet carries `lang = "hin_Deva"` and the Devanagari text.
- Nothing downstream detects that the string is English-derived. `onPacket` (`MainActivity.kt:883-915`) synthesises `msg.text` with the locally loaded (default Hindi) voice and pipes it to `Speaker` (`:915`). The optional Laya triage (`:900-902`) only may raise NORMAL→ALERT priority; it does not relabel language.
- Effectively treated as **Hindi only**: it survives only because it is in the Hindi expected script, it is tagged `hin_Deva`, and it is played through the Hindi voice. (Note: if a second device ever had `langTag = Lang.ENGLISH`, `LanguageFilter.filter(raw, "eng_Latn")` would drop the same Devanagari string as foreign — there is no transliteration tolerance. That path cannot occur through any UI today, only through the bench/loopback hard-coding in Q1.)

## 4. Other paths where text can be spoken by a mismatched/unavailable voice

A. **Live receive uses one global voice for all messages.**  
   `MainActivity.kt:883` `val engine = tts` ignores `received.lang`. If two phones genuinely used different `langTag`s, every incoming message would still be spoken in the *recipient's* selected voice, not the sender's declared language. Combined with Q1, in practice the recipient's `langTag` is also the frozen Hindi default, so the receiver speaks Hindi voice for everything it receives.  
   `onPacket` emits no per-message voice-selection log (no `TtsEngine.voiceFor(...)` call) — unlike loopback (`:480`), so a phone log cannot show voice selection on the live receive path.

B. **No voice bundled for the tag → text-only, silent.**  
   `TtsEngine.voiceFor()` (`SpeechEngine.kt:364-373`) returns `null` for `guj_Gujr`, `kan_Knda`, `ory_Orya` and any unknown/typo tag. At load time (`MainActivity.kt:632-648`) `tts` stays `null` and the app logs:
   - `MainActivity.kt:639` `log("no TTS voice bundled for $langTag — this language is TEXT-ONLY")`
   - `MainActivity.kt:640` `log("  voices bundled for: $spoken")`
   `onPacket` then shows the text with no audio (`:884-887`, UI append `"▸ ${msg.text}   (no voice loaded)"`). Only inner/outer langTag paths can trigger this; today the default Hindi has a voice.

C. **`LanguageFilter` with a null/unknown tag falls back to "all nine scripts"** (`LanguageFilter.kt:119,146,183,208,217,232`): `EXPECTED[langTag] ?: return null`, and call sites do `langTag?.let { rangesFor(it) } ?: ALLOWED_RANGES`.  
   - Null tag → `ALLOWED_RANGES` (`:68-87`, Latin + all nine scripts).  
   - An unknown non-null tag → `rangesFor` returns null → same all-script fallback.  
   Consequence: a forgotten `langTag` (or a packet/recognised tag not in `EXPECTED`) accepts **any** in-vocabulary script, including the wrong Indic script, and `expectedScriptPurity(..., unknown)` (`:182`) measures against `ALLOWED_RANGES` (so it reports high). `QualityGate.evaluate` with `strictScript=true` does still check foreign letters against those ranges (`:175`), so it only catches scripts outside the nine, not in-family flips. This is documented on `LanguageFilter.kt:273-276` as the reason a tag is "strongly recommended".

D. **Malformed/missing packet language defaults to Hindi.**  
   `Message.kt:70-76` `fromJson()` uses `o.optString("lang", "hin_Deva")`, so a packet without `lang` is assumed Hindi on the receiver (and in loopback would be spoken with the Hindi voice at `MainActivity.kt:480`).

E. **Loopback differs from live.**  
   The loopback path does call `TtsEngine.voiceFor(received.lang)` (`MainActivity.kt:480`) and skips playback with:
   - `MainActivity.kt:482` `Log.i(TAG, "LOOP   no bundled voice for ${received.lang}")`
   This makes loopback look correct while the live `onPacket` path does not perform that selection; do not assume a green loopback run means a green live receive.

## 5. Exact log lines emitted in each case

Values are the string templates actually passed to `log(...)` / `Log.i(TAG, ...)` (all also go to `FileLogger`). Quotes below are the literal source templates.

Live capture (mic), whole app uses tags `iTantra`, messages from `log()` are also printed `Log.i(TAG, msg)` at `MainActivity.kt:92`:

- Segment entry: `MainActivity.kt:794`  
  `segment $n (${segment.size} samples) but ASR not loaded — is this phone in LISTENER mode?` only when `asr == null`; otherwise  
  `segment $n: ${segment.size} samples = ${"%.2f".format(segment.size / 16000.0)}s -> recognising`
- ASR result: `:802` `ASR ${ms}ms -> "${raw.take(48)}"` and `:803` `  segment ${"%.2f".format(seconds)}s  rms ${"%.5f".format(level)}`
- Physical gate drops: `:821` `SILENT (${gate.reason}) — ${"%.2f".format(seconds)}s rms=${"%.5f".format(level)}`; `:825` `REPEAT (${gate.reason}) — asking the sender to say it again`; empty `:833` `ASR returned empty — nothing to send`
- Script filter drop (the broken/wrong-script case): `:847-850`
  `DROPPED whole segment: ${verdict.removed} foreign char(s) [${culprits}] fraction=${verdict.fraction}`  
  where `culprits` is produced at `:844-846` as `Letter(U+XXXX)` joined by spaces.
- Send: `:863` `packet ${bytes.size} B -> transport`; UI shows `▸ ${verdict.text}` (`:865`) and status `Sent ${bytes.size} B` (`:866`).
- Flush on release: `:774` `stopped; VAD flushed, segments this hold = $segmentsThisHold`
- Model load with no voice: `:639` `no TTS voice bundled for $langTag — this language is TEXT-ONLY`, `:640` `  voices bundled for: $spoken`

Live receive (`onPacket`): `MainActivity.kt:873-918`

- `:875` `Log.w(TAG, "dropped malformed packet")`
- `:879` `Log.i(TAG, "duplicate ${msg.id} — not replaying")`
- `:885` UI only, no log: `▸ ${msg.text}   (no voice loaded)`
- `:891` UI only: `▸ ${msg.text}   (stale, not announced)`
- `:902` (if triage enabled) `triage ${msg.id.take(8)}: ${triage.describe()}`
- On playback UI: `:910` appends `(ALERT ▸) ...` text and sets status `Speaking (...)`/`Speaking (ALERT — triage)` (`:911-914`).  
  There is **no** `TtsEngine.voiceFor(...)` or `no bundled voice for ...` log on this path, so live receive cannot be told apart by voice-selection logging.

Test paths:

- Pipeline: `:355` `PIPE === full speaker pipeline test ===`; `:376` `PIPE $name: ...s audio -> ... segments before flush, ... after (+... from flush)`; `:394-399` `PIPE   seg$idx ...s ...ms send=${verdict.send} script=${"%.2f".format(verdict.fraction)} packet=${msg.toBytes().size}B raw="${raw.take(40)}"`; `:404` `PIPE === done ===`; failure `:406` `PIPE FAILED` (Log.e).
- Loopback: `:423` `LOOP === loopback test: speaker side then listener side ===`; `:446` `LOOP $name seg$idx DROPPED (script=${verdict.fraction})`; `:458` `LOOP $name seg$idx SENT ${wire.size} B "${verdict.text.take(36)}"`; `:463` `LOOP   FAIL: packet did not deserialise`; `:467` `LOOP   FAIL: text changed in transit`; `:471` `LOOP   duplicate suppressed (correct)`; `:475` `LOOP   stale, not announced (correct)`; `:482` `LOOP   no bundled voice for ${received.lang}`; `:490-494` `LOOP   TTS $synthMs ms -> ${samples.size} samples (...)`; `:504-508` `LOOP   playback started mode=$mode isPlaying=${player.isPlaying()}`; `:512` `LOOP   seg$idx COMPLETE`; `:517` `LOOP === done ===`; failure `:519` `LOOP FAILED`.
- Benchmark: `:266` `BENCH === on-device latency benchmark ===`; `:267` device line; `:276` `BENCH ASR model load: ${asrLoadMs} ms`; `:294-299` `BENCH ASR ... dur=...s median=...ms ... RTF=...`; `:300` `BENCH   text: ...`; `:302-304` totals; `:311` `BENCH TTS voice load: ... ms  sampleRate=...`; `:323-324` `BENCH TTS synth=...ms audio=...s RTF=...`; `:326-327` totals; `:332-334` heap; `:335` `BENCH === done ===`; `:337` `BENCH FAILED`.

## Most likely mechanism for broken English over the live path

The live path never receives a language choice: `langTag` is fixed to `Lang.HINDI` at `MainActivity.kt:140` and no UI changes it (`showSettings`, `MainActivity.kt:930`), so an English speaker is processed as if Hindi. Worse, `LanguageFilter.kt:280-298` accepts the result because `rangesFor("hin_Deva")` adds `LATIN_RANGES` (`:118-121`) to the Devanagari expected script, so whole Latin-English utterances pass with `remove = 0` instead of being dropped (Latin was allowed for numerals/loanwords, which lets complete English through). Those sentences are then packed as `lang = "hin_Deva"` (`MainActivity.kt:856-861`) and on the other phone `onPacket` (`MainActivity.kt:883-915`) synthesises them with whatever TTS was loaded at startup from `TtsEngine.voiceFor(local langTag)` (`:632`), i.e. the Hindi voice (`hi_IN-pratham-medium`) since the frozen default is Hindi — there is no `TtsEngine.voiceFor(received.lang)` call in the live receive path (unlike the unused loopback path at `:480`). Hence English text gets read aloud by the Hindi voice, which mis-pronounces it; the log signature of such a report is an `ASR … -> "…"` line containing Latin letters followed by `packet … B -> transport`, with no `DROPPED whole segment` line in between, and the receive side shows plain `▸ <text>` playback with no `voiceFor`/`LOOP` logging. If the ASR instead emits Devanagari-transliterated English, it likewise passes and is spoken as Hindi, because the filter only checks script, not language, and nothing downstream relabels it.
