"""SIH26173 iTantra deck in the official SIH template. Team B.J.P (Bug Janta Party)."""
from pathlib import Path
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE

OUT = Path(__file__).resolve().parent
prs = Presentation(OUT / "template.pptx")
last = prs.slides._sldIdLst[-1]
prs.part.drop_rel(last.rId)
prs.slides._sldIdLst.remove(last)

NAVY = "142D4E"; TEAL = "007F82"; INK = "24384B"; LIGHT = "EFF5F8"; ORANGE = "B85C13"

def text(slide, x, y, w, h, value, size=18, color=INK, bold=False):
    s = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    f = s.text_frame
    f.word_wrap = True
    f.margin_left = Pt(0); f.margin_right = Pt(0); f.margin_top = Pt(0); f.margin_bottom = Pt(0)
    for i, line in enumerate(value.split("\n")):
        p = f.paragraphs[0] if i == 0 else f.add_paragraph()
        p.text = line
        p.font.name = "Arial"; p.font.size = Pt(size); p.font.bold = bold
        p.font.color.rgb = RGBColor.from_string(color); p.space_after = Pt(7)
    return s

def panel(slide, x, y, w, h, title, body, size=18):
    s = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    s.fill.solid(); s.fill.fore_color.rgb = RGBColor.from_string(LIGHT); s.line.fill.background()
    text(slide, x + .18, y + .14, w - .36, .5, title, 16, TEAL, True)
    text(slide, x + .18, y + .72, w - .36, h - .85, body, size)

heads = ["TITLE PAGE", "IDEA TITLE", "TECHNICAL APPROACH", "FEASIBILITY AND VIABILITY",
         "IMPACT AND BENEFITS", "RESEARCH  AND REFERENCES"]
for i, sl in enumerate(prs.slides):
    for sh in list(sl.shapes):
        if sh.has_text_frame and sh.text.strip():
            sh._element.getparent().remove(sh._element)
    text(sl, .38, .48, 11.5, .62, "SMART INDIA HACKATHON 2026" if i == 0 else heads[i], 29, NAVY, True)
    if i:
        text(sl, .45, 7.05, 3.6, .25, "B.J.P (Bug Janta Party)", 10, NAVY)
        text(sl, 5.1, 7.05, 4.8, .25, "@SIH Idea submission- Template", 10, NAVY)
        text(sl, 12.4, 7.05, .4, .25, str(i + 1), 10, NAVY)

# ---------------- SLIDE 1 : TITLE ----------------
s = prs.slides[0]
text(s, .5, 1.45, 11.7, .65, "iTantra — Offline Voice Transceiver", 36, TEAL, True)
text(s, .5, 2.2, 11.7, .65, "Speech to text to speech, fully offline, over low-bitrate links.", 21, NAVY, True)
text(s, .5, 3.1, 11.7, 2.3,
     "Problem Statement ID – SIH26173\n"
     "Problem Statement Title – iTantra - Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links\n"
     "Theme – Smart Automation\n"
     "PS Category – Software", 18)
text(s, .5, 5.55, 11.7, 1.0,
     "Team ID – [PORTAL TEAM ID]\nTeam Name (Registered on portal) – B.J.P (Bug Janta Party)", 19, NAVY, True)
text(s, .5, 6.7, 11.7, .35,
     "PROPOSAL WITH LOCAL MEASUREMENTS • Integrated two-phone prototype and Android performance not yet validated", 11, ORANGE)

# ---------------- SLIDE 2 : IDEA TITLE ----------------
s = prs.slides[1]
text(s, .45, 1.35, 12, .52, "iTantra | send voice as text, not as audio", 25, TEAL, True)
panel(s, .45, 2.03, 6.0, 2.2, "Proposed Solution (Describe your Idea/Solution/Prototype)",
      "An offline Android app: speech in, text out, tiny packet over Wi-Fi/Bluetooth, speech back out on the receiving phone.", 19)
panel(s, 6.65, 2.03, 6.2, 2.2, "Detailed explanation of the proposed solution",
      "Push-to-talk works like a walkie-talkie. When idle, both phones run a full-duplex phone-style path. Alerts play at maximum volume and cannot be interrupted.", 18)
