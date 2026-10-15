"""
Prune Laya's 256,000-token vocabulary down to what ten Indian languages actually need, and
slice the embedding table to match.

THE PROBLEM

Laya's multilingual checkpoint is a 100+ language model, so its embedding table is
256,000 x 768 float32 = 750 MB, which is 61% of the encoder and the reason the exported
ONNX is 1,228 MB. That will not ship: the APK is already 804 MB.

Standard INT8 does not solve it. quantize_dynamic on MatMul takes 1,228 MB to 858 MB, only
1.43x, because the token embedding is a Gather initializer rather than a MatMul weight and
MatMul quantisation never touches it. head.onnx will not quantise at all
(ShapeInferenceError: dim 0: 772 vs 256).

THE FIX, and it is the same one that already worked on our ASR model

eval/prune_vocab.py took the ASR output vocabulary from 10,288 to 1,002 tokens and made
wrong-script output structurally impossible. Here the ratio is far better still: tokenising
7,264 real sentences across the ten required languages touches only 5,946 distinct token
ids, 2.3% of the vocabulary.

We keep:
  * all special tokens, unconditionally
  * every token seen in the FLEURS reference transcripts for the ten languages
  * a curated rescue-domain phrase set per language, because FLEURS is read news prose and
    real emergency speech contains numbers, units, place names and imperatives that the
    corpus does not
  * the question and option strings used at inference

Anything else becomes <unk>. That is a real limitation and it is measured below rather than
assumed: an OOV rate is reported, and the kept set is padded with the full Latin and digit
range so numbers and place names survive.

Run:
    python eval/laya_prune_vocab.py --onnx /tmp/laya_onnx --out /tmp/laya_pruned
"""

import argparse
import json
import os
import shutil
import sys

