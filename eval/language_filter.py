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


def clean(text: str) -> str:
    """Strip foreign-script characters, keeping allowed letters, digits and punctuation.

    A mostly-Hindi sentence with two stray CJK characters is a Hindi sentence. Accept it,
    then remove the noise so the transmitted packet contains only valid text.
    """
    out = []
    for ch in text:
        s = script_of(ch)
        if s in ("NEUTRAL", "OTHER") or s in ALLOWED:
            out.append(ch)
    # collapse the whitespace left behind
    import re

    return re.sub(r"\s{2,}", " ", "".join(out)).strip()


def filter_transcript(text: str, threshold: float = THRESHOLD) -> dict:
    """The function the app should actually call.

    Returns whether to send the segment, and the cleaned text to send.
    """
    ok, info = accept(text, threshold)
    cleaned = clean(text) if ok else ""
    removed = sum(n for s, n in info["scripts"].items() if s not in ALLOWED)
    return {
        "send": ok,
        "text": cleaned,
        "raw": text,
        "verdict": info["verdict"],
        "allowed_fraction": info["allowed_fraction"],
        "foreign_chars_removed": removed,
    }


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
        ("Hindi + 2 stray CJK", "यह एक आपातकालीन संदेश है 緊急", True),
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
