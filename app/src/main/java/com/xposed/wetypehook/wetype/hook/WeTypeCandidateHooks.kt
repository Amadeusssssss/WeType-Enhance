package com.xposed.wetypehook.wetype.hook

import android.view.View
import android.view.inputmethod.InputConnection
import com.xposed.wetypehook.wetype.pinyin.ShuangpinExpander
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Array
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList

/**
 * 候选栏、工具栏及双拼显示 Hook 组
 *
 * 覆盖三项增强：
 * 1. 【功能 B】英文模式下关闭候选/联想/补齐/纠错
 * 2. 【功能 C】工具栏（候选栏）高度调整（dp 单位，支持恢复默认）
 * 3. 【功能 D】双拼模式下展开显示完整拼音（如“nihc”展开为“nihao”，自动识别小鹤双拼等方案）
 */
internal object WeTypeCandidateHooks {

    private const val TAG = "WeTypeCandidate"
    private const val CANDIDATE_VIEW_CLASS = "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
    private const val STRIKE_TEXT_VIEW_CLASS = "com.tencent.wetype.plugin.hld.candidate.ImeStrikeTextView"
    private const val MODEL_MANAGER_CLASS = "com.tencent.wetype.plugin.hld.model.N"
    private const val CONFIG_UTIL_CLASS = "com.tencent.wetype.plugin.hld.utils.j1"
    private const val KEYBOARD_TYPE_CLASS = "com.tencent.wetype.plugin.hld.keyboard.t"

    // 候选行容器资源 ID
    private const val ID_CANDIDATE_NORMAL_CONTAINER = 0x7f0900fe

    @Volatile
    private var isInstalled = false

    @Volatile
    private var boundLoader: ClassLoader? = null

    @Volatile
    private var boundSourceDir: String? = null

    @Volatile
    private var modelNInstance: Any? = null

    @Volatile
    private var isKeyboardTypeMethod: Method? = null

    @Volatile
    private var englishKeyboardTypeEnum: Any? = null

    @Volatile
    private var doublePinT9TypeEnum: Any? = null

    @Volatile
    private var doublePinQwertyTypeEnum: Any? = null

    @Volatile
    private var doublePinSchemeMethod: Method? = null

    @Volatile
    private var configUtilInstance: Any? = null

    @Volatile
    private var isAutoCorrectHooked = false

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        if (isInstalled) return
        isInstalled = true
        boundLoader = classLoader
        boundSourceDir = sourceDir

