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

rootProject.name = "CarWithMe"

// 宿主 ⇄ 插件的唯一公共契约（AIDL + Parcelable + 帧线格式）。
// 自有版权、不随本项目的 GPL-3.0 授权，详见 LICENSING.md。
include(":plugin-api")

// 插件本体（GPL-3.0）：构建产物就是最终对外分发的那个 APK。
include(":plugin")
