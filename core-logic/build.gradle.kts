plugins {
    alias(libs.plugins.android.library)
}

/**
 * :core-logic —— 纯逻辑模块（不含 Android UI / 不含 JNI）。
 *
 * 承载可独立单元测试的纯逻辑。当前迁入：T9 拼音双向映射（util 包）。
 * 采用 Android library 形态以复用 AGP 内置 Kotlin 工具链，但模块内代码不依赖任何
 * Android framework 类型，保持纯逻辑。依赖方向：:app → :core-logic（单向，编译器强制边界）。
 */
android {
    namespace = "com.ziyou.ime.corelogic"
    // 与 :app 对齐：compileSdk 37（androidx 新库硬性要求），模块内仍为纯逻辑无 framework 依赖
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
