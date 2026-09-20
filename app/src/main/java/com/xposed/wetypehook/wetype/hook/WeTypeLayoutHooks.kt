package com.xposed.wetypehook.wetype.hook

import android.content.res.AssetManager
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookBefore
import java.io.ByteArrayInputStream
import java.io.InputStream

internal object WeTypeLayoutHooks {
    private const val TARGET_ASSET_NAME = "S8DoublePinT9Keyboard.json"
    private const val MODULE_ASSET_PATH = "keyboard/18key_layout.json"

    @Volatile
    private var cachedLayoutBytes: ByteArray? = null
    private val cacheLock = Any()

    fun install(getModuleAssetManager: () -> AssetManager) {
        fun resolveLayoutStream(): InputStream? {
            val bytes = cachedLayoutBytes ?: synchronized(cacheLock) {
                cachedLayoutBytes ?: runCatching {
                    getModuleAssetManager().open(MODULE_ASSET_PATH).use { it.readBytes() }
                }.getOrElse { e1 ->
                    Log.e("WeTypeLayoutHooks: Failed to read from module AssetManager: ${e1.message}")
                    runCatching {
                        WeTypeLayoutHooks::class.java.classLoader?.getResourceAsStream("assets/$MODULE_ASSET_PATH")?.use { it.readBytes() }
                    }.getOrNull()
                }?.also {
                    cachedLayoutBytes = it
                    Log.i("WeTypeLayoutHooks: Loaded 18-key layout into memory (${it.size} bytes)")
                }
            }
            return bytes?.let { ByteArrayInputStream(it) }
        }

        runCatching {
            // Hook AssetManager.open(String)
            AssetManager::class.java.getDeclaredMethod(
                "open",
                String::class.java
            ).hookBefore { param ->
                val fileName = param.args[0] as? String ?: return@hookBefore
                if (!fileName.endsWith(TARGET_ASSET_NAME)) return@hookBefore
                if (!WeTypeSettings.isLayout18KeyEnabledXposed()) {
                    Log.i("WeTypeLayoutHooks: 18-key layout is disabled in settings, skipping")
                    return@hookBefore
                }

                val stream = resolveLayoutStream()
                if (stream != null) {
                    param.result = stream
                    Log.i("WeTypeLayoutHooks: Successfully injected 18-key layout for $fileName")
                } else {
                    Log.e("WeTypeLayoutHooks: Failed to obtain 18-key layout stream, falling back to official asset")
                }
            }

            // Hook AssetManager.open(String, Int)
            AssetManager::class.java.getDeclaredMethod(
                "open",
                String::class.java,
                Int::class.javaPrimitiveType
            ).hookBefore { param ->
                val fileName = param.args[0] as? String ?: return@hookBefore
                if (!fileName.endsWith(TARGET_ASSET_NAME)) return@hookBefore
                if (!WeTypeSettings.isLayout18KeyEnabledXposed()) return@hookBefore

                val stream = resolveLayoutStream()
                if (stream != null) {
                    param.result = stream
                    Log.i("WeTypeLayoutHooks: Successfully injected 18-key layout (mode) for $fileName")
                } else {
                    Log.e("WeTypeLayoutHooks: Failed to obtain 18-key layout stream (mode), falling back")
                }
            }

            Log.i("Success: Hook WeType 18-key layout")
        }.onFailure {
            Log.e("Failed: Hook WeType 18-key layout")
            Log.i(it)
        }
    }
}
