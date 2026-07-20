# 字由输入法 ProGuard rules
-keep class com.ziyou.ime.core.** { *; }
-keepclassmembers class com.ziyou.ime.core.RimeNative {
    native <methods>;
}
