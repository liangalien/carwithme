import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * CarWith 插件 —— 独立 APK，**不打包进宿主**。
 *
 * ## 为什么要独立成模块
 * 本模块里的协议实现（七路拨号 / AES / protobuf / 组帧）派生自 cardisplay2，是 GPL-3.0。
 * 宿主是闭源自研代码，两者只能通过 `:plugin-api` 的 AIDL + loopback 帧通道往来：
 * 不共享实现代码、不链接、不捆绑分发。少了任何一条，GPL 的传染性就会落到宿主上。
 *
 * ## 独立签名
 * 插件与宿主是**两个独立发行物**，必须各用各的密钥：
 * - 密钥从 `local.properties` 的 `plugin.storeFile / plugin.storePassword / plugin.keyAlias /
 *   plugin.keyPassword` 读取，或同名环境变量 `PLUGIN_STORE_FILE` 等；
 * - 没配置时 release 回退到 debug 签名（只为让 CI / 本地 `assembleRelease` 能跑通，
 *   正式分发前必须换成独立密钥）；
 * - 权限定为 normal，所以签名不同**不影响**绑定（见 `plugin-api` 的 `PluginApi.PERMISSION`）。
 *
 * ## 发版
 * 每次对外发布都要 +1 [versionCode]。宿主只校验 [com.ikang.vm.plugin.api.PluginApi.API_VERSION]
 * （契约 ABI），`versionCode` 用于用户侧"装的是旧插件"的提示与覆盖安装。
 */
android {
    namespace = "com.ikang.vm.carwithme"

    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ikang.vm.carwithme"
        minSdk = 29
        targetSdk = 36

        versionCode = 1
        versionName = "1.0.0"

        fun gitOutput(vararg args: String, fallback: String): String {
            val output = providers.exec {
                commandLine("git", *args)
                isIgnoreExitValue = true
            }
            return if (output.result.get().exitValue == 0) {
                output.standardOutput.asText.get().trim()
            } else {
                fallback
            }
        }

        buildConfigField("String", "SOURCE_URL", "\"https://github.com/liangalien/carwithme\"")
        buildConfigField("String", "GIT_COMMIT", "\"${gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown")}\"")
        buildConfigField("String", "LICENSE_NAME", "\"GPL-3.0-only\"")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    val signingProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }

    fun signingValue(envName: String, key: String): String? =
        System.getenv(envName)?.takeIf { it.isNotEmpty() } ?: signingProperties.getProperty(key)

    val pluginStoreFile: File? = signingValue("PLUGIN_STORE_FILE", "plugin.storeFile")
        ?.let { rootProject.file(it) }
    val pluginStorePassword = signingValue("PLUGIN_STORE_PASSWORD", "plugin.storePassword")
    val pluginKeyPassword = signingValue("PLUGIN_KEY_PASSWORD", "plugin.keyPassword")
    val pluginKeyAlias = signingValue("PLUGIN_KEY_ALIAS", "plugin.keyAlias") ?: "plugin"

    signingConfigs {
        if (pluginStoreFile != null && pluginStoreFile.exists()) {
            create("plugin") {
                storeFile = pluginStoreFile
                storePassword = pluginStorePassword
                keyAlias = pluginKeyAlias
                keyPassword = pluginKeyPassword
            }
        } else {
            logger.warn(
                "[plugin] 未配置独立签名（local.properties: plugin.storeFile 或环境变量 " +
                    "PLUGIN_STORE_FILE）；release 将回退到 debug 签名，仅用于本地验证，不要这样分发。",
            )
        }
    }

    buildTypes {
        release {
            // 插件源码本身就是 GPL 明文可得的，混淆只增加排查成本；体积也不值得优化。
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("plugin") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        // BuildConfig 里的版本/源码地址要显示在信息页，也是 GPL "源码获取声明"的锚点。
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 唯一依赖：两端共用的契约（AIDL / Parcelable / 帧格式）。不得反向依赖宿主 app。
    implementation(project(":plugin-api"))

    testImplementation(libs.junit)
}
