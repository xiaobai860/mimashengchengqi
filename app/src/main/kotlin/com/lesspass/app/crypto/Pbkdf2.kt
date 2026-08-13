package com.lesspass.app.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Pbkdf2 {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun derive(
        password: String,
        salt: String,
        iterations: Int = 100000,
        keyLength: Int = 32,
    ): String {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt.toByteArray(),
            iterations,
            keyLength * 8, // bits
        )
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val key = factory.generateSecret(spec).encoded
        return key.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
