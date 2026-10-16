package com.bjp.itantra

/**
 * Everything the About and Credits screens display, written for a reader who is not a developer.
 *
 * WHY THE WORDING HERE IS CAREFUL
 * -------------------------------
 * The first version of this file was technically accurate and practically useless. It said things
 * like "CC-BY-NC-SA 4.0" and "espeak-ng phonemiser" and "CTC vocabulary pruning" — true, and
 * meaningless to a judge, a teacher, or anyone who has not read a model card. A credits page that
 * the reader cannot understand does not demonstrate honesty; it just looks like boilerplate.
 *
 * So each entry leads with WHAT IT DOES FOR YOU in ordinary words, and only then names the
 * project and its licence. Every licence carries a plain-English statement of whether the reader
 * has to do anything about it, because "CC-BY-NC-SA 4.0" on its own does not tell a reader
 * whether they are allowed to use it.
 *
 * TWO ENTRIES CARRY CONDITIONS, and they are marked, not buried:
 *   - the speech recognition model is CC-BY-NC-4.0  -> non-commercial
 *   - the Hindi voice is CC-BY-NC-SA-4.0              -> non-commercial AND share-alike
 * Both were in the app before this page existed.
 *
 * EVERY URL WAS VERIFIED BY HTTP REQUEST. A dead link in a credits page undermines the entire
 * page. Re-check with:
 *     curl -s -o /dev/null -w "%{http_code}" -L -A "<browser UA>" "<url>"
 */
object ProjectInfo {

    // ------------------------------------------------------------------ competition

    const val PS_NUMBER = "SIH26173"
    const val PS_TITLE_PLAIN = "A two-way radio that works on a bad connection, using 10 Indian languages"
    const val PS_TITLE_OFFICIAL =
        "Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links"
    const val ORGANISATION = "ISRO, Department of Space"
    const val CATEGORY = "Software"
    const val THEME = "Smart Automation"
    const val TEAM_NAME = "B.J.P — Bug Janta Party"

    // ------------------------------------------------------------------ languages

    data class Language(
        val name: String,
        /** True if a voice is bundled, so received text is spoken aloud in this language. */
        val speaks: Boolean,
    )

    /**
     * The ten required languages, with an honest per-language split.
     *
     * [speaks] is false for Gujarati, Kannada and Odia: we recognise those languages but ship no
     * voice for them, so a message in one arrives as text and stays silent. The app says so on
     * screen rather than failing quietly, and this table says the same thing here.
     */
    val LANGUAGES = listOf(
        Language("Hindi", speaks = true),
        Language("Marathi", speaks = true),
        Language("Bengali", speaks = true),
        Language("English", speaks = true),
        Language("Malayalam", speaks = true),
        Language("Telugu", speaks = true),
        Language("Tamil", speaks = true),
        Language("Gujarati", speaks = false),
        Language("Kannada", speaks = false),
        Language("Odia", speaks = false),
    )

    // ------------------------------------------------------------------ team

    data class Member(
        val name: String,
        val role: String,
        /** Null when the person has not published a profile yet. */
        val linkedIn: String?,
    )

    /** UTM tracking parameters were stripped from the supplied URLs — campaign noise, not identity. */
    val TEAM = listOf(
        Member("Prathamesh Bhujbal", "Team Lead", "https://www.linkedin.com/in/prathamesh-bhujbal-psb"),
        Member("Atharv Chougule", "Team Member", "https://www.linkedin.com/in/atharv-chougule-626452393"),
        Member("Aayush Kandhare", "Team Member", "https://www.linkedin.com/in/aayush-kandhare-32646538b"),
        Member("Harshal Karachkhed", "Team Member", "https://www.linkedin.com/in/harshal-karchkhed-239543400"),
        Member("Aniket Kolsepatil", "Team Member", "https://www.linkedin.com/in/aniket-kolsepatil-a138b0383"),
        Member("Tanishka Gupte", "Team Member", "https://www.linkedin.com/in/tanishka-gupte-2a586b303"),
    )

    // ------------------------------------------------------------------ credits

    /** What a licence means for the reader, in words. */
    enum class Action(val textRes: Int) {
        NONE(R.string.licence_action_none),
        ATTRIBUTION(R.string.licence_action_attribution),
        NON_COMMERCIAL(R.string.licence_action_noncommercial),
        SHARE_ALIKE(R.string.licence_action_sharealike),
        UNCLEAR(R.string.licence_action_unclear),
    }