        runCatching {
            hookCandidateBarHeight(classLoader)
            hookEnglishCandidateSuppression(classLoader)
            hookShuangpinPinyinExpansion(classLoader)
            Log.i("Success: WeType candidate hooks installed")
        }.onFailure {
            Log.e("[$TAG] Failed to install candidate hooks: ${it.message}")
            Log.i(it)
        }
    }

    /**
     * 延迟解析键盘模式管理器 N.a 与键盘枚举 t，避免在 onBaseContextAttached 时触发类初始化导致 NPE
     */
    private fun getModelN(): Any? {
        if (modelNInstance != null) return modelNInstance
        val cl = boundLoader ?: return null
        return runCatching {
            val nClass = Class.forName(MODEL_MANAGER_CLASS, false, cl)
            val instanceField = nClass.declaredFields.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.type == nClass
            }?.apply { isAccessible = true }
            val instance = instanceField?.get(null)
            if (instance != null) {
                modelNInstance = instance
                val tClass = Class.forName(KEYBOARD_TYPE_CLASS, false, cl)
                if (tClass.isEnum) {
                    val constants = tClass.enumConstants
                    englishKeyboardTypeEnum = constants?.firstOrNull { (it as Enum<*>).name == "EnglishQwerty" }
                    doublePinT9TypeEnum = constants?.firstOrNull { (it as Enum<*>).name == "DoublePinT9" }
                    doublePinQwertyTypeEnum = constants?.firstOrNull { (it as Enum<*>).name == "DoublePinQwerty" }
                }
                isKeyboardTypeMethod = nClass.declaredMethods.firstOrNull {
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == tClass && it.returnType == java.lang.Boolean.TYPE
                }?.apply { isAccessible = true }
                Log.i("[$TAG] Lazily resolved ModelN: instance=$modelNInstance checkMethod=${isKeyboardTypeMethod?.name}")
            }
            instance
        }.getOrNull()
    }

    /**
     * 延迟解析双拼方案读取工具 j1.a
     */
    private fun getConfigUtil(): Any? {
        if (configUtilInstance != null) return configUtilInstance
        val cl = boundLoader ?: return null
        return runCatching {
            val j1Class = Class.forName(CONFIG_UTIL_CLASS, false, cl)
            val instanceField = j1Class.declaredFields.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.type == j1Class
            }?.apply { isAccessible = true }
            val instance = instanceField?.get(null)
            if (instance != null) {
                configUtilInstance = instance
                doublePinSchemeMethod = j1Class.declaredMethods.firstOrNull {
                    it.name == "X" && it.parameterTypes.isEmpty() && it.returnType == Integer.TYPE
                }?.apply { isAccessible = true }

                val sourceDir = boundSourceDir
                if (doublePinSchemeMethod == null && !sourceDir.isNullOrEmpty()) {
                    DexKitLoader.ensureLoaded()
                    DexKitBridge.create(sourceDir).use { bridge ->
                        doublePinSchemeMethod = bridge.findMethod {
                            matcher {
                                usingStrings("ime_double_pin_solution")
                                returnType = "int"
                                paramCount = 0
                            }
                        }.firstOrNull()?.getMethodInstance(cl)?.apply { isAccessible = true }
                    }
                }
                Log.i("[$TAG] Lazily resolved ConfigUtil: schemeMethod=${doublePinSchemeMethod?.name}")
            }
            instance
        }.getOrNull()
    }

    /**
     * 获取当前生效的双拼方案 ID（3: 小鹤双拼，默认兜底 3）
     */
    private fun getCurrentDoublePinScheme(): Int {
        val instance = getConfigUtil()
        val method = doublePinSchemeMethod
        if (method != null && instance != null) {
            runCatching {
                val res = method.invoke(instance) as? Int
                if (res != null && res > 0) return res
            }
        }
        return ShuangpinExpander.SCHEME_XIAOHE
    }

    /**
     * 判定当前是否处于英文键盘模式
     */
    private fun isEnglishKeyboardActive(candidateView: View? = null): Boolean {
        val n = getModelN()
        val checkMethod = isKeyboardTypeMethod
        val enType = englishKeyboardTypeEnum
        if (n != null && checkMethod != null && enType != null) {
            val result = runCatching { checkMethod.invoke(n, enType) as? Boolean }.getOrNull()
            if (result != null) return result
        }

        if (candidateView != null) {
            val parent = candidateView.parent as? View
            val keyboardView = parent?.findViewById<View>(0x7f0902a3) // 键盘容器 FrameLayout
            val className = keyboardView?.javaClass?.name?.lowercase() ?: ""
            if (className.contains("english") || className.contains("s3")) return true
        }

        return false
    }

    /**
     * 判定当前是否处于双拼键盘模式（双拼九键/双拼全键/18键）
     */
     private fun isDoublePinKeyboardActive(): Boolean {
        val n = getModelN()
        val checkMethod = isKeyboardTypeMethod
        if (n != null && checkMethod != null) {
            doublePinT9TypeEnum?.let {
                if (runCatching { checkMethod.invoke(n, it) == true }.getOrDefault(false)) return true
            }
            doublePinQwertyTypeEnum?.let {
                if (runCatching { checkMethod.invoke(n, it) == true }.getOrDefault(false)) return true
            }
        }
        return false
    }

    // =========================================================================
    // 【功能 C】候选词栏 / 工具栏高度调整（dp 单位，0 代表恢复默认）
    // =========================================================================
    private fun hookCandidateBarHeight(classLoader: ClassLoader) {
        runCatching {
            val candidateViewClass = Class.forName(CANDIDATE_VIEW_CLASS, false, classLoader)
            val onMeasureMethod = candidateViewClass.getDeclaredMethod(
                "onMeasure",
                Integer.TYPE,
                Integer.TYPE
            )

            val setMeasuredDimensionMethod = runCatching {
                View::class.java.getDeclaredMethod("setMeasuredDimension", Integer.TYPE, Integer.TYPE).apply {
                    isAccessible = true
                }
            }.getOrNull()

            onMeasureMethod.hookBefore { param ->
                val heightDp = WeTypeSettings.getCandidateBarHeightDpXposed()
                if (heightDp <= 0) return@hookBefore

                val view = param.thisObject as? View ?: return@hookBefore
                val customPx = (heightDp * view.resources.displayMetrics.density).toInt()
                val exactlySpec = View.MeasureSpec.makeMeasureSpec(customPx, View.MeasureSpec.EXACTLY)
                param.args[1] = exactlySpec
            }

            onMeasureMethod.hookAfter { param ->
                val heightDp = WeTypeSettings.getCandidateBarHeightDpXposed()
                if (heightDp <= 0) return@hookAfter

                val view = param.thisObject as? View ?: return@hookAfter
                val customPx = (heightDp * view.resources.displayMetrics.density).toInt()
                runCatching {
                    setMeasuredDimensionMethod?.invoke(view, view.measuredWidth, customPx)
                }
            }

            Log.i("[$TAG] Hooked candidate view onMeasure for height adjustment")
        }.onFailure {
            Log.e("[$TAG] Failed to hook onMeasure: ${it.message}")
        }
    }

    private fun ensureAutoCorrectHooked(classLoader: ClassLoader) {
        if (isAutoCorrectHooked) return
        isAutoCorrectHooked = true
        runCatching {
            val j1Class = Class.forName(CONFIG_UTIL_CLASS, false, classLoader)
            val autoCorrectMethod = j1Class.declaredMethods.firstOrNull {
                it.name == "W1" && it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE
            }
            autoCorrectMethod?.hookBefore { param ->
                if (WeTypeSettings.isHideEnglishCandidatesXposed() && isEnglishKeyboardActive()) {
                    param.result = false
                }
            }
            Log.i("[$TAG] Lazily hooked j1.W1 for auto-correction suppression")
        }.onFailure {
            Log.e("[$TAG] Failed to hook auto-correction lazily: ${it.message}")
        }
    }

    // =========================================================================
    // 【功能 B】英文模式下关闭候选/联想/补齐/纠错
    // =========================================================================
    private fun hookEnglishCandidateSuppression(classLoader: ClassLoader) {
        runCatching {
            // 拦截候选词行数据分发入口 ImeCandidateView.t(PendingInput[], CharSequence, int, boolean, boolean)
            val candidateViewClass = Class.forName(CANDIDATE_VIEW_CLASS, false, classLoader)
            val tMethod = candidateViewClass.declaredMethods.firstOrNull {
                it.name == "t" && it.parameterTypes.size == 5 &&
                    it.parameterTypes[0].isArray &&
                    CharSequence::class.java.isAssignableFrom(it.parameterTypes[1])
            }

            tMethod?.hookBefore { param ->
                val cl = boundLoader ?: (param.thisObject as? View)?.context?.classLoader
                if (cl != null) ensureAutoCorrectHooked(cl)

                if (!WeTypeSettings.isHideEnglishCandidatesXposed()) return@hookBefore
                val view = param.thisObject as? View ?: return@hookBefore
                if (!isEnglishKeyboardActive(view)) return@hookBefore

                // 将传入的 PendingInput 数组替换为空数组，阻断联想与候选词上屏逻辑
                val originalArray = param.args[0]
                if (originalArray != null && java.lang.reflect.Array.getLength(originalArray) > 0) {
                    val componentType = originalArray.javaClass.componentType ?: return@hookBefore
                    val emptyArray = Array.newInstance(componentType, 0)
                    param.args[0] = emptyArray
                }

                // 隐藏默认候选词横向滚动容器
                view.findViewById<View>(ID_CANDIDATE_NORMAL_CONTAINER)?.visibility = View.GONE
            }

            // 3. 拦截候选词列表更新 ImeCandidateView.A(ArrayList, int, boolean, boolean)
            val aMethod = candidateViewClass.declaredMethods.firstOrNull {
                it.name == "A" && it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == ArrayList::class.java &&
                    it.parameterTypes[1] == Integer.TYPE
            }

            aMethod?.hookBefore { param ->
                if (!WeTypeSettings.isHideEnglishCandidatesXposed()) return@hookBefore
                val view = param.thisObject as? View ?: return@hookBefore
                if (!isEnglishKeyboardActive(view)) return@hookBefore

                // 清空候选词列表，确保候选词引擎无法向视图灌入候选项
                val candidatesList = param.args[0] as? ArrayList<*>
                candidatesList?.clear()
            }

            Log.i("[$TAG] Hooked English candidate suppression successfully")
        }.onFailure {
            Log.e("[$TAG] Failed to hook English candidate suppression: ${it.message}")
        }
    }

    // =========================================================================
    // 【功能 D】双拼模式下展开显示完整拼音（如“nihc”展开为“nihao”）
    // =========================================================================
    private fun hookShuangpinPinyinExpansion(classLoader: ClassLoader) {
        runCatching {
            val candidateViewClass = Class.forName(CANDIDATE_VIEW_CLASS, false, classLoader)

            // 1. 预编辑文本更新入口 ImeCandidateView.s1(CharSequence currentComposingText)
            val s1Method = candidateViewClass.declaredMethods.firstOrNull {
                it.name == "s1" && it.parameterTypes.size == 1 &&
                    CharSequence::class.java.isAssignableFrom(it.parameterTypes[0])
            }

            s1Method?.hookBefore { param ->
                if (!WeTypeSettings.isShuangpinExpandPinyinXposed()) return@hookBefore
                if (!isDoublePinKeyboardActive()) return@hookBefore

                val rawText = param.args[0] as? CharSequence ?: return@hookBefore
                val schemeId = getCurrentDoublePinScheme()
                val expanded = ShuangpinExpander.expand(rawText, schemeId)
                if (expanded.isNotEmpty() && expanded != rawText) {
                    param.args[0] = expanded
                }
            }

            // 2. 预编辑 TextView 显示入口 ImeStrikeTextView.setText(CharSequence)
            val strikeTextViewClass = Class.forName(STRIKE_TEXT_VIEW_CLASS, false, classLoader)
            val setTextMethods = strikeTextViewClass.declaredMethods.filter {
                it.name == "setText" && it.parameterTypes.isNotEmpty() &&
                    CharSequence::class.java.isAssignableFrom(it.parameterTypes[0])
            }

            setTextMethods.forEach { method ->
                method.hookBefore { param ->
                    if (!WeTypeSettings.isShuangpinExpandPinyinXposed()) return@hookBefore
                    if (!isDoublePinKeyboardActive()) return@hookBefore

                    val rawText = param.args[0] as? CharSequence ?: return@hookBefore
                    val schemeId = getCurrentDoublePinScheme()
                    val expanded = ShuangpinExpander.expand(rawText, schemeId)
                    if (expanded.isNotEmpty() && expanded != rawText) {
                        param.args[0] = expanded
                    }
                }
            }

            // 3. 输入框预编辑状态显示拦截 InputConnection.setComposingText(CharSequence, int)
            val inputConnectionClass = InputConnection::class.java
            val setComposingMethod = inputConnectionClass.getDeclaredMethod(
                "setComposingText",
                CharSequence::class.java,
                Integer.TYPE
            )

            setComposingMethod.hookBefore { param ->
                if (!WeTypeSettings.isShuangpinExpandPinyinXposed()) return@hookBefore
                if (!isDoublePinKeyboardActive()) return@hookBefore

                val rawText = param.args[0] as? CharSequence ?: return@hookBefore
                val schemeId = getCurrentDoublePinScheme()
                val expanded = ShuangpinExpander.expand(rawText, schemeId)
                if (expanded.isNotEmpty() && expanded != rawText) {
                    param.args[0] = expanded
                }
            }

            Log.i("[$TAG] Hooked Shuangpin pinyin expansion successfully")
        }.onFailure {
            Log.e("[$TAG] Failed to hook Shuangpin pinyin expansion: ${it.message}")
        }
    }
}
