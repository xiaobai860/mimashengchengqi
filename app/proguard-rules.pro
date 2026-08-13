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

# LessPass 自有密码学实现：保留类与成员。
# 说明：PBKDF2-HMAC-SHA256 改用 BouncyCastle 的 PKCS5S2ParametersGenerator(SHA256Digest())
# 直接实现（非 javax.crypto 的 SecretKeyFactory SPI），以规避 Android 16 release 下
# 系统 crypto provider 查找失败（NoSuchAlgorithmException）的问题。仍保留类以防 R8 误裁。
-keep class com.lesspass.app.crypto.** { *; }
-keepclassmembers class com.lesspass.app.crypto.** { *; }

# BouncyCastle：KeePassDX 引擎与 LessPass 均依赖它。
# BouncyCastleProvider 通过内部注册表（反射/字符串类名）加载具体算法实现
#（如 Blowfish KeyGenerator、AES、Twofish、ChaCha20 等，位于 org.bouncycastle.jcajce.provider.*），
# R8 无法追踪这类间接引用，会把它们当未使用而 shrink，
# 导致运行时 NoSuchAlgorithmException / NoClassDefFoundError
#（例如 Android 16 release 下 DatabaseKDBX 构造时 Blowfish not available）。
# 因此整体保留 provider 门面与 jcajce 实现包。
-keep class org.bouncycastle.jce.provider.** { *; }
-keepclassmembers class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keepclassmembers class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator { *; }
-keep class org.bouncycastle.crypto.digests.SHA256Digest { *; }
-keep class org.bouncycastle.crypto.params.KeyParameter { *; }
-dontwarn org.bouncycastle.**

