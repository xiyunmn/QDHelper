package com.xiyunmn.qdhelper.shared.ui

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager

@Suppress("DEPRECATION")
fun Window.setDecorFitsSystemWindowsCompat(fits: Boolean) {
    if (Build.VERSION.SDK_INT >= 30) {
        setDecorFitsSystemWindows(fits)
    } else {
        val mask = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        decorView.systemUiVisibility = if (fits) decorView.systemUiVisibility and mask.inv()
        else decorView.systemUiVisibility or mask
    }
}

fun Window.disableSystemBarContrastCompat() {
    if (Build.VERSION.SDK_INT >= 29) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
}

fun Window.allowDisplayCutoutCompat() {
    attributes = attributes.apply {
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}

@Suppress("DEPRECATION")
fun Window.copySystemBarsFrom(source: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        val bars = decorView.windowInsetsController
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        bars?.setSystemBarsAppearance(source.windowInsetsController?.systemBarsAppearance ?: 0, mask)
        source.rootWindowInsets?.let { insets ->
            if (!insets.isVisible(WindowInsets.Type.statusBars())) bars?.hide(WindowInsets.Type.statusBars())
            if (!insets.isVisible(WindowInsets.Type.navigationBars())) bars?.hide(WindowInsets.Type.navigationBars())
        }
    } else {
        val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        decorView.systemUiVisibility = (decorView.systemUiVisibility and mask.inv()) or
            (source.rootView.systemUiVisibility and mask)
        val sourceFlags = (source.rootView.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
        if (sourceFlags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }
}

@Suppress("DEPRECATION")
fun WindowInsets.safeAreaCompat(): Rect {
    if (Build.VERSION.SDK_INT >= 30) {
        val safe = getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        return Rect(safe.left, safe.top, safe.right, safe.bottom)
    }
    val cutout = displayCutout
    return Rect(
        maxOf(systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
        maxOf(systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
        maxOf(systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
        maxOf(systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
    )
}

@Suppress("DEPRECATION")
fun WindowInsets.consumeSafeAreaCompat(): WindowInsets =
    if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else consumeSystemWindowInsets().consumeDisplayCutout()

@Suppress("DEPRECATION")
fun View.isImeVisibleCompat(): Boolean {
    if (Build.VERSION.SDK_INT >= 30) return rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
    rootWindowInsets?.let { if (it.systemWindowInsetBottom > it.stableInsetBottom) return true }
    val visible = Rect()
    getWindowVisibleDisplayFrame(visible)
    return rootView.height - visible.bottom > maxOf(rootView.height / 6, (100 * resources.displayMetrics.density).toInt())
}

fun View.hideImeCompat() {
    if (Build.VERSION.SDK_INT >= 30) windowInsetsController?.hide(WindowInsets.Type.ime())
    else context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
}
