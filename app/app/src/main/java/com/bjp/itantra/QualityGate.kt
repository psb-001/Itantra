package com.bjp.itantra

/**
 * Decides whether a decoded segment is trustworthy enough to transmit, using only signals the
 * app can compute on-device in one pass.
 *
 * WHY THIS EXISTS
 *
 * The recogniser is a character-level CTC model decoded greedily. Every post-hoc filter we
 * measured was a net loss, and the measurements are recorded in [REJECTED] so the same ideas are
 * not re-tried blind. What survived is a small gate plus a THIRD outcome.
 *
 * The important product change is not the gate. It is the third state. Today a segment is either
 * sent or silently dropped, and under noise the strict script filter drops effectively everything:
 * measured on FLEURS plus synthetic pink noise, a script-purity threshold of 0.8 keeps 100% of
 * English and Hindi segments but 0% of Gujarati at 15 dB SNR. A user in that state sees a working
 * app that never sends anything, and has no way to tell that from a broken microphone.
 *
 * So an untrustworthy segment now returns [Verdict.REPEAT]: the app says "I could not read that
 * clearly — hold and say it again" instead of failing silently. Silence is ambiguous; a prompt is
 * actionable, and it is honest: we do not know what was said, so we do not guess.
 *
 * WHAT WAS TRIED AND REJECTED — with numbers, so nobody redoes this
 *
 * Re-measured on the shipped pruned model, FLEURS test clips, 25 clips per language:
 *
 *   - Beam search. Unavailable, not merely unused. sherpa-onnx 1.13.8 refuses it for this model
 *     type: "Only greedy_search is supported at present" (offline-recognizer-ctc-impl.h:208).
 *     modified_beam_search and fast_beam_search both abort the process. This is the single
 *     biggest reason the decoder cannot be made to behave better, and it is upstream.
 *
 *   - A chars-per-second plausibility gate. Actively counterproductive. The catastrophic decodes
 *     scored HIGHER chars/sec than the good ones: bad min 9.00, median 10.75; good median 9.30.
 *     A length floor would have discarded the wrong segments. On one 46 s Tamil clip it produced
 *     CER 1.55 at 9.0 chars/sec.
 *
 *   - Self-consistency (decode twice with a 3% gain change, transmit only on agreement). This is
 *     the standard way to estimate decoder confidence without log-probs. It fails here because
 *     the decoder is far too sensitive to amplitude: agreement was only 24-80% overall and it
 *     rejected 41-73% of clips that were already correct. Shipping it would throw away most of
 *     the good speech to catch a handful of bad segments.
 *
 *   - Tightening the Latin allowance. Measured 0.00 CER recovered by dropping the 0-10% of
 *     non-English segments containing Latin letters. No change made.
 *
 *   - Retuning the lead-in silence. Swept 0.0-0.8 s across all ten languages. Best 0.05 s beat the
 *     app's 0.20 s by 0.0023 CER, which is inside run-to-run noise on 20 clips per language. Not
 *     worth changing; 0.20 s stays.
 *
 *   - Genuine repetition loops. Rare and mostly harmless. Adjacent tiling of a 1-3 token n-gram
 *     three or more times occurred on 2.5% of Hindi, 5% of Gujarati, 12.5% of English and 0% of
 *     Tamil clips, and those clips had LOW CER (0.04-0.17) because the repetition was real
 *     repetition in the sentence. An earlier detector that counted n-grams globally reported a
 *     20-25% "loop rate" on ordinary sentences; it was flagging naturally repeated phrases such
 *     as a bigram appearing in a long news sentence. It was wrong and was discarded.
 *
 * THE ACTUAL FAILURE MODE, for the record
 *
 * Script confusion, not repetition. Observed real outputs:
 *
 *     Hindi audio -> "जैसے चान पृथवी परे खीचाव पेदाकर"  (Gujarati opening, Devanagari middle)
 *     Tamil audio -> "പുതിതാക്ക ഒരു വിഷയം"        (Malayalam, CER 0.96)
 *     Gujarati     -> "ફોકલેન્ડ પૉન્ડ એfkp"        (Latin letters inside Gujarati)
 *
 * LanguageFilter already stops the wrong-script case on clean audio. The gap this class fills is
 * the cases it cannot judge — and, more usefully, telling the user instead of going quiet.
 */
