package com.xposed.wetypehook.wetype.hook

import android.os.Bundle
import android.view.View
import com.xposed.wetypehook.xposed.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * 宿主面板切换桥接（与宿主「＋面板」入口同契约）
 *
 * 面板枚举与切换入口在 3.5.3 / 3.5.4 之间**混淆名会漂移**，因此这里只按稳定的
 * 结构签名绑定，不再硬编码方法名：
 * - 面板枚举 `...hld.keyboard.t`：常量名（`CustomPhraseAndClipboard` /
 *   `HandwriteFindWordT9` / `HandwriteFindWordT26`）与取值 getter `()I` 两版一致；
 * - 面板管理器 `...hld.model.N`：静态自引用单例字段两版一致；
 * - 切换入口：`N` 上**唯一**的 `(int, Bundle) -> void`（3.5.3=`h3`、3.5.4=`k3`），
 *   语义等同宿主「＋面板」走的带展开动画切换。
 *
 * 实测对照（dexdump 二进制验证）：
 * ```
 *                        3.5.3          3.5.4
 * (int, Bundle) -> void  h3（唯一）     k3（唯一）
 * (enum, Bundle) -> void k3, p3         n3, s3
 * 宿主 ＋面板 入口        k3(enum)       n3(enum)   -> 都落到上面的 int 入口
 * 面板取值 getter         c(): int       c(): int
 * ```
 *
 * 剪贴板与常用语共用 `CustomPhraseAndClipboard(501)`，靠 Bundle 的
 * `target_tab_index`（0=剪贴板，1=常用语）区分。
 *
 * 找字面板按当前一级键盘分流：九键族走 `HandwriteFindWordT9`，其余走
 * `HandwriteFindWordT26`。分流信号取自手势按键所在的键盘视图类名（与手势层
 * `isT9View` 同规则），避免依赖会在 3.5.3/3.5.4 之间换名的键盘值 getter
 * （3.5.3 的一级键盘值是 `m0()`，3.5.4 是 `n0()`）。
 *
 * 调用方负责切主线程（手势侧由 `view.post {}` 保证）。
 */
internal object WeTypePanelSwitcher {

    private const val TAG = "WeTypePanelSwitcher"

    private const val PANEL_ENUM_CLASS = "com.tencent.wetype.plugin.hld.keyboard.t"
    private const val MANAGER_CLASS = "com.tencent.wetype.plugin.hld.model.N"

    private const val PANEL_CUSTOM_PHRASE_AND_CLIPBOARD = "CustomPhraseAndClipboard"
    private const val PANEL_FIND_WORD_T9 = "HandwriteFindWordT9"
    private const val PANEL_FIND_WORD_T26 = "HandwriteFindWordT26"

    private const val KEY_TARGET_TAB_INDEX = "target_tab_index"
    private const val KEY_FIND_WORD_SCENE_FROM_OTHER = "key_enter_hand_write_find_word_scene_from_other_scene"

    private const val TAB_CLIPBOARD = 0
    private const val TAB_CUSTOM_PHRASE = 1

    @Volatile
    private var boundLoader: ClassLoader? = null
    private var manager: Any? = null

    /** 面板枚举常量名 -> 枚举实例（复用宿主稳定的 `name()`）。 */
    private var panelConstants: Map<String, Any> = emptyMap()

    /** `N` 上唯一的 `(int, Bundle) -> void`；3.5.3=h3 / 3.5.4=k3。 */
    private var switchByInt: Method? = null

    fun openClipboard(classLoader: ClassLoader): Boolean =
        switchPanel(
            classLoader,
            PANEL_CUSTOM_PHRASE_AND_CLIPBOARD,
            Bundle().apply { putInt(KEY_TARGET_TAB_INDEX, TAB_CLIPBOARD) }
        )

    fun openCustomPhrase(classLoader: ClassLoader): Boolean =
        switchPanel(
            classLoader,
            PANEL_CUSTOM_PHRASE_AND_CLIPBOARD,
            Bundle().apply { putInt(KEY_TARGET_TAB_INDEX, TAB_CUSTOM_PHRASE) }
        )

