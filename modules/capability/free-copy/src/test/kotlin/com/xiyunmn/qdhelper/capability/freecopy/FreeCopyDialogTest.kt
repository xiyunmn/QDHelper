package com.xiyunmn.qdhelper.capability.freecopy

import android.app.Activity
import android.graphics.Typeface
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.text.font.FontWeight
import com.xiyunmn.qdhelper.shared.compose.ModuleComposeEnvironment
import com.xiyunmn.qdhelper.shared.ui.HostThemeResolver
import com.xiyunmn.qdhelper.shared.ui.UiColors
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class FreeCopyDialogTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val activity = controller.get()
    private val source = TextView(activity)
    private var session: CopyDialogSession? = null
    private var released = 0
    private val errors = mutableListOf<Throwable>()

    @Before fun setup() {
        activity.setContentView(source)
        ModuleComposeEnvironment.configure(activity.applicationInfo)
        FreeCopyDialog.configure({ AutoCloseable { released++ } }, errors::add)
    }

    @After fun teardown() {
        session?.close()
        UiColors.hostThemeResolver = null
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertTrue(errors.joinToString { it.stackTraceToString() }, errors.isEmpty())
    }

    private fun open(beforeShow: () -> Unit = {}): CopyDialogSession {
        val result = FreeCopyDialog.show(source, "正文\n第二段", beforeShow = beforeShow)
        assertNotNull(errors.joinToString { it.stackTraceToString() }, result)
        return (result as CopyDialogSession).also { session = it }
    }

    private fun mount(session: CopyDialogSession) {
        val view = session.view
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        val attach = org.robolectric.util.ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        org.robolectric.util.ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE)
        view.measure(View.MeasureSpec.makeMeasureSpec(368, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val layer = view.parent as android.view.ViewGroup
        layer.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        layer.layout(0, 0, 400, 800)
        layer.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(view.hasComposition)
        assertTrue(view.measuredHeight > 0)
    }

    @Test fun inheritsBrightnessBeforeNativeMenuClosesAndFadesTheBackground() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.08f }
        val copy = open { activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.7f } }
        val attributes = copy.dialog.window!!.attributes
        assertEquals(0.08f, attributes.screenBrightness, 0f)
        assertEquals(0f, attributes.dimAmount, 0f)
        assertEquals(0, attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(0, attributes.windowAnimations)
        mount(copy)
        assertEquals(0f, copy.dialog.window!!.attributes.dimAmount, 0f)
        assertEquals(1f, copy.view.alpha, .001f)
        copy.close()
        assertFalse(copy.view.hasComposition)
        assertEquals(1, released)
        assertEquals(0.7f, activity.window.attributes.screenBrightness, 0f)
    }

    @Test fun preservesSystemBrightnessAndReleasesPreviousSessionExactlyOnce() {
        val first = open()
        mount(first)
        val second = open()
        mount(second)
        assertEquals(-1f, second.dialog.window!!.attributes.screenBrightness, 0f)
        assertFalse(first.dialog.isShowing)
        assertFalse(first.view.hasComposition)
        assertEquals(1, released)
        second.close()
        second.close()
        assertEquals(2, released)
    }

    @Test fun sourceWindowBrightnessWinsAndDefaultFallsBackToActivity() {
        val popupSource = View(activity)
        popupSource.layoutParams = WindowManager.LayoutParams().apply { screenBrightness = 0f }
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.4f }
        assertEquals(0f, copyBrightness(popupSource, activity), 0f)
        (popupSource.layoutParams as WindowManager.LayoutParams).screenBrightness = -1f
        assertEquals(0.4f, copyBrightness(popupSource, activity), 0f)
    }

    @Test fun themeRefreshAndCancelDisposeMountedComposition() {
        var night = false
        UiColors.hostThemeResolver = object : HostThemeResolver {
            override fun isNightMode(context: android.content.Context) = night
            override fun color(context: android.content.Context, role: String): Int? = null
        }
        val copy = open()
        mount(copy)
        night = true
        // AndroidUiDispatcher is a frame dispatcher, not a Delay implementation. The
        // polling delay uses the real coroutine clock, independent of Robolectric's looper.
        Thread.sleep(550)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(UiColors.IsNight)
        copy.pause()
        assertFalse(copy.active)
        copy.resume()
        copy.dialog.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertFalse(copy.dialog.isShowing)
        assertFalse(copy.view.hasComposition)
        assertEquals(1, released)
    }

    @Test fun keepsFullTextAndCircleTitleFormatting() {
        val input = SpannableStringBuilder("标题\n\n正文😀\n完整简介")
        input.setSpan(StyleSpan(Typeface.BOLD), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        input.setSpan(RelativeSizeSpan(1.2f), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val result = copyAnnotatedText(input)
        assertEquals(input.toString(), result.text)
        assertTrue(result.spanStyles.any { it.start == 0 && it.end == 2 && it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.start == 0 && it.end == 2 && it.item.fontSize.value == 1.2f })
        assertEquals("\n正文\n", copyAnnotatedText("\n正文\n").text)
    }
}
