package com.bjp.itantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the language inference the live path gained on 15 Oct 2026:
 * [LanguageFilter.tagForScript] (what the characters say this text is) and
 * [LanguageFilter.voiceTagFor] (which voice should speak a message).
 *
 * The English and Hindi strings are the ones actually measured with the shipped models that day:
 * `eval/probe_en_script.py` transcribed our own bench recordings and FLEURS clips — English came
 * back Latin 22/22, Hindi Devanagari 6/6 — and `eval/probe_voice_mismatch.py` measured what
 * getting the routing wrong costs: similarity 0.76 and unrecognisable audio when the Hindi voice
 * reads Latin English ("the bridge has collapsed…" -> "अबिद्ज हज कलापस …"), 0.96–1.00 when the
 * English voice does. These tests pin the fix, not the old behaviour.
 */
class LanguageRoutingTest {

    @Test
    fun `english sentences infer eng_Latn`() {
        assertEquals(
            "eng_Latn",
            LanguageFilter.tagForScript("the bridge has collapsed please send help to the school"),
        )
        assertEquals("eng_Latn", LanguageFilter.tagForScript("hello i am fine how are you"))
    }

    @Test
    fun `hindi sentences infer hin_Deva`() {
        assertEquals("hin_Deva", LanguageFilter.tagForScript("पुलिस को बुलाओ, स्कूल में मदद भेजो"))
    }

    @Test
    fun `devanagari cannot tell hindi from marathi - the representative tag is hin_Deva`() {
        // A limitation, stated plainly: a language selector is the honest way to choose between
        // the two. This only has to be good enough to pick a Devanagari voice.
        assertEquals("hin_Deva", LanguageFilter.tagForScript("नमस्कार, तुम्ही कसे आहात"))
    }

    @Test
    fun `every other script names itself`() {
        assertEquals("ben_Beng", LanguageFilter.tagForScript("আপনি কেমন আছেন"))
        assertEquals("guj_Gujr", LanguageFilter.tagForScript("તમે કેમ છો"))
        assertEquals("ory_Orya", LanguageFilter.tagForScript("ଆପଣ କେମିତି ଅଛନ୍ତି"))
        assertEquals("tam_Taml", LanguageFilter.tagForScript("நீங்கள் எப்படி இருக்கிறீர்கள்"))
        assertEquals("tel_Telu", LanguageFilter.tagForScript("మీరు ఎలా ఉన్నారు"))
        assertEquals("kan_Knda", LanguageFilter.tagForScript("ನೀವು ಹೇಗಿದ್ದೀರಿ"))
        assertEquals("mal_Mlym", LanguageFilter.tagForScript("നിങ്ങൾ എങ്ങനെയുണ്ട്"))
    }

    @Test
    fun `mixed text follows the majority script`() {
        assertEquals("hin_Deva", LanguageFilter.tagForScript("स्कूल में help भेजो"))
        assertEquals("eng_Latn", LanguageFilter.tagForScript("please send मदद"))
    }

    @Test
    fun `a tie is broken towards Latin`() {
        assertEquals("eng_Latn", LanguageFilter.tagForScript("ab कख"))
    }

    @Test
    fun `no letters means no inference`() {
        assertNull(LanguageFilter.tagForScript("12345 🚨 ✋"))
        assertNull(LanguageFilter.tagForScript(""))
    }

    @Test
    fun `an unsupported script infers nothing`() {
        // The pruned vocabulary cannot emit Arabic; the filter below is what drops it if it leaks.
        assertNull(LanguageFilter.tagForScript("مرحبا"))
    }

    @Test
    fun `the sender flow accepts english with the nine-script check`() {
        val raw = "the bridge has collapsed please send help to the school"
        assertEquals("eng_Latn", LanguageFilter.tagForScript(raw))
        assertTrue(LanguageFilter.filter(raw, null).send)
    }

    @Test
    fun `the measured arabic substitution is still dropped whole`() {
        // The 26 Sep case: "help me" came back as "help مe" on clean audio. Zero tolerance stays.
        val raw = "help مe"
        assertEquals("eng_Latn", LanguageFilter.tagForScript(raw))
        assertFalse(LanguageFilter.filter(raw, null).send)
    }

    @Test
    fun `the broken-English case is corrected to the english voice`() {
        assertEquals("eng_Latn", LanguageFilter.voiceTagFor("hin_Deva", "hello i am fine"))
    }

    @Test
    fun `a tag that already matches survives`() {
        assertEquals("eng_Latn", LanguageFilter.voiceTagFor("eng_Latn", "hello i am fine"))
        assertEquals("hin_Deva", LanguageFilter.voiceTagFor("hin_Deva", "पुलिस को बुलाओ"))
    }

    @Test
    fun `two tags on one script do not knock each other out`() {
        // mar_Deva and hin_Deva share Devanagari; the sender's exact tag must be kept.
        assertEquals("mar_Deva", LanguageFilter.voiceTagFor("mar_Deva", "नमस्कार, तुम्ही कसे आहात"))
    }

    @Test
    fun `a wrong tag does not survive a clear script`() {
        assertEquals("eng_Latn", LanguageFilter.voiceTagFor("guj_Gujr", "hello i am fine"))
        assertEquals("hin_Deva", LanguageFilter.voiceTagFor("eng_Latn", "पुलिस को बुलाओ"))
    }

    @Test
    fun `messages with no letters keep their tag`() {
        assertEquals("hin_Deva", LanguageFilter.voiceTagFor("hin_Deva", "12345"))
    }
}