    data class Credit(
        /** The project name, as its authors write it. */
        val name: String,
        /** Who made it. */
        val author: String,
        /** The licence, in its official short form. */
        val licence: String,
        /** PLAIN ENGLISH. What this part does for the person using the app. */
        val does: String,
        val action: Action,
        val url: String,
    )

    // ---- speech parts -----------------------------------------------------

    val SPEECH = listOf(
        Credit(
            name = "Omnilingual speech recognition",
            author = "Meta AI Research",
            licence = "CC-BY-NC-4.0",
            does = "This is the part that works out what you said. We took Meta's model, " +
                "which normally understands 1,600 languages, and cut it down to only the " +
                "letters our ten languages actually need. That is why it can no longer " +
                "mishear a Hindi speaker as speaking Arabic — the Arabic letters are not in " +
                "it any more.",
            action = Action.NON_COMMERCIAL,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
        ),
        Credit(
            name = "Omnilingual research paper",
            author = "Meta AI Research",
            licence = "arXiv:2511.09690",
            does = "The written research behind that model, if you want to know how it works.",
            action = Action.NONE,
            url = "https://arxiv.org/abs/2511.09690",
        ),
        Credit(
            name = "Piper voice models",
            author = "Piper contributors and the original voice recorders",
            licence = "Mixed — see each voice",
            does = "These are the voices that read messages out loud. We ship six: Hindi, " +
                "English, Marathi, Bengali, Malayalam and Telugu. The voices are not all " +
                "under the same terms, which is why this entry says \"mixed\". The Hindi " +
                "voice in particular is the most restricted of the six.",
            action = Action.SHARE_ALIKE,
            url = "https://huggingface.co/rhasspy/piper-voices",
        ),
        Credit(
            name = "Silero voice detector",
            author = "Silero Team",
            licence = "MIT",
            does = "Decides when you have finished speaking, so it only picks up your sentence " +
                "and not the silence around it.",
            action = Action.NONE,
            url = "https://github.com/snakers4/silero-vad",
        ),
    )

    // ---- engineering parts ------------------------------------------------

    val ENGINEERING = listOf(
        Credit(
            name = "sherpa-onnx",
            author = "k2-fsa",
            licence = "Apache-2.0",
            does = "The engine that loads and runs all the speech models on an Android phone. " +
                "Without it, none of the above would work on a handset.",
            action = Action.NONE,
            url = "https://github.com/k2-fsa/sherpa-onnx",
        ),
        Credit(
            name = "ONNX Runtime",
            author = "Microsoft",
            licence = "MIT",
            does = "The maths underneath sherpa-onnx that actually performs the speech recognition.",
            action = Action.NONE,
            url = "https://github.com/microsoft/onnxruntime",
        ),
        Credit(
            name = "espeak-ng",
            author = "espeak-ng project",
            licence = "GPL-3.0",
            does = "Supplies pronunciation rules so the voices say Indian words correctly. It " +
                "generates no sound of its own — it only tells the voices how to pronounce " +
                "things. This is a common misunderstanding: the app cannot speak using espeak " +
                "alone, it can only help the other voices sound right.",
            action = Action.ATTRIBUTION,
            url = "https://github.com/espeak-ng/espeak-ng",
        ),
        Credit(
            name = "Material Design 3 for Android",
            author = "Google",
            licence = "Apache-2.0",
            does = "The set of screen components this app's interface is built from — the " +
                "buttons, cards and colours you are looking at.",
            action = Action.NONE,
            url = "https://developer.android.com/jetpack/androidx/releases/material",
        ),
        Credit(
            name = "Kotlin and AndroidX",
            author = "JetBrains and the Android Open Source Project",
            licence = "Apache-2.0",
            does = "The language this app is written in, and Android's standard building blocks.",
            action = Action.NONE,
            url = "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt",
        ),
    )

    // ---- test data --------------------------------------------------------

    val TESTDATA = listOf(
        Credit(
            name = "FLEURS speech dataset",
            author = "Google Research",
            licence = "CC-BY-4.0",
            does = "The 2,000 recorded sentences of real people speaking that we used to " +
                "measure how accurate the app is. None of this audio is included in the app — " +
                "it is listed because the accuracy numbers we quote come from it.",
            action = Action.ATTRIBUTION,
            url = "https://huggingface.co/datasets/google/fleurs",
        ),
    )

    val ALL_GROUPS = listOf(
        R.string.licence_group_models to SPEECH,
        R.string.licence_group_software to ENGINEERING,
        R.string.licence_group_data to TESTDATA,
    )
}
