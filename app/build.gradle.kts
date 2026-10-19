import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ── 签名配置 ──────────────────────────────────────────────────────────────
// 密码写在项目根目录的 keystore.properties（已在 .gitignore 里，绝不入库）。
// 文件不存在或密码为空时 signingReady=false，assembleRelease 会退化成产出
// app-release-unsigned.apk —— 这样别人 clone 下来照样能构建，只是出的是未签名包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val signingReady = !keystoreProps.getProperty("storePassword").isNullOrBlank()

android {
    namespace = "com.xieguojian.aiseclab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xieguojian.aiseclab"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/aiseclab.jks"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                // PKCS12 格式里 key 口令与 keystore 口令几乎总是同一个，
                // 留空就回退到 storePassword —— 少让用户填一个字段。
                keyPassword = keystoreProps.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
                    ?: keystoreProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 密钥加密存储
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 判定方向回归测试（纯 JVM，不需要设备/网络）：见 JudgeDirectionTest
    testImplementation("junit:junit:4.13.2")
}
