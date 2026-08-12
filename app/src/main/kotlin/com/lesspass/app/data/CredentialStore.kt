package com.lesspass.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * 凭据安全存储：把"密码本密码"加密保存在 AndroidKeystore 中。
 *
 * - 自动解锁密钥：不要求用户认证，App 自身可直接解密（用于进入应用自动解锁）。
 * - 指纹密钥：要求生物/设备凭证认证，解密时会触发系统 BiometricPrompt（用于指纹解锁）。
 *
 * 每个密码本用 vaultId 隔离，切换密码本后旧密文对新库无效，需重新设置。
 */
class CredentialStore(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cred_prefs", Context.MODE_PRIVATE)

    private fun sanitize(vaultId: String): String =
        vaultId.toByteArray().let { bytes ->
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        }

    private fun autoAlias(vaultId: String) = "mima_auto_${sanitize(vaultId)}"
    private fun fpAlias(vaultId: String) = "mima_fp_${sanitize(vaultId)}"

    private fun getOrCreateKey(alias: String, requireAuth: Boolean): SecretKey {
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (requireAuth) {
            builder.setUserAuthenticationRequired(true)
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        }
        kg.init(builder.build())
        return kg.generateKey()
    }

    private fun encrypt(alias: String, requireAuth: Boolean, plain: String): String {
        val key = getOrCreateKey(alias, requireAuth)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(alias: String, blob: String): String {
        val key = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry
        val (ivB64, ctB64) = blob.split(":", limit = 2)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key.secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ============ 自动解锁 ============

    fun storeAutoPassword(vaultId: String, password: String) {
        prefs.edit().putString("auto_${sanitize(vaultId)}", encrypt(autoAlias(vaultId), false, password)).apply()
    }

    fun getAutoPassword(vaultId: String): String? {
        val blob = prefs.getString("auto_${sanitize(vaultId)}", null) ?: return null
        return try { decrypt(autoAlias(vaultId), blob) } catch (e: Exception) { null }
    }

    fun clearAutoPassword(vaultId: String) {
        prefs.edit().remove("auto_${sanitize(vaultId)}").apply()
    }

    fun hasAutoPassword(vaultId: String): Boolean =
        prefs.contains("auto_${sanitize(vaultId)}")

    // ============ 指纹解锁 ============

    fun storeFingerprintPassword(vaultId: String, password: String) {
        prefs.edit().putString("fp_${sanitize(vaultId)}", encrypt(fpAlias(vaultId), true, password)).apply()
    }

    fun hasFingerprintPassword(vaultId: String): Boolean =
        prefs.contains("fp_${sanitize(vaultId)}")

    fun clearFingerprintPassword(vaultId: String) {
        prefs.edit().remove("fp_${sanitize(vaultId)}").apply()
        try { keyStore.deleteEntry(fpAlias(vaultId)) } catch (_: Exception) {}
    }

    /**
     * 使用指纹密钥解密密码。需要 activity 配合弹出 BiometricPrompt，
     * 认证成功后通过 CryptoObject 完成解密。
     */
    fun decryptFingerprint(
        vaultId: String,
        activity: androidx.fragment.app.FragmentActivity,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val blob = prefs.getString("fp_${sanitize(vaultId)}", null)
        if (blob == null) { onError("未设置指纹解锁"); return }
        try {
            val key = keyStore.getEntry(fpAlias(vaultId), null) as KeyStore.SecretKeyEntry
            val (ivB64, ctB64) = blob.split(":", limit = 2)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key.secretKey, GCMParameterSpec(128, iv))

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("指纹解锁")
                .setSubtitle("验证指纹以解锁密码本")
                .setNegativeButtonText("取消")
                .build()
            val prompt = BiometricPrompt(activity,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val decrypted = String(cipher.doFinal(ct), Charsets.UTF_8)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onSuccess(decrypted)
                            }
                        } catch (e: Exception) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onError("解密失败: ${e.message}")
                            }
                        }
                    }
                    override fun onAuthenticationFailed() {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onError("指纹验证失败") }
                    }
                    override fun onAuthenticationError(code: Int, err: CharSequence) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onError(err.toString()) }
                    }
                })
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            onError("指纹解锁初始化失败: ${e.message}")
        }
    }

    companion object {
        fun isBiometricAvailable(context: Context): Boolean {
            val bm = BiometricManager.from(context)
            return bm.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }
}
