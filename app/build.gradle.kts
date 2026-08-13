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
        versionCode = 21
        versionName = "2.10"
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
