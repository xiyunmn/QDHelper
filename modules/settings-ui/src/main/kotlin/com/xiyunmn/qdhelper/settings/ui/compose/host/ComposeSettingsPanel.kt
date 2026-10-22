package com.xiyunmn.qdhelper.settings.ui.compose.host

import com.xiyunmn.qdhelper.shared.ui.installModuleWatermark
import com.xiyunmn.qdhelper.shared.ui.setDecorFitsSystemWindowsCompat
import com.xiyunmn.qdhelper.shared.ui.disableSystemBarContrastCompat
import com.xiyunmn.qdhelper.shared.ui.allowDisplayCutoutCompat
import com.xiyunmn.qdhelper.shared.ui.registerBackHandler

import com.xiyunmn.qdhelper.shared.compose.ModuleComposeContext
import com.xiyunmn.qdhelper.shared.compose.ModuleCompositionOwner
import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.settings.ui.compose.theme.QDHelperTheme
import com.xiyunmn.qdhelper.shared.ui.UiColors
import com.xiyunmn.qdhelper.shared.ui.applySystemBarsAppearance
import com.xiyunmn.qdhelper.settings.ui.SettingsUiRuntime
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.SettingsNavigator
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsPresentationCatalog
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import com.xiyunmn.qdhelper.settings.ui.compose.screen.SettingsApp

internal object ComposeSettingsPanel {
    @Volatile
    private var current: Dialog? = null
    private val currentLock = Any()
    private var dialogSequence = 0L

    /** Release the previous generation's window before a hot reload installs a new one. */
    fun closeForHotReload() {
        val dialog = synchronized(currentLock) { current.also { current = null } } ?: return
        val dismiss: () -> Unit = {
            runCatching { if (dialog.isShowing) dialog.dismiss() }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) dismiss()
        else Handler(Looper.getMainLooper()).post(dismiss)
    }

    fun showSettings(activity: Activity) {
        com.xiyunmn.qdhelper.config.ConfigRuntime.initialize(activity)
        val catalog = SettingsPresentationCatalog(SettingsUiRuntime.requireConfiguration().contributions)
        var controller: SettingsController? = null
        var backAction: () -> Unit = { current?.dismiss() }
        show(activity, onBack = { backAction() }, onDispose = { controller?.close(); controller = null }) { close ->
            val accepted = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(com.xiyunmn.qdhelper.config.ConfigRuntime.readBoolean(
                    com.xiyunmn.qdhelper.config.SettingIds.KEY_DISCLAIMER_ACCEPTED))
            }
            if (!accepted.value) {
                PublicDisclaimerDialog(onExit = close, onAccepted = { accepted.value = true })
                return@show
            }
            val state = androidx.compose.runtime.remember {
                SettingsController(activity, catalog, SettingsNavigator(), close).also { controller = it }
            }
            SettingsApp(state, installBack = { backAction = it })
        }
    }

    fun show(activity: Activity, onBack: (() -> Unit)? = null, onDispose: () -> Unit = {}, content: @Composable (() -> Unit) -> Unit) {
        if (activity.isDestroyed || activity.isFinishing) return
        val previous = synchronized(currentLock) { current.also { current = null } }
        previous?.dismiss()
        val owner = ModuleCompositionOwner()
        val openedAt = android.os.SystemClock.uptimeMillis()
        val dialog = object : Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                // The host long press can finish over our top bar. Ignore only that existing
                // gesture; a fresh touch after opening is accepted immediately.
                if (event.downTime < openedAt) return true
                return super.dispatchTouchEvent(event)
            }
        }
        val registrationId = "settings.compose.dialog.${dialogSequence++}"
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = ComposeView(ModuleComposeContext.create(activity))
        owner.attach(view)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        dialog.setContentView(view)
        view.installModuleWatermark()
        owner.attach(dialog.window!!.decorView)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window!!.apply {
            setDecorFitsSystemWindowsCompat(false)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            statusBarColor = android.graphics.Color.TRANSPARENT
            navigationBarColor = android.graphics.Color.TRANSPARENT
            disableSystemBarContrastCompat()
            allowDisplayCutoutCompat()
        }
        val back = dialog.registerBackHandler(onBack = { if (onBack == null) dialog.dismiss() else onBack() })
        dialog.setOnKeyListener { _, key, event ->
            if (key != android.view.KeyEvent.KEYCODE_BACK) false else {
                if (event.action == android.view.KeyEvent.ACTION_UP) { if (onBack == null) dialog.dismiss() else onBack() }
                true
            }
        }
        val lifecycle = ActivityLifecycleCallbacks.subscribe(
            OwnerId("settings-ui"), registrationId,
            resumed = { if (it === activity) owner.resume() },
            paused = { if (it === activity) owner.pause() },
            destroyed = { if (it === activity) dialog.dismiss() },
        )
        val cleanup = FeatureResources.register(registrationId) { activity.runOnUiThread { dialog.dismiss() } }
        dialog.setOnDismissListener {
            lifecycle?.close()
            cleanup.close()
            back.close()
            owner.close()
            view.disposeComposition()
            onDispose()
            synchronized(currentLock) {
                if (current === dialog) current = null
            }
        }
        try {
            view.setContent {
                QDHelperTheme(
                    activity,
                    isVisible = { dialog.isShowing && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
                    onPalette = { palette ->
                        dialog.window?.apply {
                            setBackgroundDrawable(ColorDrawable(palette.surface))
                            statusBarColor = android.graphics.Color.TRANSPARENT
                            navigationBarColor = android.graphics.Color.TRANSPARENT
                            applySystemBarsAppearance(light = !palette.night)
                        }
                    },
                ) { content { dialog.dismiss() } }
            }
            dialog.show()
            synchronized(currentLock) { current = dialog }
            owner.resume()
            dialog.window!!.apply {
                setBackgroundDrawable(ColorDrawable(UiColors.Panel))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                applySystemBarsAppearance(light = !UiColors.IsNight)
            }
        } catch (failure: Throwable) {
            dialog.dismiss()
            lifecycle?.close()
            cleanup.close()
            owner.close()
            view.disposeComposition()
            onDispose()
            throw failure
        }
    }
}
