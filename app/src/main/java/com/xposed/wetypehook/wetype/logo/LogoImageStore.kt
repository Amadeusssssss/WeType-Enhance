package com.xposed.wetypehook.wetype.logo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 自定义图片 Logo 的导入与校验。
 *
 * 图片数据以内联字符串存入 [com.xposed.wetypehook.wetype.settings.WeTypeSettings] 偏好，
 * 走既有远端偏好/Bundle 桥同步，hook 侧与 UI 侧都只读本进程偏好，无跨进程文件读取。
 */
object LogoImageStore {
    /** PNG 解码后边长上限：键盘 Logo 槽位很小，512px 足够清晰。 */
    const val MAX_PNG_EDGE_PX = 512

    /** PNG 重编码后的字节上限：Base64 后约 550KB，必须小于 Binder 单次传输上限。 */
    const val MAX_PNG_BYTES = 400 * 1024

    /** SVG 原始文本字符上限。 */
    const val MAX_SVG_CHARS = 256 * 1024

    /** 读取源文件时的硬上限，防止 OOM。 */
    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024

    data class PngImport(
        val base64: String,
        val width: Int,
        val height: Int,
        val name: String
    )

    data class SvgImport(
        val text: String,
        val name: String
    )

    fun displayName(resolver: ContentResolver, uri: Uri, fallback: String): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    if (name.isNotEmpty()) return name
                }
            }
        }
        return fallback
    }

    fun importPng(resolver: ContentResolver, uri: Uri): Result<PngImport> = runCatching {
        val name = displayName(resolver, uri, "logo.png")
        val source = readCapped(resolver, uri, MAX_SOURCE_BYTES)
            ?: throw IllegalStateException("无法读取所选图片")
        if (!isPng(source)) throw IllegalStateException("所选文件不是 PNG 格式")
        var bitmap = decodeBounded(source, MAX_PNG_EDGE_PX)
            ?: throw IllegalStateException("无法解码 PNG 图片")
        var encoded = encodePng(bitmap)
        if (encoded.size > MAX_PNG_BYTES) {
            // 降到一半边长再试一次，仍超限则拒绝。
            bitmap.recycle()
            bitmap = decodeBounded(source, MAX_PNG_EDGE_PX / 2)
                ?: throw IllegalStateException("无法解码 PNG 图片")
            encoded = encodePng(bitmap)
        }
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        if (encoded.size > MAX_PNG_BYTES) {
            throw IllegalStateException("图片过大（需小于 400KB），请换一张更小的图")
        }
        PngImport(
            base64 = Base64.encodeToString(encoded, Base64.NO_WRAP),
            width = width,
            height = height,
            name = name
        )
    }

    fun importSvg(resolver: ContentResolver, uri: Uri): Result<SvgImport> = runCatching {
        val name = displayName(resolver, uri, "logo.svg")
        val bytes = readCapped(resolver, uri, MAX_SVG_CHARS + 1)
            ?: throw IllegalStateException("无法读取所选文件")
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrElse {
            throw IllegalStateException("SVG 文件编码异常，请使用 UTF-8 编码")
        }
        if (text.length > MAX_SVG_CHARS) throw IllegalStateException("SVG 文件过大（需小于 256KB）")
        val trimmed = text.trim()
        if (!trimmed.contains("<svg", ignoreCase = true)) {
            throw IllegalStateException("所选文件不是 SVG 格式")
        }
        if (trimmed.contains("<script", ignoreCase = true)) {
            throw IllegalStateException("SVG 内含脚本，为安全起见已拒绝")
        }
        SvgImport(text = trimmed, name = name)
    }

    private fun readCapped(resolver: ContentResolver, uri: Uri, cap: Int): ByteArray? {
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > cap) throw IllegalStateException("所选文件过大")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
        return null
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
    }

    private fun decodeBounded(source: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / (sampleSize * 2) >= maxEdge) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        if (max(bitmap.width, bitmap.height) > maxEdge) {
            val scale = maxEdge.toFloat() / max(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
        return output.toByteArray()
    }
}
