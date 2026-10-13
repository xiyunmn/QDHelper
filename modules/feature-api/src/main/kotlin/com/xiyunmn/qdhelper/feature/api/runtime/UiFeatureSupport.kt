package com.xiyunmn.qdhelper.feature.api.runtime

import android.view.View
import android.view.ViewGroup
import com.xiyunmn.qdhelper.host.api.reflection.callNoArgMethod
import com.xiyunmn.qdhelper.host.api.reflection.declaredFieldValue
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object UiFeatureSupport {
    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()

    fun installAfter(
        module: HookModule,
        id: String,
        method: Method,
        hookId: String,
        phase: String,
        after: (Any?) -> Boolean,
    ): Int = installAfter(module, id, method, hookId, phase) { chain, _ -> after(chain.thisObject) }

    fun installAfter(
        module: HookModule,
        id: String,
        method: Method,
        hookId: String,
        phase: String,
        after: (HookChain, Any?) -> Boolean,
    ): Int {
        val success = FeatureHooks.hookAfter(
            module,
            method,
            "ui.$id:${HostSymbols.classLabel(method.declaringClass.name)}.${method.name}",
            hookId,
        ) { chain, result -> if (after(chain, result)) logHit(id, hookId, "hide-views") }
        if (success) FeatureHooks.i("UI feature installed $id: ${method.name}, phase=$phase")
        return if (success) 1 else 0
    }

    fun installAround(
        module: HookModule,
        id: String,
        method: Method,
        hookId: String,
        phase: String,
        around: (HookChain) -> Any?,
    ): Int {
        val success = FeatureHooks.hookAround(
            module,
            method,
            "ui.$id:${HostSymbols.classLabel(method.declaringClass.name)}.${method.name}",
            hookId,
            around,
        )
        if (success) FeatureHooks.i("UI feature installed $id: ${method.name}, phase=$phase")
        return if (success) 1 else 0
    }

    fun installReturnValue(
        module: HookModule,
        id: String,
        method: Method,
        hookId: String,
        phase: String,
        shouldBlock: (HookChain) -> Boolean,
        action: String,
        value: (HookChain) -> Any?,
    ): Int = installAround(module, id, method, hookId, phase) { chain ->
        if (!shouldBlock(chain)) return@installAround chain.proceed()
        logHit(id, hookId, action)
        value(chain)
    }

    fun reorderAndHideChildren(
        container: ViewGroup,
        orderedKeys: List<String>,
        hiddenKeys: Set<String>,
        items: Map<String, View?>,
        anchors: Set<View> = emptySet(),
    ): Boolean {
        val present = orderedKeys.mapNotNull { key -> items[key]?.let { key to it } }
        if (present.isEmpty()) return false
        val safeHidden = if (present.all { (key, _) -> key in hiddenKeys }) emptySet() else hiddenKeys
        var changed = false
        present.forEach { (key, view) ->
            val visibility = if (key in safeHidden) View.GONE else View.VISIBLE
            if (view.visibility != visibility) {
                view.visibility = visibility
                changed = true
            }
        }
        val currentOrder = (0 until container.childCount).map(container::getChildAt)
        val desired = present.map { it.second }
        val anchoredSlots = currentOrder.indices.filter { currentOrder[it] in anchors }.toSet()
        val fillQueue = ArrayDeque<View>().apply {
            addAll(desired)
            currentOrder.forEachIndexed { index, view ->
                if (index !in anchoredSlots && view !in desired) add(view)
            }
        }
        val result = arrayOfNulls<View>(currentOrder.size)
        anchoredSlots.forEach { result[it] = currentOrder[it] }
        currentOrder.indices.forEach { index ->
            if (index !in anchoredSlots) result[index] = fillQueue.removeFirstOrNull() ?: currentOrder[index]
        }
        val nextOrder = result.filterNotNull()
        if (currentOrder != nextOrder) {
            val params = nextOrder.associateWith { it.layoutParams }
            nextOrder.forEach { if (it.parent === container) container.removeView(it) }
            nextOrder.forEach { container.addView(it, params[it]) }
            changed = true
        }
        return changed
    }

    fun findChildViewByIdName(root: View, idName: String): View? {
        val id = root.resources.getIdentifier(idName, "id", root.context.packageName)
        return id.takeIf { it != 0 }?.let(root::findViewById)
    }

    fun directChildOf(container: ViewGroup, view: View?): View? {
        var current = view ?: return null
        while (current.parent is View && current.parent !== container) current = current.parent as View
        return current.takeIf { it.parent === container }
    }

    fun rootViewOf(owner: Any?): View? {
        if (owner is View) return owner
        owner ?: return null
        return (owner.callNoArgMethod("getRoot") as? View)
            ?: (owner.callNoArgMethod("getView") as? View)
            ?: (owner.callNoArgMethod("getWindow")?.callNoArgMethod("getDecorView") as? View)
            ?: (owner.declaredFieldValue("itemView") as? View)
    }

    fun applyFromOwner(owner: Any?, apply: (View) -> Boolean): Boolean =
        rootViewOf(owner)?.let(apply) ?: false

    fun hideView(view: View?) {
        if (view != null && view.visibility != View.GONE) view.visibility = View.GONE
    }

    private fun logHit(id: String, hookId: String, action: String) {
        val count = hitCounters.computeIfAbsent(hookId) { AtomicLong() }.incrementAndGet()
        if (count <= 20L || count % 50L == 0L) FeatureHooks.i("UI hit $id#$count: action=$action")
    }
}
