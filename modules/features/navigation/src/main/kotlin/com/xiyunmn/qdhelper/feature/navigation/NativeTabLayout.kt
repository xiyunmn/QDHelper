package com.xiyunmn.qdhelper.feature.navigation

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/** Reposition native children without changing their indices, click closures or icon arrays. */
internal class NativeTabLayout(private val row: ViewGroup) : AutoCloseable {
    private val children = (0 until row.childCount).map(row::getChildAt)
    private val visibility = children.associateWith { it.visibility }
    private val writtenVisibility = mutableMapOf<View, Int>()
    private var ordered = children
    private var closed = false
    private val observer = row.viewTreeObserver
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { position() }
    private val preDraw = ViewTreeObserver.OnPreDrawListener { position(); true }
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = close()
    }

    init {
        observer.addOnGlobalLayoutListener(globalLayout)
        observer.addOnPreDrawListener(preDraw)
        row.addOnAttachStateChangeListener(detach)
    }

    fun matches() = !closed && children.size == row.childCount &&
        children.indices.all { row.getChildAt(it) === children[it] }

    fun apply(tabs: List<LiveTab>, custom: Boolean, order: List<String>, hidden: Set<String>, hideActivity: Boolean): Boolean {
        val fixed = tabs.filter { it.key != null }.associateBy { it.key!! }
        val queue = ArrayDeque<View>()
        if (custom) {
            (order + fixed.keys).distinct().mapNotNull { fixed[it]?.view }.forEach(queue::addLast)
        } else tabs.filter { it.key != null }.forEach { queue.addLast(it.view) }
        ordered = tabs.map { if (it.key == null) it.view else queue.removeFirst() }
        val safeHidden = if (fixed.keys.all { it in hidden }) emptySet() else hidden
        var changed = false
        tabs.forEach { tab ->
            val target = when {
                tab.isActivity && hideActivity -> View.GONE
                custom && tab.key != null -> if (tab.key in safeHidden) View.GONE else View.VISIBLE
                else -> visibility.getValue(tab.view)
            }
            if (tab.view.visibility != target) { tab.view.visibility = target; changed = true }
            writtenVisibility[tab.view] = target
        }
        return position() || changed
    }

    internal fun position(): Boolean {
        if (!matches()) return false
        val visible = ordered.filter { it.visibility != View.GONE && it.width > 0 }
            .let { if (row.layoutDirection == View.LAYOUT_DIRECTION_RTL) it.reversed() else it }
        if (visible.isEmpty()) return false
        fun leftMargin(view: View) = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0
        fun rightMargin(view: View) = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin ?: 0
        var x = visible.minOf { it.left - leftMargin(it) }
        var changed = false
        visible.forEach { view ->
            x += leftMargin(view)
            if (view.left != x) { view.offsetLeftAndRight(x - view.left); changed = true }
            x += view.width + rightMargin(view)
        }
        return changed
    }

    override fun close() {
        if (closed) return
        closed = true
        if (observer.isAlive) {
            observer.removeOnGlobalLayoutListener(globalLayout)
            observer.removeOnPreDrawListener(preDraw)
        }
        row.removeOnAttachStateChangeListener(detach)
        writtenVisibility.forEach { (view, value) ->
            if (view.visibility == value) view.visibility = visibility.getValue(view)
        }
        row.requestLayout()
    }
}
