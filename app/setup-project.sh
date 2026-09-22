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
# ASR: 348 MiB. VAD: 629 KB.
ASR_SRC="$OUT/omni300m"
VAD_SRC="$OUT/omni300m_vad/silero_vad.onnx"
ASR_DST="$SRC_ASSETS/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"

if [ -f "$ASR_SRC/model.int8.onnx" ]; then
  mkdir -p "$ASR_DST"
  cp -v "$ASR_SRC/model.int8.onnx" "$ASR_DST/"
  cp -v "$ASR_SRC/tokens.txt"      "$ASR_DST/"
else
  echo "  ASR model not found at $ASR_SRC"
fi

if [ -f "$VAD_SRC" ]; then
  cp -v "$VAD_SRC" "$SRC_ASSETS/silero_vad.onnx"
else
  echo "  VAD model not found at $VAD_SRC"
fi

# TTS voices + espeak data. Only the two we have.
TTS_SRC="$OUT/tts"
TTS_DST="$SRC_ASSETS/tts"
mkdir -p "$TTS_DST"
for v in hi_IN-pratham-medium en_US-lessac-medium; do
  [ -f "$TTS_SRC/$v.onnx" ]   && cp -v "$TTS_SRC/$v.onnx"   "$TTS_DST/"
  [ -f "$TTS_SRC/$v.tokens" ] && cp -v "$TTS_SRC/$v.tokens" "$TTS_DST/"
done
if [ -d "$TTS_SRC/espeak-ng-data" ]; then
  echo "  copying espeak-ng-data (this takes a moment)"
  cp -R "$TTS_SRC/espeak-ng-data" "$TTS_DST/"
fi

echo
echo "=== done. project state: ==="
echo "  kotlin API : $(ls -1 "$SRC_JAVA" 2>/dev/null | wc -l | tr -d ' ') files"
echo "  jniLibs    : $(ls -1 "$SRC_JNI" 2>/dev/null | wc -l | tr -d ' ') files"
echo "  assets     : $(du -sh "$SRC_ASSETS" 2>/dev/null | cut -f1)"
echo
echo "Next:  cd $PROJ && ./gradlew assembleDebug"
