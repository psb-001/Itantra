#!/bin/zsh
# Prepare the iTantra Android project: vendor the sherpa-onnx API, native libs and models.
#
# Copies only. Deletes nothing.
#
# Usage:  ./setup-project.sh
set -u

PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJ/../.."              # output/option_a_stt
REPO="$PROJ/../../../.."       # repo root (four levels up)
APK_DIR="$REPO/sherpa_onnx_1_13_8_arm64_v8a_vad_asr_1600_omnilingual_asr_300M_ctc.apk-decompiled"

SRC_JAVA="$PROJ/app/src/main/java/com/k2fsa/sherpa/onnx"
SRC_JNI="$PROJ/app/src/main/jniLibs/arm64-v8a"
SRC_ASSETS="$PROJ/app/src/main/assets"

mkdir -p "$SRC_JAVA" "$SRC_JNI" "$SRC_ASSETS"

echo "=== 1. sherpa-onnx Kotlin API (from the upstream repo, Apache 2.0) ==="
BASE="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api"
# Only the files this app needs. Adding more is harmless but slows the build.
for f in FeatureConfig.kt OfflineRecognizer.kt OfflineStream.kt Vad.kt Tts.kt \
         HomophoneReplacerConfig.kt QnnConfig.kt WaveReader.kt; do
  if [ -f "$SRC_JAVA/$f" ]; then
    echo "  $f  (already present, skipping)"
  else
    code=$(curl -sL --max-time 60 -o "$SRC_JAVA/$f" -w "%{http_code}" "$BASE/$f")
    echo "  $f  http=$code"
  fi
done

echo
echo "=== 2. native libraries ==="
# IMPORTANT: do NOT copy these out of the reference APK.
#
# The SherpaOnnxVadAsr APK is speech-to-TEXT only, so its libsherpa-onnx-jni.so was built
# WITHOUT TTS support. Reusing it produces:
#     UnsatisfiedLinkError: No implementation found for ... OfflineTts.newFromAsset(...)
# at runtime, on the LISTENER phone, with no warning at build time. Verified 20 Sep 2026:
# the APK's library contains 0 OfflineTts symbols; the official one contains 58.
#
# Always take the official release, which is built with the full feature set.
JNI_TARBALL="sherpa-onnx-v1.13.8-android.tar.bz2"
JNI_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/$JNI_TARBALL"

if [ -f "$SRC_JNI/libsherpa-onnx-jni.so" ] && \
   [ "$(strings "$SRC_JNI/libsherpa-onnx-jni.so" 2>/dev/null | grep -c OfflineTts)" -gt 0 ]; then
  echo "  jniLibs already present and contain TTS symbols — skipping"
else
  echo "  fetching the official Android jniLibs (46 MB)"
  if [ ! -f "/tmp/$JNI_TARBALL" ]; then
    curl -L --max-time 900 "$JNI_URL" -o "/tmp/$JNI_TARBALL" || {
      echo "  download failed. Manual step:"; echo "    curl -L $JNI_URL -o /tmp/$JNI_TARBALL"; }
  fi
  if [ -f "/tmp/$JNI_TARBALL" ]; then
    mkdir -p /tmp/sherpa_jni && tar -xjf "/tmp/$JNI_TARBALL" -C /tmp/sherpa_jni
    cp -v /tmp/sherpa_jni/jniLibs/arm64-v8a/libsherpa-onnx-jni.so "$SRC_JNI/"
    cp -v /tmp/sherpa_jni/jniLibs/arm64-v8a/libonnxruntime.so     "$SRC_JNI/"
    n=$(strings "$SRC_JNI/libsherpa-onnx-jni.so" | grep -c OfflineTts)
    echo "  verified: $n OfflineTts symbols present"
    [ "$n" -gt 0 ] || echo "  !! WARNING: still no TTS symbols — the listener phone will crash"
  fi
fi

echo
echo "=== 3. models into assets ==="
# Resolve the repo root from this script's location, so the paths work regardless of cwd.
REPO="$(cd "$(dirname "$0")/.." && pwd)"

