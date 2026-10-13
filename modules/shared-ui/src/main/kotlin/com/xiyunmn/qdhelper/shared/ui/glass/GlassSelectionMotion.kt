package com.xiyunmn.qdhelper.shared.ui.glass

/** Normalized bar coordinates give identical motion at different screen densities and tab counts. */
internal class GlassSelectionMotion {
    private val leading = GlassSpring(1f, 1000f)
    private val trailing = GlassSpring(1f, 1000f)
    var visible = false
        private set
    val left: Float get() = leading.value
    val right: Float get() = trailing.value
    val center: Float get() = (left + right) / 2f
    val targetCenter: Float get() = (leading.target + trailing.target) / 2f
    val velocity: Float get() = (leading.velocity + trailing.velocity) / 2f
    var top = 0f
        private set
    var bottom = 0f
        private set
    val running: Boolean get() = leading.running || trailing.running

    fun target(left: Float, top: Float, right: Float, bottom: Float, animate: Boolean): Boolean {
        val changed = !visible || leading.target != left || trailing.target != right || this.top != top || this.bottom != bottom
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
        leading.advance(seconds)
        trailing.advance(seconds)
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
