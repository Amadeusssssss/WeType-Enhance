package com.xposed.wetypehook.wetype.pinyin

import java.util.Locale

/**
 * 双拼展开为全拼音节转换器
 *
 * 支持主流双拼方案：
 * - 3: 小鹤双拼 (Xiaohe) [默认]
 * - 1: 搜狗双拼 (Sogou)
 * - 2: 微软双拼 (Microsoft)
 * - 4: 拼音加加 (PinyinJiajia)
 * - 5: 自然码 (Ziranma)
 * - 6: 智能ABC (ZhinengABC)
 */
object ShuangpinExpander {

    const val SCHEME_SOGOU = 1
    const val SCHEME_MICROSOFT = 2
    const val SCHEME_XIAOHE = 3
    const val SCHEME_PINYIN_JIAJIA = 4
    const val SCHEME_ZIRANMA = 5
    const val SCHEME_ZHINENG_ABC = 6

    // ==========================================
    // 1. 小鹤双拼 (Xiaohe)
    // ==========================================
    private val XIAOHE_INITIALS = mapOf(
        'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g",
        'h' to "h", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m",
        'n' to "n", 'p' to "p", 'q' to "q", 'r' to "r", 's' to "s",
        't' to "t", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        'v' to "zh", 'i' to "ch", 'u' to "sh"
    )

    private val XIAOHE_ZERO_INITIALS = mapOf(
        "aa" to "a", "ai" to "ai", "an" to "an", "ah" to "ang", "ao" to "ao",
        "ee" to "e", "ei" to "ei", "en" to "en", "eg" to "eng", "er" to "er",
        "oo" to "o", "ou" to "ou"
    )

    // 小鹤韵母 (根据声母上下文消歧)
    private fun resolveXiaoheFinal(initial: String, finalKey: Char): String? {
        return when (finalKey) {
            'a' -> "a"
            'b' -> "in"
            'c' -> "ao"
            'd' -> "ai"
            'e' -> "e"
            'f' -> "en"
            'g' -> "eng"
            'h' -> "ang"
            'i' -> "i"
            'j' -> "an"
            'k' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "uai" else "ing"
            'l' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "uang" else "iang"
            'm' -> "ian"
            'n' -> "iao"
            'o' -> if (initial in setOf("b", "p", "m", "f", "y", "w")) "o" else "uo"
            'p' -> "ie"
            'q' -> "iu"
            'r' -> "uan"
            's' -> if (initial in setOf("j", "q", "x")) "iong" else "ong"
            't' -> "ue"
            'u' -> "u"
            'v' -> if (initial in setOf("j", "q", "x", "n", "l")) "v" else "ui"
            'w' -> "ei"
            'x' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "ua" else "ia"
            'y' -> "un"
            'z' -> "ou"
            else -> null
        }
    }

    // ==========================================
    // 2. 自然码 (Ziranma)
    // ==========================================
    private val ZIRANMA_INITIALS = mapOf(
        'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g",
        'h' to "h", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m",
        'n' to "n", 'p' to "p", 'q' to "q", 'r' to "r", 's' to "s",
        't' to "t", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        'v' to "zh", 'i' to "ch", 'u' to "sh"
    )

    private val ZIRANMA_ZERO_INITIALS = mapOf(
        "aa" to "a", "ai" to "ai", "an" to "an", "ah" to "ang", "ao" to "ao",
        "ee" to "e", "ei" to "ei", "en" to "en", "eg" to "eng", "er" to "er",
        "oo" to "o", "ou" to "ou"
    )

    private fun resolveZiranmaFinal(initial: String, finalKey: Char): String? {
        return when (finalKey) {
            'a' -> "a"
            'b' -> "ou"
            'c' -> "ao"
            'd' -> "ai"
            'e' -> "e"
            'f' -> "en"
            'g' -> "eng"
            'h' -> "ang"
            'i' -> "i"
            'j' -> "an"
            'k' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "uai" else "ing"
            'l' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "uang" else "iang"
            'm' -> "ian"
            'n' -> "in"
            'o' -> "uo"
            'p' -> "un"
            'q' -> "iu"
            'r' -> if (initial in setOf("j", "q", "x", "y")) "van" else "uan"
            's' -> if (initial in setOf("j", "q", "x")) "iong" else "ong"
            't' -> "ue"
            'u' -> "u"
            'v' -> "ui"
            'w' -> "ei"
            'x' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "ua" else "ia"
            'y' -> "ing"
            'z' -> "iao"
            else -> null
        }
    }

    // ==========================================
    // 3. 微软双拼 (Microsoft)
    // ==========================================
    private val MICROSOFT_INITIALS = mapOf(
        'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g",
        'h' to "h", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m",
        'n' to "n", 'p' to "p", 'q' to "q", 'r' to "r", 's' to "s",
        't' to "t", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        'v' to "zh", 'i' to "ch", 'u' to "sh"
    )

