"""Restrict Omnilingual ASR output to the ten languages SIH26173 requires.

WHY THIS EXISTS
The Omnilingual ASR model is a 1,600-language character-level CTC model. Its tokenizer
contains CJK (5,762 tokens), Hangul (1,222), Ethiopic, Arabic, Greek, Cyrillic, Myanmar,
Khmer, Thai, Tibetan and more. There is NO language parameter in the API:

    OfflineRecognizer.from_omnilingual_asr_ctc(model, tokens, num_threads,
                                               decoding_method, debug, provider)

So the model will happily transcribe whatever it hears in whatever script fits best.
On a noisy phone this means stray Chinese, Arabic or Korean text appearing in the
transcript. You cannot configure this away — you must filter the output.

HOW IT WORKS
Every required language uses exactly one Unicode script:

    Hindi, Marathi  -> DEVANAGARI     Kannada    -> KANNADA
    Gujarati        -> GUJARATI       Malayalam  -> MALAYALAM
    Tamil           -> TAMIL          Telugu     -> TELUGU
    Odia            -> ORIYA          Bengali    -> BENGALI
    English         -> LATIN

Score the transcript by the fraction of its LETTERS that belong to those nine scripts.
Digits, punctuation and spaces are neutral and ignored. Below the threshold -> reject.

CAVEAT, stated honestly: this stops a wrong *script*, not a wrong *language*. A Hindi
speaker misrecognised as Bengali passes, because both are allowed scripts. Distinguishing
those needs a language-ID model, which is out of scope. The filter's real job is to stop
Chinese/Arabic/Korean/etc. leaking into an Indic emergency message.

Run from the repo root:
    output/option_a_stt/.venv/bin/python output/option_a_stt/language_filter.py
"""
import json
import os
import unicodedata

BASE = os.path.dirname(os.path.abspath(__file__))

# The nine scripts that cover all ten required languages.
ALLOWED = {
    "DEVANAGARI": "Hindi, Marathi",
    "GUJARATI": "Gujarati",
    "KANNADA": "Kannada",
    "MALAYALAM": "Malayalam",
    "TAMIL": "Tamil",
    "TELUGU": "Telugu",
    "ORIYA": "Odia",
    "BENGALI": "Bengali",
    "LATIN": "English",
}

# Scripts we explicitly expect to see leaking in from a 1,600-language model.
KNOWN_FOREIGN = {
    "CJK", "HIRAGANA", "KATAKANA", "HANGUL", "ARABIC", "CYRILLIC", "GREEK",
    "HEBREW", "THAI", "ETHIOPIC", "MYANMAR", "KHMER", "LAO", "SINHALA",
    "TIBETAN", "ARMENIAN", "GEORGIAN", "BOPOMOFO",
}

THRESHOLD = 0.60  # fraction of letters that must be in an allowed script


def script_of(ch: str) -> str:
    """Best-effort Unicode script name for a single character."""
    if not ch.isalpha():
        return "NEUTRAL"
    try:
        name = unicodedata.name(ch, "")
    except ValueError:
        return "OTHER"
    for s in list(ALLOWED) + sorted(KNOWN_FOREIGN):
        if name.startswith(s):
            return s
    # Indic scripts not in our allow-list still have readable names
    for s in ("DEVANAGARI", "BENGALI", "GURMUKHI", "TAMIL", "TELUGU", "KANNADA",
              "MALAYALAM", "GUJARATI", "ORIYA"):
        if name.startswith(s):
            return s
    if name.startswith("LATIN"):
        return "LATIN"
    return "OTHER"


def score(text: str) -> dict:
    """Return the script breakdown and the allowed-fraction for a transcript."""
    counts: dict[str, int] = {}
    for ch in text:
        s = script_of(ch)
        if s == "NEUTRAL":
            continue
        counts[s] = counts.get(s, 0) + 1

    total = sum(counts.values())
    allowed = sum(n for s, n in counts.items() if s in ALLOWED)
    return {
        "text": text,
        "scripts": counts,
        "letters": total,
        "allowed_letters": allowed,
        "allowed_fraction": round(allowed / total, 4) if total else 0.0,
    }


def accept(text: str, threshold: float = THRESHOLD) -> tuple[bool, dict]:
    """True if the transcript is plausibly one of the ten required languages."""
    info = score(text)
    info["threshold"] = threshold
    if info["letters"] == 0:
        info["verdict"] = "REJECT (no letters)"
        return False, info
    ok = info["allowed_fraction"] >= threshold
    info["verdict"] = "ACCEPT" if ok else "REJECT (foreign script)"
    return ok, info


