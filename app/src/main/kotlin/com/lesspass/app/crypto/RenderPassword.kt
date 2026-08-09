package com.lesspass.app.crypto

import java.math.BigInteger

data class PasswordOptions(
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val length: Int = 16,
    val excludeAmbiguous: Boolean = false,
)

object RenderPassword {

    fun render(entropyHex: String, options: PasswordOptions): String {
        val rules = Chars.getRules(options.toMap())
        val setOfCharacters = Chars.getSetOfCharacters(rules, options.excludeAmbiguous)
        val entropy = BigInteger(entropyHex, 16)

        // 排除近似字符时，过滤掉那些所有字符都被排除的规则
        val rulesForOneChar = if (options.excludeAmbiguous) {
            rules.filter { r -> hasNonAmbiguousChar(r) }
        } else {
            rules
        }

        // Step 1: generate base password from entropy
        val generatedPassword = ConsumeEntropy.consumeEntropy(
            "",
            entropy,
            setOfCharacters,
            options.length - rulesForOneChar.size,
        )

        // Step 2: get one char per rule from remaining entropy
        val charactersToAdd = Chars.getOneCharPerRule(
            generatedPassword.entropy,
            rulesForOneChar,
            options.excludeAmbiguous,
        )

        // Step 3: insert characters pseudo-randomly
        return Chars.insertStringPseudoRandomly(
            generatedPassword.value,
            charactersToAdd.entropy,
            charactersToAdd.value,
        )
    }

    private fun hasNonAmbiguousChar(rule: CharRule): Boolean {
        // 排除近似字符后，该规则是否还有可用字符
        return Chars.getExcludedRuleCharset(rule).isNotEmpty()
    }
}

fun PasswordOptions.toMap(): Map<CharRule, Boolean> = mapOf(
    CharRule.LOWERCASE to lowercase,
    CharRule.UPPERCASE to uppercase,
    CharRule.DIGITS to digits,
    CharRule.SYMBOLS to symbols,
)
