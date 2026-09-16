package com.xposed.wetypehook.wetype.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * 自定义图片 Logo：位图按 centerCrop 铺满边界，不加白色圆底、不做多余装饰。
 * [tint] 非空时（SVG 开启“替换颜色”）以 SRC_IN 整体染成单色，保留原始透明度；
 * PNG 始终传 null，原色显示。
 */
internal class CustomLogoDrawable(
    private val bitmap: Bitmap,
    private val tint: Int? = null
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || bitmap.isRecycled) return
        paint.colorFilter = tint?.let { PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
        val src = centerCropSrc(bitmap.width, bitmap.height, bounds.width(), bounds.height())
        canvas.drawBitmap(bitmap, src, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = bitmap.width

    override fun getIntrinsicHeight(): Int = bitmap.height

    private fun centerCropSrc(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): Rect {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            return Rect(0, 0, srcWidth.coerceAtLeast(0), srcHeight.coerceAtLeast(0))
        }
        val scale = maxOf(dstWidth / srcWidth.toFloat(), dstHeight / srcHeight.toFloat())
        val cropWidth = min(srcWidth, (dstWidth / scale).toInt().coerceAtLeast(1))
        val cropHeight = min(srcHeight, (dstHeight / scale).toInt().coerceAtLeast(1))
        val left = ((srcWidth - cropWidth) / 2).coerceAtLeast(0)
        val top = ((srcHeight - cropHeight) / 2).coerceAtLeast(0)
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }
}
