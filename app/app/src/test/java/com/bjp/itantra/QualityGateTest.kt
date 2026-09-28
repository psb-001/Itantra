package com.bjp.itantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [QualityGate].
 *
 * The strings in here are not invented. The wrong-script and repetition cases are real decodes
 * captured from the shipped pruned model on FLEURS clips, and the thresholds in [QualityGate] were
 * chosen from the measurements recorded in that file's doc comment — not tuned until the tests
 * passed.
 */
class QualityGateTest {

    // A real Hindi decode that came back opening in Gujarati. The receiver could not read it.
    private val hiInGujarati = "જैसે ચान पૃથवी परे खीचाव पेदाकर चवार भा टे का"
    private val hiTags = "hin_Deva"
    private val guTags = "guj_Gujr"
    private val taTags = "tam_Taml"
    private val enTags = "eng_Latn"

    private fun tokens(vararg t: String): List<String> = t.toList()

    @Test
    fun `clean segment is sent`() {
        val d = QualityGate.evaluate(
            text = "सड़कों पर ज्यादा दरकटना की वजह बहुत ज्यादा कारें",
            tokens = tokens("स", "ड़", "क", "ो", "ं"),
            audioSeconds = 8f,
            rms = 0.08f,
            langTag = hiTags,
        )
        assertEquals(QualityGate.Verdict.SEND, d.verdict)
        assertTrue("reason was ${d.reason}", d.text.isNotEmpty())
    }

    @Test
    fun `empty output is silent not repeat`() {
        val d = QualityGate.evaluate("", emptyList(), 5f, 0.08f, hiTags)
        assertEquals(QualityGate.Verdict.SILENT, d.verdict)
    }

    @Test
    fun `very short segment is silent`() {
        // This is what the VAD actually produced: 0.31 s, RMS 0.00008, empty decode.
        val d = QualityGate.evaluate("", emptyList(), 0.31f, 0.00008f, hiTags)
        assertEquals(QualityGate.Verdict.SILENT, d.verdict)
        assertEquals("too-short", d.reason)
    }

    @Test
    fun `near-silent segment is silent`() {
        val d = QualityGate.evaluate("hello there", tokens("h", "e", "l", "l", "o"), 3f, 0.0001f, enTags)
        assertEquals(QualityGate.Verdict.SILENT, d.verdict)
        assertEquals("below-noise-floor", d.reason)
    }

    @Test
    fun `wrong script asks for a repeat rather than transmitting`() {
        val d = QualityGate.evaluate(hiInGujarati, tokens("જ", "ै", "स", "ॆ"), 6f, 0.08f, hiTags)
        assertEquals(QualityGate.Verdict.REPEAT, d.verdict)
        assertEquals("wrong-script", d.reason)
        assertEquals("must not carry text", "", d.text)
    }

    @Test
    fun `latin letters inside a non-latin script are ALLOWED, deliberately`() {
        // This test asserts the behaviour we chose after measuring it, and it is here so a future
        // edit cannot tighten this by accident without someone seeing the number.
        //
        // A real Gujarati decode was "ફોકલેન્ડ પૉન્ડ એfkp એ ફોકલેન્ડનું" — Latin f/k/p mid-script, which
        // looks like garbage. Measured over 30 clips in each of 9 non-English languages, Latin
        // letters appeared in 0-10% of decodes, and REMOVING those decodes recovered
        // 0.00 CER. There was nothing to gain, and rejecting them would have discarded real speech.
        //
        // So Latin stays permitted, exactly as LanguageFilter documents: numerals and loanwords are
        // legitimate in all ten languages. A decode that is mostly Latin when the selected language
        // is Gujarati will still fail the purity check below, which is the correct place for that.
        val d = QualityGate.evaluate(
            "ફોકલેન્ડ પૉન્ડ એfkp એ ફોકલેન્ડનું",
            tokens("ફ", "ો", "ક", "f"),
            8f,
            0.08f,
            guTags,
        )
        assertEquals(QualityGate.Verdict.SEND, d.verdict)
        // Purity is measured against the expected script only, so the three Latin letters are not
        // counted as evidence — but they are also not enough to push a genuinely Gujarati decode
        // under the 0.75 threshold, which is the point.
        assertTrue(
            "expected-script purity was ${d.scriptPurity}",
            d.scriptPurity > QualityGate.MIN_SCRIPT_PURITY,
        )
        assertTrue("Latin is not free credit: ${d.scriptPurity}", d.scriptPurity < 1.0f)
    }

    @Test
    fun `a decode that is mostly latin for a gujarati selection fails on purity`() {
        val d = QualityGate.evaluate(
            "fk p vfk gjdk fkd gkfk",
            tokens("f", "k", "p", "v"),
            8f,
            0.08f,
            guTags,
        )
        assertEquals(QualityGate.Verdict.REPEAT, d.verdict)
        assertEquals("mixed-script", d.reason)
    }

    @Test
    fun `adjacent loop is caught`() {
        val d = QualityGate.evaluate(
            "aaaaaaaaaaaaaaaa",
            tokens(*Array(10) { "a" }),
            4f,
            0.08f,
            enTags,
        )
        assertEquals(QualityGate.Verdict.REPEAT, d.verdict)
        assertEquals("repetition-loop", d.reason)
    }

    @Test
    fun `a word that genuinely repeats three times is still sent`() {
        // The benign cases clustered at exactly 3 adjacent repeats and had LOW CER, because the
        // repetition was real. The threshold is 4 for this reason, and this test locks it in.
        val d = QualityGate.evaluate(
            "help help help",
            tokens("help", "help", "help"),
            5f,
            0.08f,
            enTags,
        )
        assertEquals(QualityGate.Verdict.SEND, d.verdict)
    }

    @Test
    fun `a bigram repeated across a long sentence is not a loop`() {
        // The bug that made an early detector report a 20-25% loop rate on clean speech: a bigram
        // occurring several times spread through a sentence is normal language, not a decoder loop.
        val t = tokens(
            "the", "end", "of", "the", "century", "the", "end", "of", "the", "road",
            "the", "end", "of", "it", "was", "the", "end",
        )
        assertEquals(1, QualityGate.adjacentRepeat(t))
    }

    @Test
    fun `rms ignores every stride-th sample but stays in range`() {
        val loud = FloatArray(800) { 0.5f }
        val quiet = FloatArray(800) { 0.0001f }
        assertTrue(QualityGate.rms(loud) > 0.4f)
        assertTrue(QualityGate.rms(loud) < 0.6f)
        assertTrue(QualityGate.rms(quiet) < QualityGate.MIN_RMS)
        assertEquals(0f, QualityGate.rms(FloatArray(0)), 0f)
    }

    @Test
    fun `threshold sits in the flat region the measurements showed`() {
        // Sweeps from 0.70 to 0.90 behaved identically on the measured data. Pinning the middle
        // of that region stops a future edit from drifting to an untested value.
        assertTrue(QualityGate.MIN_SCRIPT_PURITY in 0.70f..0.90f)
        assertTrue(QualityGate.MAX_ADJACENT_REPEAT >= 4)
        assertTrue(QualityGate.MIN_RMS < 0.02f)
    }
}
