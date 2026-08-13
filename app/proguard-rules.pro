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

# LessPass 自有密码学实现：保留类与成员，避免 R8 的 optimize/shrink
# 破坏 javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") 的运行时查找
#（release 下曾出现 NoSuchAlgorithmException，debug 正常，典型 R8 优化所致）。
-keep class com.lesspass.app.crypto.** { *; }
-keepclassmembers class com.lesspass.app.crypto.** { *; }

# BouncyCastle：LessPass 的 PBKDF2-HMAC-SHA256 直接引用以下具体类
#（PKCS5S2ParametersGenerator / SHA256Digest / KeyParameter），必须保留，
# 否则 R8 会将其当作未使用而移除，导致运行时 NoClassDefFoundError。
# 同时保留 KeePass 模块用到的 BouncyCastleProvider（provider 注册表间接引用）。
-keep class org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator { *; }
-keep class org.bouncycastle.crypto.digests.SHA256Digest { *; }
-keep class org.bouncycastle.crypto.params.KeyParameter { *; }
-dontwarn org.bouncycastle.**

