plugins {
    alias(libs.plugins.android.library)
}

/**
 * :rime-sdk —— 底层 SDK 模块（librime 交互 + 输入法通用基础能力，交付 AAR）。
 *
 * 承载五层引擎栈的 Core/JNI/Engine 层：RimeNative(JNI) · RimeApi/SimpleRimeImpl ·
 * RimeDispatcher · RimeEngine/RimeSession · 通用资源部署框架（AssetDeployer）。
 * 依赖方向：:app → :rime-sdk（单向，编译器强制边界）。
 *
 * 迁移期约束（docs/SDK模块拆分重构方案.md §8 R1）：迁入类保持原包名
 * （com.ziyou.ime.core / daemon / config），避免 C++ 侧 21 个
 * Java_com_ziyou_ime_core_RimeNative_* JNI 导出符号同步改名。
 */

// 发布 ABI 列表与 :app 同源（-Pziyou.abis=... 覆盖）；
// 每个 ABI 必须先经 librime-prebuilt/build.sh 产出 libs/<abi>/librime.a，
// 否则 CMake 配置阶段即失败（见 CMakeLists.txt 的 FATAL_ERROR 守卫）
val releaseAbis = (project.findProperty("ziyou.abis") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

android {
    namespace = "com.ziyou.ime.sdk"
    // 与 :app 对齐：compileSdk 37（androidx 新库硬性要求）
    compileSdk = 37

    defaultConfig {
        minSdk = 24

        ndk {
            abiFilters += releaseAbis
        }

        externalNativeBuild {
            cmake {
                // 模块开关必须与 librime-prebuilt 侧 WITH_* 编入情况一一配对，
                // 不一致即链接失败（undefined symbol）。详见 :app 原注释与
                // docs/SDK模块拆分重构方案.md §6.1
                arguments("-DWITH_PREDICT=ON")
                arguments("-DWITH_LUA=ON")
                arguments("-DWITH_WITOGRAM=ON")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    lint {
        // 与 :app 同因：AGP lint AsyncExecutionService NPE 为内部 bug，不阻断构建
        abortOnError = false
    }

    testOptions {
        unitTests {
            // Android 桩方法返回默认值而非抛出 "not mocked" 异常
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // SDK 仅依赖协程核心（RimeDispatcher/RimeSession 的 suspend 调度），
    // 不引入 coroutines-android，保持 AAR 依赖面最小
    implementation(libs.kotlinx.coroutines.core)

    // ===== 单元测试（随类迁入：RimeDispatcher/SimpleRimeImpl/RimeSession 生命周期）=====
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