# ASR: the v2 Omnilingual model with its CTC output vocabulary pruned to the nine scripts the
# ten required languages use (10,288 -> 1,002 tokens). Built by eval/prune_vocab.py; see
# docs/VOCAB_PRUNE_FINDINGS.md. Shipping the unpruned v1 here is what caused "hello" to come
# back as "هello" (Arabic heh) and then be transmitted as "ello".
ASR_SRC="$REPO/models/asr-v3-10lang/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-int8"
VAD_SRC="$REPO/models/vad/silero_vad.onnx"
ASR_DST="$SRC_ASSETS/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8"

if [ ! -f "$ASR_SRC/model.int8.onnx" ]; then
  echo "  pruned ASR model missing at $ASR_SRC — building it now"
  "$REPO/.venv/bin/python" "$REPO/eval/prune_vocab.py" \
    --src "$REPO/models/asr-v2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05" \
    --dst "$ASR_SRC" || { echo "  could not build the pruned model — aborting" >&2; exit 1; }
fi

if [ -f "$ASR_SRC/model.int8.onnx" ]; then
  mkdir -p "$ASR_DST"
  cp -v "$ASR_SRC/model.int8.onnx" "$ASR_DST/"
  cp -v "$ASR_SRC/tokens.txt"      "$ASR_DST/"
else
  echo "  ASR model not found at $ASR_SRC" >&2; exit 1
fi

if [ -f "$VAD_SRC" ]; then
  cp -v "$VAD_SRC" "$SRC_ASSETS/silero_vad.onnx"
else
  echo "  VAD model not found at $VAD_SRC"
fi

# TTS voices + espeak data.
# Six of the ten required languages have a bundled voice: hi, mr, bn, ml, te, en. Gujarati,
# Kannada, Tamil and Odia have none — see docs/TTS_LICENCE_AUDIT.md for why no permissively
# licensed voice exists for them that we can integrate.
#
# fetch_tts_voices.py derives each voice's phoneme table from its own .onnx.json and patches
# the ONNX metadata that sherpa-onnx requires. Do not hand-copy .onnx files: raw Piper models
# have no metadata_props and sherpa-onnx aborts natively with
# "'sample_rate' does not exist in the metadata".
TTS_SRC="$REPO/tts"
TTS_DST="$SRC_ASSETS/tts"
mkdir -p "$TTS_DST"
for v in hi_IN-pratham-medium en_US-lessac-medium; do
  [ -f "$TTS_SRC/$v.onnx" ]   && cp -v "$TTS_SRC/$v.onnx"   "$TTS_DST/"
  [ -f "$TTS_SRC/$v.tokens" ] && cp -v "$TTS_SRC/$v.tokens" "$TTS_DST/"
done

echo "  fetching the four additional MIT Piper voices (mr, bn, ml, te)"
"$REPO/.venv/bin/python" "$REPO/eval/fetch_tts_voices.py" --dest "$TTS_DST" \
  || echo "  !! voice fetch failed — the app will still build, but those languages go text-only"

if [ -d "$TTS_SRC/espeak-ng-data" ]; then
  echo "  copying espeak-ng-data (this takes a moment)"
  cp -R "$TTS_SRC/espeak-ng-data" "$TTS_DST/"
  # espeak-ng ships 113 language dictionaries (18 MB) and we need 10. Keeping Russian (8.1 MB),
  # Mandarin (1.5 MB) and 101 others in the APK is 15.4 MB of dead weight. The script keeps the
  # shared phoneme tables and the lang/ and voices/ family directories whole, because those are
  # load-bearing and small; it deletes only *_dict.
  echo "  trimming espeak-ng-data to the ten required languages"
  "$REPO/.venv/bin/python" "$REPO/eval/trim_espeak.py" --dir "$TTS_DST/espeak-ng-data" \
    || echo "  !! trim failed - voices may be missing for some languages"
  echo "  verifying every voice still speaks after the trim"
  "$REPO/.venv/bin/python" "$REPO/eval/tts_smoke.py" --tts-dir "$TTS_DST" \
    || echo "  !! SMOKE TEST FAILED - do not ship this build, a dictionary was needed"
fi

echo
echo "=== done. project state: ==="
echo "  kotlin API : $(ls -1 "$SRC_JAVA" 2>/dev/null | wc -l | tr -d ' ') files"
echo "  jniLibs    : $(ls -1 "$SRC_JNI" 2>/dev/null | wc -l | tr -d ' ') files"
echo "  assets     : $(du -sh "$SRC_ASSETS" 2>/dev/null | cut -f1)"
echo
echo "Next:  cd $PROJ && ./gradlew assembleDebug"
