plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}


android {
    namespace = "com.ziyou.ime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ziyou.ime"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // Debug 构建也 strip native 库，减小 APK 体积加速部署
            isJniDebuggable = false
        }
        release {
            // JNI 重度应用：R8 会按名反射查找 core 包下的类/方法（见 proguard-rules.pro 的
            // -keep com.ziyou.ime.core.**），故开启压缩/混淆以减小体积，但关闭激进优化
            // 以避免破坏 JNI 符号与反射调用。二者组合是有意为之，并非冲突。
            optimization {
                enable = false
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/librime_jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {

    // ===== 内部模块：纯逻辑层（无 Android UI / 无 JNI 依赖）=====
    implementation(project(":core-logic"))

    // ===== AndroidX Core =====
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ===== Jetpack Compose（单一 BOM 统一版本）=====
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ===== Coroutines =====
    implementation(libs.kotlinx.coroutines.android)

    // ===== Preferences =====
    implementation(libs.androidx.preference.ktx)

    // ===== Debug 工具 =====
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ===== 单元测试 =====
    testImplementation(libs.junit)

    // ===== 仪器化测试 =====
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
