package com.xiyunmn.qdhelper.feature.navigation

import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport

import android.view.View
import android.view.ViewGroup
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime

/**
 * 首页右上角入口排序隐藏
 */
object HomeTopActionCustomization {

    fun applyHomeTopActionCustom(root: View): Boolean {
        val context = HostSessionStore.contextOrNull()
        if (!SettingsRuntime.isHomeTopActionCustomEnabled(context)) {
            return false
        }
        val container = UiFeatureSupport.findChildViewByIdName(root, "rightIvContainer") as? ViewGroup ?: return false
        val items = mapOf(
            "search" to UiFeatureSupport.directChildOf(container, UiFeatureSupport.findChildViewByIdName(root, "ivSearch")),
            "game" to UiFeatureSupport.directChildOf(container, UiFeatureSupport.findChildViewByIdName(root, "ivGame")),
            "more" to UiFeatureSupport.directChildOf(container, UiFeatureSupport.findChildViewByIdName(root, "ivMoreView")),
        )
        return UiFeatureSupport.reorderAndHideChildren(
            container = container,
            orderedKeys = SettingsRuntime.homeTopActionOrder(context),
            hiddenKeys = SettingsRuntime.homeTopActionHidden(context),
            items = items,
        )
    }

}
