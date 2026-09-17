package com.xposed.wetypehook.wetype.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the version-agnostic panel switcher.
 *
 * WeType renames the obfuscated panel entry between releases (3.5.3 = h3,
 * 3.5.4 = k3), so binding must be driven by stable structure — the enum
 * `keyboard.t` and the unique `(int, Bundle) -> void` on `model.N` — never by
 * the obfuscated method name.
 */
class WeTypePanelSwitcherTest {
    private val source = File("src/main/java/com/xposed/wetypehook/wetype/hook/WeTypePanelSwitcher.kt").readText()

    /** Strip block/line comments so doc examples of old names do not false-positive. */
    private val code: String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test fun resolvesSwitchEntryBySignatureShapeNotObfuscatedName() {
        assertFalse(
            "panel switch must not match an obfuscated method name",
            Regex("""name\s*==\s*"(n3|p3|k3|l3|h3)"""").containsMatchIn(code)
        )
        assertTrue(
            "must bind the (int, Bundle) -> void shape",
            code.contains("it.parameterTypes[0] == Int::class.javaPrimitiveType") &&
                code.contains("it.parameterTypes[1] == Bundle::class.java") &&
                code.contains("it.returnType == Void.TYPE")
        )
    }

    @Test fun panelValuesComeFromStableEnumGetter() {
        assertTrue(
            "panel enum must resolve to the first no-arg int getter (t.c())",
            code.contains("it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType")
        )
    }

    @Test fun findWordSplitsByKeyboardShapeNotDriftingKeyGetter() {
        assertTrue("find word must branch on T9 keyboard shape", code.contains("isT9KeyboardView"))
        assertFalse(
            "must not depend on the renamed keyboard value getters",
            Regex("""name\s*==\s*"(n0|m0)"""").containsMatchIn(code)
        )
    }
}
