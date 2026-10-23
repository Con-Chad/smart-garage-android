# ONNX Runtime binds its Java API to native code over JNI (class/field/method lookups by name),
# so R8 must neither rename nor strip anything under ai.onnxruntime.
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class ai.onnxruntime.** { native <methods>; }
