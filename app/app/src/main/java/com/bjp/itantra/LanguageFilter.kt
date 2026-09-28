package com.bjp.itantra

/**
 * Restricts ASR output to the ten languages SIH26173 requires.
 *
 * WHY: the Omnilingual ASR model is a 1,600-language character-level CTC model. Its
 * tokenizer carries CJK (5,750 tokens), Hangul (1,220), Ethiopic, Arabic, Greek,
 * Cyrillic and more. The API exposes no language parameter:
 *
 *     OfflineRecognizer.fromOmnilingualAsrCtc(model, tokens, numThreads, ...)
 *
 * So it transcribes whatever it hears in whatever script fits best, and because it is
 * character-level it will substitute a *phonetically identical* character from an unrelated
 * script — "hello" comes back as "هello" (Arabic heh), on completely clean audio.
 *
 * TWO LAYERS STOP THIS, and both are required:
 *
 *  1. **The model is shipped with its output vocabulary pruned** to the nine target scripts
 *     (`eval/prune_vocab.py`, 10,288 -> 1,002 tokens). It therefore *cannot* emit Arabic, CJK or
 *     Hangul at all. Measured effect: Hindi script retention under pink noise at 15 dB SNR rose
 *     from 40% to 78%, and clean-audio Hindi from 94% to 100%. Nine of the ten languages scored
 *     bit-identical CER; Hindi improved from 0.113 to 0.096.
 *
 *  2. **This filter drops anything that still comes back in the wrong script.** Pruning cannot
 *     prevent confusion *within* the target set — Hindi arriving in Gujarati, both in-vocabulary
 *     — so the filter enforces the expected script for the language the user selected.
 *
 * Every required language uses exactly one Unicode script:
 *
 *     Hindi, Marathi -> Devanagari      Kannada   -> Kannada
 *     Gujarati       -> Gujarati        Malayalam -> Malayalam
 *     Tamil          -> Tamil           Telugu    -> Telugu
 *     Odia           -> Oriya           Bengali   -> Bengali
 *     English        -> Latin
 *
 * The filter is STRICT: one foreign letter drops the whole segment. It does not delete the
 * offending character and transmit the remainder, because doing that shipped "ello" and
 * "help e" as live emergency text. See [filter].
 *
 * HONEST LIMITATION: this stops a wrong *script*, not a wrong *language*. A Hindi speaker
 * misrecognised as Bengali passes, because both scripts are allowed. Distinguishing those
 * needs a language-ID model, which is out of scope.
 *
 * Implemented with code-point ranges rather than Character.UnicodeScript so it works
 * below API 24.
 *
 * Usage in the ASR callback:
 *
 *     val r = LanguageFilter.filter(recognizerText, langTag)
 *     if (r.send) transport.send(r.text, langTag)
 *     else Log.i(TAG, "dropped segment: ${r.removed} foreign chars")
 */
object LanguageFilter {

    /**
     * RETIRED. Do not reintroduce a fraction threshold.
     *
     * The rule used to be "at least THRESHOLD of the letters must be in an allowed script".
     * That is what let "help مe" (5 of 6 letters fine) through as the transmitted text "help e".
     * The rule is now absolute: zero foreign letters, or the segment is dropped.
     *
     * [allowedFraction] is still computed and returned, but only as a diagnostic for logging —
     * nothing branches on it any more.
     */
    const val THRESHOLD = 0.60f

    /** Inclusive code-point ranges for the nine scripts covering all ten languages. */
    private val ALLOWED_RANGES: Array<IntRange> = arrayOf(
        // Latin — English (basic, Latin-1 supplement, Latin extended-A/B)
        0x0041..0x005A, 0x0061..0x007A, 0x00C0..0x024F, 0x1E00..0x1EFF,
        // Devanagari — Hindi, Marathi
        0x0900..0x097F, 0xA8E0..0xA8FF,
        // Bengali — Bengali
        0x0980..0x09FF,
        // Gujarati
        0x0A80..0x0AFF,
        // Oriya — Odia
        0x0B00..0x0B7F,
        // Tamil
        0x0B80..0x0BFF,
        // Telugu
        0x0C00..0x0C7F,
        // Kannada
        0x0C80..0x0CFF,
        // Malayalam
        0x0D00..0x0D7F,
    )

    /** Latin, allowed alongside any expected script for loanwords and numerals. */
    private val LATIN_RANGES: Array<IntRange> = arrayOf(
        0x0041..0x005A, 0x0061..0x007A, 0x00C0..0x024F, 0x1E00..0x1EFF,
    )

