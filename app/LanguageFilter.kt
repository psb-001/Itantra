package com.k2fsa.sherpa.onnx.vad.asr

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

    data class Result(
        val send: Boolean,
        val text: String,
        val fraction: Float,
        val removed: Int,
    )

    private fun isAllowed(cp: Int): Boolean {
        for (r in ALLOWED_RANGES) {
            if (cp in r) return true
        }
        return false
    }

    /** True for characters that count toward the score (letters only). */
    private fun isLetter(cp: Int): Boolean = Character.isLetter(cp)

    /**
     * Fraction of the text's letters that are in an allowed script.
     * Returns 0f when there are no letters at all.
     */
    fun allowedFraction(text: String): Float {
        var total = 0
        var allowed = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isLetter(cp)) {
                total++
                if (isAllowed(cp)) allowed++
            }
            i += Character.charCount(cp)
        }
        return if (total == 0) 0f else allowed.toFloat() / total
    }

    /** Strip characters from non-allowed scripts, keeping allowed letters, digits, punctuation. */
    fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            // keep neutral characters (digits, punctuation, spaces) and allowed letters
            if (!isLetter(cp) || isAllowed(cp)) {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex("\\s{2,}"), " ").trim()
    }

    /** Count letters that were dropped by [clean]. */
    private fun foreignCount(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isLetter(cp) && !isAllowed(cp)) n++
            i += Character.charCount(cp)
        }
        return n
    }

    /**
     * The function to call on every ASR result.
     *
     * @return [Result.send] true when the segment should be transmitted;
     *         [Result.text] the cleaned text to send (empty when send is false).
     */
    fun filter(rawText: String): Result {
        val text = rawText.trim()
        val fraction = allowedFraction(text)
        val removed = foreignCount(text)

        if (text.isEmpty()) {
            return Result(send = false, text = "", fraction = 0f, removed = 0)
        }
        if (fraction < THRESHOLD) {
            return Result(send = false, text = "", fraction = fraction, removed = removed)
        }
        return Result(send = true, text = clean(text), fraction = fraction, removed = removed)
    }
}
