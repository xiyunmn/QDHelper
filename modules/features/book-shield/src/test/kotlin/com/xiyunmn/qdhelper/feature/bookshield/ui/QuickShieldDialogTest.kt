package com.xiyunmn.qdhelper.feature.bookshield.ui

import android.app.Activity
import android.content.ClipboardManager
import android.graphics.Insets
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.ComposeView
import com.xiyunmn.qdhelper.config.BookShieldRuleRepository
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfo
import com.xiyunmn.qdhelper.shared.compose.ModuleComposeEnvironment
import com.xiyunmn.qdhelper.shared.compose.ModuleComposeContext
import com.xiyunmn.qdhelper.shared.compose.ModuleCompositionOwner
import com.xiyunmn.qdhelper.shared.compose.ModuleFloatingDialog
import com.xiyunmn.qdhelper.shared.compose.ModuleFloatingWindow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class QuickShieldDialogTest {
    private val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val activity = host.get()
    private val source = TextView(activity)
    private val info = BookInfo(123456L, " 完整书名😀 ", " 作者 ", null, null, null, null, null, null, "test")
    private var session: QuickShieldSession? = null

    @Before fun setup() {
        activity.setContentView(source)
        ModuleComposeEnvironment.configure(activity.applicationInfo)
        BookShieldRuleRepository.clearRuntime()
    }
    @After fun teardown() {
        session?.close()
        host.pause().stop().destroy()
        BookShieldRuleRepository.clearRuntime()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
    }
    private fun open(): QuickShieldSession = requireNotNull(BookShieldQuickAddDialog.show(activity, info, source)).also { session = it }
    private fun mount(session: QuickShieldSession) {
        val view = session.view
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        val attach = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE)
        view.measure(View.MeasureSpec.makeMeasureSpec(368, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val layer = view.parent as ViewGroup
        layer.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        layer.layout(0, 0, 400, 800)
        layer.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(260))
        assertTrue(view.hasComposition)
        assertTrue(view.measuredHeight > 0)
    }
    @Test fun stableBrightnessAndAnimatedDismissReleaseComposition() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = .08f }
        val popup = open()
        assertEquals(.08f, popup.dialog.window!!.attributes.screenBrightness, 0f)
        assertEquals(0f, popup.dialog.window!!.attributes.dimAmount, 0f)
        assertEquals(0, popup.dialog.window!!.attributes.windowAnimations)
        mount(popup)
        assertEquals(0f, popup.dialog.window!!.attributes.dimAmount, 0f)
        assertEquals(0, popup.dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(1f, popup.view.alpha, .001f)
        popup.dismiss()
        assertTrue(popup.dialog.isShowing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertFalse(popup.dialog.isShowing)
        assertFalse(popup.view.hasComposition)
        assertEquals(.08f, activity.window.attributes.screenBrightness, 0f)
    }

    @Test fun firstFrameAndExitDoNotAnimateWindowAttributes() {
        val popup = open()
        val window = popup.dialog.window!!
        assertEquals(0f, popup.view.alpha, 0f)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
        var updates = 0
        val original = window.callback
        window.callback = object : Window.Callback by original {
            override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
                updates++
                original.onWindowAttributesChanged(attrs)
            }
        }
        mount(popup)
        assertEquals(0, updates)
        popup.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(0, updates)
        assertFalse(popup.dialog.isShowing)
    }

    @Test fun closingBeforeFirstDrawCancelsPendingEntrance() {
        val popup = open()
        val observer = popup.view.viewTreeObserver
        popup.close()
        if (observer.isAlive) observer.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertFalse(popup.dialog.isShowing)
        assertEquals(0f, popup.view.alpha, 0f)
    }

    @Test fun safeInsetsKeepCardAboveKeyboardAndOnlyOutsideTapsCancel() {
        val popup = open()
        mount(popup)
        val layer = popup.view.parent as ViewGroup
        layer.dispatchApplyWindowInsets(WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 24))
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 320)).build())
        layer.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        layer.layout(0, 0, 400, 800)
        assertTrue(popup.view.top >= 24)
        assertTrue(popup.view.bottom <= 480)
        fun tap(x: Float, y: Float) {
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(0, 0, action, x, y, 0)
                try { layer.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        // Blank padding inside the card must not behave like an outside tap.
        tap(popup.view.x + 2f, popup.view.y + 2f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(popup.dialog.isShowing)
        popup.dialog.setCanceledOnTouchOutside(false)
        tap(1f, 1f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(popup.dialog.isShowing)
        popup.dialog.setCanceledOnTouchOutside(true)
        tap(1f, 1f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertFalse(popup.dialog.isShowing)
    }
    @Test fun everyRuleCopiesItsOwnValueAndMembershipRefreshes() {
        val popup = open()
        assertEquals(listOf("完整书名😀", "作者", "123456"), popup.rules.map { it.value })
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        popup.rules.forEachIndexed { index, rule ->
            popup.copy(index)
            assertEquals(rule.value, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(rule.save(activity, false))
            popup.refresh()
            assertTrue(popup.membership[index])
            assertTrue(rule.save(activity, true))
            popup.refresh()
            assertFalse(popup.membership[index])
        }
    }
    @Test fun reopeningReleasesPreviousWindowAndMissingValuesAreOmitted() {
        val first = open()
        mount(first)
        val second = open()
        assertFalse(first.dialog.isShowing)
        assertFalse(first.view.hasComposition)
        assertEquals(-1f, second.dialog.window!!.attributes.screenBrightness, 0f)
        assertTrue(quickShieldRules(info.copy(id = 0, title = " ", author = null)).isEmpty())
    }

    @Test fun sharedComposePopupKeepsContentForExitAndClosesWithItsParent() {
        val owner = ModuleCompositionOwner()
        val parent = ComposeView(ModuleComposeContext.create(activity))
        val visible = mutableStateOf(true)
        var dismissed = 0
        var contentDisposedAt = 0L
        owner.attach(parent)
        parent.setContent { if (visible.value) ModuleFloatingDialog({ dismissed++; visible.value = false }) {
            DisposableEffect(Unit) { onDispose { contentDisposedAt = android.os.SystemClock.uptimeMillis() } }
            Text("内容")
        } }
        activity.setContentView(parent)
        owner.resume()
        fun frame() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 400, 800)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
        try {
            frame()
            val first = ShadowDialog.getLatestDialog() as ModuleFloatingWindow
            assertTrue(first.isShowing)
            val exitStarted = android.os.SystemClock.uptimeMillis()
            visible.value = false
            frame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertFalse(first.isShowing)
            assertTrue("Content must survive the exit transition", contentDisposedAt - exitStarted >= 180L)
            assertEquals(0, dismissed)
            visible.value = true
            frame()
            val second = ShadowDialog.getLatestDialog() as ModuleFloatingWindow
            assertTrue(second.isShowing)
            owner.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(second.isShowing)
        } finally { owner.close(); parent.disposeComposition() }
    }
}
