package com.xiyunmn.qdhelper.feature.navigation

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeTabLayoutTest {
    @Test fun reorderKeepsNativeClickIndicesAndIsIdempotentAcrossHostLayoutPasses() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val row = LinearLayout(activity.get())
            activity.get().setContentView(row)
            val keys = listOf("shelf", "selection", "discover", "mine")
            var selected = -1
            val tabs = keys.mapIndexed { index, key ->
                val view = View(activity.get()).apply { setOnClickListener { selected = index } }
                row.addView(view, LinearLayout.LayoutParams(80, 60))
                LiveTab(view, key, false)
            }
            val layout = NativeTabLayout(row)
            repeat(4) {
                // The host keeps laying out in its original child order.
                tabs.forEachIndexed { index, tab -> tab.view.layout(index * 80, 0, (index + 1) * 80, 60) }
                layout.apply(tabs, true, listOf("selection", "shelf", "mine", "discover"), emptySet(), false)
                assertEquals(listOf(80, 0, 240, 160), tabs.map { it.view.left })
                tabs.forEachIndexed { index, tab ->
                    assertSame(tab.view, row.getChildAt(index))
                    tab.view.performClick()
                    assertEquals(index, selected)
                }
                assertFalse(layout.position())
            }
            layout.close()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun cloudAnchorHidingRestorationAndNativeRebuild() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val row = LinearLayout(activity.get())
            val tabs = listOf("shelf", "selection", null, "discover", "mine").mapIndexed { index, key ->
                val view = View(activity.get())
                row.addView(view, LinearLayout.LayoutParams(60, 50))
                view.layout(index * 60, 0, (index + 1) * 60, 50)
                LiveTab(view, key, key == null)
            }
            val layout = NativeTabLayout(row)
            layout.apply(tabs, true, listOf("mine", "selection", "shelf", "discover"), setOf("shelf"), false)
            assertEquals(View.GONE, tabs[0].view.visibility)
            assertTrue(tabs[1].view.left < tabs[2].view.left)
            assertTrue(tabs[2].view.left < tabs[3].view.left)
            layout.apply(tabs, true, listOf("mine", "selection", "shelf", "discover"), setOf("shelf"), true)
            assertEquals(View.GONE, tabs[2].view.visibility)
            layout.close()
            assertTrue(tabs.all { it.view.visibility == View.VISIBLE })
            val next = NativeTabLayout(row)
            row.removeViewAt(0)
            row.addView(View(activity.get()), 0)
            assertFalse(next.matches())
            next.close()
        } finally { activity.pause().stop().destroy() }
    }
}
