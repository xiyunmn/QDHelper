package com.xiyunmn.qdhelper.shared.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** Shows the compact confirmation surface shared by module settings and feature panels. */
fun showModuleConfirmDialog(
    activity: Activity,
    title: String,
    message: String,
    confirmText: String = "确认",
    onConfirm: () -> Unit,
): Dialog? {
    if (activity.isFinishing || activity.isDestroyed) return null
    UiColors.refresh(activity)
    val dialog = Dialog(activity)
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(UiColors.Panel, MODULE_DIALOG_CORNER_RADIUS_DP, activity)
        setPadding(activity.dp(18), activity.dp(18), activity.dp(18), activity.dp(10))
        addView(TextView(activity).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiColors.Text)
            includeFontPadding = false
        })
        addView(TextView(activity).apply {
            text = message
            textSize = 14f
            setTextColor(UiColors.Muted)
            includeFontPadding = false
            setLineSpacing(activity.dp(2).toFloat(), 1f)
            setPadding(0, activity.dp(10), 0, activity.dp(6))
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "取消"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(UiColors.Muted)
                isClickable = true
                setPressedAlpha()
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(activity.dp(72), activity.dp(42)))
            addView(TextView(activity).apply {
                text = confirmText
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(UiColors.Brand)
                isClickable = true
                setPressedAlpha()
                setOnClickListener {
                    onConfirm()
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(activity.dp(72), activity.dp(42)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(root)
    root.installModuleWatermark()
    dialog.setCanceledOnTouchOutside(true)
    // Configure the first surface, not the already-visible default host dialog. The platform
    // owns both card and dim transitions here; no per-frame WindowManager updates are needed.
    dialog.window?.let { window ->
        val windowWidth = if (android.os.Build.VERSION.SDK_INT >= 30) {
            activity.windowManager.currentWindowMetrics.bounds.width()
        } else activity.resources.displayMetrics.widthPixels
        val width = min(windowWidth - activity.dp(32), activity.dp(420))
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.attributes = window.attributes.apply {
            screenBrightness = activity.window.attributes.screenBrightness
            dimAmount = .22f
            windowAnimations = android.R.style.Animation_Dialog
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
    }
    dialog.show()
    return dialog
}
