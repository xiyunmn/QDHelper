package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.Canvas
import android.view.View

/** Narrow GPU bridge. The UI owns scheduling, lifetime, input and both drawing layers. */
class GlassRenderSurface(private val source: View, failure: (Throwable) -> Unit) : AutoCloseable {
    init { check(android.os.Build.VERSION.SDK_INT >= 33) { "Liquid glass requires Android 13 or newer" } }
    private val renderer = GlassLensRenderer(failure)
    private var tilt: GlassTilt? = null
    private var effects = GlassEffectsSpec()
    private var closed = false

    fun configure(width: Int, height: Int, density: Float, effects: GlassEffectsSpec, night: Boolean) {
        this.effects = effects
        renderer.configure(width, height, density, effects, night)
    }

    fun update(motion: GlassBarMotion) {
        renderer.setPress(motion.progress, motion.selectionCenterX ?: motion.touchX,
            if (motion.selectionVisible) motion.selectionRect.centerY() else motion.touchY, motion.highlight)
        renderer.setSelection(motion.selectionRect, motion.selectionVisible, motion.scaleX, motion.scaleY)
    }

    fun setTabContent(content: GlassTabContent?) { if (!closed) renderer.setTabContent(content) }

    fun setActive(active: Boolean, onLightChanged: () -> Unit) {
        if (closed) return
        if (effects.tracksLight && active && tilt == null) {
            tilt = GlassTilt(source.context, { source.display?.rotation ?: 0 }, { angle ->
                renderer.setLightAngle(angle)
                onLightChanged()
            }, { /* Sensor absence must not disable an otherwise usable bar. */ })
        }
        tilt?.setActive(active && effects.tracksLight)
        if (!effects.tracksLight) renderer.setLightAngle(GlassTilt.REST_ANGLE)
    }

    fun prepare(x: Int, y: Int, nativeTabs: View?): Boolean = !closed && renderer.prepare(source, x, y, nativeTabs)
    fun invalidateMaterials() { if (!closed) renderer.invalidateMaterials() }
    fun draw(canvas: Canvas, foreground: Boolean) {
        if (closed) return
        if (foreground) renderer.drawForeground(canvas) else renderer.draw(canvas)
    }

    override fun close() {
        if (closed) return
        closed = true
        tilt?.setActive(false)
        tilt = null
        renderer.release()
    }
}
