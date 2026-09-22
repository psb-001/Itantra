# sherpa-onnx reaches native code through JNI. Stripping or renaming these classes breaks
# the native bridge at runtime, so keep them.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