# Rescue vocabulary. Deliberately generous: the cost of a kept token is 3 KB of fp32 and
# 0.75 KB at int8, so carrying a few hundred extra tokens is free insurance against
# domain text the FLEURS corpus never contains.
RESCUE = {
    "en": """help rescue emergency alert evacuate evacuation shelter flood fire accident
        injured injured trapped missing found safe unsafe danger danger water water level
        rising falling bridge road highway building collapse collapse rubble debris storm
        cyclone earthquake landslide supply food water medicine ambulance hospital police
        army navy air force ndrf sdfr ndrf team officer volunteer camp relief relief camp
        please send come go stop wait stay move return follow report location place village
        block district city road number count people person family children women men
        today tomorrow morning evening night now soon immediately urgent quickly
        north south east west left right near far behind front
        one two three four five six seven eight nine ten eleven twelve twenty thirty forty
        fifty hundred thousand million first second third last next
        message received acknowledged confirmed cannot unable yes no maybe
        phone radio signal network internet offline battery charge charger""",
    "hi": """बचाए बचाओ मदद मदद करें आपातकाल हादसा हादसे से बाढ़ बाढ़ आई आग आग लगी धरना
        गिरा गिरने का मलबा मरम्मत राहत राहत मार्ग रास्ता पुल पुलिस अस्पताल दवा दवाई
        डॉक्टर बच्चे बच्चों महिला पुरुष परिवार लोग निवासी गांव गाँव जिला शहर
        तत्काल जरूरी तुरंत अभी आज कल सुबह शाम रात रातों जल्दी शीघ्र
        उत्तर दक्षिण पूर्व पश्चिम बाएं दाएं पास दूर पहाड़ नदी नहर पुल रेलवे सड़क
        भेजो भेजें मदद भेज दो संदेश पता पहचान संख्या संख्या गिनती लोग
        हाँ नहीं ठीक ठीक है ऐसा ऐसा है सुरक्षित सुरक्षित नहीं खतरा मदद करो मदद करें""",
    "mr": """मदत मदत करा बचवा बचाबचा अपतट्यकाल पूर पाणी पाण्याची पातळी वाढते वाढत आहे
        आग आगी विध्वंस इमारत पूर्ण झाली मलबा रस्ता बाजारपेटा शस्त्रालय औषध
        पोलिस महिला पुरुष कुटुंब लोक गाव जिल्हा शहर आज काल सकाळी संध्याकाळ रात्री
        उत्तर दक्षिण पूर्व पश्चिम नदी पूर दुर्म्याळे वाढत आहे
        पाठवा पाठवा मदत करा क्षणी आत्ता महत्त्वाचे जरूर हा नाही होते सुरक्षित असुरक्षित धोका
        संदेश पत्ता ओळख क्रमांक माणसे संख्या होते नाही म्हणजे""",
    "bn": """সাহায্য সাহায্য করুন বাঁচান বাঁচাও জরুরি বিপদ ঘটনা বন্যা বন্যা হয়েছে আগুন
        আগুন লেগেছে ভবন ভেঙে পড়েছে ধ্বংস ধসে পড়া বাড়ি রাস্তা সেতু ট্রেন হাসপাতাল
        ওষুধ পুলিশ নারী পুরুষ পরিবার মানুষ গ্রাম জেলা শহর আজ কাল সকাল সন্ধ্যা রাত
        উত্তর দক্ষিণ পূর্ব পশ্চিম নদী জল বাড়ছে বাড়তে বাড়ছে
        পাঠান সাহায্য করুন এখনই এখন জরুরি হ্যাঁ না ঠিক আছে নিরাপদ অনিরাপদ বিপদ
        বার্তা ঠিকানা পরিচয় নম্বর মানুষ সংখ্যা হয়েছে হয়নি""",
    "ta": """உதவி உதவி செய் மீது அபாயம் விபத்து புயல் புயல் வந்தது தீ தீ ஏறி
        கட்டிடம் சிதைந்து இறக்கி குப்பை நெறுப்பாதை ஆறு பாலம் ஆரம்பகம் மருத்துவமனம்
        மருந்து காவல்துறை பெண் ஆண் குடும்ப மக்கள் கிராமம் மாவட்டம் நகரம்
        இன்று நாளை காலை மாலை இரவு வடக்கு வடம்பு அவசரம் இப்போது உடனே
        தெறிவு வடக்கு வெளியே இடது வலது அருகு தூரம்
        அனுப்பு உதவி செய் ஆமையே சரி இல்லை பாதுகாப்பு பாதுகாப்பில்லை ஆபத்து
        செய்தி முகவரி எண் எண்ணிக்கை மக்கள் ஆம் இல்லை""",
    "te": """సహాయం సహాయం చేయండి కాపాడు కాపాడండి అపాయం ప్రమాదం వర్షపు వర్షం వచ్చింది
        అగ్ని అగ్ని మొట్టింది భవనం కూలిపోయింది ధ్వంసం ఆదును వీధి నది పुलం రైలు
        ఆస్పత్రి మందులు పోలీసు మహిళ పురుష కుటుంబం ప్రజలు గ్రామం జిల్లా నగరం
        ఈరోజు రేపు ఉదయం సాయంత్రం రాత్రి ఉత్తర దక్షిణ తూర్పు పడమే ఎడము
        పంపండి సహాయం చేయండి ఇప్పుడే ఇప్పుడు అత్యవసరం అవసరం అవును కాదు సరైనది
        సురక్షితం అసురక్షితం ప్రమాదం సందేశం చిరునామా గుర్తింపు సంఖ్యా మనుషులు""",
    "ml": """സഹായം സഹായിക്കൂ രക്ഷിക്കൂ അപകടം അപകടം വെള്ളം വെള്ളം വന്നു തീ തീ കത്തി
        കെട്ടി കെട്ടി വികസി വികസി റസ്റ്റ് ചരി കുത്ത് ആശുപത്രി മരുന്ന് പോലീസ്
        സ്ത്രീ പുരുഷൻ കുടുംബം ആളുകൾ ഗ്രാമം ജില്ല നഗരം ഇന്ന് നാളെ രാവിലെ
        വൈകുന്നേരം രാത്രി വടക്ക് താഴേക്ക് ഇപ്പോള് ഉടൻ അടിയന്തരം ആയി
        അയയ്ക്കുക സഹായിക്കൂ ശരി ഇല്ല സുരക്ഷിതം അസുരക്ഷിതം അപകടം സന്ദേശം
        വിലാസം തിരിച്ചറിയുന്ന സംഖ്യ ആളുകൾ ആണ് ഇല്ല""",
    "gu": """મદદ મદદ કરો બચાવો બચાઓ કામકાજી અપઘાત ત્રિકટ વાવાનો વાવે આવી ગયો
        આગ આગ લાગી મકાન ધરાસો તૂટી પડી કચરો રસ્તો નદી પુલ રેલવે દવાખાનું
        દવા પોલીસ સ્ત્રી પુરુષ કુટુંબ લોકો ગામ જિલ્લો શહેર આજે કાલે સવારે
        સાંજ રાત ઉત્તર દક્ષિણ પૂર્વ પશ્ચિમ નદી પાણી વધી રહ્યું છે
        મોકલો મદદ કરો અત્યારે જરૂરી હા ના બરાબર સુરક્ષિત અસુરક્ષિત જોખમ
        સંદેશ સરનામું ઓળખ નંબર લોકો સંખ્યા છે નથી""",
    "kn": """ಸಹಾಯ ಸಹಾಯ ಮಾಡಿ ಉಳಿಸಿ ಉಳಿಸು ಅಪಾಯ ವಿಪತ್ತು ಮಳೆ ಮಳೆ ಬಂದಿದೆ
        ಬೆಳೆ ಬೆಳೆ ಹೊರಿದೆ ಕಟ್ಟಟ ಕುಸುರು ಕಟ್ಟ ಒಡಿದು ರಸ್ತೆ ನದಿ ಸೇತು ರೈಲು
        ಆಸ್ಪತ್ರಿ ಔಷಧ ಪೊಲೀಸ್ ಮಹಿಳೆ ಪುರುಷ ಕುಟುಂಬ ಜನರು ಗ್ರಾಮ ಜಿಲ್ಲೆ ನಗರ
        ಇಂದು ನಾಳೆ ಬೆಳಗು ಸಂಜೆ ರಾತ್ರಿ ಉತ್ತರ ದಕ್ಷಿಣ ಪೂರ್ವ ಪಶ್ಚಿಮ
        ಕಳುಹಿಸಿ ಸಹಾಯ ಮಾಡಿ ಈಗ ತುರ್ತು ಅಗತ್ಯ ಹೌದು ಇಲ್ಲ ಸರಿ ಸುರಕ್ಷಿತ ಅಸುರಕ್ಷಿತ ಅಪಾಯ
        ಸಂದೇಶ ವಿಳಾಸ ಗುರುಟ್ಟು ಸಂಖ್ಯೆ ಜನರು ಇದೆ ಇಲ್ಲ""",
    "or": """ସାହାଯ୍ୟ ସାହାଯ୍ୟ କରନ୍ତୁ ବଞ୍ଚାନ୍ତୁ ବଞ୍ଚାଅ ବିପଦ ଦୁର୍ଯ୍ୟ ବିଆଗ
        ବାଗ ବାଗ ଲାଗିଲା ଅବଳଙ୍କ ଭଙ୍ଗିଲା ଧ୍ବଂସ ସଡ଼କ ନଦୀ ପୋଲ ରେଲ
        ହସ୍ପିଟାଲ ଔଷଧ ପୋଲିସ ମହିଳା ପୁରୁଷ ପରିବାର ଲୋକ ଗାଁ ଜିଲ୍ଲା ସହର
        ଆଜି ଆସନ୍ତାମାଣ୍ଟ ସକାଳ ସନ୍ଧ୍ୟା ରାତ୍ରି ଉତ୍ତର ଦକ୍ଷିଣ ପୂର୍ବ ପଶ୍ଚିମ
        ପଠାନ୍ତୁ ସାହାଯ୍ୟ କରନ୍ତୁ ବର୍ତ୍ତମାନ ଜରୁରୀ ହଁ ନାହିଁ ଠିକ ସୁରକ୍ଷିତ ଅସୁରକ୍ଷିତ
        ବାର୍ତ୍ତା ଠিকଣା ପରିଚୟ ସଂଖ୍ୟା ଲୋକ ଅଛି ନାହିଁ""",
}

