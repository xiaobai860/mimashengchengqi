package com.lesspass.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

object Pbkdf2 {

    /**
     * PBKDF2-HMAC-SHA256 派生。
     *
     * 实现说明：直接使用 BouncyCastle 的 [PKCS5S2ParametersGenerator]（RFC 2898 /
     * PKCS#5 v2.0），而非依赖 `javax.crypto.SecretKeyFactory.getInstance(...)` 的
     * SPI 查找。原因：在 Android 16 (API 36) 的 release 构建下，系统框架的
     * `PBKDF2WithHmacSHA256` provider 查找会抛出 `NoSuchAlgorithmException`
     * （debug 正常、release 异常），而 BouncyCastle 是本项目 crypto 模块自带依赖，
     * 通过具体类的直接调用可由 R8 稳定保留，结果确定可重现，且与官方 LessPass
     * （Node `pbkdf2Sync(password, salt, { digest: 'sha256' })`）完全一致。
     */
    fun deriveRaw(
        password: String,
        salt: String,
        iterations: Int = 100000,
        keyLength: Int = 32,
    ): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(
            password.toByteArray(Charsets.UTF_8),
            salt.toByteArray(Charsets.UTF_8),
            iterations,
        )
        return (generator.generateDerivedParameters(keyLength * 8) as KeyParameter).key
    }

    fun derive(
        password: String,
        salt: String,
        iterations: Int = 100000,
        keyLength: Int = 32,
    ): String {
        return deriveRaw(password, salt, iterations, keyLength)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
