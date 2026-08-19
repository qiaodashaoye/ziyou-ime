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
        // ime-sdk AAR 本地 Maven 坐标依赖（先在 ziyou-ime-sdk 工程执行
        // ./gradlew publishToMavenLocal 发布；正式发布可替换为私有仓库 URL）。
        // 限定 com.ziyou 组，避免 mavenLocal 遮蔽 google/mavenCentral 同名坐标
        mavenLocal {
            content {
                includeGroup("com.ziyou")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ziyou-ime"
include(":app")

// 底层 SDK 以 AAR 坐标依赖引入（com.ziyou:ime-sdk，见 app/build.gradle.kts），
// 不再使用 composite build 源码级联编；SDK 变更后需重新 publishToMavenLocal
// 才能被主工程感知。如需源码联调，临时改回 includeBuild("../ziyou-ime-sdk") 即可。
