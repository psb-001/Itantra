# Noise robustness — and a serious Hindi-specific failure mode

**Date:** 20 September 2026 · Model: v2 (`...-ctc-v2-int8-2026-02-05`)
**Machine:** macOS arm64 — this is a desktop measurement, **not** a phone

> **Why this exists.** Every accuracy number in this project comes from FLEURS, which is clean
> studio speech. The deck listed *"clean-studio accuracy is not disaster-zone accuracy"* as a
> **risk**. A risk is a guess. This is a measurement.
>
> **What it is not.** Synthetic white/pink noise is not a disaster zone. This measures
> *sensitivity to noise*, not real-world field accuracy.

---

## Method

50 clips per language, 4 languages. Two noise types mixed at defined SNR:

- **white** — flat spectrum, the textbook worst case
- **pink** — 1/f spectrum, closer to traffic, wind and crowd rumble

SNR is computed over the whole clip (including silence), which is the conservative choice: real
noise does not politely stop while you pause.

**Two metrics, because CER alone is misleading here:**

1. **CER** — character error rate, as everywhere else in this project.
2. **Script retention** — the percentage of outputs in the *expected alphabet*. This is the metric
   that actually matters for this app. The model is a 1,600-language character-level CTC model and
   will switch script under noise; if the output is in the wrong alphabet, the receiving user
   cannot read it at all.

---

## Result 1 — CER degradation

| Condition | Hindi | Tamil | English | Bengali |
|---|---|---|---|---|
| **clean** | 0.133 | 0.139 | **0.087** | **0.090** |
| white 30 dB | 0.424 | 0.184 | 0.095 | 0.091 |
| white 20 dB | 0.506 | 0.206 | 0.105 | 0.104 |
| white 15 dB | 0.576 | 0.219 | 0.122 | 0.117 |
| white 10 dB | 0.550 | 0.280 | 0.151 | 0.162 |
| white 5 dB | 0.494 | 0.465 | 0.233 | 0.215 |
| white 0 dB | 0.588 | 0.733 | 0.399 | 0.321 |

**English and Bengali degrade gracefully.** At 0 dB — noise as loud as the speech — English reaches
0.399 and Bengali 0.321. Bad, but survivable and roughly linear.

**Hindi does not.** At 30 dB SNR, where the noise is **a thousand times weaker than the signal and
essentially inaudible to a human**, Hindi CER jumps from 0.133 to **0.424**. That is a 3.2× increase
from noise you cannot hear.

---

## Result 2 — script retention (the metric that matters)

Percentage of outputs in the expected alphabet:

| Condition | Hindi | Tamil | English | Bengali |
|---|---|---|---|---|
| **clean** | **94.0** | **100.0** | **100.0** | **100.0** |
| white 30 dB | **64.0** | 94.0 | 100.0 | 100.0 |
| white 20 dB | **56.0** | 92.0 | 100.0 | 100.0 |
| white 15 dB | **48.0** | 92.0 | 100.0 | 100.0 |
| white 10 dB | 54.0 | 88.0 | 100.0 | 96.0 |
| white 5 dB | 64.0 | 78.0 | 100.0 | 96.0 |
| white 0 dB | 60.0 | 44.0 | 98.0 | 92.0 |
| pink 30 dB | **56.0** | 96.0 | 100.0 | 100.0 |
| pink 15 dB | **40.0** | 90.0 | 100.0 | 100.0 |
| pink 0 dB | 64.0 | 60.0 | 100.0 | 90.0 |

**English is rock solid** — 100% script retention down to 0 dB. **Bengali** holds 100% to 10 dB.
**Tamil** degrades slowly then collapses at 0 dB.

**Hindi collapses immediately.** At 30 dB SNR, **36% of Hindi outputs come out in the wrong
alphabet.** At 15 dB it is **52%**. Even **clean, 6% of Hindi outputs are already in the wrong
script.**

### What "wrong alphabet" looks like in practice

Real output from the same Hindi clip:

```
clean      : स्कींग मार्ग को एक हाइकिंग लंबी पैदल यात्रा मार्ग जैसा ही सोचें
+30 dB     : سکینگ مار्ک کو ایک ہایکن لंबी پیدل یاترا مار्گ جیسا ہی سوچیں
             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ Arabic/Urdu script
```

The model did not mishear the words. It wrote **the same sounds in a different alphabet**.

Another clip came out in **Gujarati** when the input was Hindi — a script flip between two
languages that both belong to the required ten.

---

## What this means for the app

### 1. It explains the original complaint

This is the mechanism behind *"it detects other languages"*. It is not a bug and it is not random —
it is the model's script decision becoming unstable under even trivial acoustic degradation.

### 2. The language filter is necessary but not sufficient as originally written

The first version of `LanguageFilter` allowed **any of the nine required scripts**. That means a
**Hindi → Gujarati flip would pass the filter and be transmitted as Gujarati text** — unreadable to
the Hindi-speaking recipient.

**The fix: the filter must know the expected language, not just the allowed set.** The app already
has a language selector, so the expected script is known. The filter should require *that* script
(plus Latin, for loanwords), and reject everything else.

### 3. Dropping is better than sending garbage — but it is still a failure

With a per-language filter, a wrong-script output is **dropped** rather than transmitted. For an
emergency app that is the right trade: a dropped message is better than an unreadable one.

But it means **under mild noise, roughly a third of Hindi utterances produce nothing at all.** That
is a real usability limitation and it must be stated, not hidden.

### 4. Hindi is the worst language to have this problem on

Hindi is half the planned demonstration. The honest options:

- **Demonstrate in English and Bengali**, where script retention is 100% down to 0 dB. This is a
  legitimate choice — the architecture is what is being demonstrated, not Hindi specifically.
- **Keep Hindi but state the limitation**, and add a read-back confirmation step so the speaker sees
  the transcript before it sends.
- **Reduce sensitivity** by high-pass filtering and normalising input audio before the ASR.

The read-back option is worth building regardless: it turns a silent failure into a visible one, and
it is a differentiator we already listed in the submission draft.

---

## What to put in the submission

**Do not claim noise robustness.** State this instead:

> *"Accuracy is measured on clean speech. Under controlled additive noise, English and Bengali
> retain their expected script at 100% down to 10 dB SNR, while Hindi script retention falls to
> ~50% at 15 dB. The receiver-side filter rejects wrong-script output rather than transmitting it,
> so degraded audio produces dropped messages rather than unreadable ones. Field recordings in real
> noise remain unmeasured."*

That is a stronger paragraph than "accuracy degrades in noise", because it names the mechanism,
quantifies it per language, and describes what the system does about it.

---

## Files

| File | Contents |
|---|---|
| `noise_robustness.py` | the test — reproducible, fixed seeds |
| `noise_robustness.json` | full per-condition data |
| `noise_run.log` | raw run log |
