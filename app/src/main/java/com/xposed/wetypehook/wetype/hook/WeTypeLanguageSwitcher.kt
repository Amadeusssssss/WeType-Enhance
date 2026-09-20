package com.xposed.wetypehook.wetype.hook

import android.view.View
import com.xposed.wetypehook.xposed.Log
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信输入法中英切换触发器
 *
 * 通过反射调用官方中英切换入口 `com.tencent.wetype.plugin.hld.key.d.O(7, null)`，
 * 复用官方完整切换流程：清候选词 → 重置输入状态机 → 通知输入内核 → 更新 UI。
 *
 * `key.d` 类在 3.5.3 与 3.5.4 之间类名与方法名均未混淆变动，
 * 包含 `"WxIme.ImeKeyboardActionListener"` 字符串常量。
 */
internal object WeTypeLanguageSwitcher {

    private const val TAG = "WeTypeLangSwitch"
    private const val ACTION_LISTENER_CLASS = "com.tencent.wetype.plugin.hld.key.d"
    /**
     * functionCode 7 = chSwitch（中文切换键），与 8 = enSwitch 共用同一处理函数 `M`，
     * 内部自动做 toggle：读当前键盘类型 → 计算对面类型 → 切换。
     */
    private const val CH_SWITCH_FUNCTION_CODE = 7

    @Volatile
    private var actionListenerInstance: Any? = null

    @Volatile
    private var dispatchMethod: Method? = null

    @Volatile
    private var boundSourceDir: String? = null

    @Volatile
    private var boundLoader: ClassLoader? = null

    /**
     * 初始化：保存环境，实际解析延迟到首次切换或视图交互时。
     */
    fun install(sourceDir: String?, classLoader: ClassLoader) {
        boundSourceDir = sourceDir
        boundLoader = classLoader
    }

    private fun ensureInitialized(view: View?): Boolean {
        if (actionListenerInstance != null && dispatchMethod != null) return true
        val cl = boundLoader ?: view?.context?.classLoader ?: return false
        val sourceDir = boundSourceDir

        runCatching {
            val listenerClass = Class.forName(ACTION_LISTENER_CLASS, true, cl)

            val instanceField = listenerClass.declaredFields.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.type == listenerClass
            } ?: error("No static self-referencing field in $ACTION_LISTENER_CLASS")
            instanceField.isAccessible = true
            val inst = instanceField.get(null) ?: runCatching {
                listenerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrNull()
            actionListenerInstance = inst ?: error("Instance is null in $ACTION_LISTENER_CLASS")

            val method = listenerClass.getDeclaredMethod("O", Integer.TYPE, Any::class.java)
            check(method.returnType == Void.TYPE) { "O method return type is not void" }
            method.isAccessible = true
            dispatchMethod = method

            Log.i("[$TAG] Bound language switcher: class=$ACTION_LISTENER_CLASS method=O instance=${actionListenerInstance != null}")
            return true
        }.onFailure {
            Log.e("[$TAG] Failed to bind language switcher: ${it.message}")

            if (!sourceDir.isNullOrEmpty()) {
                runCatching {
                    DexKitLoader.ensureLoaded()
                    DexKitBridge.create(sourceDir).use { bridge ->
                        installViaDexKit(bridge, cl)
                    }
                    return true
                }.onFailure { dexErr ->
                    Log.e("[$TAG] DexKit fallback also failed: ${dexErr.message}")
                }
            }
        }
        return false
    }

    private fun installViaDexKit(bridge: DexKitBridge, classLoader: ClassLoader) {
        val listenerClass = bridge.findClass {
            matcher {
                usingStrings("WxIme.ImeKeyboardActionListener")
            }
        }.firstOrNull()?.getInstance(classLoader) ?: error("DexKit: listener class not found")

        val instanceField = listenerClass.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == listenerClass
        } ?: error("DexKit: no singleton field")
        instanceField.isAccessible = true
        actionListenerInstance = instanceField.get(null)

        val method = listenerClass.declaredMethods.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Integer.TYPE &&
                it.parameterTypes[1] == Any::class.java &&
                it.returnType == Void.TYPE
        } ?: error("DexKit: dispatch method not found")
        method.isAccessible = true
        dispatchMethod = method

        boundLoader = classLoader
        Log.i("[$TAG] DexKit fallback bound: class=${listenerClass.name} method=${method.name}")
    }

    /**
     * 触发中英文切换。由手势系统调用。
     *
     * @param view 键盘视图，用于获取 ClassLoader（如单例未在 install 时就绪，
     *             此处延迟获取）
     */
    fun toggle(view: View) {
        if (!ensureInitialized(view)) {
            Log.e("[$TAG] Language switch unavailable: failed to initialize")
            return
        }
        val method = dispatchMethod ?: return
        val instance = actionListenerInstance ?: return

        runCatching {
            method.invoke(instance, CH_SWITCH_FUNCTION_CODE, null)
            Log.i("[$TAG] Language switch triggered successfully")
        }.onFailure {
            Log.e("[$TAG] Language switch invocation failed: ${it.message}")
            Log.i(it)
        }
    }

    fun reset() {
        actionListenerInstance = null
        dispatchMethod = null
        boundLoader = null
    }
}