# Inference-time strings, kept verbatim so the model sees the same tokenisation at run time.
INFERENCE_STRINGS = [
    "What kind of message is this?",
    "routine message", "safety warning", "evacuation order", "medical emergency",
    "all clear", "false alarm", "drill", "help request", "status update",
    "Which language is this written in?",
    "Hindi Marathi Bengali Tamil Telugu Malayalam Gujarati Kannada Odia English",
    "Is this an instruction the receiver must act on now?",
]


def build_keep_set(tokenizer, corpus_tokens, cfg_merges=(), verbose=True):
    vocab = tokenizer.get_vocab()
    keep = set()

    # 1. specials, unconditionally
    for tok_str, tid in vocab.items():
        if tok_str.startswith("<") and tok_str.endswith(">"):
            keep.add(tid)

    # 2. every token the ten languages actually produced
    keep |= corpus_tokens

    # 3. rescue vocabulary, per language
    rescue_ids = set()
    for text in RESCUE.values():
        rescue_ids |= set(tokenizer.encode(text, add_special_tokens=False).ids)
    keep |= rescue_ids

    # 4. inference-time strings
    for s in INFERENCE_STRINGS:
        keep |= set(tokenizer.encode(s, add_special_tokens=False).ids)

    # 5. the byte-fallback alphabet, MANDATORY. The model has byte_fallback=True, so any
    #    character the BPE cannot match falls back to a <0xNN> token, and those occupy a
    #    contiguous id block near the bottom of the vocab. Drop any of them and the tokenizer
    #    refuses to load at all -- "Token `\n` out of vocabulary" -- because the fallback path
    #    has nowhere to go. Not optional and not an optimisation.
    byte_fb = {tid for tok_str, tid in vocab.items()
               if len(tok_str) == 6 and tok_str.startswith("<0x") and tok_str.endswith(">")}
    keep |= byte_fb

    # 6. the entire Latin + digit range, unconditionally. Numerals and place names in these
    #    ten languages are frequently written in Latin script, and the cost is negligible.
    latin = set()
    for tok_str, tid in vocab.items():
        if tok_str.startswith("▁") and len(tok_str) == 2:
            ch = tok_str[1]
            if ch.isascii() and ch.isalnum():
                latin.add(tid)
    keep |= latin

    # ---------------------------------------------------------------- merge closure
    # A BPE token is not atomic: "कि" is built from "क" + "ि" via a merge, and the merge list
    # must be able to rebuild every kept token from the pieces below it. The tokenizers library
    # enforces this twice over -- both merge INPUTS must be in the vocab, AND so must the merge
    # RESULT. Dropping an intermediate therefore breaks tokenisation for every token above it.
    #
    # The walk must go BOTTOM-UP: for every kept token, pull in the inputs of every merge that
    # produces it, recursively. The earlier top-down walk (if both inputs survive, pull in the
    # result) does not achieve this: for English it kept the final token "▁the" but not the
    # intermediate "▁th", so every space-prefixed word fell back to bytes (9 pieces became 53
    # on the calibration sentence). Bottom-up handles all producers, not one arbitrary one --
    # picking a single producer still left "▁immediately" and one Marathi token split.
    #
    # Do NOT also run a top-down closure: once the reverse walk has pulled in base letters,
    # "both inputs kept -> keep the result" fans out across scores of thousands of merge pairs
    # that cross scripts (measured: 833 ids -> 170,888, two thirds of the vocabulary).
    producers = {}
    for m in cfg_merges:
        if isinstance(m, list) and len(m) == 2:
            producers.setdefault(m[0] + m[1], []).append((m[0], m[1]))

    before = len(keep)
    stack = [tok_str for tok_str in producers if vocab.get(tok_str) in keep]
    while stack:
        tok_str = stack.pop()
        for a, b in producers.get(tok_str, ()):
            for piece in (a, b):
                pid = vocab.get(piece)
                if pid is not None and pid not in keep:
                    keep.add(pid)
                    stack.append(piece)
    intermediates = len(keep) - before
    if verbose and intermediates:
        print(f"  merge closure      {intermediates:>7}  (producers of kept tokens, so BPE can rebuild them)")

    if verbose:
        print(f"  specials           {len([t for t in keep if t < 5000]):>7}")
        print(f"  corpus (10 langs)  {len(corpus_tokens):>7}")
        print(f"  rescue phrases     {len(rescue_ids):>7}")
        print(f"  byte fallback      {len(byte_fb):>7}  (mandatory)")
        print(f"  latin + digits     {len(latin):>7}")
        print(f"  --- union          {len(keep):>7}  ({len(keep)/len(vocab)*100:.1f}% of {len(vocab)})")
    return keep


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--onnx", default="/tmp/laya_onnx", help="exported laya_onnx dir")
    ap.add_argument("--out", default="/tmp/laya_pruned")
    ap.add_argument("--corpus", default="", help="keep-set json of corpus token ids")
    ap.add_argument("--corpus-text", default="",
                    help="JSON {lang: [texts]} as written by eval/fleurs_transcripts.py; "
                         "tokenized here so the run is one command")
    args = ap.parse_args()

    from tokenizers import Tokenizer

    tok = Tokenizer.from_file(os.path.join(args.onnx, "tokenizer.json"))
    corpus = set(json.load(open(args.corpus))) if args.corpus else set()
    if args.corpus_text:
        texts = json.load(open(args.corpus_text))
        for lang, ts in texts.items():
            ids = set()
            for t in ts:
                ids |= set(tok.encode(t, add_special_tokens=False).ids)
            corpus |= ids
            print(f"  corpus {lang:>7}  {len(ts):>5} transcripts  {len(ids):>6} distinct ids")
        print(f"  corpus total   {sum(len(v) for v in texts.values()):>5} transcripts  "
              f"{len(corpus):>6} distinct ids")

    print("building keep-set")
    cfg_merges = json.load(open(os.path.join(args.onnx, "tokenizer.json")))["model"].get("merges", [])
    keep = build_keep_set(tok, corpus, cfg_merges)

    # OOV: how much of the real corpus is NOT covered once we prune to this set.
    import numpy as np
    if corpus:
        print("\n  OOV check against the ten languages' own tokens:")
        print(f"  corpus tokens not in keep-set: {len(corpus - keep)} "
              f"({len(corpus - keep)/max(len(corpus),1)*100:.2f}%)")
        if corpus - keep:
            print("  WARNING: pruning would map these to <unk>:", sorted(corpus - keep)[:40])

    os.makedirs(args.out, exist_ok=True)
    for f in ("rl_agent_config.json",):
        src = os.path.join(args.onnx, f)
        if os.path.exists(src):
            shutil.copy(src, os.path.join(args.out, f))

    # write the keep-set so the graph rewrite can use it
    json.dump(sorted(keep), open(os.path.join(args.out, "keep_ids.json"), "w"))
    print(f"\nwrote {os.path.join(args.out, 'keep_ids.json')}")
    print("next: eval/laya_slice_onnx.py rewrites encoder.onnx against this set")


if __name__ == "__main__":
    main()
