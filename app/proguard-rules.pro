# 字由输入法 ProGuard rules
-keep class com.ziyou.ime.core.** { *; }
-keepclassmembers class com.ziyou.ime.core.RimeNative {
    native <methods>;
}

# sherpa-onnx 语音识别：JNI 层会反射构造 OnlineRecognizerResult 等 Kotlin 类型，
# 且 native 方法名不可混淆
-keep class com.k2fsa.sherpa.onnx.** { *; }
