package com.lesspass.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class FingerprintIcon(val name: String)
data class FingerprintColor(val hex: String)
data class Finger(var icon: FingerprintIcon, var color: FingerprintColor)

object Fingerprint {

    private val icons = listOf(
        "fa-hashtag", "fa-heart", "fa-hotel", "fa-university", "fa-plug",
        "fa-ambulance", "fa-bus", "fa-car", "fa-plane", "fa-rocket",
        "fa-ship", "fa-subway", "fa-truck", "fa-jpy", "fa-eur",
        "fa-btc", "fa-usd", "fa-gbp", "fa-archive", "fa-area-chart",
        "fa-bed", "fa-beer", "fa-bell", "fa-binoculars", "fa-birthday-cake",
        "fa-bomb", "fa-briefcase", "fa-bug", "fa-camera", "fa-cart-plus",
        "fa-certificate", "fa-coffee", "fa-cloud", "fa-coffee", "fa-comment",
        "fa-cube", "fa-cutlery", "fa-database", "fa-diamond", "fa-exclamation-circle",
        "fa-eye", "fa-flag", "fa-flask", "fa-futbol-o", "fa-gamepad",
        "fa-graduation-cap",
    )

    private val colors = listOf(
        "#000000", "#074750", "#009191", "#FF6CB6", "#FFB5DA",
        "#490092", "#006CDB", "#B66DFF", "#6DB5FE", "#B5DAFE",
        "#920000", "#924900", "#DB6D00", "#24FE23",
    )

    fun hmAC(key: String, algorithm: String = "HmacSHA256"): String {
        val spec = SecretKeySpec(key.toByteArray(), algorithm)
        val mac = Mac.getInstance(algorithm)
        mac.init(spec)
        return mac.doFinal().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun create(hmacSHA256: String): List<Finger> {
        val hash1 = hmacSHA256.substring(0, 6)
        val hash2 = hmacSHA256.substring(6, 12)
        val hash3 = hmacSHA256.substring(12, 18)
        return listOf(
            Finger(getIcon(hash1), getColor(hash1)),
            Finger(getIcon(hash2), getColor(hash2)),
            Finger(getIcon(hash3), getColor(hash3)),
        )
    }

    private fun getColor(color: String): FingerprintColor {
        val index = Integer.parseInt(color, 16) % colors.size
        return FingerprintColor(colors[index])
    }

    private fun getIcon(hash: String): FingerprintIcon {
        val index = Integer.parseInt(hash, 16) % icons.size
        return FingerprintIcon(icons[index])
    }
}