    /**
     * The script each language MUST produce, keyed by the ASR model's own language tags.
     *
     * THIS IS THE IMPORTANT PART. Measured on 20 Sep 2026 (see NOISE_ROBUSTNESS_FINDINGS.md):
     * under mild additive noise the model flips script — a Hindi utterance can come back in
     * Devanagari, Arabic or Gujarati. Allowing "any of the nine required scripts" would let a
     * Hindi→Gujarati flip through, and the recipient could not read it.
     *
     * Because the app has a language selector, the expected script is known. Enforce it.
     */
    private val EXPECTED: Map<String, Array<IntRange>> = mapOf(
        "hin_Deva" to arrayOf(0x0900..0x097F, 0xA8E0..0xA8FF),   // Hindi
        "mar_Deva" to arrayOf(0x0900..0x097F, 0xA8E0..0xA8FF),   // Marathi
        "ben_Beng" to arrayOf(0x0980..0x09FF),                    // Bengali
        "guj_Gujr" to arrayOf(0x0A80..0x0AFF),                    // Gujarati
        "ory_Orya" to arrayOf(0x0B00..0x0B7F),                    // Odia
        "tam_Taml" to arrayOf(0x0B80..0x0BFF),                    // Tamil
        "tel_Telu" to arrayOf(0x0C00..0x0C7F),                    // Telugu
        "kan_Knda" to arrayOf(0x0C80..0x0CFF),                    // Kannada
        "mal_Mlym" to arrayOf(0x0D00..0x0D7F),                    // Malayalam
        "eng_Latn" to LATIN_RANGES,                               // English
    )

    /** Ranges that count as valid for a given language tag (expected script + Latin). */
    private fun rangesFor(langTag: String): Array<IntRange>? {
        val expected = EXPECTED[langTag] ?: return null
        return expected + LATIN_RANGES
    }

    data class Result(
        val send: Boolean,
        val text: String,
        val fraction: Float,
        val removed: Int,
    )

    private fun inRanges(cp: Int, ranges: Array<IntRange>): Boolean {
        for (r in ranges) {
            if (cp in r) return true
        }
        return false
    }

    /** True for characters that count toward the score (letters only). */
    private fun isLetter(cp: Int): Boolean = Character.isLetter(cp)

