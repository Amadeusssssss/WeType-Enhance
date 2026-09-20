package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.view.Window
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.invokeStaticMethodAuto

internal object WeTypeBottomViewManager {
    private const val TRANSPARENT_BOTTOM_VIEW_DARK_CONTENT = 0xFFF5F5F5.toInt()
    private const val TRANSPARENT_BOTTOM_VIEW_LIGHT_CONTENT = 0xFF202020.toInt()
    private const val DEFAULT_DARK_BOTTOM_VIEW_COLOR = 0xFF202020.toInt()
    private const val DEFAULT_LIGHT_BOTTOM_VIEW_COLOR = 0xFFECECEC.toInt()

    @Volatile
    var injectorClass: Class<*>? = null

    @Volatile
    var bottomViewSourceColor: Int? = null

    @Volatile
    var navBarColor: Int? = null

    fun shouldForceTransparent(isWeType: Boolean): Boolean {
        return isWeType && WeTypeSettings.isBeautificationEnabledXposed()
    }

    fun resolveDefaultBottomViewColor(context: Context? = null): Int {
        val uiMode = (context?.resources ?: Resources.getSystem()).configuration.uiMode
        val isDarkMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) DEFAULT_DARK_BOTTOM_VIEW_COLOR else DEFAULT_LIGHT_BOTTOM_VIEW_COLOR
    }

    fun resolveEffectiveColor(context: Context? = null): Int {
        return navBarColor?.takeIf { it != 0 && it != Color.TRANSPARENT }
            ?: bottomViewSourceColor?.takeIf { it != 0 && it != Color.TRANSPARENT }
            ?: resolveDefaultBottomViewColor(context)
    }

    fun resolveTransparentBottomViewContentColor(context: Context? = null): Int {
        val uiMode = (context?.resources ?: Resources.getSystem()).configuration.uiMode
        val isDarkMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) TRANSPARENT_BOTTOM_VIEW_DARK_CONTENT else TRANSPARENT_BOTTOM_VIEW_LIGHT_CONTENT
    }

    fun applyBottomViewColor(clazz: Class<*>? = injectorClass, forceTransparent: Boolean) {
        val targetClass = clazz ?: injectorClass ?: return
        runCatching {
            if (forceTransparent) {
                val contentColor = resolveTransparentBottomViewContentColor()
                targetClass.invokeStaticMethodAuto(
                    "customizeBottomViewColor",
                    true,
                    Color.TRANSPARENT,
                    contentColor,
                    withAlpha(contentColor, 0x66)
                )
                return
            }

            val colorValue = resolveEffectiveColor()
            val invertedColor = -0x1 - colorValue
            targetClass.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true,
                colorValue,
                invertedColor or -0x1000000,
                invertedColor or 0x66000000
            )
        }.onFailure {
            Log.i("Failed: applyBottomViewColor (forceTransparent=$forceTransparent)")
            Log.i(it)
        }
    }

    fun reconcileBottomView(window: Window?, forceTransparent: Boolean) {
        if (window != null) {
            runCatching {
                val targetNavBarColor = if (forceTransparent) {
                    Color.TRANSPARENT
                } else {
                    resolveEffectiveColor(window.context)
                }
                if (window.navigationBarColor != targetNavBarColor) {
                    window.navigationBarColor = targetNavBarColor
                }
            }.onFailure {
                Log.i("Failed: reconcile window navigationBarColor")
                Log.i(it)
            }
        }
        val targetClass = injectorClass
        if (targetClass != null) {
            applyBottomViewColor(targetClass, forceTransparent)
        }
    }

    fun reset() {
        injectorClass = null
        bottomViewSourceColor = null
        navBarColor = null
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}

