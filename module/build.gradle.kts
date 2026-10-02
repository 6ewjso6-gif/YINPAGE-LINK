plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * ============================================================================
 *  YINPAGE-LINK LSPosed 模块
 * ============================================================================
 *  作用：把 YINPAGE 音贝奇 Feel 1 Pro 伪装成小米「受支持的高级耳机」，
 *  从而在澎湃 OS 的蓝牙设置页 / 控制中心 / 灵动岛里出现耳机卡片与四档 ANC 控件，
 *  并把系统 UI 的操作转成耳机真实的中科蓝讯 AB 系 SPP 命令。
 *
 *  ⚠️ 本模块需要 root + LSPosed。免 root 的控制能力见仓库根目录的 :app 独立应用。
 *
 *  协议代码与 :app 共享：通过 sourceSets 直接引用 app 的 protocol 源码目录，
 *  不复制文件（协议的单一事实来源仍在 app/src/main/java/com/yinpage/link/protocol）。
 * ============================================================================
 */
android {
    namespace = "com.yinpage.link.module"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yinpage.link.module"
        minSdk = 29          // HyperOS 3/4 均为 Android 13+，这里放宽到 29 便于调试
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    /**
     * 签名配置。
     * AGP 默认把 debug.keystore 放在 ~/.android/，在受限环境（沙箱 / 只读用户目录）
     * 会因 AccessDeniedException 打包失败。这里与 :app 一致，显式指向仓库内路径。
     * 该目录已加入 .gitignore。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.layout.projectDirectory
                .file(".android/debug.keystore").asFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // LSPosed 模块必须签名；未配置签名时产出 unsigned，需自行签名后再安装
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    /**
     * 复用 :app 的协议实现：把 app 的 protocol 源码目录加入本模块的编译单元。
     * 这样 `com.yinpage.link.protocol.bluetrum.*` 在本模块里可直接使用，
     * 且永远与独立应用保持同一份实现。
     */
    sourceSets {
        getByName("main") {
            java.srcDir("$rootDir/app/src/main/java/com/yinpage/link/protocol")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // libxposed 现代 API（编译期依赖，运行时由 LSPosed 框架提供）
    compileOnly(libs.libxposed.api)

    // 注意：焦点通知 / 灵动岛构建库（com.xzakota.hyper.notification:focus-api）
    // 暂时不引入——它的 Kotlin metadata 是 2.2.0，与本项目的 Kotlin 2.0.21 不兼容。
    // 灵动岛功能尚未实现（见 doc/FUSION-CENTER.md §7.1），后续实现时再引入。
}