def filter_transcript(text: str, threshold: float = THRESHOLD) -> dict:
    """The function the app should actually call.

    STRICT BY DESIGN. The old rule scored the fraction of in-script letters and, if it cleared
    `threshold`, transmitted the transcript with the foreign characters DELETED. Measured on
    26 Sep 2026, that shipped garbled emergency text:

        said "hello"        -> "هello"       (Arabic heh for Latin h)
        said "help me"      -> "help مe"      (Arabic meem for Latin m)
        said "are you okay" -> "اr yوk"       (Arabic alif, Arabic waw)

    Those scored 0.60-0.83, cleared the threshold, and were sent with the foreign character
    removed — the receiving phone displayed "ello" and "help e". Confident, unreadable, wrong
    text for an emergency, with nothing marking it as suspect.

    The rule now: any letter outside the allowed set drops the WHOLE segment. Nothing is ever
    partially stripped.

    The primary defence is the model itself, whose CTC output vocabulary is pruned to the nine
    target scripts by `eval/prune_vocab.py`. This filter is the second layer, and it exists for
    the confusion pruning cannot prevent: Hindi arriving in Gujarati, both in-vocabulary.
    """
    info = score(text)
    foreign = sum(n for s, n in info["scripts"].items() if s not in ALLOWED)

    if info["letters"] == 0:
        return {"send": False, "text": "", "raw": text,
                "verdict": "REJECT (no letters)",
                "allowed_fraction": info["allowed_fraction"],
                "foreign_chars_removed": foreign}

    if foreign > 0:
        return {"send": False, "text": "", "raw": text,
                "verdict": f"REJECT ({foreign} foreign chars - whole segment dropped)",
                "allowed_fraction": info["allowed_fraction"],
                "foreign_chars_removed": foreign}

    return {"send": True, "text": tidy(text), "raw": text,
            "verdict": "ACCEPT",
            "allowed_fraction": info["allowed_fraction"],
            "foreign_chars_removed": 0}


def tidy(text: str) -> str:
    """Whitespace only. This does NOT remove characters - see filter_transcript."""
    import re
    return re.sub(r"\s{2,}", " ", text).strip()


# --- script -> language tag ---------------------------------------------------------------
# Twin of the Kotlin LanguageFilter.tagForScript / voiceTagFor added on 15 Oct 2026. The app
# infers a message's language from the script the recogniser actually used (no language selector
# exists) and picks the TTS voice from that. Kept here so evaluation scripts reproduce the app's
# live behaviour instead of the old "everything is Hindi" behaviour.
#
# Order is the tie-break order, Latin first - identical to the Kotlin SCRIPTS list.

SCRIPT_ORDER = ["LATIN", "DEVANAGARI", "BENGALI", "GUJARATI", "ORIYA", "TAMIL", "TELUGU",
                "KANNADA", "MALAYALAM"]

SCRIPT_TAG = {
    "LATIN": "eng_Latn",
    "DEVANAGARI": "hin_Deva",
    "BENGALI": "ben_Beng",
    "GUJARATI": "guj_Gujr",
    "ORIYA": "ory_Orya",
    "TAMIL": "tam_Taml",
    "TELUGU": "tel_Telu",
    "KANNADA": "kan_Knda",
    "MALAYALAM": "mal_Mlym",
}
TAG_SCRIPT = {v: k for k, v in SCRIPT_TAG.items()}

# Every tag the app can transmit, including the second tag on one script. [SCRIPT_TAG] only has
# one representative per script; a Marathi tag must still keep itself when the text is Devanagari.
TAG_EXPECTED_SCRIPT = dict(TAG_SCRIPT)
TAG_EXPECTED_SCRIPT["mar_Deva"] = "DEVANAGARI"

# Devanagari code points - letters AND the vowel marks inside a word, so "स्कूल" counts as ONE
# word below, not three. Twin of the Kotlin DEVANAGARI_RANGES.
_DEVANAGARI_RANGES = ((0x0900, 0x097F), (0xA8E0, 0xA8FF))


def devanagari_words(text: str) -> int:
    """Runs of Devanagari code points bounded by anything else. Twin of the Kotlin helper."""
    words = 0
    in_run = False
    for ch in text:
        cp = ord(ch)
        deva = any(lo <= cp <= hi for lo, hi in _DEVANAGARI_RANGES)
        if deva and not in_run:
            words += 1
        in_run = deva
    return words


def tag_for_script(text: str) -> str | None:
    """The language tag the characters of `text` say it is, or None when no letters or none of
    the nine scripts. Devanagari cannot separate Hindi from Marathi - it returns hin_Deva.

    Carries the Hinglish rule (twin of the Kotlin dominantScript): two or more Devanagari words
    win even when Latin letters outnumber them. Measured 15 Oct 2026
    (eval/probe_hinglish_routing.py): "स्कूल में emergency alert भेजो" (3 Devanagari words, 6
    Devanagari letters vs 14 Latin) survives the Devanagari voice at similarity 0.90 and loses
    its Hindi words under the English voice, while "the police went to स्कूल" (ONE Devanagari
    word) reads correctly under the English voice and is destroyed by the Devanagari voice.
    """
    counts = {s: 0 for s in SCRIPT_ORDER}
    for ch in text:
        s = script_of(ch)
        if s in counts:
            counts[s] += 1
    if not any(counts.values()):
        return None
    # max() keeps the first maximal element, which is the Kotlin tie-break (Latin first).
    best = max(SCRIPT_ORDER, key=lambda s: counts[s])
    if best != "DEVANAGARI" and devanagari_words(text) >= 2:
        best = "DEVANAGARI"
    return SCRIPT_TAG[best]


