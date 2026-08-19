# 字由输入法 ProGuard rules
# 注：Rime JNI 保活规则已迁至 :ime-sdk/consumer-rules.pro（随 AAR 下发，P4）

# sherpa-onnx 语音识别：JNI 层会反射构造 OnlineRecognizerResult 等 Kotlin 类型，
# 且 native 方法名不可混淆
-keep class com.k2fsa.sherpa.onnx.** { *; }
