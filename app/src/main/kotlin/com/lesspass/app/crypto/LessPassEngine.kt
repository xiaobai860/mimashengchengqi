package com.lesspass.app.crypto

data class PasswordProfile(
    val site: String = "",
    val login: String = "",
    val counter: Int = 1,
    val length: Int = 16,
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val excludeAmbiguous: Boolean = false,
)

object LessPassEngine {

    fun generatePassword(
        profile: PasswordProfile,
        masterPassword: String,
    ): String {
        val entropyHex = Entropy.calcEntropy(
            site = profile.site,
            login = profile.login,
            counter = profile.counter,
            masterPassword = masterPassword,
        )
        val options = PasswordOptions(
            uppercase = profile.uppercase,
            lowercase = profile.lowercase,
            digits = profile.digits,
            symbols = profile.symbols,
            length = profile.length,
            excludeAmbiguous = profile.excludeAmbiguous,
        )
        return RenderPassword.render(entropyHex, options)
    }

    fun buildFingerprint(key: String): List<Finger> {
        val hmacResult = Fingerprint.hmAC(key)
        return Fingerprint.create(hmacResult)
    }

    /**
     * 官方自检向量校验（对应官方 entropy.ts 的 isSupported）。
     * 用官方内置的标准用例跑一次 PBKDF2：
     *   site=lesspass.com, login=♥, counter=1,
     *   主密码=tHis is a g00d! password, 迭代次数=1
     * 期望熵值: e99e20abab609cc4564ef137acb540de20d9b92dcc5cda58f78ba431444ef2da
     * 只有算出精确 hex 才返回 true，证明 PBKDF2 + 盐值构造与官方一致。
     */
    fun isSupported(): Boolean {
        return try {
            val entropy = Entropy.calcEntropy(
                site = "lesspass.com",
                login = "♥",
                counter = 1,
                masterPassword = "tHis is a g00d! password",
                iterations = 1,
            )
            entropy == "e99e20abab609cc4564ef137acb540de20d9b92dcc5cda58f78ba431444ef2da"
        } catch (_: Exception) {
            false
        }
    }
}
