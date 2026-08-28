plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lesspass.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lesspass.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 23
        versionName = "2.12"
    }

    // 签名配置从用户级 ~/.gradle/gradle.properties 读取（密钥库在项目外 E:\Android\paibanrili），
    // 不写入本仓库，避免被提交到 GitHub。
    signingConfigs {
        create("release") {
            val storeFileProp = findProperty("RELEASE_STORE_FILE") as? String
            storeFile = if (storeFileProp != null) file(storeFileProp) else null
            storePassword = findProperty("RELEASE_STORE_PASSWORD") as? String
            keyAlias = findProperty("RELEASE_KEY_ALIAS") as? String
            keyPassword = findProperty("RELEASE_KEY_PASSWORD") as? String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                // 用基础 proguard-android.txt（仅混淆+压缩，不做激进优化），
                // 避免 proguard-android-optimize.txt 的优化破坏
                // javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") 的运行时查找。
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/*.MF"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            // 体积优化：剥离 BouncyCastle 未使用子系统的资源文件。
            // 本应用仅用 BC 的 PBKDF2/Argon2/ChaCha/Salsa20 等具体类（见 Pbkdf2.kt 及
            // crypto/database 模块），APK 的 dex 中 org.bouncycastle.pqc|pkix|x509|ocsp|openpgp
            // 引用均为 0，运行时不会加载这些资源，可安全移除。
            // 回查：若后期 KDBX 打开/证书/PGP 相关功能异常，先确认是否误删资源，
            // 移除下方对应 exclude 即可回退（无需改代码）。
            excludes += "/org/bouncycastle/pqc/**"
            excludes += "/org/bouncycastle/pkix/**"
            excludes += "/org/bouncycastle/x509/**"
            excludes += "/org/bouncycastle/ocsp/**"
            excludes += "/org/bouncycastle/openpgp/**"
        }
    }
}
 
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(project(":crypto"))
    implementation(project(":database"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
