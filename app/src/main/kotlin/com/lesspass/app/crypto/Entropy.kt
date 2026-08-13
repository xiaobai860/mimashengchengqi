package com.lesspass.app.crypto

object Entropy {

    /**
     * 计算 LessPass entropy（与官方算法一致）：
     *   salt = site + login + counter.toString(16)
     *   entropy = PBKDF2(masterPassword, salt, iterations, 32-byte, HmacSHA256) 的 hex
     * 注意：官方 entropy 就是 PBKDF2 的直接输出，没有额外的 HMAC 层。
     */
    fun calcEntropy(
        site: String,
        login: String,
        counter: Int,
        masterPassword: String,
        iterations: Int = 100000,
    ): String {
        val salt = site + login + counter.toString(16)
        return Pbkdf2.derive(masterPassword, salt, iterations, 32)
    }
}