    /**
     * Fraction of the text's letters that are in an allowed script.
     * @param langTag when supplied, only that language's expected script (plus Latin) counts.
     *                When null, any of the nine required scripts counts.
     */
    fun allowedFraction(text: String, langTag: String? = null): Float {
        val ranges = langTag?.let { rangesFor(it) } ?: ALLOWED_RANGES
        var total = 0
        var allowed = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isLetter(cp)) {
                total++
                if (inRanges(cp, ranges)) allowed++
            }
            i += Character.charCount(cp)
        }
        return if (total == 0) 0f else allowed.toFloat() / total
    }

    /**
     * REMOVED: `clean(text, langTag)` used to strip foreign characters out of an accepted
     * transcript. Do not reintroduce it.
     *
     * Deleting characters from an emergency message converts a detectable error into an
     * undetectable wrong answer. See the doc comment on [filter] for the measured case
     * ("هello" -> "ello") and [tidy] for the whitespace-only replacement.
     */

    /**
     * Share of the text's letters that are in the language's OWN script, excluding Latin.
     *
     * Added for [QualityGate]. [allowedFraction] cannot do this job: it deliberately counts Latin
     * as allowed in every language, because numerals and loanwords are legitimate. That makes it
     * useless as a diagnostic — a decode consisting entirely of Latin letters when Gujarati was
     * selected still scores 1.0.
     *
     * This measures the expected script alone, so it actually falls when the decoder has wandered.
     * Latin is not penalised here; it is simply not counted as evidence that we guessed right.
     * The gate's threshold (0.75) was chosen against this measure, not against [allowedFraction].
     */
    fun expectedScriptPurity(text: String, langTag: String? = null): Float {
        val expected = langTag?.let { EXPECTED[it] } ?: ALLOWED_RANGES
        var total = 0
        var right = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isLetter(cp)) {
                total++
                if (inRanges(cp, expected)) right++
            }
            i += Character.charCount(cp)
        }
        return if (total == 0) 0f else right.toFloat() / total
    }

    /**
     * True when [ch] is a letter the given language is allowed to produce.
     *
     * Public so the UI can name the offending characters when it drops a segment. A drop
     * message that just says "dropped" is unactionable — you cannot tell a model failure from
     * a filter bug without knowing which characters were rejected.
     */
    fun isAllowed(ch: Char, langTag: String? = null): Boolean {
        val cp = ch.code
        if (!isLetter(cp)) return true // digits, punctuation and spaces are always fine
        val ranges = langTag?.let { rangesFor(it) } ?: ALLOWED_RANGES
        return inRanges(cp, ranges)
    }

    /** The distinct letters in [text] that [langTag] would not allow. For diagnostics. */
    fun foreignLetters(text: String, langTag: String? = null): String {
        val sb = StringBuilder()
        for (ch in text) {
            val cp = ch.code
            if (isLetter(cp) && !inRanges(cp, langTag?.let { rangesFor(it) } ?: ALLOWED_RANGES)
                && sb.indexOf(ch.toString()) < 0
            ) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** True when [text] contains any letter the given language may not produce. */
    fun hasForeignLetters(text: String, langTag: String? = null): Boolean =
        foreignCount(text, langTag) > 0

    /** Count letters that [filter] would reject. Zero means the segment is clean. */
    private fun foreignCount(text: String, langTag: String? = null): Int {
        val ranges = langTag?.let { rangesFor(it) } ?: ALLOWED_RANGES
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isLetter(cp) && !inRanges(cp, ranges)) n++
            i += Character.charCount(cp)
        }
        return n
    }

    /**
     * Score a transcript and decide whether to transmit it.
     *
     * STRICT BY DESIGN — this used to be lenient, and leniency shipped garbled emergency text.
     *
     * The old rule was: score the fraction of letters in an allowed script, and if it cleared
     * [THRESHOLD], SEND the transcript with the foreign characters *deleted*. On 26 Sep 2026 we
     * reproduced what that does. The Omnilingual model swaps a character for a phonetically
     * identical one from another script, and it does so on CLEAN audio:
     *
     *     said "hello"        -> "هello"       (Arabic heh for Latin h)
     *     said "help me"      -> "help مe"      (Arabic meem for Latin m)
     *     said "are you okay" -> "اr yوk"       (Arabic alif, Arabic waw)
     *
     * Those scored 0.60-0.83, cleared the 0.60 threshold, and were transmitted with the foreign
     * character removed. The receiving phone displayed "ello" and "help e". The user described
     * it as "it says hola". That is the worst failure this app has: the recipient gets
     * *confident, unreadable, wrong* text for an emergency, and nothing marks it as suspect.
     *
     * The rule now: **any** letter outside the allowed set means the segment is DROPPED whole.
     * Nothing is ever partially stripped. Silence is recoverable — the sender can see the drop
     * and repeat themselves. "Help e" is not.
     *
     * Defence in depth, not the primary defence. The model is now shipped with its CTC output
     * vocabulary pruned to the nine target scripts (see `eval/prune_vocab.py`), so it *cannot*
     * emit Arabic, CJK or Hangul at all. Pruning alone lifted Hindi script retention from 40%
     * to 78% under pink noise at 15 dB SNR. This filter remains necessary for the confusion
     * pruning cannot prevent: Hindi coming back in Gujarati, which is in-vocabulary.
     *
     * @param rawText  the recogniser's output
     * @param langTag  the language the user selected. **Strongly recommended.** Without it the
     *                 filter only checks "is this one of the nine required scripts", which lets a
     *                 Hindi->Gujarati script flip through. With it, the output must be in the
     *                 expected alphabet or it is dropped.
     * @return [Result.send] true when the segment should be transmitted;
     *         [Result.text] the text to send (empty when send is false).
     */
    fun filter(rawText: String, langTag: String? = null): Result {
        val text = rawText.trim()
        if (text.isEmpty()) {
            return Result(send = false, text = "", fraction = 0f, removed = 0)
        }

        val removed = foreignCount(text, langTag)
        if (removed > 0) {
            // Drop the whole segment. Do not clean() it — see the doc comment above.
            return Result(
                send = false,
                text = "",
                fraction = allowedFraction(text, langTag),
                removed = removed,
            )
        }

        return Result(send = true, text = tidy(text), fraction = 1f, removed = 0)
    }

    /**
     * Normalise whitespace only. **This no longer removes characters.**
     *
     * It is kept separate from [filter] on purpose: the name `clean` invited the mistake of
     * using it as the sanitiser, and using it that way is what shipped "ello". If a future
     * caller needs to sanitise, it must go through [filter], which drops rather than edits.
     */
    private fun tidy(text: String): String =
        text.replace(Regex("\\s{2,}"), " ").trim()
}
