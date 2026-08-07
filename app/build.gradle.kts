import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ===== 发布签名配置（keystore.properties 不入库，见根目录 keystore.properties.template）=====
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// 发布 ABI 列表可通过 -Pziyou.abis=arm64-v8a,armeabi-v7a 覆盖；
// 每个 ABI 必须先经 librime-prebuilt/build.sh 产出 libs/<abi>/librime.a
val releaseAbis = (project.findProperty("ziyou.abis") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

android {
    namespace = "com.ziyou.ime"
    // compileSdk 37 为 androidx.core 1.19 / lifecycle 2.11 / compose-bom 2026.06 的硬性要求；
    // targetSdk 保持 35 不变，不改变运行时行为
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ziyou.ime"
        minSdk = 24
        // targetSdk 36：Google Play 自 2026/8/31 起要求 target API 36+；
        // 16KB 页面对齐已通过链接参数启用，满足 Android 16 对 target 36 应用的强制要求
        targetSdk = 36
        // versionCode 变更会触发 AssetDeployer 重新部署（schema 变更/predict.db 需随升版生效）
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += releaseAbis
        }

        externalNativeBuild {
            cmake {
                // 启用 librime-predict 模块依赖声明（rime_require_module_predict）。
                // 必须与 librime-prebuilt 侧的 WITH_PREDICT 开关一致：
                // 库未编入插件时此处开启会链接失败（undefined symbol）
                arguments("-DWITH_PREDICT=ON")
            }
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
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
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 无 keystore.properties 时产出未签名 APK（CI 校验编译用），有则正式签名
            signingConfig = signingConfigs.findByName("release")
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

    lint {
        // AGP lint 在分析部分 Kotlin 文件时存在内部 bug（AsyncExecutionService NPE），
        // 非代码质量问题；关闭 abortOnError 避免阻断 Release 构建。
        abortOnError = false
    }

    testOptions {
        unitTests {
            // Android 桩方法返回默认值而非抛出 "not mocked" 异常
            isReturnDefaultValues = true
        }
    }
}

// ===== 开发文档同步进 assets（单一来源：docs/，构建时自动拷贝，供 App 内文档页展示）=====

/** 把仓库根 docs/ 下的开发文档拷为 assets/docs/<destName>（ASCII 文件名避免编码问题） */
abstract class SyncDevDocTask : DefaultTask() {
    @get:InputFile
    abstract val guideFile: RegularFileProperty

    /** assets/docs/ 下的目标文件名 */
    @get:Input
    abstract val destName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val destDir = outputDir.get().asFile.resolve("docs").apply { mkdirs() }
        guideFile.get().asFile.copyTo(destDir.resolve(destName.get()), overwrite = true)
    }
}

val syncSkillDevGuide = tasks.register<SyncDevDocTask>("syncSkillDevGuide") {
    description = "同步 docs/自定义技能开发教程.md 到 assets/docs/（App 内文档页展示）"
    group = "ziyou"
    guideFile.set(rootProject.file("docs/自定义技能开发教程.md"))
    destName.set("skill_dev_guide.md")
    outputDir.set(layout.buildDirectory.dir("generated/skillDocsAssets"))
}

val syncSkinDevGuide = tasks.register<SyncDevDocTask>("syncSkinDevGuide") {
    description = "同步 docs/自定义皮肤开发指南.md 到 assets/docs/（App 内文档页展示）"
    group = "ziyou"
    guideFile.set(rootProject.file("docs/自定义皮肤开发指南.md"))
    destName.set("skin_dev_guide.md")
    outputDir.set(layout.buildDirectory.dir("generated/skinDocsAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            syncSkillDevGuide, SyncDevDocTask::outputDir
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            syncSkinDevGuide, SyncDevDocTask::outputDir
        )
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

    // ===== WebView 安全增强（技能插件系统：DOCUMENT_START_SCRIPT 垫片注入）=====
    implementation(libs.androidx.webkit)

    // ===== 流式语音识别（sherpa-onnx 预编译 AAR，不入 git；缺失时先跑 scripts/fetch-sherpa-onnx.sh）=====
    implementation(files("libs/sherpa-onnx-1.13.3.aar"))

    // ===== 无障碍（候选区功能栏 Canvas 虚拟按钮的 ExploreByTouchHelper）=====
    implementation(libs.androidx.customview)

    // ===== Debug 工具 =====
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ===== 单元测试 =====
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // 皮肤 skin.json 解码/皮肤包校验测试需真实 org.json（优先于 android.jar 桩）
    testImplementation(libs.json)

    // ===== 仪器化测试 =====
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
