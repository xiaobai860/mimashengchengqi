package com.lesspass.app.crypto

object Entropy {

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
