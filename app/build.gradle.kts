import java.util.Properties

// ⚠️ AGP 9 起 Kotlin 由插件内置提供（KGP 2.2.10），不可再声明
// org.jetbrains.kotlin.android，否则报 "Cannot add extension with name 'kotlin'"。
// 需要更高版本 KGP 时改在根 build.gradle.kts 的 buildscript classpath 里声明。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mima.app"
    compileSdk = 36

    defaultConfig {
        // 应用标识与代码包名（namespace）解耦：改包名只动这里，代码无需重构
        applicationId = "com.mima.app"
        minSdk = 34
        targetSdk = 36
        // versionName：展示用版本号 = 年月日 + 两位迭代版号（当天第一版 01，第二版 02……）
        // versionCode：系统用的递增整数，每次发版 +1（不可回退，与 versionName 无关）
        versionCode = 64
        versionName = "2026091001"
    }

    // 签名配置：优先读取项目根目录 keystore.properties（已 gitignore，不进仓库），
    // 文件不存在时回退命令行 -P 参数。密钥库在项目外 D:\Android\paibanrili。
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { stream -> load(stream) }
    }
    fun releaseProp(name: String): String? =
        keystoreProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (findProperty(name) as? String)?.takeIf { it.isNotEmpty() }

    signingConfigs {
        create("release") {
            val storeFileProp = releaseProp("RELEASE_STORE_FILE")
            storeFile = if (storeFileProp != null) file(storeFileProp) else null
            storePassword = releaseProp("RELEASE_STORE_PASSWORD")
            keyAlias = releaseProp("RELEASE_KEY_ALIAS")
            keyPassword = releaseProp("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                // AGP 8.11 起强制要求 proguard-android-optimize.txt
                // （proguard-android.txt 因含 -dontoptimize 被弃用/拒绝）。
                // ⚠️ 曾为规避 SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") 的 release
                // NoSuchAlgorithmException 而用非 optimize 配置；现已改用 BouncyCastle
                // PKCS5S2ParametersGenerator 实现 PBKDF2，不再依赖该 SPI 查找，可安全启用 optimize。
                getDefaultProguardFile("proguard-android-optimize.txt"),
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
    // JVM 目标版本由内置 Kotlin 自动对齐 compileOptions.targetCompatibility(17)，无需再设置。
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
            // 剔除 Kotlin 协程的 debug-only 调试探针（release 不应携带；AGP 8.11 未默认移除）
            excludes += "/DebugProbesKt.bin"
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