panel(s, .45, 4.42, 6.0, 2.2, "How it addresses the problem",
      "Audio is too large for low-data-rate links. One spoken sentence becomes ~50-110 bytes of UTF-8 text — roughly 1,000x smaller — so voice fits where it could not before.", 18)
panel(s, 6.65, 4.42, 6.2, 2.2, "Innovation and uniqueness of the solution",
      "Asymmetric pipeline: the speaker's phone carries the ASR cost, the listener's phone carries TTS. Idle listening runs only a voice-activity detector, so idle CPU stays near zero by design.", 17)

# ---------------- SLIDE 3 : TECHNICAL APPROACH ----------------
s = prs.slides[2]
text(s, .45, 1.45, 12, .5, "Technologies to be used (e.g. programming languages, frameworks, hardware)", 16, TEAL, True)
text(s, .45, 1.98, 12, 1.0,
     "Kotlin Android app • sherpa-onnx (open-source, ONNX runtime) • Silero VAD 629 KB, RTF 0.004\n"
     "ASR: Meta Omnilingual CTC 300M INT8, Apache 2.0 • TTS: Piper VITS voices, MIT licence • Wi-Fi Direct / Bluetooth RFCOMM transport", 16)
text(s, .45, 3.06, 12, .6, "Methodology and process for implementation (Flow Charts/Images/ working prototype)", 16, TEAL, True)
for x, title, body in [
    (.45, "EITHER PHONE — TALKING", "Mic 16 kHz mono\nSilero VAD cuts sentence\nOmnilingual ASR → text\nLanguage filter → packet"),
    (4.7, "TRANSPORT", "Bluetooth RFCOMM (primary)\nWi-Fi Direct fallback\nUTF-8 text only\n~50–250 bytes per message"),
    (8.95, "EITHER PHONE — RECEIVING", "Piper TTS (hi / en)\nAudio out 22050 Hz\nMax volume, no interrupt\nAlert loops until read")]:
    panel(s, x, 3.70, 3.9, 2.12, title, body, 14)
text(s, 4.39, 4.5, .35, .4, "→", 23, TEAL, True)
text(s, 8.64, 4.5, .35, .4, "→", 23, TEAL, True)
text(s, .5, 6.0, 12, .6,
     "Desktop: ASR RTF 0.039–0.043, TTS RTF 0.032–0.044. On Android: ASR RTF 0.120–0.127, TTS RTF 0.055–0.058, 536 MB PSS with all three models resident. Mouth-to-ear ≈ 0.9–1.0 s.", 15, NAVY, True)
text(s, .5, 6.62, 12, .27,
     "All Android figures measured on an API 34 arm64 emulator, not a low-end phone. A real budget handset will be slower.", 11, ORANGE)

# ---------------- SLIDE 4 : FEASIBILITY AND VIABILITY ----------------
s = prs.slides[3]
panel(s, .45, 1.56, 3.95, 3.6, "Analysis of the feasibility of the idea",
      "• Every component is open-source and permissively licensed (Apache 2.0 / MIT).\n"
      "• All 10 required languages scored locally on real human speech.\n"
      "• An official Android reference app already runs this exact ASR model with VAD.", 16)
panel(s, 4.62, 1.56, 3.95, 3.6, "Potential challenges and risks",
      "• Latency and RAM measured on an emulator, not a low-end phone.\n"
      "• Under noise, Hindi script retention falls to ~50% at 15 dB SNR.\n"
      "• TTS voices: 7 of 10 neural; Kannada, Tamil, Odia use espeak-ng.\n"
      "• Wi-Fi Direct behaves differently across Android vendors.", 15)
panel(s, 8.79, 1.56, 4.05, 3.6, "Strategies for overcoming these challenges",
      "• Benchmark on the cheapest available phone first.\n"
      "• Report CER alongside WER for complex scripts.\n"
      "• Scope the demonstrator to Hindi + English, then extend.\n"
      "• Ship Bluetooth fallback; never depend on one transport.", 16)
