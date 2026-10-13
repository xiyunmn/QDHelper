package com.xiyunmn.qdhelper.feature.pagecustomization

import android.content.Context
import android.view.View
import android.widget.TextView
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import kotlin.math.abs

internal object MinePageDataEngine {
    private val mineStringGetterPriority = listOf(
        "getName",
        "getTitle",
        "getText",
        "getLabel",
        "getButtonText",
        "getMenuName",
    )
    private val mineOptions = listOf(
        "活动中心",
        "起点会员",
        "横幅",
        "关注收藏",
        "成为作者",
        "成为作家",
        "奇妙世界",
        "新书投资",
        "加入我们",
        "大神之光",
        "福利中心",
        "我的定制",
        "帮助与客服",
        "我的全订",
        "我的阅历",
        "浏览记录",
        "装扮中心",
        "卡牌广场",
        "游戏中心",
        "达人中心",
        "我发布的",
        "周边商城",
        "成就徽章",
        "荣誉称号",
    )
    fun scanItemsFromRoot(root: View): List<PageCustomItem> {
        val labels = linkedSetOf<String>()
        PageCustomizationViewInspector.traverse(root) { view ->
            if (PageCustomizationViewInspector.isRecyclerLike(view)) {
                labels += scanMineLabelsFromSource(adapterOf(view))
            }
        }
        labels += scanVisibleKnownLabels(root, mineOptions)
        return labels.toMineItems()
    }

    fun scanItemsFromSource(source: Any?): List<PageCustomItem> {
        return scanMineLabelsFromSource(source).toMineItems()
    }

    private fun scanMineLabelsFromSource(source: Any?): LinkedHashSet<String> {
        val labels = linkedSetOf<String>()
        collectMineLabels(source, labels)
        return labels
    }

    private fun scanVisibleKnownLabels(root: View, options: List<String>): Set<String> {
        val known = options.toSet()
        val labels = linkedSetOf<String>()
        PageCustomizationViewInspector.traverse(root) { view ->
            if (view is TextView && view.visibility == View.VISIBLE) {
                val text = view.text?.toString()?.trim().orEmpty()
                if (text in known) labels += text
            }
        }
        return labels
    }

    private fun adapterOf(view: View): Any? {
        return runCatching {
            val method = FeatureHooks.declaredMethodsNamed(view.javaClass, "getAdapter")
                .singleOrNull { candidate -> candidate.parameterTypes.isEmpty() }
                ?: return@runCatching null
            FeatureHooks.invoke(method, view)
        }.getOrNull()
    }

    private fun collectMineLabels(source: Any?, labels: MutableSet<String>) {
        collectMineLabels(source, labels, PageCustomizationHostAccess.identitySet())
    }

    private fun collectMineLabels(source: Any?, labels: MutableSet<String>, visited: MutableSet<Any>) {
        source ?: return
        if (!visited.add(source)) return
        if (source is Iterable<*>) {
            source.forEach { item -> collectMineLabels(item, labels, visited) }
            return
        }
        mineSyntheticLabels(source).forEach(labels::add)
        mineLabelOf(source)?.let(labels::add)
        mineChildValues(source).forEach { child ->
            collectMineLabels(child, labels, visited)
        }
    }

    fun filterData(source: Any?, hiddenLabels: Set<String>): Boolean {
        return filterMineData(source, hiddenLabels, PageCustomizationHostAccess.identitySet())
    }

    private fun filterMineData(source: Any?, hiddenLabels: Set<String>, visited: MutableSet<Any>): Boolean {
        source ?: return false
        if (!visited.add(source)) return false
        var changed = false
        changed = filterMineSyntheticSource(source, hiddenLabels) || changed
        if (source is MutableList<*>) {
            changed = filterMineCombinedMembers(source, hiddenLabels) || changed
            changed = removeFromMutableList(source, hiddenLabels) { item -> mineLabelOf(item) } || changed
        }
        mutableListsFromGetters(source).forEach { list ->
            changed = filterMineCombinedMembers(list, hiddenLabels) || changed
            changed = removeFromMutableList(list, hiddenLabels) { item -> mineLabelOf(item) } || changed
        }
        if (source is Iterable<*>) {
            source.forEach { item ->
                changed = filterMineData(item, hiddenLabels, visited) || changed
            }
        }
        mineChildValues(source).forEach { child ->
            changed = filterMineData(child, hiddenLabels, visited) || changed
        }
        return changed
    }