object QualityGate {

    enum class Verdict {
        /** Decode looks sound. Transmit. */
        SEND,

        /**
         * Decode is present but untrustworthy: wrong script, implausibly repetitive, or a very
         * short fragment. Do NOT transmit. Ask the speaker to repeat.
         *
         * Transmitting a half-right emergency message is worse than not transmitting, because a
         * person acts on it. But silently dropping is also wrong, because the user cannot tell a
         * rejected message from a broken microphone. So: reject loudly.
         */
        REPEAT,

        /** Nothing usable here at all — silence, or too short to be a sentence. Ignore quietly. */
        SILENT,
    }

    data class Decision(
        val verdict: Verdict,
        /** Why, in one word, for the log and the UI. Never a claim about content. */
        val reason: String,
        /** The text if it is to be sent, otherwise empty. */
        val text: String = "",
        /** Share of letters in the expected script. Diagnostic only; see [filter]. */
        val scriptPurity: Float = 1f,
    )

    /**
     * Shortest segment we will even look at.
     *
     * A 0.31 s segment from the VAD decoded to the empty string on every clip tried, with an RMS
     * as low as 0.00008 — that is a breath or a door, not a sentence. Dropping it before the ASR
     * saves the decode entirely.
     */
    const val MIN_SPEECH_SECONDS = 0.35f

    /**
     * Root-mean-square amplitude below which a segment is treated as silence.
     *
     * 0.005 is roughly -46 dBFS. Measured segment RMS for real speech in this app's own logs runs
     * an order of magnitude above it, and the 0.31 s junk segments sat at 0.00008 and 0.007.
     */
    const val MIN_RMS = 0.005f

    /**
     * Script purity needed to transmit without asking for a repeat.
     *
     * Deliberately NOT 1.0. On clean audio the strict filter already sits at 0.99+ and this gate
     * is not the thing rejecting it. Under noise, measured, a 0.80 threshold keeps 100% of English
     * and Hindi segments and 0% of Gujarati — so the ask-a-repeat path exists precisely to keep
     * the 0% case honest instead of silent. Values from 0.70 to 0.90 behave identically on the
     * measurements taken; 0.75 is the middle of that flat region, not a tuned magic number.
     */
    const val MIN_SCRIPT_PURITY = 0.75f

    /**
     * Adjacent repeats of a 1-3 token n-gram that count as a decoder stuck in a loop.
     *
     * Four, not three: the genuine (benign) cases clustered at exactly 3, and the real failures
     * were far higher. A threshold of 3 would discard correct speech.
     *
     * The check only became live on 16 Oct 2026: the recogniser's tokens were dropped on the way
     * in — an empty list was passed — so it could never fire. Measured on 90 clean FLEURS clips
     * (eval/probe_loopcheck_tokens.py): one decode in ninety was degenerate enough that the
     * receiving phone would have SPOKEN it as garbage ("ख      0 f 1"). Activating it is also
     * why single digits carry an exemption in [adjacentRepeat].
     */
    const val MAX_ADJACENT_REPEAT = 4

