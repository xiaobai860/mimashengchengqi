# KeePass KDBX 数据库引擎
-keep class com.kunzisoft.keepass.** { *; }
-keepclassmembers class com.kunzisoft.keepass.** { *; }

# Kotlin 协程
-dontwarn kotlinx.coroutines.**

# Compose
-dontwarn androidx.compose.**

# Argon2 KDF
-keep class com.kunzisoft.keepass.database.crypto.kdf.** { *; }

# MasterCredential
-keep class com.kunzisoft.keepass.database.element.MasterCredential { *; }

# joda-time 依赖的 joda-convert 注解（未打包进 APK，仅编译期注解，运行时不需）
-dontwarn org.joda.convert.**
-keep class org.joda.time.** { *; }
-dontwarn org.joda.time.**
