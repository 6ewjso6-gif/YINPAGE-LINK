import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 本地构建默认版本名（CI 发布时会用 `-PversionName` 覆盖为真实 tag）。
 *
 * issue #3-5：本地构建也可用环境变量 `YINPAGE_VERSION_NAME` 覆盖，
 * 避免打出来的包永远叫旧版本、装上去后反复提示"发现新版本"。
 */
val DEFAULT_VERSION_NAME = "APP-t-v0.16"

/** versionCode 基座：高于历史基于 Unix 秒数的最大值，保证可覆盖安装。 */
val VERSION_CODE_BASE = 1_800_000_000

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.yinpage.link"
    compileSdk = 35

    /**
     * Debug 签名配置。
     * 默认情况下 AGP 会把 debug.keystore 放到 ~/.android/，在受限环境
     * （沙箱 / 只读用户目录）里会因 AccessDeniedException 打包失败。
     * 这里显式指定到构建目录内，保证任何环境都能出包。
     *
     * Release 签名（可选，CI 专用）：
     * 只有提供了 RELEASE_KEYSTORE 等环境变量（GitHub Actions Secrets 注入）时
     * 才会创建 release 签名配置，本地构建不变（保持 unsigned，手动 apksigner）。
     * 密钥不落仓库、不落公开代码，从源头避免"开源仓库泄露签名"。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.layout.projectDirectory
                .file(".android/debug.keystore").asFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
        if (!releaseKeystore.isNullOrBlank() && file(releaseKeystore).exists()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASS") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("RELEASE_KEY_PASS") ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "com.yinpage.link"
        minSdk = 27          // 蓝牙 5.x 私有协议 + BLE 扫描所需最低版本
        targetSdk = 35

        /**
         * N6 修复：versionName 由 **git tag 注入**（CI 通过 `-PversionName=$GITHUB_REF_NAME` 传入），
         * 本地构建回退到默认值。这样「Release 的 tag」与「本机 BuildConfig.VERSION_NAME」
         * 同源，杜绝「发版漏改 versionName → 装完仍提示有新版本」的无限更新陷阱。
         *
         * F30 修复：versionCode 收敛为「固定基座 + 语义化版本」，不再用 Unix 秒数。
         *   - 单调递增（0.12 → 0.13 递增），支持同签名直接覆盖安装；
         *   - 远离 Google Play 上限 2_100_000_000，且与时间无关、永不溢出；
         *   - 基座 1_800_000_000 高于历史基于秒数的最大值（v0.11 ≈ 1.79e9），
         *     保证老用户能直接覆盖安装新包。
         */
        val injectedVersionName = ((project.findProperty("versionName") as? String)
            ?: System.getenv("YINPAGE_VERSION_NAME"))?.takeIf { it.isNotBlank() }
        val resolvedVersionName = injectedVersionName ?: DEFAULT_VERSION_NAME
        versionName = resolvedVersionName

        val semver = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(resolvedVersionName)
        val major = semver?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minor = semver?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val patch = semver?.groupValues?.get(3)?.toIntOrNull() ?: 0
        versionCode = VERSION_CODE_BASE + major * 1_000_000 + minor * 1_000 + patch

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 该 App 不使用任何系统级/框架注入能力：完全免 root、免 LSPosed
        manifestPlaceholders["appLabel"] = "YINPAGE-LINK"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 不再加 .debug 后缀：包名与之前发布的版本保持一致，
            // 用户可以直接覆盖安装，不需要卸载。
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 无签名配置时产出 unsigned APK；如需签名请配置 keystore.properties
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
