package com.xiyunmn.qdhelper.shared.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Rect
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@Suppress("DEPRECATION")
class WindowCompatibilityTest {
    @Test
    fun android9DialogKeepsWindowFlagsWithoutLoadingModernBackOrInsetsApis() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = host.get()
        val dialog = Dialog(activity)
        try {
            val window = requireNotNull(dialog.window)
            window.setDecorFitsSystemWindowsCompat(false)
            window.disableSystemBarContrastCompat()
            window.allowDisplayCutoutCompat()
            window.applySystemBarsAppearance(true)
            val flags = window.decorView.systemUiVisibility
            assertNotEquals(0, flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            assertNotEquals(0, flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                window.attributes.layoutInDisplayCutoutMode)
            window.applySystemBarsAppearance(false)
            assertEquals(0, window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            assertNotEquals(0, window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            dialog.registerBackHandler(onBack = { fail("Legacy dialogs use their key listener") }).close()
            dialog.show()
            assertTrue(dialog.isShowing)
        } finally {
            dialog.dismiss()
            host.pause().stop().destroy()
        }
    }

    @Test
    fun android9SafeInsetsAreConsumedOnlyAfterTheContainerReservesThem() {
        val insets = ReflectionHelpers.callConstructor(WindowInsets::class.java,
            ClassParameter.from(Rect::class.java, Rect(1, 2, 3, 4)))
        assertEquals(Rect(1, 2, 3, 4), insets.safeAreaCompat())
        val consumed = insets.consumeSafeAreaCompat()
        assertEquals(0, consumed.systemWindowInsetTop)
        assertEquals(0, consumed.systemWindowInsetBottom)
        assertEquals(2, insets.systemWindowInsetTop)
    }
}
