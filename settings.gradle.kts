pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ziyou-ime"
include(":app")

// 底层 SDK 以本地文件 AAR 引入（app/libs/ime-sdk-release.aar，与 sherpa-onnx
// 同模式，零外部仓库依赖）：克隆本仓后无需网络/无需构建 SDK 源码即可编译。
// SDK 升级流程：在 ziyou-ime-sdk 工程执行 ./gradlew assembleRelease，
// 将 build/outputs/aar/ime-sdk-release.aar 拷贝覆盖 app/libs/ 下同名文件。
