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

            val is64 = android.os.Process.is64Bit()
            val abisToTry = if (is64) {
                Build.SUPPORTED_64_BIT_ABIS.toList().ifEmpty { listOf("arm64-v8a") }
            } else {
                Build.SUPPORTED_32_BIT_ABIS.toList().ifEmpty { listOf("armeabi-v7a") }
            }

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

                // 宿主进程内定位可执行写入目录 (优先 code_cache/cache)
                val app = runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication")
                        .invoke(null) as? android.app.Application
                }.getOrNull()

                val hostDataDir = ModuleRuntime.getHostDataDir()
                    ?: app?.applicationInfo?.dataDir
                    ?: runCatching {
                        val activityThread = Class.forName("android.app.ActivityThread")
                            .getMethod("currentActivityThread")
                            .invoke(null)
                        val boundApp = activityThread?.let {
                            it.javaClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.get(it)
                        }
                        val appInfo = boundApp?.let {
                            it.javaClass.getDeclaredField("appInfo").apply { isAccessible = true }.get(it) as? android.content.pm.ApplicationInfo
                        }
                        appInfo?.dataDir
                    }.getOrNull()

                val candidateDirs = mutableListOf<File>()
                app?.codeCacheDir?.let { candidateDirs.add(it) }
                app?.cacheDir?.let { candidateDirs.add(it) }

                if (!hostDataDir.isNullOrBlank()) {
                    candidateDirs.add(File(hostDataDir, "code_cache"))
                    candidateDirs.add(File(hostDataDir, "cache"))
                    candidateDirs.add(File(hostDataDir, "files"))
                }

                val packageNames = listOfNotNull(
                    app?.packageName,
                    "com.tencent.wetype"
                ).distinct()

                for (pkg in packageNames) {
                    candidateDirs.add(File("/data/user/0/$pkg/code_cache"))
                    candidateDirs.add(File("/data/user/0/$pkg/cache"))
                    candidateDirs.add(File("/data/data/$pkg/code_cache"))
                    candidateDirs.add(File("/data/data/$pkg/cache"))
                    candidateDirs.add(File("/data/user_de/0/$pkg/code_cache"))
                    candidateDirs.add(File("/data/user_de/0/$pkg/cache"))
                }

                val triedDirs = mutableListOf<String>()
                for (dir in candidateDirs.distinctBy { it.absolutePath }) {
                    val canUse = runCatching {
                        if (!dir.exists()) dir.mkdirs()
                        dir.exists() && dir.canWrite()
                    }.getOrDefault(false)

                    if (!canUse) continue
                    triedDirs.add(dir.absolutePath)

                    val loadSuccess = runCatching {
                        val destFile = File(dir, "libdexkit_${targetEntry.crc}.so")
                        if (!destFile.exists() || destFile.length() != targetEntry.size) {
                            val tempFile = File(dir, "libdexkit_${targetEntry.crc}.tmp")
                            zip.getInputStream(targetEntry).use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (!tempFile.renameTo(destFile)) {
                                tempFile.copyTo(destFile, overwrite = true)
                                tempFile.delete()
                            }
                            destFile.setReadable(true, false)
                            destFile.setExecutable(true, false)
                        }

                        System.load(destFile.absolutePath)
                        Log.i("[$TAG] Successfully loaded libdexkit from: ${destFile.absolutePath}")
                        true
                    }.onFailure { err ->
                        Log.w("[$TAG] Failed to load libdexkit from ${dir.absolutePath}: ${err.message}")
                    }.getOrDefault(false)

                    if (loadSuccess) {
                        return true
                    }
                }

                Log.e("[$TAG] Tried all writable candidate directories without success: $triedDirs")
                false
            }
        }.onFailure { error ->
            Log.e("[$TAG] Exception during libdexkit extraction and load: ${error.message}")
            Log.i(error)
        }.getOrDefault(false)
    }
}

