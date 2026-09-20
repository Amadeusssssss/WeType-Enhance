package com.xposed.wetypehook.wetype.hook

import android.os.Build
import com.xposed.wetypehook.ModuleRuntime
import com.xposed.wetypehook.xposed.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 健壮的 DexKit 动态链接库加载器。
 * 兼容 LSPatch 内嵌模式、LSPosed 模块模式、以及各类 Root/非 Root 环境。
 * 解决在 LSPosed 下由于 ClassLoader 命名空间隔离或 target app 私有 nativeLibraryDir 缺失导致的 UnsatisfiedLinkError。
 */
object DexKitLoader {
    private const val TAG = "DexKitLoader"

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return

            // 1. 尝试系统默认 loadLibrary
            val directLoaded = runCatching {
                System.loadLibrary("dexkit")
                true
            }.getOrDefault(false)
            if (directLoaded) {
                loaded = true
                Log.i("[$TAG] Successfully loaded libdexkit via System.loadLibrary")
                return
            }

            // 2. 尝试从模块自身的 nativeLibraryDir 加载
            val moduleNativeDir = ModuleRuntime.getModuleNativeLibDir()
            if (!moduleNativeDir.isNullOrBlank()) {
                val libFile = File(moduleNativeDir, "libdexkit.so")
                if (libFile.exists() && libFile.canRead()) {
                    val dirLoaded = runCatching {
                        System.load(libFile.absolutePath)
                        true
                    }.getOrDefault(false)
                    if (dirLoaded) {
                        loaded = true
                        Log.i("[$TAG] Successfully loaded libdexkit from module nativeLibraryDir: ${libFile.absolutePath}")
                        return
                    }
                }
            }

            // 3. 尝试从模块 APK 文件中解压对应架构的 libdexkit.so 到宿主应用可执行目录并加载
            val moduleApk = ModuleRuntime.resolveModuleApkPath()
            if (!moduleApk.isNullOrBlank()) {
                val extracted = extractAndLoadFromApk(moduleApk)
                if (extracted) {
                    loaded = true
                    Log.i("[$TAG] Successfully extracted and loaded libdexkit from APK: $moduleApk")
                    return
                }
            }

            Log.e("[$TAG] Failed to load libdexkit through all available strategies!")
        }
    }

    private fun extractAndLoadFromApk(apkPath: String): Boolean {
        return runCatching {
            val apkFile = File(apkPath)
            if (!apkFile.exists() || !apkFile.canRead()) {
                Log.e("[$TAG] APK file does not exist or unreadable: $apkPath")
                return false
            }

            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val abisToTry = listOf(primaryAbi) + Build.SUPPORTED_ABIS.filter { it != primaryAbi }

            ZipFile(apkFile).use { zip ->
                var targetEntry: java.util.zip.ZipEntry? = null
                for (abi in abisToTry) {
                    val entry = zip.getEntry("lib/$abi/libdexkit.so")
                    if (entry != null) {
                        targetEntry = entry
                        break
                    }
                }

                if (targetEntry == null) {
                    Log.e("[$TAG] No libdexkit.so found in $apkPath for ABIs: $abisToTry")
                    return false
                }

                // 获取宿主进程的 codeCacheDir 或 cacheDir (具备可执行与读取权限)
                val app = runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication")
                        .invoke(null) as? android.app.Application
                }.getOrNull()
                val hostDataDir = ModuleRuntime.getHostDataDir()
                val targetDir = app?.codeCacheDir
                    ?: app?.cacheDir
                    ?: hostDataDir?.let { File(it, "code_cache") }
                    ?: hostDataDir?.let { File(it, "cache") }
                    ?: File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")

                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                val destFile = File(targetDir, "libdexkit_${targetEntry.crc}.so")
                if (!destFile.exists() || destFile.length() != targetEntry.size) {
                    zip.getInputStream(targetEntry).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    destFile.setReadable(true, false)
                    destFile.setExecutable(true, false)
                }

                System.load(destFile.absolutePath)
                true
            }
        }.onFailure { error ->
            Log.e("[$TAG] Exception during libdexkit extraction and load: ${error.message}")
        }.getOrDefault(false)
    }
}

