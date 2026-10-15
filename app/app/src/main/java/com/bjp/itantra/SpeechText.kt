package com.bjp.itantra

/**
 * Text conditioning for SPEECH ONLY. Its output is never displayed, stored or transmitted — the
 * message a human reads stays exactly what the recogniser produced.
 *
 * HOW THIS LIST WAS BUILT, AND HOW IT SHRANK. Two rules exist because the shipped voices were
 * measured failing without them; one rule LOOKED needed and was refuted by a cleaner measurement;
 * two more were measured fine and are deliberately absent.
 *
 *     loanwords (kept) — eval/probe_normaliser_roundtrip.py, 3 runs each
 *         in        "स्कूल में emergency alert भेजो"       (Hindi voice), mean similarity 0.77
 *         heard     "स्कूल में इमज्जाल्सी एलत भेजो"        the English words are unrecoverable
 *         with map  "स्कूल में इमेर्जेंसी अलर्ट भीजो"      mean similarity 0.93 — recognisable
 *
 *     acronyms (kept) — same probe
 *         in        "send sos to the police"              (English voice), mean similarity 0.79
 *         heard     "send sauce to the police"            lowercase acronym read as a word
 *         spaced    "S O S" heard as "sends os ..."       the letters, recognised — mean 0.90
 *
 *     digits (REMOVED — an earlier reading was wrong)
 *         Single-run ASR rendering made digits look broken ("500" -> "5ा00"), but VITS sampling
 *         is stochastic and our own ASR renders number words loosely. Re-measured with repeats
 *         (`eval/probe_hindi_numbers.py`): the Hindi voice reads "108" as "ek so art", i.e.
 *         एक सौ आठ, correctly. So digits are left alone in EVERY language, exactly like English —
 *         and a number-to-words expander would have been dead weight with a phone-number risk.
 *
 *     emoji and currency symbols: the only text source is the ASR transcript, whose alphabet is
 *         letters, digits and punctuation. Rules for symbols it cannot emit would be dead code.
 *
 * WHY THE GATE IS THE VOICE AND NOT THE TEXT. By the time this runs, the router
 * ([LanguageFilter.voiceTagFor]) has already decided which voice speaks, and each voice has a
 * different failure mode: a Devanagari voice cannot read Latin words (measured above), while a
 * Latin voice must never receive Devanagari spellings it cannot pronounce. So the caller passes
 * the voice it is about to use, and the loanword map — whose output is Devanagari — only ever
 * runs for a Devanagari voice.
 *
 * The same discipline applies to coverage: Devanagari is the only script with loanword spellings
 * measured here, so every other script is passed through untouched. That is stated rather than
 * silently half-done. (Marathi shares Devanagari and receives the Hindi spellings; the script
 * cannot tell them apart — the same limitation `LanguageFilter.tagForScript` documents.)
 */
object SpeechText {

    /** The string to hand to the TTS engine. Pure and cheap: string work on a worker thread. */
    fun forSynthesis(text: String, devanagariVoice: Boolean): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed

        var out = trimmed
        if (devanagariVoice) out = replaceLoanwords(out)
        out = spellAcronyms(out)
        return out.replace(Regex("\\s{2,}"), " ").trim()
    }

    /**
     * Latin words inside Devanagari text, mapped to the Devanagari spelling a Hindi speaker
     * writes. This is what makes "emergency alert" survive the Hindi voice.
     *
     * Deliberately small and closed-domain: every entry is an emergency/coordination word, and
     * words not in this map are left exactly as recognised. Growing this list is cheap and
     * testable; inventing a general transliterator is not (see research/tts/routing.md).
     */
    private val LOANWORDS: Map<String, String> = mapOf(
        "emergency" to "इमरजेंसी",
        "urgent" to "अर्जेंट",
        "alert" to "अलर्ट",
        "help" to "हेल्प",
        "police" to "पुलिस",
        "hospital" to "हॉस्पिटल",
        "ambulance" to "एम्बुलेंस",
        "doctor" to "डॉक्टर",
        "medical" to "मेडिकल",
        "school" to "स्कूल",
        "village" to "विलेज",
        "shelter" to "शेल्टर",
        "water" to "वाटर",
        "food" to "फूड",
        "fire" to "फायर",
        "flood" to "फ्लड",
        "bridge" to "ब्रिज",
        "road" to "रोड",
        "border" to "बॉर्डर",
        "message" to "मैसेज",
        "report" to "रिपोर्ट",
        "call" to "कॉल",
        "phone" to "फोन",
        "mobile" to "मोबाइल",
        "battery" to "बैटरी",
        "network" to "नेटवर्क",
        "signal" to "सिग्नल",
        "safe" to "सेफ",
        "danger" to "डेंजर",
        "rescue" to "रेस्क्यू",
        "team" to "टीम",
        "officer" to "ऑफिसर",
        "sir" to "सर",
        "madam" to "मैडम",
        "please" to "प्लीज़",
        "thanks" to "थैंक्स",
        "ok" to "ओके",
        "minute" to "मिनट",
        "minutes" to "मिनट",
    )

    private fun replaceLoanwords(text: String): String =
        Regex("\\b[A-Za-z]+\\b").replace(text) { m ->
            LOANWORDS[m.value.lowercase()] ?: m.value
        }

    /**
     * Closed list. The CTC emits lowercase, and a lowercase acronym is read as a word
     * ("sos" -> "sauce"); letter-spacing it makes the voice read the letters instead.
     *
     * Only acronyms that cannot be ordinary words are listed. "pm" (4 pm) and "cm" (centimetres)
     * are deliberately absent — spelling them out would break a time or a measurement to fix
     * nothing. India-specific ones (FIR, NDRF, SDRF, CBI) are unambiguous in this app's domain.
     */
    private val ACRONYMS = setOf("sos", "fir", "ndrf", "sdrf", "cbi")

    private fun spellAcronyms(text: String): String =
        Regex("\\b[A-Za-z]+\\b").replace(text) { m ->
            val lower = m.value.lowercase()
            if (lower in ACRONYMS) lower.uppercase().toCharArray().joinToString(" ") else m.value
        }
}
