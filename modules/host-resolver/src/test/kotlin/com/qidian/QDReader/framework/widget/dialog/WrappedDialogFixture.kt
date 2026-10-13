package com.qidian.QDReader.framework.widget.dialog

import android.content.Context

open class WrappedDialogFixture(@JvmField protected val mContext: Context) {
    fun show() = Unit
    fun dismiss() = Unit
    fun isShowing(): Boolean = false
}
