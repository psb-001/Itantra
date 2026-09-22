package com.bjp.itantra

/**
 * Restricts ASR output to the ten languages SIH26173 requires.
 *
 * WHY: the Omnilingual ASR model is a 1,600-language character-level CTC model. Its
 * tokenizer carries CJK (5,762 tokens), Hangul (1,222), Ethiopic, Arabic, Greek,
 * Cyrillic and more. The API exposes no language parameter:
 *
 *     OfflineRecognizer.fromOmnilingualAsrCtc(model, tokens, numThreads, ...)
 *
 * So it transcribes whatever it hears in whatever script fits best. On a phone with
 * background noise this means stray Chinese, Arabic or Korean leaking into the
 * transcript. You cannot configure this away — you filter the output.
 *
 * HOW: every required language uses exactly one Unicode script.
 *
 *     Hindi, Marathi -> Devanagari      Kannada   -> Kannada
 *     Gujarati       -> Gujarati        Malayalam -> Malayalam
 *     Tamil          -> Tamil           Telugu    -> Telugu
 *     Odia           -> Oriya           Bengali   -> Bengali
 *     English        -> Latin
 *
 * Score the transcript by the fraction of its LETTERS in those scripts. Digits,
 * punctuation and spaces are neutral and ignored. Below the threshold -> drop.
 * Above it -> send, with foreign characters stripped out.
 *
 * HONEST LIMITATION: this stops a wrong *script*, not a wrong *language*. A Hindi
 * speaker misrecognised as Bengali passes, because both scripts are allowed.
 * Distinguishing those needs a language-ID model, which is out of scope. The filter's
 * real job is stopping Chinese/Arabic/Korean from reaching an Indic emergency message.
 *
 * Implemented with code-point ranges rather than Character.UnicodeScript so it works
 * below API 24.
 *
 * Usage in the ASR callback:
 *
 *     val r = LanguageFilter.filter(recognizerText)
 *     if (r.send) transport.send(r.text, langTag)
 *     else Log.i(TAG, "dropped ${r.removed} foreign chars: ${r.fraction}")
 */
object LanguageFilter {

    /** Fraction of letters that must fall in an allowed script for the segment to be sent. */
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

    /** Strip characters outside the allowed set, keeping digits, punctuation and spaces. */
    fun clean(text: String, langTag: String? = null): String {
        val ranges = langTag?.let { rangesFor(it) } ?: ALLOWED_RANGES
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isLetter(cp) || inRanges(cp, ranges)) {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex("\\s{2,}"), " ").trim()
    }

    /** Count letters that [clean] would drop. */
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
     * The function to call on every ASR result.
     *
     * @param rawText  the recogniser's output
     * @param langTag  the language the user selected. **Strongly recommended.** Without it the
     *                 filter only checks "is this one of the nine required scripts", which lets a
     *                 Hindi→Gujarati script flip through. With it, the output must be in the
     *                 expected alphabet or it is dropped.
     * @return [Result.send] true when the segment should be transmitted;
     *         [Result.text] the cleaned text to send (empty when send is false).
     */
    fun filter(rawText: String, langTag: String? = null): Result {
        val text = rawText.trim()
        val fraction = allowedFraction(text, langTag)
        val removed = foreignCount(text, langTag)

        if (text.isEmpty()) {
            return Result(send = false, text = "", fraction = 0f, removed = 0)
        }
        if (fraction < THRESHOLD) {
            return Result(send = false, text = "", fraction = fraction, removed = removed)
        }
        return Result(send = true, text = clean(text, langTag), fraction = fraction, removed = removed)
    }
}