    private fun removeFromMutableList(
        list: List<*>,
        hiddenLabels: Set<String>,
        labelOf: (Any?) -> String?,
    ): Boolean {
        if (list !is MutableList<*>) return false
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val iterator = (list as MutableList<Any?>).iterator()
            var changed = false
            while (iterator.hasNext()) {
                val item = iterator.next()
                val label = labelOf(item) ?: continue
                if (label in hiddenLabels) {
                    iterator.remove()
                    changed = true
                }
            }
            changed
        }.getOrDefault(false)
    }

    fun csvKeys(context: Context, key: String): Set<String> {
        return SettingsRuntime.prefs(context)
            .getString(key, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
    }

    fun hiddenLabels(context: Context): Set<String> {
        val byKey = PageCustomization.storedItems(context, PageCustomKind.Mine).ifEmpty { knownItems() }.associateBy { it.key }
        return SettingsRuntime.prefs(context)
            .getString(PageCustomKind.Mine.hiddenKey, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.mapNotNull { byKey[it]?.label }
            ?.toSet()
            .orEmpty()
    }

    fun mineLabelOf(item: Any?): String? {
        item ?: return null
        mineCombineBeanLabel(item)?.let { return it }
        if (!isMineEntryCandidate(item)) {
            return null
        }
        minePriorityStringCandidates(item).forEach { label ->
            normalizeMineLabel(label, allowUnknown = allowUnknownMineLabel(item))?.let { return it }
        }
        return null
    }

    private fun mineSyntheticLabels(item: Any): Set<String> {
        val labels = linkedSetOf<String>()
        if (hasMineAdBanner(item)) labels += "横幅"
        if (mineMemberFilterAction(item, hideMember = true) != MineMemberFilterAction.KEEP) {
            labels += MINE_MEMBER_LABEL
        }
        return labels
    }

    private fun filterMineSyntheticSource(source: Any, hiddenLabels: Set<String>): Boolean {
        var changed = false
        if ("横幅" in hiddenLabels && hasMineAdBanner(source)) {
            val setter = FeatureHooks.declaredMethodsNamed(source.javaClass, "setAdList")
                .singleOrNull { method -> method.parameterTypes.size == 1 }
            if (setter != null) {
                FeatureHooks.invoke(setter, source, null)
                changed = true
            }
        }
        if (MINE_MEMBER_LABEL in hiddenLabels &&
            mineMemberFilterAction(source, hideMember = true) == MineMemberFilterAction.CLEAR_MEMBER
        ) {
            changed = clearMineMember(source) || changed
        }
        return changed
    }

    private fun filterMineCombinedMembers(list: MutableList<*>, hiddenLabels: Set<String>): Boolean {
        if (MINE_MEMBER_LABEL !in hiddenLabels) return false
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val iterator = (list as MutableList<Any?>).iterator()
            var changed = false
            while (iterator.hasNext()) {
                val item = iterator.next() ?: continue
                // 7.9.472 merges membership into the type-2 balance row. Keep the balance half
                // when it exists; a member-only merged row must be removed to avoid a blank item.
                when (mineMemberFilterAction(item, hideMember = true)) {
                    MineMemberFilterAction.KEEP -> Unit
                    MineMemberFilterAction.CLEAR_MEMBER -> changed = clearMineMember(item) || changed
                    MineMemberFilterAction.REMOVE_ITEM -> {
                        iterator.remove()
                        changed = true
                    }
                }
            }
            changed
        }.getOrDefault(false)
    }

    private fun mineMemberFilterAction(item: Any, hideMember: Boolean): MineMemberFilterAction {
        if (!item.javaClass.name.endsWith(".UserAccountCombineBean")) return MineMemberFilterAction.KEEP
        val type = PageCustomizationHostAccess.invokeIntMethod(item, "getType")
        val hasMember = PageCustomizationHostAccess.invokeNoArgValue(item, "getMember") != null
        val hasAccountBalance = PageCustomizationHostAccess.invokeNoArgValue(item, "getAccountBalance") != null
        return mineMemberFilterAction(type, hasMember, hasAccountBalance, hideMember)
    }

    private fun clearMineMember(item: Any): Boolean {
        val setter = FeatureHooks.declaredMethodsNamed(item.javaClass, "setMember")
            .singleOrNull { method -> method.parameterTypes.size == 1 }
            ?: return false
        return FeatureHooks.invokeResult(setter, item, null) is HostAccess.Value
    }

    private fun mineCombineBeanLabel(item: Any): String? {
        if (!item.javaClass.name.endsWith(".UserAccountCombineBean")) return null
        return when (PageCustomizationHostAccess.invokeIntMethod(item, "getType")) {
            3 -> MINE_MEMBER_LABEL
            else -> null
        }
    }

    private fun hasMineAdBanner(item: Any): Boolean {
        if (!item.javaClass.name.endsWith(".UserAccountCombineBean")) return false
        if (PageCustomizationHostAccess.invokeIntMethod(item, "getType") != 5) return false
        val adList = PageCustomizationHostAccess.invokeNoArgValue(item, "getAdList") as? List<*> ?: return false
        return adList.isNotEmpty()
    }

    private fun minePriorityStringCandidates(item: Any): Sequence<String> {
        val seen = linkedSetOf<String>()
        return sequence {
            mineStringGetterPriority.forEach { methodName ->
                PageCustomizationHostAccess.invokeStringGetter(item, methodName)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && seen.add(it) }
                    ?.let { yield(it) }
            }
            FeatureHooks.allDeclaredMethods(item.javaClass)
                .asSequence()
                .filter { method ->
                    method.parameterTypes.isEmpty() &&
                        method.returnType == String::class.java &&
                        method.name.startsWith("get")
                }
                .sortedBy { method -> method.name }
                .forEach { method ->
                    runCatching { FeatureHooks.invoke(method, item) as? String }
                        .getOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && seen.add(it) }
                        ?.let { yield(it) }
                }
        }
    }

    private fun normalizeMineLabel(label: String, allowUnknown: Boolean): String? {
        val normalized = label.trim()
        if (normalized in mineOptions) return normalized
        if (!allowUnknown) return null
        return normalized.takeIf(::isPlausibleMineLabel)
    }

    private fun allowUnknownMineLabel(item: Any): Boolean {
        val simpleName = item.javaClass.simpleName
        return simpleName.contains("Button", ignoreCase = true) ||
            simpleName.contains("Entrance", ignoreCase = true) ||
            simpleName.contains("Menu", ignoreCase = true) ||
            simpleName.contains("Function", ignoreCase = true) ||
            simpleName.contains("Benefit", ignoreCase = true) ||
            simpleName.contains("Bottom", ignoreCase = true)
    }

    private fun isPlausibleMineLabel(label: String): Boolean {
        if (label.isBlank() || label.length > 8) return false
        if (label.any { it.isDigit() }) return false
        if (label.any { it.code < 0x4e00 || it.code > 0x9fff }) return false
        return !label.contains("://") &&
            !label.contains('/') &&
            !label.contains('\\') &&
            !label.contains('?') &&
            !label.contains('&') &&
            !label.contains('=')
    }

    private fun isMineEntryCandidate(item: Any): Boolean {
        val className = item.javaClass.name
        val simpleName = item.javaClass.simpleName
        if (simpleName.endsWith("FunctionButton") ||
            simpleName.endsWith("BottomButton") ||
            simpleName.endsWith("BenefitButton") ||
            simpleName == "Member"
        ) {
            return true
        }
        if (!className.contains("user_account", ignoreCase = true)) return false
        return simpleName.contains("Button", ignoreCase = true) ||
            simpleName.contains("Entrance", ignoreCase = true) ||
            simpleName.contains("Menu", ignoreCase = true) ||
            simpleName.contains("Function", ignoreCase = true) ||
            simpleName.contains("Benefit", ignoreCase = true) ||
            simpleName.contains("Bottom", ignoreCase = true) ||
            simpleName.contains("Item", ignoreCase = true)
    }

    private fun mineChildValues(source: Any?): List<Any> {
        source ?: return emptyList()
        if (!isMineTraversalCandidate(source)) return emptyList()
        return FeatureHooks.allDeclaredMethods(source.javaClass)
            .asSequence()
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.name != "getClass" &&
                    method.name.startsWith("get") &&
                    method.returnType != String::class.java &&
                    method.returnType != Int::class.javaPrimitiveType &&
                    method.returnType != Long::class.javaPrimitiveType &&
                    method.returnType != Boolean::class.javaPrimitiveType
            }
            .mapNotNull { method -> runCatching { FeatureHooks.invoke(method, source) }.getOrNull() }
            .filter { value ->
                value is Iterable<*> ||
                    value.javaClass.isArray ||
                    value.javaClass.name.contains("user_account", ignoreCase = true)
            }
            .flatMap { value ->
                when {
                    value is Iterable<*> -> value.asSequence().filterNotNull()
                    value.javaClass.isArray -> (0 until java.lang.reflect.Array.getLength(value))
                        .asSequence()
                        .mapNotNull { index -> java.lang.reflect.Array.get(value, index) }
                    else -> sequenceOf(value)
                }
            }
            .toList()
    }

    private fun mutableListsFromGetters(source: Any?): List<MutableList<*>> {
        source ?: return emptyList()
        if (!isMineTraversalCandidate(source)) return emptyList()
        val seen = PageCustomizationHostAccess.identitySet()
        return FeatureHooks.allDeclaredMethods(source.javaClass)
            .asSequence()
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.name != "getClass" &&
                    method.name.startsWith("get")
            }
            .mapNotNull { method -> runCatching { FeatureHooks.invoke(method, source) }.getOrNull() as? MutableList<*> }
            .filter { list -> seen.add(list) }
            .toList()
    }

    private fun isMineTraversalCandidate(source: Any): Boolean {
        val className = source.javaClass.name
        return className.contains("user_account", ignoreCase = true) ||
            className.contains("UserAccount", ignoreCase = true) ||
            className.contains("Account", ignoreCase = true) ||
            className.contains("BaseRecyclerAdapter", ignoreCase = true) ||
            className.contains("RecyclerAdapter", ignoreCase = true)
    }

    fun knownItems(): List<PageCustomItem> {
        return mineOptions.map { label ->
            PageCustomItem(stableMineKey(label), label)
        }
    }

    private fun Iterable<String>.toMineItems(): List<PageCustomItem> {
        val knownOrder = mineOptions
        val scanned = toList()
        val present = scanned.toSet()
        val ordered = knownOrder.filter { it in present }
            .plus(scanned.filter { it !in knownOrder })
            .distinct()
        return ordered.map { label ->
            PageCustomItem(stableMineKey(label), label)
        }
    }

    private fun stableMineKey(label: String): String {
        val hash = abs(label.hashCode()).toString(16)
        return "${PageCustomKind.Mine.id}_$hash"
    }

    fun toItems(labels: Iterable<String>): List<PageCustomItem> = labels.toMineItems()

    private const val MINE_MEMBER_LABEL = "起点会员"
}

internal enum class MineMemberFilterAction {
    KEEP,
    CLEAR_MEMBER,
    REMOVE_ITEM,
}

internal fun mineMemberFilterAction(
    type: Int?,
    hasMember: Boolean,
    hasAccountBalance: Boolean,
    hideMember: Boolean,
): MineMemberFilterAction {
    if (!hideMember || !hasMember) return MineMemberFilterAction.KEEP
    return when (type) {
        2 -> if (hasAccountBalance) MineMemberFilterAction.CLEAR_MEMBER else MineMemberFilterAction.REMOVE_ITEM
        3 -> MineMemberFilterAction.REMOVE_ITEM
        else -> MineMemberFilterAction.KEEP
    }
}