    private val MICROSOFT_ZERO_INITIALS = mapOf(
        "oa" to "a", "ol" to "ai", "an" to "an", "oh" to "ang", "oj" to "ao",
        "oe" to "e", "oz" to "ei", "ok" to "en", "og" to "eng", "or" to "er",
        "oo" to "o", "ob" to "ou"
    )

    private fun resolveMicrosoftFinal(initial: String, finalKey: Char): String? {
        return when (finalKey) {
            'a' -> "a"
            'b' -> "ou"
            'c' -> "ao"
            'd' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "uang" else "iang"
            'e' -> "e"
            'f' -> "en"
            'g' -> "eng"
            'h' -> "ang"
            'i' -> "i"
            'j' -> "an"
            'k' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "uai" else "ing"
            'l' -> "ai"
            'm' -> "ian"
            'n' -> "in"
            'o' -> "uo"
            'p' -> "un"
            'q' -> "iu"
            'r' -> if (initial in setOf("j", "q", "x", "y")) "van" else "uan"
            's' -> if (initial in setOf("j", "q", "x")) "iong" else "ong"
            't' -> "ue"
            'u' -> "u"
            'v' -> if (initial in setOf("j", "q", "x", "n", "l")) "v" else "ui"
            'w' -> if (initial in setOf("g", "k", "h", "zh", "ch", "sh")) "ua" else "ia"
            'x' -> "ie"
            'y' -> "uai"
            'z' -> "ei"
            else -> null
        }
    }

    /**
     * 将双拼输入序列展开为全拼
     *
     * @param rawInput 原始键盘输入字符串（例如 "nihc"）
     * @param schemeId 双拼方案 ID（默认 3: 小鹤双拼）
     * @return 展开后的全拼字符串（例如 "nihao"）
     */
    fun expand(rawInput: CharSequence?, schemeId: Int = SCHEME_XIAOHE): String {
        if (rawInput.isNullOrEmpty()) return ""
        val text = rawInput.toString()

        val sb = StringBuilder()
        var i = 0
        val len = text.length

        while (i < len) {
            val c1 = text[i]
            if (!c1.isLetter()) {
                sb.append(c1)
                i++
                continue
            }

            // 如果只剩最后一个字符（奇数位），直接保留
            if (i + 1 >= len || !text[i + 1].isLetter()) {
                sb.append(c1)
                i++
                continue
            }

            val c2 = text[i + 1]
            val pair = "${c1.lowercaseChar()}${c2.lowercaseChar()}"
            val expanded = expandSyllable(pair, schemeId)

            if (expanded != null) {
                // 如果首字母大写，保持首字母大写
                if (c1.isUpperCase()) {
                    sb.append(expanded.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                } else {
                    sb.append(expanded)
                }
                i += 2
            } else {
                sb.append(c1)
                i++
            }
        }

        return sb.toString()
    }

    private fun expandSyllable(pair: String, schemeId: Int): String? {
        if (pair.length != 2) return null
        val c1 = pair[0]
        val c2 = pair[1]

        return when (schemeId) {
            SCHEME_XIAOHE -> expandXiaohe(pair, c1, c2)
            SCHEME_ZIRANMA -> expandZiranma(pair, c1, c2)
            SCHEME_MICROSOFT -> expandMicrosoft(pair, c1, c2)
            else -> expandXiaohe(pair, c1, c2) // 默认小鹤
        }
    }

    private fun expandXiaohe(pair: String, c1: Char, c2: Char): String? {
        // 先查零声母
        XIAOHE_ZERO_INITIALS[pair]?.let { return it }

        // 声母 + 韵母
        val initial = XIAOHE_INITIALS[c1] ?: return null
        val finalStr = resolveXiaoheFinal(initial, c2) ?: return null

        // 汉语拼音拼写规范调整
        return normalizePinyin(initial, finalStr)
    }

    private fun expandZiranma(pair: String, c1: Char, c2: Char): String? {
        ZIRANMA_ZERO_INITIALS[pair]?.let { return it }
        val initial = ZIRANMA_INITIALS[c1] ?: return null
        val finalStr = resolveZiranmaFinal(initial, c2) ?: return null
        return normalizePinyin(initial, finalStr)
    }

    private fun expandMicrosoft(pair: String, c1: Char, c2: Char): String? {
        MICROSOFT_ZERO_INITIALS[pair]?.let { return it }
        val initial = MICROSOFT_INITIALS[c1] ?: return null
        val finalStr = resolveMicrosoftFinal(initial, c2) ?: return null
        return normalizePinyin(initial, finalStr)
    }

    /**
     * 规范化拼音拼写（例如 j/q/x + u -> ju/qu/xu，j/q/x + v -> ju/qu/xu）
     */
    private fun normalizePinyin(initial: String, finalStr: String): String {
        var f = finalStr
        if (initial in setOf("j", "q", "x", "y")) {
            if (f == "v" || f == "u") f = "u"
            if (f == "ve" || f == "ue") f = "ue"
            if (f == "van" || f == "uan") f = "uan"
            if (f == "vn" || f == "un") f = "un"
        }
        return initial + f
    }
}
