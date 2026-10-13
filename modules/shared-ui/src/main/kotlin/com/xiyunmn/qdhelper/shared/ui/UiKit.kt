package com.xiyunmn.qdhelper.shared.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

interface HostThemeResolver {
    fun isNightMode(context: Context): Boolean?
    fun color(context: Context, role: String): Int?
}

object UiColors {
    @Volatile
    var hostThemeResolver: HostThemeResolver? = null
    var Brand = 0xFFE5352F.toInt()
        private set
    var Panel = 0xFFF7F7F8.toInt()
        private set
    var Card = Color.WHITE
        private set
    var Text = 0xFF202124.toInt()
        private set
    var Muted = 0xFF70747A.toInt()
        private set
    var Divider = 0xFFE7E8EA.toInt()
        private set
    var Handle = 0xFFB0B3B8.toInt()
        private set
    var DragElevated = Color.WHITE
        private set
    var IsNight = false
        private set

    fun refresh(context: Context) {
        val isNight = hostThemeResolver?.isNightMode(context)
            ?: ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        val fallbackPanel = if (isNight) 0xFF121214.toInt() else 0xFFF7F7F8.toInt()
        val fallbackCard = if (isNight) 0xFF1D1D20.toInt() else Color.WHITE
        val fallbackText = if (isNight) 0xFFF2F2F3.toInt() else 0xFF202124.toInt()
        val fallbackMuted = if (isNight) 0xFFA6A7AC.toInt() else 0xFF70747A.toInt()

        val hostPanel = hostThemeResolver?.color(context, "surface")
        Panel = hostPanel
            ?.takeIf { color -> isSurfaceCompatible(color, isNight) }
            ?: resolveThemeColor(context, android.R.attr.colorBackground)
                ?.takeIf { color -> isSurfaceCompatible(color, isNight) }
            ?: fallbackPanel
        Card = if (isNight) {
            blend(Panel, Color.WHITE, 0.055f)
        } else {
            blend(Panel, Color.WHITE, 0.76f)
        }.takeIf { color -> contrastRatio(color, Panel) >= 1.01 }
            ?: fallbackCard

        Text = firstReadableColor(
            background = Card,
            fallback = fallbackText,
            candidates = listOf(
                hostThemeResolver?.color(context, "text"),
                resolveThemeColor(context, android.R.attr.textColorPrimary),
            ),
        )
        Muted = firstReadableColor(
            background = Card,
            fallback = fallbackMuted,
            minimumContrast = 2.4,
            candidates = listOf(
                hostThemeResolver?.color(context, "muted"),
                hostThemeResolver?.color(context, "onSurface"),
                resolveThemeColor(context, android.R.attr.textColorSecondary),
            ),
        )

        val fallbackBrand = 0xFFE5352F.toInt()
        Brand = firstReadableColor(
            background = Card,
            fallback = fallbackBrand,
            minimumContrast = 2.0,
            candidates = listOf(
                hostThemeResolver?.color(context, "brand"),
                resolveThemeColor(context, android.R.attr.colorControlActivated),
                resolveThemeColor(context, android.R.attr.colorAccent),
            ),
        )
        Divider = hostThemeResolver?.color(context, "divider")
            ?.takeIf { color -> contrastRatio(color, Card) in 1.03..2.4 }
            ?: blend(Card, Text, if (isNight) 0.12f else 0.08f)
        IsNight = isNight
        Handle = blend(Card, Text, if (isNight) 0.34f else 0.30f)
        DragElevated = if (isNight) blend(Card, Color.WHITE, 0.08f) else blend(Card, Color.BLACK, 0.02f)
    }

    private fun resolveThemeColor(context: Context, attribute: Int): Int? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) {
            return null
        }
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data
        }
        if (value.resourceId == 0) {
            return null
        }
        return runCatching { context.getColorStateList(value.resourceId).defaultColor }.getOrNull()
    }

    private fun isSurfaceCompatible(color: Int, isNight: Boolean): Boolean {
        val luminance = luminance(color)
        return if (isNight) luminance < 0.36 else luminance > 0.56
    }

    private fun firstReadableColor(
        background: Int,
        fallback: Int,
        minimumContrast: Double = 3.0,
        candidates: List<Int?>,
    ): Int {
        return candidates.firstOrNull { color ->
            color != null && Color.alpha(color) > 0 && contrastRatio(color, background) >= minimumContrast
        } ?: fallback
    }

    private fun blend(background: Int, foreground: Int, fraction: Float): Int {
        val weight = fraction.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(background) + (Color.red(foreground) - Color.red(background)) * weight).toInt(),
            (Color.green(background) + (Color.green(foreground) - Color.green(background)) * weight).toInt(),
            (Color.blue(background) + (Color.blue(foreground) - Color.blue(background)) * weight).toInt(),
        )
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val lighter = max(luminance(first), luminance(second))
        val darker = min(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return channel(Color.red(color)) * 0.2126 +
            channel(Color.green(color)) * 0.7152 +
            channel(Color.blue(color)) * 0.0722
    }
}

fun Context.dp(value: Int): Int {
    return (value * resources.displayMetrics.density + 0.5f).toInt()
}

/** Outer corner radius shared by module-styled dialogs. */
const val MODULE_DIALOG_CORNER_RADIUS_DP = 18

fun rounded(color: Int, radiusDp: Int, context: Context): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }
}

fun divider(context: Context, marginStartDp: Int = 16): View = View(context).apply {
    setBackgroundColor(UiColors.Divider)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
        marginStart = context.dp(marginStartDp)
    }
}

fun View.setPressedAlpha() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> view.alpha = 0.72f
            android.view.MotionEvent.ACTION_CANCEL,
            android.view.MotionEvent.ACTION_UP -> view.alpha = 1f
        }
        false
    }
}

/** Applies light/dark system-bar icons without replacing unrelated window flags. */
@Suppress("DEPRECATION")
fun Window.applySystemBarsAppearance(light: Boolean) {
    if (android.os.Build.VERSION.SDK_INT < 30) {
        val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decorView.systemUiVisibility = if (light) decorView.systemUiVisibility or mask
        else decorView.systemUiVisibility and mask.inv()
        return
    }
    val controller = insetsController ?: return
    val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    controller.setSystemBarsAppearance(if (light) mask else 0, mask)
}