text(s, .5, 5.30, 12.4, .36, "Measured accuracy — all 10 required languages, 200 real-speech clips each (FLEURS)", 18, TEAL, True)
text(s, .5, 5.62, 12.4, .22, "Normalised character error rate — lower is better", 11, INK)
text(s, .5, 5.84, 12.4, .72,
     "Kannada 0.053   ·   Malayalam 0.059   ·   English 0.067   ·   Gujarati 0.071   ·   Bengali 0.100\n"
     "Telugu 0.100   ·   Marathi 0.108   ·   Hindi 0.113   ·   Tamil 0.129   ·   Odia 0.134", 15)
text(s, .5, 6.62, 12.4, .3,
     "Every required language measured; all under 0.14. TTS round-trip: English 0.030 (strong), Hindi 0.339 (weak — open problem).", 11, ORANGE)

# ---------------- SLIDE 5 : IMPACT AND BENEFITS ----------------
s = prs.slides[4]
text(s, .5, 1.48, 12, .5, "Potential impact on the target audience", 20, TEAL, True)
text(s, .5, 2.05, 12, .85,
     "For field responders, volunteers and citizens on degraded or saturated networks: spoken alerts and instructions "
     "that reach the other end as intelligible speech, without requiring literacy or an internet connection.", 20)
text(s, .5, 3.1, 12, .5, "Benefits of the solution (social, economic, environmental, etc.)", 17, TEAL, True)
panel(s, .45, 3.72, 3.95, 1.8, "SOCIAL / proposed",
      "Voice in, voice out — inclusive of users who cannot read or type. Alerts are heard, not read.", 16)
panel(s, 4.62, 3.72, 3.95, 1.8, "ECONOMIC / proposed",
      "No paid API, no cloud, no proprietary SDK. Runs on phones people already own; only free open-source models.", 16)
panel(s, 8.79, 3.72, 4.05, 1.8, "ENVIRONMENTAL / proposed",
      "Text instead of audio lowers transmitted energy per message; effect on battery runtime must still be measured.", 16)
text(s, .5, 5.72, 12, .8,
     "Measured so far: all 10 required languages scored on 200 real-speech clips each; Hindi and English round-trip with meaning intact; ~1,000x bandwidth reduction per sentence.\n"
     "Not yet claimed: field accuracy in noise, phone-class latency, human TTS legibility panel, battery runtime.", 16, NAVY, True)
text(s, .5, 6.6, 12, .28,
     "Benefits are proposed outcomes, not measured results. No claim of lives saved or guaranteed delivery at this stage.", 11, ORANGE)

# ---------------- SLIDE 6 : RESEARCH AND REFERENCES ----------------
s = prs.slides[5]
text(s, .5, 1.48, 12, .5, "Details / Links of the reference and research work", 19, TEAL, True)
text(s, .5, 2.1, 12, 4.4,
     "Models and runtime\n"
     "• csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12 — ASR, Apache 2.0, 348 MiB\n"
     "• rhasspy/piper-voices hi_IN pratham and en_US lessac — TTS, MIT licence, ~60 MiB each\n"
     "• k2-fsa/sherpa-onnx — Silero VAD (629 KB) and the SherpaOnnxVadAsr Android reference app\n"
     "• Pre-built APK verified: sherpa-onnx-1.13.8-arm64-v8a-vad_asr-1600-omnilingual_asr_300M_ctc_int8.apk, 284 MiB\n\n"
     "Evaluation data\n"
     "• google/fleurs (CC-BY-4.0) — real recorded human speech used for per-language accuracy\n\n"
     "Platform documentation\n"
     "• k2-fsa.github.io/sherpa/onnx — VAD, ASR, TTS and Android guides\n"
     "• developer.android.com — Wi-Fi Direct and Bluetooth P2P transport\n\n"
     "Local evidence: fleurs_eval_full.py, tts_intelligibility.py, measure_ram_all.py, end_to_end.py — all reproducible from this submission.",
     15)

prs.save(OUT / "iTantra_SIH26173_DRAFT.pptx")
print("saved", OUT / "iTantra_SIH26173_DRAFT.pptx")
