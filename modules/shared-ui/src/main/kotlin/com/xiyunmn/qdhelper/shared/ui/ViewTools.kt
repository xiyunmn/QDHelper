package com.xiyunmn.qdhelper.shared.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup

fun Context?.unwrapActivity(): Activity? {
    var current = this
    val seen = HashSet<Context>()
    while (current != null && seen.add(current)) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

fun View.descendants(includeSelf: Boolean = false): Sequence<View> = sequence {
    if (includeSelf) yield(this@descendants)
    if (this@descendants !is ViewGroup) return@sequence
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        yield(child)
        yieldAll(child.descendants())
    }
}
