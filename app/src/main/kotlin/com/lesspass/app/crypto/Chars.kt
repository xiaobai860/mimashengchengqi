package com.lesspass.app.crypto

import java.math.BigInteger

enum class CharRule {
    LOWERCASE, UPPERCASE, DIGITS, SYMBOLS
}

object Chars {

    private val characterSubsets = mapOf(
        CharRule.LOWERCASE to "abcdefghijklmnopqrstuvwxyz",
        CharRule.UPPERCASE to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        CharRule.DIGITS to "0123456789",
        CharRule.SYMBOLS to "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
    )

    /**
     * 容易混淆的近似字符（ambiguous characters）。
     * 业界公认最易混淆的 9 个核心字符：
     *   0 / O / o       -> 数字0、大写O、小写o
     *   1 / l / I / i   -> 数字1、小写l、大写I、小写i
     *   |               -> 竖线
     *   `               -> 反引号
     */
    val ambiguousChars = setOf(
        '0', 'O', 'o',
        '1', 'l', 'I', 'i',
        '|', '`',
    )

    /** 返回排除近似字符后，某个规则剩余的字符集。 */
    fun getExcludedRuleCharset(rule: CharRule): String {
        return (characterSubsets[rule] ?: "").filter { it !in ambiguousChars }
    }

    fun getSetOfCharacters(rules: List<CharRule>, excludeAmbiguous: Boolean = false): String {
        return if (excludeAmbiguous) {
            getSetOfCharactersExcluded(rules)
        } else {
            getSetOfCharactersDefault(rules)
        }
    }

    private fun getSetOfCharactersDefault(rules: List<CharRule>): String {
        if (rules.isEmpty()) {
            return characterSubsets.values.joinToString("")
        }
        return rules.joinToString("") { characterSubsets[it] ?: "" }
    }

    private fun getSetOfCharactersExcluded(rules: List<CharRule>): String {
        if (rules.isEmpty()) {
            return characterSubsets.values.joinToString("").filter { it !in ambiguousChars }
        }
        return rules.joinToString("") {
            (characterSubsets[it] ?: "").filter { c -> c !in ambiguousChars }
        }
    }

    fun getRules(options: Map<CharRule, Boolean>): List<CharRule> {
        return CharRule.entries.filter { options[it] == true }
    }

    fun getOneCharPerRule(
        entropy: BigInteger,
        rules: List<CharRule>,
        excludeAmbiguous: Boolean = false,
    ): ConsumeEntropy.Consumed {
        var oneCharPerRules = ""
        var consumedEntropy = entropy
        for (rule in rules) {
            val charset = if (excludeAmbiguous) getExcludedRuleCharset(rule) else (characterSubsets[rule] ?: continue)
            if (charset.isEmpty()) continue
            val result = ConsumeEntropy.consumeEntropy("", consumedEntropy, charset, 1)
            oneCharPerRules += result.value
            consumedEntropy = result.entropy
        }
        return ConsumeEntropy.Consumed(oneCharPerRules, consumedEntropy)
    }

    fun insertStringPseudoRandomly(
        initialString: String,
        entropy: BigInteger,
        stringToInsert: String,
    ): String {
        var consumedEntropy = entropy
        var string = initialString
        for (i in stringToInsert.indices) {
            val longDivision = ConsumeEntropy.divMod(consumedEntropy, BigInteger.valueOf(string.length.toLong()))
            val remainder = longDivision.remainder.toInt()
            string = string.substring(0, remainder) + stringToInsert[i] + string.substring(remainder)
            consumedEntropy = longDivision.quotient
        }
        return string
    }
}
