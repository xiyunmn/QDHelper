package com.xiyunmn.qdhelper.shared.ui.glass

/** Normalized bar coordinates give identical motion at different screen densities and tab counts. */
internal class GlassSelectionMotion {
    private val leading = GlassSpring(1f, 1000f)
    private val trailing = GlassSpring(1f, 1000f)
    private var speed = 1f
    var visible = false
        private set
    val left: Float get() = leading.value
    val right: Float get() = trailing.value
    val center: Float get() = (left + right) / 2f
    val targetCenter: Float get() = (leading.target + trailing.target) / 2f
    val velocity: Float get() = (leading.velocity + trailing.velocity) / 2f * speed
    var top = 0f
        private set
    var bottom = 0f
        private set
    val running: Boolean get() = leading.running || trailing.running

    fun target(left: Float, top: Float, right: Float, bottom: Float, animate: Boolean, speed: Float = 1f): Boolean {
        val changed = !visible || leading.target != left || trailing.target != right || this.top != top || this.bottom != bottom
        if (changed) this.speed = speed
        if (!animate || !visible) {
            leading.snapTo(left)
            trailing.snapTo(right)
        } else {
            leading.animateTo(left)
            trailing.animateTo(right)
        }
        visible = right > left && bottom > top
        this.top = top
        this.bottom = bottom
        return changed
    }

    fun advance(seconds: Float) {
        leading.advance(seconds * speed)
        trailing.advance(seconds * speed)
    }

    fun release(left: Float, top: Float, right: Float, bottom: Float, velocity: Float, animate: Boolean, speed: Float = 1f) {
        this.speed = speed
        target(left, top, right, bottom, animate, speed)
        if (animate) {
            leading.launch(left, velocity / speed)
            trailing.launch(right, velocity / speed)
        }
    }

    fun settle() {
        leading.snapTo(leading.target)
        trailing.snapTo(trailing.target)
    }

    fun clear() {
        visible = false
        settle()
    }
}
