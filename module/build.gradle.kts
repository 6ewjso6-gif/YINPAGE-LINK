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
     * 签名配置（issue #5 整改）。
     *
     * 该模块拥有系统进程 Hook 权限，**绝不能**再用仓库内公开的 debug 密钥签名：
     * 私钥公开意味着任何人都能产出同包名、同签名的恶意模块，系统会当成正常升级。
     *
     * - debug：优先 `DEBUG_KEYSTORE`，其次本机 `.android/debug.keystore`
     *   （build.sh 生成、已被 .gitignore 排除），都没有则交给 AGP 用
     *   `~/.android/debug.keystore`；
     * - release：只接受 CI Secrets 注入的独立密钥，缺失时产出 **unsigned**，
     *   不再回退到 debug 密钥。
     */
    signingConfigs {
        val debugStore = System.getenv("DEBUG_KEYSTORE")
            ?.takeIf { it.isNotBlank() }
            ?.let { rootProject.file(it) }
            ?: rootProject.file(".android/debug.keystore")
        if (debugStore.exists()) {
            getByName("debug") {
                storeFile = debugStore
                storePassword = System.getenv("DEBUG_KEYSTORE_PASS") ?: "android"
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEBUG_KEY_PASS") ?: "android"
            }
        }

        val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
        if (!releaseKeystore.isNullOrBlank() && rootProject.file(releaseKeystore).exists()) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASS") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("RELEASE_KEY_PASS") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 未配置 release 密钥时保持 unsigned：宁可不发布，也不回退到 debug 密钥。
            signingConfig = signingConfigs.findByName("release")
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