def voice_tag_for(lang_tag: str, text: str) -> str:
    """Twin of LanguageFilter.voiceTagFor: the tag that should pick the TTS voice.

    The sender's tag is kept when the text agrees with it (including two tags on one script,
    e.g. mar_Deva on Devanagari text); otherwise the text's own script wins.
    """
    spoken = tag_for_script(text)
    if spoken is None:
        return lang_tag
    expected = TAG_EXPECTED_SCRIPT.get(lang_tag)
    if expected is None:
        return spoken
    return lang_tag if expected == TAG_SCRIPT[spoken] else spoken


def main():
    # (label, text, should_be_sent)
    cases = [
        ("Hindi", "नमस्ते यह एक आपातकालीन संदेश है", True),
        ("Marathi", "युरोप हा खंड तुलनेने लहान आहे परंतु त्यात अनेक स्वतंत्र देश आहेत", True),
        ("Bengali", "গুরুত্বপূর্ণ সামরিক এবং অসামরিক যোগান", True),
        ("Odia", "ମନୋବିଜ୍ଞାନ ସମେତ ବିଜ୍ଞାନ ସମ୍ବନ୍ଧିତ ସମସ୍ତ ବିଷୟ", True),
        ("Gujarati", "આ એક કટોકટીનો સંદેશ છે", True),
        ("Kannada", "ಇದು ತುರ್ತು ಸಂದೇಶವಾಗಿದೆ", True),
        ("Tamil", "இது ஒரு அவசர செய்தி", True),
        ("Telugu", "ఇది అత్యవసర సందేశం", True),
        ("Malayalam", "ഇതൊരു അടിയന്തിര സന്ദേശമാണ്", True),
        ("English", "Emergency alert. Move to a safe location immediately.", True),
        ("Hindi + English mix", "यह एक emergency alert संदेश है", True),
        ("Numbers in Hindi", "बाड़ 3 पर आग लगी है, 112 पर कॉल करें", True),
        # --- STRICT POLICY: the next three were the bug. Under the old 0.60-fraction rule they
        # --- were SENT with the foreign character deleted, producing "ello" / "help e" / "r yk".
        ("Real bug: heh for h", "هello", False),
        ("Real bug: meem for m", "help مe", False),
        ("Real bug: alif+waw", "اr yوk", False),
        ("Hindi + 2 stray CJK", "यह एक आपातकालीन संदेश है 緊急", False),
        ("Chinese", "紧急警报请立即转移到安全地点", False),
        ("Japanese", "緊急警報です安全な場所に移動してください", False),
        ("Korean", "긴급 경보 안전한 장소로 이동하십시오", False),
        ("Arabic", "تنبيه طارئ انتقل إلى مكان آمن", False),
        ("Russian", "Экстренное оповещение переместитесь в безопасное место", False),
        ("Thai", "แจ้งเตือนฉุกเฉินโปรดย้ายไปยังที่ปลอดภัย", False),
        ("Greek", "Επείγουσα ειδοποίηση μετακινηθείτε σε ασφαλές μέρος", False),
        ("Empty", "", False),
        ("Numbers only", "12345", False),
    ]

    print("=" * 78)
    print("LANGUAGE FILTER — restrict output to the ten required languages")
    print(f"allowed scripts: {', '.join(ALLOWED)}")
    print(f"threshold: {THRESHOLD} of letters must be in an allowed script")
    print("=" * 78)

    results = []
    correct = 0

    for label, text, should_send in cases:
        r = filter_transcript(text)
        right = r["send"] == should_send
        correct += right
        mark = "ok " if right else "!! "
        print(f"\n{mark}[{label}]  expected {'SEND' if should_send else 'DROP'}")
        print(f"    raw     : {text[:60] if text else '(empty)'}")
        print(f"    verdict : {r['verdict']}   (allowed fraction {r['allowed_fraction']})")
        if r["send"]:
            print(f"    SEND    : {r['text'][:60]}")
            if r["foreign_chars_removed"]:
                print(f"    cleaned : {r['foreign_chars_removed']} foreign chars stripped")
        results.append({"label": label, "expected_send": should_send,
                        "correct": right, **r})

    print()
    print("=" * 78)
    print(f"RESULT: {correct}/{len(cases)} cases correct")
    print("=" * 78)

    with open(os.path.join(BASE, "language_filter.json"), "w") as f:
        json.dump({"allowed_scripts": ALLOWED, "threshold": THRESHOLD,
                   "cases": results}, f, indent=2, ensure_ascii=False)
    print("Wrote language_filter.json")


if __name__ == "__main__":
    main()
