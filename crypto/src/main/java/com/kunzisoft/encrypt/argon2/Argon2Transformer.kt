package com.kunzisoft.encrypt.argon2

import com.kunzisoft.encrypt.NativeLib
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

object Argon2Transformer {

    fun transformKey(type: Argon2Type,
                     password: ByteArray,
                     salt: ByteArray,
                     parallelism: Long,
                     memory: Long,
                     iterations: Long,
                     version: Int): ByteArray {

        NativeLib.init()
        val argon2Type = when(type) {
            Argon2Type.ARGON2_I -> NativeArgon2KeyTransformer.CType.ARGON2_I
            Argon2Type.ARGON2_D -> NativeArgon2KeyTransformer.CType.ARGON2_D
            Argon2Type.ARGON2_ID -> NativeArgon2KeyTransformer.CType.ARGON2_ID
        }

        return try {
            NativeArgon2KeyTransformer.nTransformKey(
                    argon2Type.cValue,
                    password,
                    salt,
                    parallelism.toInt(),
                    memory.toInt(),
                    iterations.toInt(),
                    ByteArray(0),
                    ByteArray(0),
                    version)
        } catch (e: UnsatisfiedLinkError) {
            // 没有 native 库时，回退到 BouncyCastle 的纯 Java Argon2 实现
            transformKeyJava(type, password, salt, parallelism, memory, iterations)
        }
    }

    /**
     * 纯 Java Argon2 实现（BouncyCastle fallback）。
     * 当项目没有编译 native .so 库时，此方法保证 Argon2 KDF 正常工作。
     */
    private fun transformKeyJava(
            type: Argon2Type,
            password: ByteArray,
            salt: ByteArray,
            parallelism: Long,
            memory: Long,
            iterations: Long,
    ): ByteArray {
        val output = ByteArray(32)
        val bcType = when (type) {
            Argon2Type.ARGON2_I -> Argon2Parameters.ARGON2_i
            Argon2Type.ARGON2_D -> Argon2Parameters.ARGON2_d
            Argon2Type.ARGON2_ID -> Argon2Parameters.ARGON2_id
        }
        val params = Argon2Parameters.Builder(bcType)
            .withSalt(salt)
            .withParallelism(parallelism.toInt())
            .withMemoryAsKB(memory.toInt())
            .withIterations(iterations.toInt())
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        generator.generateBytes(password, output, 0, output.size)
        return output
    }
}
