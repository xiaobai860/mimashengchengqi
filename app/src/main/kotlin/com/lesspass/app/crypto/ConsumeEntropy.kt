package com.lesspass.app.crypto

import java.math.BigInteger

object ConsumeEntropy {

    data class DivModResult(
        val quotient: BigInteger,
        val remainder: BigInteger,
    )

    data class Consumed(
        val value: String,
        val entropy: BigInteger,
    )

    fun divMod(dividend: BigInteger, divisor: BigInteger): DivModResult {
        return DivModResult(
            quotient = if (divisor > BigInteger.ZERO) dividend / divisor else BigInteger.ZERO,
            remainder = dividend % divisor,
        )
    }

    fun consumeEntropy(
        generatedPassword: String,
        quotient: BigInteger,
        setOfCharacters: String,
        maxLength: Int,
    ): Consumed {
        return if (generatedPassword.length >= maxLength) {
            Consumed(generatedPassword, quotient)
        } else {
            val longDivision = divMod(quotient, BigInteger.valueOf(setOfCharacters.length.toLong()))
            val newPassword = generatedPassword + setOfCharacters[longDivision.remainder.toInt()]
            consumeEntropy(newPassword, longDivision.quotient, setOfCharacters, maxLength)
        }
    }
}