    fun openFindWord(classLoader: ClassLoader, isT9: Boolean): Boolean {
        val bundle = Bundle().apply { putBoolean(KEY_FIND_WORD_SCENE_FROM_OTHER, true) }
        return switchPanel(classLoader, if (isT9) PANEL_FIND_WORD_T9 else PANEL_FIND_WORD_T26, bundle)
    }

    /** 与手势层 `isT9View` 同规则：按键视图祖先类名含 t9/nine 即九键族。 */
    fun isT9KeyboardView(view: View): Boolean {
        if (isT9Class(view.javaClass)) return true
        var parent = view.parent
        repeat(8) {
            val parentView = parent as? View ?: return false
            if (isT9Class(parentView.javaClass)) return true
            parent = parentView.parent
        }
        return false
    }

    private fun isT9Class(clazz: Class<*>): Boolean {
        val name = clazz.name.lowercase(Locale.ROOT)
        return name.contains("t9") || name.contains("nine")
    }

    private fun switchPanel(classLoader: ClassLoader, panelName: String, bundle: Bundle): Boolean {
        bind(classLoader) ?: return false
        val instance = manager ?: return false
        val method = switchByInt ?: return false
        val value = panelValue(panelName) ?: run {
            Log.e("[$TAG] Panel $panelName value unresolved")
            return false
        }
        val ok = runCatching { method.invoke(instance, value, bundle) }.isSuccess
        if (ok) {
            Log.i("[$TAG] Switched panel $panelName ($value) via ${method.name}")
        } else {
            Log.e("[$TAG] Failed to switch panel $panelName ($value)")
        }
        return ok
    }

    /** 读取面板枚举常量的 int 值：取唯一的无参 int 方法（`c()`）。 */
    private fun panelValue(panelName: String): Int? {
        val constant = panelConstants[panelName] ?: return null
        return runCatching {
            constant.javaClass.declaredMethods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }?.invoke(constant) as? Int
        }.getOrNull()
    }

    @Synchronized
    private fun bind(classLoader: ClassLoader): Unit? {
        if (boundLoader === classLoader && manager != null && switchByInt != null) return Unit

        boundLoader = null
        manager = null
        panelConstants = emptyMap()
        switchByInt = null

        val enumClass = runCatching { Class.forName(PANEL_ENUM_CLASS, false, classLoader) }.getOrNull()
        if (enumClass == null) {
            Log.e("[$TAG] Panel enum $PANEL_ENUM_CLASS not found")
            return null
        }
        panelConstants = enumClass.enumConstants
            ?.filterIsInstance<Enum<*>>()
            ?.associate { it.name to (it as Any) }
            .orEmpty()
        if (panelConstants.isEmpty()) {
            Log.e("[$TAG] Panel enum constants unavailable on $PANEL_ENUM_CLASS")
            return null
        }

        val managerClass = runCatching { Class.forName(MANAGER_CLASS, false, classLoader) }.getOrNull()
        if (managerClass == null) {
            Log.e("[$TAG] Panel manager $MANAGER_CLASS not found")
            return null
        }
        val instance = staticSelfInstance(managerClass)
        if (instance == null) {
            Log.e("[$TAG] Panel manager singleton not found on $MANAGER_CLASS")
            return null
        }

        val intSwitches = managerClass.declaredMethods.filter {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Bundle::class.java &&
                it.returnType == Void.TYPE
        }
        switchByInt = intSwitches.firstOrNull()?.apply { isAccessible = true }
        if (switchByInt == null) {
            Log.e("[$TAG] Panel switch entry missing ((int, Bundle) -> void) on $MANAGER_CLASS")
            return null
        }
        if (intSwitches.size > 1) {
            Log.e("[$TAG] Multiple (int, Bundle) entries ${intSwitches.map { it.name }}; using ${switchByInt?.name}")
        }

        manager = instance
        boundLoader = classLoader
        Log.i("[$TAG] Bound panel switcher: panels=${panelConstants.size} switch=${switchByInt?.name}")
        return Unit
    }

    private fun staticSelfInstance(cls: Class<*>): Any? {
        val field = cls.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == cls
        } ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
    }
}