    /**
     * Evaluate one decoded segment.
     *
     * @param text     the recogniser's output for this segment
     * @param tokens   the recogniser's raw token list; used only for the loop check
     * @param audioSeconds  length of the segment in seconds
     * @param rms      root-mean-square amplitude of the segment
     * @param langTag  the selected language, for the expected-script check
     * @param strictScript  when true, reuse LanguageFilter's zero-tolerance rule
     */
    fun evaluate(
        text: String,
        tokens: List<String>,
        audioSeconds: Float,
        rms: Float,
        langTag: String?,
        strictScript: Boolean = true,
    ): Decision {
        val trimmed = text.trim()

        // Physical checks FIRST, so the reason names what is actually wrong with the audio rather
        // than blaming the decoder for a segment that was too short to decode. A 0.31 s empty
        // segment is "too-short", not "empty" — the message goes to the user and to the log.
        if (audioSeconds < MIN_SPEECH_SECONDS) {
            return Decision(Verdict.SILENT, "too-short", scriptPurity = 1f)
        }
        if (rms in 0f..MIN_RMS) {
            return Decision(Verdict.SILENT, "below-noise-floor", scriptPurity = 1f)
        }
        if (trimmed.isEmpty()) {
            return Decision(Verdict.SILENT, "empty", scriptPurity = 1f)
        }
        if (adjacentRepeat(tokens) >= MAX_ADJACENT_REPEAT) {
            return Decision(Verdict.REPEAT, "repetition-loop", scriptPurity = 1f)
        }

        // Expected script ONLY, not [allowedFraction] -- see the note on that method.
        val purity = LanguageFilter.expectedScriptPurity(trimmed, langTag)

        // Strict mode is the shipped behaviour and is the right default: it is what turned
        // "hello" into "ello" off. This gate never loosens it.
        if (strictScript && LanguageFilter.foreignLetters(trimmed, langTag).isNotEmpty()) {
            return Decision(Verdict.REPEAT, "wrong-script", scriptPurity = purity)
        }
        if (purity < MIN_SCRIPT_PURITY) {
            return Decision(Verdict.REPEAT, "mixed-script", scriptPurity = purity)
        }

        return Decision(Verdict.SEND, "ok", trimmed, purity)
    }

    /**
     * Root-mean-square amplitude of a segment, 0.0 for an empty one.
     *
     * Computed in one pass on a stride-8 sample rather than every sample: this runs on the audio
     * thread for every VAD segment, and the energy of speech is flat enough at 2 ms resolution
     * that sampling every 8th point gives the same gate decision at a fraction of the cost.
     */
    fun rms(samples: FloatArray, stride: Int = 8): Float {
        if (samples.isEmpty()) return 0f
        var acc = 0.0
        var n = 0
        var i = 0
        while (i < samples.size) {
            val v = samples[i].toDouble()
            acc += v * v
            n++
            i += stride
        }
        return Math.sqrt(acc / n).toFloat()
    }

    /**
     * How many times the same 1-3 token n-gram repeats BACK TO BACK.
     *
     * Adjacent only, on purpose. Counting n-grams globally flags ordinary sentences — a bigram
     * that occurs three times in a long news sentence is not a decoder bug — and an early version
     * of this did exactly that, reporting a 20-25% loop rate on clean speech. A CTC loop looks
     * like the model repeating itself with nothing in between, which is what this tests.
     */
    fun adjacentRepeat(tokens: List<String>): Int {
        if (tokens.size < 2) return 1
        var best = 1
        for (n in 1..3) {
            if (tokens.size < n * 2) continue
            var i = 0
            while (i + n * 2 <= tokens.size) {
                val gram = tokens.subList(i, i + n)
                // A SINGLE DIGIT is exempt from the 1-token pass: "100000 বছর" is a number, not a
                // loop. Measured — eval/probe_loopcheck_tokens.py: without this exemption a clean
                // Bengali FLEURS sentence containing 100000 was rejected on the very first sweep.
                // Two-token digit runs ("10 10 10 10") are still counted — that is not a number,
                // it is a stuck decoder.
                if (n == 1 && gram[0].length == 1 && gram[0][0].isDigit()) {
                    i++
                    continue
                }
                var k = 0
                var j = i
                while (j + n <= tokens.size && tokens.subList(j, j + n) == gram) {
                    k++
                    j += n
                }
                if (k > best) best = k
                i++
            }
        }
        return best
    }

    /** A sentence for the user when a segment is rejected. Says what to do, not why the model failed. */
    fun repeatPrompt(): String = "I could not read that clearly — hold the button and say it again"
}
