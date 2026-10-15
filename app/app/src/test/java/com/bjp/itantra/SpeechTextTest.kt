package com.bjp.itantra

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [SpeechText] — the speech-only conditioning.
 *
 * The kept rules exist because the shipped voices were measured failing without them
 * (`eval/probe_normaliser_roundtrip.py`), and the cases measured FINE are tested too, as
 * non-changes: digits in every language and the non-Devanagari path must pass through untouched.
 */
class SpeechTextTest {

    // ------------------------------------------------------------- measured fine, must not change

    @Test
    fun `digits pass through in english and in hindi`() {
        // Re-measured with repeats: the Hindi voice reads "108" correctly ("ek so art"), so an
        // earlier number-to-words expander was removed rather than shipped on a bad reading.
        val en = "call 108 and tell them 500 people are at the school"
        assertEquals(en, SpeechText.forSynthesis(en, devanagariVoice = false))
        val hi = "स्कूल में 500 लोग हैं और 108 पर कॉल करो"
        assertEquals(hi, SpeechText.forSynthesis(hi, devanagariVoice = true))
        assertEquals("५०० लोग", SpeechText.forSynthesis("५०० लोग", devanagariVoice = true))
    }

    @Test
    fun `the map never runs for a latin voice`() {
        // The gate that keeps Devanagari spellings away from the English voice.
        val s = "स्कूल में emergency alert भेजो"
        assertEquals(s, SpeechText.forSynthesis(s, devanagariVoice = false))
    }

    @Test
    fun `pm and cm are not spelled out`() {
        assertEquals("meet at 4 pm", SpeechText.forSynthesis("meet at 4 pm", devanagariVoice = false))
        assertEquals("10 cm of water", SpeechText.forSynthesis("10 cm of water", devanagariVoice = false))
    }

    // ----------------------------------------------------------------------------- loanwords, Hindi

    @Test
    fun `measured case - english words in hindi text become devanagari`() {
        assertEquals(
            "स्कूल में इमरजेंसी अलर्ट भेजो",
            SpeechText.forSynthesis("स्कूल में emergency alert भेजो", devanagariVoice = true),
        )
    }

    @Test
    fun `loanword matching is case-insensitive`() {
        assertEquals(
            "स्कूल में इमरजेंसी भेजो",
            SpeechText.forSynthesis("स्कूल में Emergency भेजो", devanagariVoice = true),
        )
    }

    @Test
    fun `words outside the map keep their spelling`() {
        assertEquals(
            "स्कूल में zorblat भेजो",
            SpeechText.forSynthesis("स्कूल में zorblat भेजो", devanagariVoice = true),
        )
    }

    @Test
    fun `the whole emergency vocabulary is mapped`() {
        assertEquals(
            "पुलिस और एम्बुलेंस को फोन करो",
            SpeechText.forSynthesis("police और ambulance को phone करो", devanagariVoice = true),
        )
    }

    // -------------------------------------------------------------------------------- acronyms

    @Test
    fun `measured case - lowercase sos is spelled as letters`() {
        assertEquals(
            "send S O S to the police",
            SpeechText.forSynthesis("send sos to the police", devanagariVoice = false),
        )
    }

    @Test
    fun `uppercase acronyms and india-specific ones`() {
        assertEquals(
            "send S O S to the police",
            SpeechText.forSynthesis("send SOS to the police", devanagariVoice = false),
        )
        assertEquals(
            "report the F I R to police",
            SpeechText.forSynthesis("report the FIR to police", devanagariVoice = false),
        )
    }

    @Test
    fun `devanagari sentence with a latin acronym`() {
        assertEquals(
            "स्कूल में S O S भेजो",
            SpeechText.forSynthesis("स्कूल में SOS भेजो", devanagariVoice = true),
        )
    }

    // -------------------------------------------------------------------------------- hygiene

    @Test
    fun `whitespace is collapsed and trimmed`() {
        assertEquals("दो लोग", SpeechText.forSynthesis("  दो   लोग  ", devanagariVoice = false))
    }

    @Test
    fun `already-normalised text is unchanged`() {
        val once = SpeechText.forSynthesis("स्कूल में emergency alert भेजो", devanagariVoice = true)
        assertEquals(once, SpeechText.forSynthesis(once, devanagariVoice = true))
        val en = SpeechText.forSynthesis("send sos to the police", devanagariVoice = false)
        assertEquals(en, SpeechText.forSynthesis(en, devanagariVoice = false))
    }
}
