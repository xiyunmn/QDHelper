package com.xiyunmn.qdhelper.feature.pagecustomization

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal object PageCustomizationViewInspector {
    fun isModuleSettingsRoot(root: View): Boolean {
        var found = false
        traverse(root) { view ->
            if (found || view !is TextView) return@traverse
            val text = view.text?.toString().orEmpty()
            if (text == "起点助手" || text == "模块设置" || text == "页面自定义") {
                found = true
            }
        }
        return found
    }

    fun isMinePage(root: View): Boolean {
        if (isMineBottomTabSelected(root)) {
            return true
        }
        return hasAnyIdName(root, setOf("schoolLayout", "layHeader", "ivSetting", "ivTheme")) ||
            hasClassName(root, "user_account")
    }

    fun isMineBottomTabSelected(root: View): Boolean {
        val labels = setOf("我", "我的")
        var matched = false
        traverse(root) { view ->
            if (matched || view !is TextView) return@traverse
            val text = view.text?.toString()?.trim().orEmpty()
            if (text !in labels) return@traverse
            var current: View? = view
            repeat(5) {
                if (current?.isSelected == true || current?.isActivated == true) {
                    matched = true
                    return@traverse
                }
                current = current?.parent as? View
            }
        }
        return matched
    }

    fun isRecyclerLike(view: View): Boolean {
        var clazz: Class<*>? = view.javaClass
        while (clazz != null) {
            val name = clazz.name
            if (name == "androidx.recyclerview.widget.RecyclerView" ||
                name.endsWith(".RecyclerView") ||
                name.contains("RecyclerView")
            ) {
                return true
            }
            clazz = clazz.superclass
        }
        return false
    }

    fun hasAnyIdName(root: View, names: Set<String>): Boolean {
        var found = false
        traverse(root) { view ->
            if (!found && resourceEntryName(view) in names) {
                found = true
            }
        }
        return found
    }

    fun hasClassName(root: View, token: String): Boolean {
        var found = false
        traverse(root) { view ->
            if (!found) {
                found = view.javaClass.name.contains(token, ignoreCase = true)
            }
        }
        return found
    }

    fun traverse(root: View, visitor: (View) -> Unit) {
        visitor(root)
        if (root !is ViewGroup) return
        for (index in 0 until root.childCount) {
            traverse(root.getChildAt(index), visitor)
        }
    }

    fun resourceEntryName(view: View): String {
        if (view.id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
    }

}
