package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.Color
import android.graphics.RectF
import android.graphics.RenderNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Simulate HWUI discarding cached display lists without a settings/geometry change. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassRenderRecoveryTest {
    private fun node(renderer: GlassLensRenderer, name: String): RenderNode = ReflectionHelpers.getField(renderer, name)

    @Test fun restingTabHighlightRemainsInForegroundAndRecoversAfterDiscard() {
        val renderer = GlassLensRenderer { throw AssertionError(it) }
        val output = RenderNode("test.resting-selection")
        try {
            renderer.configure(240, 64, 1f, GlassEffectsSpec(tilt = false), false)
            renderer.setTabContent(GlassTabContent(listOf(RectF(0f, 0f, 80f, 64f)),
                listOf(RectF(20f, 15f, 60f, 50f)), emptyList(), Color.BLACK, Color.RED))
            val page = node(renderer, "page")
            page.beginRecording(368, 192).drawColor(Color.WHITE)
            page.endRecording()
            ReflectionHelpers.setField(renderer, "ready", true)
            ReflectionHelpers.setField(renderer, "selectionContentReady", true)
            renderer.setSelection(RectF(4f, 4f, 76f, 60f), true, 1f, 1f)
            renderer.setPress(0f, 40f, 32f, 0f)
            fun draw() {
                val canvas = output.beginRecording(368, 192)
                try { assertTrue("Resting selection must still reveal the accent row", renderer.drawForeground(canvas)) }
                finally { output.endRecording() }
            }
            draw()
            node(renderer, "embedded").discardDisplayList()
            draw()
            assertTrue(node(renderer, "embedded").hasDisplayList())
            assertFalse(ReflectionHelpers.getField<Boolean>(renderer, "selectionDirty"))
            assertFalse(node(renderer, "inner").hasDisplayList()) // No zero-radius shadow/blur.
        } finally { renderer.release(); output.discardDisplayList() }
    }

    @Test fun rebuildsMissingBaseAndShadowEvenWhenMaterialParametersAreUnchanged() {
        val renderer = GlassLensRenderer { throw AssertionError(it) }
        val output = RenderNode("test.output")
        try {
            renderer.configure(240, 64, 1f, GlassEffectsSpec(tilt = false), false)
            val page = node(renderer, "page")
            page.beginRecording(368, 192).drawColor(Color.BLUE)
            page.endRecording()
            ReflectionHelpers.setField(renderer, "ready", true)
            fun draw() {
                val canvas = output.beginRecording(368, 192)
                try { assertTrue(renderer.draw(canvas)) } finally { output.endRecording() }
            }
            draw()
            val base = node(renderer, "base")
            val shadow = node(renderer, "shadow")
            assertTrue(base.hasDisplayList())
            assertTrue(shadow.hasDisplayList())
            base.discardDisplayList()
            draw()
            assertTrue("A missing base must bypass the clean-parameters cache", base.hasDisplayList())
            shadow.discardDisplayList()
            draw()
            assertTrue("The shadow can be discarded independently", shadow.hasDisplayList())
        } finally { renderer.release(); output.discardDisplayList() }
    }

    @Test fun rebuildsIndependentlyDiscardedDropletLayers() {
        val renderer = GlassLensRenderer { throw AssertionError(it) }
        val output = RenderNode("test.output")
        try {
            renderer.configure(240, 64, 1f, GlassEffectsSpec(tilt = false), false)
            val page = node(renderer, "page")
            page.beginRecording(368, 192).drawColor(Color.BLUE)
            page.endRecording()
            ReflectionHelpers.setField(renderer, "ready", true)
            ReflectionHelpers.setField(renderer, "selectionContentReady", true)
            renderer.setSelection(RectF(4f, 4f, 56f, 60f), true, 1.1f, 1.1f)
            renderer.setPress(0.8f, 30f, 32f, 0.8f)
            fun draw() {
                val canvas = output.beginRecording(368, 192)
                try { assertTrue(renderer.drawForeground(canvas)) } finally { output.endRecording() }
            }
            draw()
            for (name in listOf("clear", "inner", "embedded")) {
                val layer = node(renderer, name)
                assertTrue(layer.hasDisplayList())
                layer.discardDisplayList()
                draw()
                assertTrue("Missing $name must be re-recorded", layer.hasDisplayList())
            }
        } finally { renderer.release(); output.discardDisplayList() }
    }
}
