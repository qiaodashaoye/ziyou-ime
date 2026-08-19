# rime-sdk consumer ProGuard rules（随 AAR 下发给所有集成方）
#
# JNI 反射查找的 Proto/回调类（objconv.h 缓存 jclass/jmethodID）与
# RimeNative 的 native 方法名不可混淆（docs/SDK模块拆分重构方案.md §6.3 / §8 R8）。
-keep class com.ziyou.ime.core.** { *; }
-keepclassmembers class com.ziyou.ime.core.RimeNative {
    native <methods>;
}
