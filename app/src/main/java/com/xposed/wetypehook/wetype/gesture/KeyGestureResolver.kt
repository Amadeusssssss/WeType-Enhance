package com.xposed.wetypehook.wetype.gesture

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.xposed.wetypehook.wetype.settings.WeTypeGestureSettings
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * 触摸手势判定器与状态机
 * 逆向还原自 WeType-Tool C1474 + C1476 + C1774 + C1769
 */
class KeyGestureResolver(
    private val keyDataMethod: Method? = null,
    private val keyIdMethod: Method? = null
) {

    private var activeViewRef = WeakReference<View>(null)
    private var startX = 0f
    private var startY = 0f
    private var thresholdPx = 20f
    private var boundAction = GestureAction.None
    private var triggered = false
    private var isCurrentT9 = false
    private var isCurrent18Key = false

    fun reset() {
        activeViewRef.clear()
        startX = 0f
        startY = 0f
        thresholdPx = 20f
        boundAction = GestureAction.None
        triggered = false
        isCurrentT9 = false
        isCurrent18Key = false
    }

    /**
     * 触摸事件拦截
     * 返回 true 表示该事件被手势消费，不应继续派发给原始按键
     *
     * @param view 键盘视图 (thisObject)
     * @param keyContext 按键数据上下文 (param.args[0]，如 selfdraw.j)
     * @param event 触摸事件
     * @param isT9 是否为九宫格模式
     * @param cancelNative 取消原生事件回调 (用 ACTION_CANCEL 调用原方法)
     */
    fun onInterceptTouch(
        view: View,
        keyContext: Any?,
        event: MotionEvent,
        isT9: Boolean,
        cancelNative: (() -> Unit)? = null
    ): Boolean {
        val is18Key = is18KeyContext(view, keyContext)
        val isEnabled = if (is18Key) {
            WeTypeSettings.isLayout18KeyGestureEnabledXposed()
        } else if (isT9) {
            WeTypeSettings.isT9GestureEnabledXposed()
        } else {
            WeTypeSettings.isQwertyGestureEnabledXposed()
        }
        if (!isEnabled) {
            reset()
            return false
        }

        val actionMasked = event.actionMasked

        // 多指操作直接放行并重置
        if (event.pointerCount != 1) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                reset()
            }
            return false
        }

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            startX = event.x
            startY = event.y
            activeViewRef = WeakReference(view)
            isCurrentT9 = isT9
            isCurrent18Key = is18Key
            triggered = false

            val action: GestureAction
            val thresholdDp: Int

            if (is18Key) {
                val keyName = resolve18KeyName(keyContext ?: view)
                if (keyName == null) {
                    reset()
                    return false
                }
                val bindings = WeTypeGestureSettings.parse18KeyBindings(
                    WeTypeSettings.getLayout18KeyGestureBindingsJsonXposed()
                )
                action = bindings[keyName] ?: GestureAction.None
                thresholdDp = WeTypeSettings.getLayout18KeyGestureThresholdXposed()
            } else {
                // 从 keyContext (selfdraw.j / KeyData) 或 view 中解析按键字符
                val keyChar = resolveKeyChar(keyContext ?: view, keyDataMethod, isT9)
                if (keyChar == '\u0000') {
                    reset()
                    return false
                }
                val bindings = WeTypeGestureSettings.parseBindings(WeTypeSettings.getGestureBindingsJsonXposed())
                action = bindings[keyChar] ?: GestureAction.None
                thresholdDp = if (isT9) {
                    WeTypeSettings.getT9GestureThresholdXposed()
                } else {
                    WeTypeSettings.getGestureThresholdXposed()
                }
            }

            boundAction = action

            val density = view.resources.displayMetrics.density
            thresholdPx = max(1f, thresholdDp.coerceIn(10, 48) * density)

            if (action == GestureAction.None || action == GestureAction.Disable) {
                reset()
                return false
            }
            return false
        }

        // 已触发状态下，后续 MOVE、UP、CANCEL 全量消费，避免输入原字符
        if (triggered) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                reset()
            }
            return true
        }

        if ((actionMasked == MotionEvent.ACTION_MOVE || actionMasked == MotionEvent.ACTION_UP) && boundAction != GestureAction.None) {
            val deltaX = event.x - startX
            val deltaY = event.y - startY

            // 动态横向漂移上限计算 (18键与QWERTY均放宽横向容差，支持人手大拇指 55° 斜滑)
            val driftLimit = max(
                thresholdPx * (if (isCurrent18Key) 2.8f else if (isT9) 0.9f else 2.5f),
                abs(deltaY) * (if (isCurrent18Key) 1.5f else if (isT9) 0.75f else 1.2f)
            )

            // 下滑判定: 纵向位移超过阈值，或快速轻弹(ACTION_UP且达75%阈值)，且横向未超容差
            val isFlick = actionMasked == MotionEvent.ACTION_UP &&
                deltaY >= thresholdPx * 0.75f &&
                abs(deltaX) <= driftLimit

            if ((deltaY >= thresholdPx || isFlick) && abs(deltaX) <= driftLimit) {
                val actionToExecute = boundAction
                triggered = true
                Log.i("Gesture triggered: action=${actionToExecute.title}, deltaY=$deltaY, threshold=$thresholdPx, isFlick=$isFlick")

                // 1. 发送 ACTION_CANCEL 中止原生按键事件 (优先回调原方法派发 CANCEL)
                if (cancelNative != null) {
                    cancelNative.invoke()
                } else {
                    sendCancelEvent(view, event)
                }

                // 2. 键盘触觉反馈
                val vibrate = if (isCurrent18Key) {
                    WeTypeSettings.isLayout18KeyGestureVibrationXposed()
                } else if (isT9) {
                    WeTypeSettings.isT9GestureVibrationXposed()
                } else {
                    WeTypeSettings.isGestureVibrationXposed()
                }
                if (vibrate) {
                    runCatching {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }

                // 3. 执行动作
                view.post {
                    GestureActionExecutor.execute(actionToExecute, view)
                }

                if (actionMasked == MotionEvent.ACTION_UP) {
                    reset()
                }
                return true
            }
        }

        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            reset()
        }
        return false
    }

    /** 兼容旧接口重载 */
    fun onInterceptTouch(view: View, event: MotionEvent, isT9: Boolean): Boolean =
        onInterceptTouch(view, null, event, isT9, null)

    /**
     * 派发 ACTION_CANCEL 给按键视图
     */
    private fun sendCancelEvent(view: View, originalEvent: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(originalEvent).apply {
            action = MotionEvent.ACTION_CANCEL
        }
        runCatching {
            view.dispatchTouchEvent(cancelEvent)
        }.also {
            cancelEvent.recycle()
        }
    }

    /**
     * 解析按键字符 (QWERTY 键名 / T9 键号)，抽成伴生方法供底部标签绘制复用。
     */
    fun resolveKeyChar(keyContext: Any?): Char = resolveKeyChar(keyContext, keyDataMethod, isCurrentT9)

    companion object {
        private data class KeyAccessor(
            val keyDataGetter: ((Any) -> Any?)?,
            val mainTextMethod: Method?,
            val floatTextMethod: Method?,
            val idMethod: Method?
        )
        private val accessorCache = ConcurrentHashMap<Class<*>, KeyAccessor>()

        fun resolveKeyChar(keyContext: Any?, keyDataMethod: Method? = null, isT9: Boolean = false): Char {
            if (keyContext == null) return '\u0000'

            // 1. 如果是 TextView，直接取文本
            if (keyContext is TextView) {
                val text = keyContext.text?.toString()
                if (!text.isNullOrEmpty()) return normalizeKeyText(text, isT9)
            }

            // 2. 尝试从 keyDataMethod 反射调用 (可能直接作用于 KeyData 或 keyContext)
            keyDataMethod?.let { method ->
                runCatching {
                    method.isAccessible = true
                    val result = method.invoke(keyContext) as? String
                    if (!result.isNullOrEmpty()) {
                        val normalized = normalizeKeyText(result, isT9)
                        if (normalized != '\u0000') return normalized
                    }
                }
            }

            // 3. 九宫格走专用路径：mainText 为空、数字在 floatText 或 `*_key_<数字>` 形状的 id 上。
            //    容器（左侧候选栏 S1_recycleview、表情分页器）必须返回 '\u0000'，
            //    绝不能从名字里捞数字。
            if (isT9) return resolveT9KeyChar(keyContext)

            // 4. 动态从 keyContext (如 selfdraw.j / KeyData) 提取 mainText / id
            val rawText = resolveRawText(keyContext)
            if (!rawText.isNullOrEmpty()) {
                val normalized = normalizeKeyText(rawText, isT9)
                if (normalized != '\u0000') return normalized
            }

            // 5. View tag 兜底
            if (keyContext is View) {
                val tagStr = keyContext.tag?.toString()
                if (!tagStr.isNullOrEmpty()) {
                    val normalized = normalizeKeyText(tagStr, isT9)
                    if (normalized != '\u0000') return normalized
                }
            }

            return '\u0000'
        }

        private fun normalizeKeyText(text: String, isT9: Boolean): Char {
            val trimmed = text.trim()
            if (trimmed.equals("space", ignoreCase = true) || trimmed == "空格" || trimmed.equals("spacebar", ignoreCase = true)) {
                return ' '
            }
            if (isT9) {
                val mapped = mapT9Text(trimmed)
                if (mapped != null) return mapped
                if (trimmed.length == 1 && trimmed[0] in '1'..'9') return trimmed[0]
                return '\u0000'
            }
            // QWERTY 模式：严格仅接受单个英文字母按键 'a'..'z' / 'A'..'Z'，坚决排除功能键（如中英切换、换行、删除、数字等）
            if (trimmed.length == 1) {
                val ch = trimmed[0]
                if (ch in 'a'..'z' || ch in 'A'..'Z') {
                    return ch.lowercaseChar()
                }
            }
            return '\u0000'
        }

        private fun mapT9Text(str: String): Char? {
            val letters = str.filter { it in 'a'..'z' || it in 'A'..'Z' }.lowercase(Locale.ROOT)
            return when (letters) {
                "abc" -> '2'
                "def" -> '3'
                "ghi" -> '4'
                "jkl" -> '5'
                "mno" -> '6'
                "pqrs" -> '7'
                "tuv" -> '8'
                "wxyz" -> '9'
                else -> null
            }
        }

        /**
         * T9 可绑定键就是 1..9（设置页的九宫格也只有这九个格子），`0` 不在域内：
         * 空格键的 `floatText` 是 `0`，放进来只会得到一个永远绑不上的键号。
         */
        private val T9_KEY_ID = Regex("""^S\d+_key_([1-9])$""")

        /**
         * 宿主给每个键的 `KeyData` 都填了稳定 id，但**九宫格按键的 `mainText` 是空的**
         * （字母印在 `floatText` 上），所以取字符时只能退到 id。
         *
         * 退到 id 有一个坑：绘制上下文也会被喂给**容器**（左侧候选栏 `S1_recycleview`、
         * 表情面板的分页器等）。曾经的兜底写法是"从字符串里捞第一个 1..9 的数字"，
         * 于是 `S1_recycleview` 被解析成 `'1'`——用户绑定在 1 号键上的"粘贴"就凭空画到了
         * 问号键左边那一列上。id 必须严格匹配 `*_key_<数字>` 才认，容器一律返回 null。
         */
        private fun t9DigitFromKeyId(id: String): Char? =
            T9_KEY_ID.matchEntire(id.trim())?.groupValues?.get(1)?.firstOrNull()

        private fun resolveRawText(obj: Any): String? {
            val clazz = obj.javaClass
            val accessor = accessorCache.getOrPut(clazz) { buildAccessor(clazz) }

            // 先尝试直接调用 mainTextMethod 或 idMethod
            accessor.mainTextMethod?.let { m ->
                runCatching { m.invoke(obj) as? String }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            accessor.idMethod?.let { m ->
                runCatching { m.invoke(obj) as? String }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            }

            // 再尝试从 keyData 提取
            val keyData = accessor.keyDataGetter?.invoke(obj)
            if (keyData != null) {
                val kdClass = keyData.javaClass
                val kdAccessor = accessorCache.getOrPut(kdClass) { buildAccessor(kdClass) }
                kdAccessor.mainTextMethod?.let { m ->
                    runCatching { m.invoke(keyData) as? String }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
                kdAccessor.idMethod?.let { m ->
                    runCatching { m.invoke(keyData) as? String }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }

            return null
        }

        /**
         * 九宫格取键：`floatText`（键帽上的数字）优先，其次才认 `*_key_<数字>` 形状的 id。
         * 只有 id 时 `mainText` 是空的（如 `S1_key_2`），只有数字键时 `floatText` 是空的
         * （如 `S26_key_1`），两条路都得留着。
         */
        private fun resolveT9KeyChar(obj: Any): Char {
            val clazz = obj.javaClass
            val accessor = accessorCache.getOrPut(clazz) { buildAccessor(clazz) }

            fun digitFrom(target: Any, a: KeyAccessor): Char? {
                a.floatTextMethod?.let { m ->
                    runCatching { m.invoke(target) as? String }.getOrNull()
                        ?.let { t9DigitFromKeyId(it) ?: it.trim().firstOrNull { c -> c in '1'..'9' } }
                        ?.let { return it }
                }
                a.idMethod?.let { m ->
                    runCatching { m.invoke(target) as? String }.getOrNull()?.let { t9DigitFromKeyId(it) }
                        ?.let { return it }
                }
                return null
            }

            digitFrom(obj, accessor)?.let { return it }
            val keyData = accessor.keyDataGetter?.invoke(obj) ?: return '\u0000'
            return digitFrom(keyData, accessorCache.getOrPut(keyData.javaClass) { buildAccessor(keyData.javaClass) })
                ?: '\u0000'
        }

        private fun buildAccessor(clazz: Class<*>): KeyAccessor {
            var mainTextM: Method? = null
            var floatTextM: Method? = null
            var idM: Method? = null
            for (m in clazz.methods) {
                if (m.parameterTypes.isEmpty() && m.returnType == String::class.java) {
                    when (m.name) {
                        "getMainText" -> { m.isAccessible = true; mainTextM = m }
                        "getFloatText" -> { m.isAccessible = true; floatTextM = m }
                        "getId" -> { m.isAccessible = true; idM = m }
                    }
                }
            }

            // 寻找 KeyData getter
            var getter: ((Any) -> Any?)? = null
            var search: Class<*>? = clazz
            while (search != null && search != Any::class.java && getter == null) {
                for (field in search.declaredFields) {
                    if (field.type.name.contains("KeyData")) {
                        field.isAccessible = true
                        getter = { target -> runCatching { field.get(target) }.getOrNull() }
                        break
                    }
                }
                search = search.superclass
            }
            if (getter == null) {
                for (m in clazz.methods) {
                    if (m.parameterTypes.isEmpty() && m.returnType.name.contains("KeyData")) {
                        m.isAccessible = true
                        getter = { target -> runCatching { m.invoke(target) }.getOrNull() }
                        break
                    }
                }
            }

            return KeyAccessor(getter, mainTextM, floatTextM, idM)
        }

        @Volatile
        private var last18KeyActiveTime = 0L

        fun mark18KeyActive() {
            last18KeyActiveTime = System.currentTimeMillis()
        }

        fun is18KeySessionActive(): Boolean {
            return (System.currentTimeMillis() - last18KeyActiveTime) < 15_000L
        }

        fun is18KeyContext(view: View?, keyContext: Any?): Boolean {
            if (keyContext != null && resolve18KeyId(keyContext)?.startsWith("zh_18key", ignoreCase = true) == true) {
                mark18KeyActive()
                return true
            }
            if (view != null && is18KeyView(view)) {
                mark18KeyActive()
                return true
            }
            if (is18KeySessionActive()) {
                val idOrText = resolve18KeyId(keyContext) ?: (keyContext as? View)?.tag?.toString()
                if (idOrText != null) {
                    val norm = normalize18KeyName(idOrText)
                    if (norm == "space") return true
                }
            }
            return false
        }

        fun is18KeyView(view: View): Boolean {
            if (is18KeyClass(view.javaClass)) return true
            var parent = view.parent
            repeat(8) {
                val parentView = parent as? View ?: return false
                if (is18KeyClass(parentView.javaClass)) return true
                parent = parentView.parent
            }
            return false
        }

        private fun is18KeyClass(clazz: Class<*>): Boolean {
            val name = clazz.name.lowercase(Locale.ROOT)
            return name.contains("doublepin") || name.contains("18key")
        }

        fun resolve18KeyId(keyContext: Any?): String? {
            if (keyContext == null) return null
            val clazz = keyContext.javaClass
            val accessor = accessorCache.getOrPut(clazz) { buildAccessor(clazz) }
            accessor.idMethod?.let { m ->
                runCatching { m.invoke(keyContext) as? String }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            val keyData = accessor.keyDataGetter?.invoke(keyContext)
            if (keyData != null) {
                val kdClass = keyData.javaClass
                val kdAccessor = accessorCache.getOrPut(kdClass) { buildAccessor(kdClass) }
                kdAccessor.idMethod?.let { m ->
                    runCatching { m.invoke(keyData) as? String }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
            return null
        }

        fun resolve18KeyName(keyContext: Any?): String? {
            if (keyContext == null) return null
            if (keyContext is TextView) {
                val text = keyContext.text?.toString()
                if (!text.isNullOrEmpty()) {
                    normalize18KeyName(text)?.let { return it }
                }
            }
            val clazz = keyContext.javaClass
            val accessor = accessorCache.getOrPut(clazz) { buildAccessor(clazz) }
            fun checkTarget(target: Any, a: KeyAccessor): String? {
                a.idMethod?.let { m ->
                    runCatching { m.invoke(target) as? String }.getOrNull()?.let { id ->
                        normalize18KeyName(id)?.let { return it }
                    }
                }
                a.mainTextMethod?.let { m ->
                    runCatching { m.invoke(target) as? String }.getOrNull()?.let { text ->
                        normalize18KeyName(text)?.let { return it }
                    }
                }
                return null
            }
            checkTarget(keyContext, accessor)?.let { return it }
            val keyData = accessor.keyDataGetter?.invoke(keyContext)
            if (keyData != null) {
                checkTarget(keyData, accessorCache.getOrPut(keyData.javaClass) { buildAccessor(keyData.javaClass) })?.let { return it }
            }
            if (keyContext is View) {
                keyContext.tag?.toString()?.let { tag ->
                    normalize18KeyName(tag)?.let { return it }
                }
            }
            return null
        }

        fun normalize18KeyName(rawIdOrText: String): String? {
            val trimmed = rawIdOrText.trim().lowercase(Locale.ROOT)
            if (trimmed.startsWith("zh_18key")) {
                return when (trimmed) {
                    "zh_18key1_1" -> "q"
                    "zh_18key1_2" -> "we"
                    "zh_18key1_3" -> "rt"
                    "zh_18key1_4" -> "y"
                    "zh_18key1_5" -> "u"
                    "zh_18key1_6" -> "io"
                    "zh_18key1_7" -> "p"
                    "zh_18key2_1" -> "a"
                    "zh_18key2_2" -> "sd"
                    "zh_18key2_3" -> "fg"
                    "zh_18key2_4" -> "h"
                    "zh_18key2_5" -> "jk"
                    "zh_18key2_6" -> "l"
                    "zh_18key3_1" -> "z"
                    "zh_18key3_2" -> "xc"
                    "zh_18key3_3" -> "v"
                    "zh_18key3_4" -> "bn"
                    "zh_18key3_5" -> "m"
                    else -> null
                }
            }
            val clean = trimmed.replace(" ", "")
            val valid18Keys = setOf(
                "q", "we", "rt", "y", "u", "io", "p",
                "a", "sd", "fg", "h", "jk", "l",
                "z", "xc", "v", "bn", "m", "space"
            )
            if (clean in valid18Keys) return clean
            if (clean == "空格" || clean == "spacebar") return "space"
            return null
        }
    }
}
