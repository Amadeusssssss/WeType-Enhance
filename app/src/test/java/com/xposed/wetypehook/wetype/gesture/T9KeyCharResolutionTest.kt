package com.xposed.wetypehook.wetype.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 九宫格取键的回归护栏。
 *
 * 现场故障：用户把 1 号键绑成"粘贴"，结果标签画到了问号键左边那一列上。绘制上下文会被
 * 喂给**容器**（`S1_recycleview` 是九宫格左侧候选栏），而旧的兜底写法是"从名字里捞第一个
 * 1..9 的数字"，`S1_recycleview` 于是被解析成 `'1'`。容器的键位解析必须返回 NUL。
 *
 * 同时钉住真实按键的解析：九宫格按键的 `mainText` 是空的，数字印在 `floatText`
 * （`S1_key_2` = ABC/2）或 id 的 `*_key_<数字>` 尾巴上（`S26_key_1` 只有 mainText）。
 */
class T9KeyCharResolutionTest {

    @Suppress("unused")
    private class FakeKeyData(
        private val keyId: String?,
        private val main: String?,
        private val floating: String?
    ) {
        fun getId(): String? = keyId
        fun getMainText(): String? = main
        fun getFloatText(): String? = floating
    }

    @Suppress("unused")
    private class FakeKeyContext(val keyData: FakeKeyData)

    private fun keyCharOf(id: String?, main: String?, float: String?): Char =
        KeyGestureResolver.resolveKeyChar(
            FakeKeyContext(FakeKeyData(id, main, float)),
            null,
            true
        )

    @Test
    fun t9DigitComesFromFloatText() {
        // S1ChineseT9Keyboard 的 2 号键：mainText 是字母组，数字在 floatText。
        assertEquals('2', keyCharOf("S1_key_2", "ABC", "2"))
        assertEquals('7', keyCharOf("S1_key_7", "PQRS", "7"))
        // 笔画键盘：键面是笔画，数字同样在 floatText。
        assertEquals('8', keyCharOf("S7_key_8", "，", "8"))
        // 空格键 floatText 是 0；可绑定域只有 1..9，0 必须落空而不是变成键号。
        assertEquals('\u0000', keyCharOf("space", "", "0"))
    }

    @Test
    fun t9DigitFallsBackToKeyIdShape() {
        // S26PureNumberKeyboard：mainText 直接是数字，floatText 为空，只能靠 id 尾巴。
        assertEquals('1', keyCharOf("S26_key_1", "1", null))
        // 0 不在可绑定域内（设置页只提供 1..9）。
        assertEquals('\u0000', keyCharOf("S26_key_0", "0", null))
    }

    @Test
    fun containerContextsResolveToNothing() {
        // 九宫格左侧那一条候选栏容器——就是"问号键左边凭空出现粘贴"的元凶。
        assertEquals('\u0000', keyCharOf("S1_recycleview", null, null))
        assertEquals('\u0000', keyCharOf("S7_recycleview", null, null))
        // 表情/符号面板的分页器与占位键。
        assertEquals('\u0000', keyCharOf("s5_content_pager", null, null))
        assertEquals('\u0000', keyCharOf("S5_key_blank_12", null, null))
        assertEquals('\u0000', keyCharOf("S11EmojiKeyboard", null, null))
    }

    @Test
    fun featureKeysAreNotMinedForDigits() {
        assertEquals('\u0000', keyCharOf("newline_clear", "换行", null))
        assertEquals('\u0000', keyCharOf("symbol", "符号", null))
        assertEquals('\u0000', keyCharOf("S12_key_symbol_13", "?", null))
        assertEquals('\u0000', keyCharOf("S12_key_symbol_14", "！", null))
    }
}
