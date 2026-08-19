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

// 底层 SDK 已独立成隔壁工程 ziyou-ime-sdk（交付坐标 com.ziyou:ime-sdk，AAR）。
// composite build：app 对坐标 com.ziyou:ime-sdk 的依赖自动替换为本地工程源码，
// 无需先 publish；对外集成方则直接使用 publishToMavenLocal/私有仓库产出的 AAR。
includeBuild("../ziyou-ime-sdk")
