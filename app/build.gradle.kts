import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Date

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

    defaultConfig {
        applicationId = "com.yinpage.link"
        minSdk = 27          // 蓝牙 5.x 私有协议 + BLE 扫描所需最低版本
        targetSdk = 35

        /**
         * 版本号每次编译自动生成，保证可**直接覆盖安装**（不需要卸载）。
         *
         * versionCode 必须落在 Android 允许的范围（<= 2_100_000_000）。
         * 用 Unix 时间戳的**秒数**：当前约 1.79e9，到 2038 年才接近上限，
         * 且天然单调递增 —— 每次编译都不同，Android 就不会拒绝覆盖安装。
         *
         * （曾试过 `YYMMDD*10000+分钟`，但 261003*10000 = 26 亿直接溢出 toInt。）
         */
        val now = Date()
        versionCode = (now.time / 1000L).toInt()
        // 发布版本名格式：APP-t-v*.*（* 由版本号填充，递增需手动改这里）
        versionName = "APP-t-v0.6"

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
