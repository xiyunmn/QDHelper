package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.ui.compose.component.ConsoleIcon
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class DragItem(val key: String, val title: String)
internal class DragGroup(val id: String) {
    var items: List<DragItem> = emptyList()
    var commit: (List<String>) -> Boolean = { false }
}
private data class DragBounds(val group: DragGroup, val key: String, val rect: Rect, val exclusion: Float)
internal data class DragSession(
    val group: DragGroup, val key: String, val source: List<String>, val origin: Rect,
    val gap: Float, val grab: Float, val pointer: Float, val target: Int, val destination: Float,
    val scroll: Float = 0f, val settling: Boolean = false, val settleY: Float = 0f,
) {
    val from get() = source.indexOf(key)
    val order get() = reordered(source, from, target)
}

/** Pure ordering operation shared by pointer and accessibility interaction. */
internal fun reordered(keys: List<String>, from: Int, to: Int): List<String> =
    if (from !in keys.indices || to !in keys.indices) keys
    else keys.toMutableList().apply { add(to, removeAt(from)) }

internal class SettingsDragState {
    internal var viewport = Rect.Zero
    private val bounds = mutableMapOf<Pair<DragGroup, String>, DragBounds>()
    private var session by mutableStateOf<DragSession?>(null)
    internal val active get() = session != null
    internal val dragging get() = session?.takeUnless { it.settling } != null
    internal val pointer get() = session?.pointer ?: 0f
    internal fun currentSession() = session
    internal fun updateSession(block: (DragSession) -> DragSession) { session = session?.let(block) }
    internal fun cancel() { session = null }
    internal fun remove(group: DragGroup) {
        if (session?.group === group) cancel()
        bounds.keys.removeAll { it.first === group }
    }
    internal fun register(group: DragGroup, key: String, rect: Rect, exclusion: Float) {
        bounds[group to key] = DragBounds(group, key, rect, exclusion)
    }
    internal fun unregister(group: DragGroup, key: String) { bounds.remove(group to key) }
    internal fun canStart(position: Offset): Boolean {
        val point = position + viewport.topLeft
        return !active && bounds.values.any { it.rect.contains(point) && point.x < it.rect.right - it.exclusion && it.group.items.size > 1 }
    }
    internal fun begin(position: Offset): Boolean {
        if (active) return false
        val point = position + viewport.topLeft
        val hit = bounds.values.lastOrNull { it.rect.contains(point) && point.x < it.rect.right - it.exclusion } ?: return false
        val keys = hit.group.items.map { it.key }
        val index = keys.indexOf(hit.key)
        if (index < 0 || keys.size < 2) return false
        val next = keys.getOrNull(index + 1)?.let { bounds[hit.group to it]?.rect }
        val previous = keys.getOrNull(index - 1)?.let { bounds[hit.group to it]?.rect }
        val gap = (next?.let { it.top - hit.rect.bottom } ?: previous?.let { hit.rect.top - it.bottom } ?: 0f).coerceAtLeast(0f)
        session = DragSession(hit.group, hit.key, keys, hit.rect, gap, point.y - hit.rect.top, point.y, index, hit.rect.top)
        return true
    }
    internal fun move(delta: Float = 0f, scrolled: Float = 0f) {
        val s = session?.takeUnless { it.settling } ?: return
        val pointer = s.pointer + delta
        val center = pointer - s.grab + s.origin.height / 2
        val candidates = bounds.values.filter { it.group === s.group && it.key in s.source && it.rect.bottom > viewport.top && it.rect.top < viewport.bottom }
        val nearest = candidates.minByOrNull { abs(it.rect.center.y - center) }
        val target = nearest?.let { s.source.indexOf(it.key) } ?: s.target
        val destination = nearest?.let { if (target > s.from) it.rect.bottom - s.origin.height else it.rect.top } ?: s.destination
        session = s.copy(pointer = pointer, scroll = s.scroll + scrolled, target = target, destination = destination)
    }
    internal fun originalTop(s: DragSession) = bounds[s.group to s.key]?.rect?.top ?: s.origin.top - s.scroll
    internal fun shift(group: DragGroup, key: String): Float {
        val s = session?.takeIf { it.group === group } ?: return 0f
        val index = s.source.indexOf(key)
        return when {
            key == s.key -> s.destination - originalTop(s)
            index > s.from && index <= s.target -> -(s.origin.height + s.gap)
            index < s.from && index >= s.target -> s.origin.height + s.gap
            else -> 0f
        }
    }
    internal fun isSource(group: DragGroup, key: String) = session?.let { it.group === group && it.key == key } == true
    internal fun inGroup(group: DragGroup) = session?.group === group
    internal fun preview(group: DragGroup, saved: List<String> = group.items.map { it.key }): List<String> = session?.takeIf { it.group === group }?.order ?: saved
    internal fun activeKey(group: DragGroup): String? = session?.takeIf { it.group === group }?.key
}

internal val LocalSettingsDrag = staticCompositionLocalOf<SettingsDragState> { error("Missing drag host") }

@Composable
internal fun rememberDragGroup(id: String, items: List<DragItem>, commit: (List<String>) -> Boolean): DragGroup {
    val host = LocalSettingsDrag.current
    val group = remember(id) { DragGroup(id) }
    SideEffect { group.items = items; group.commit = commit }
    val keys = items.map { it.key }
    LaunchedEffect(keys) {
        val s = host.currentSession()
        if (s?.group === group && s.source != keys) host.cancel()
    }
    DisposableEffect(group) { onDispose { host.remove(group) } }
    return group
}

@Composable
internal fun DragRow(group: DragGroup, id: String, modifier: Modifier = Modifier, trailingExclusion: Dp = 64.dp, content: @Composable () -> Unit) {
    val host = LocalSettingsDrag.current
    val exclusion = with(LocalDensity.current) { trailingExclusion.toPx() }
    val shift by animateFloatAsState(host.shift(group, id), spring(stiffness = 650f), label = "reorder-placement")
    val source = host.isSource(group, id)
    val blurred by animateFloatAsState(if (host.inGroup(group)) if (source) 2.5f else 1f else 0f, tween(120), label = "reorder-blur")
    DisposableEffect(group, id) { onDispose { host.unregister(group, id) } }
    Box(modifier.fillMaxWidth().onGloballyPositioned {
        // The outer untransformed slot supplies hit testing; only its child is animated.
        host.register(group, id, it.boundsInWindow(clipBounds = false), exclusion)
    }.semantics {
        customActions = listOf(-1 to "上移", 1 to "下移").map { (direction, label) ->
            CustomAccessibilityAction(label) {
                val keys = group.items.map { it.key }; val from = keys.indexOf(id); val to = from + direction
                !host.active && to in keys.indices && group.commit(reordered(keys, from, to))
            }
        }
    }) {
        Box(Modifier.graphicsLayer {
            // On commit the layout itself assumes the preview positions; do not animate twice.
            translationY = if (host.inGroup(group)) shift else 0f
            alpha = if (source) .18f else 1f
        }.blur(blurred.dp)) { content() }
    }
}

@Composable
internal fun SettingsDragHost(scroll: LazyListState, content: @Composable () -> Unit) {
    val state = remember { SettingsDragState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    fun finish(save: Boolean) {
        val s = state.currentSession()?.takeUnless { it.settling } ?: return
        val target = if (save) s.destination else state.originalTop(s)
        state.updateSession { it.copy(settling = true, settleY = it.pointer - it.grab, destination = target, target = if (save) it.target else it.from) }
        scope.launch {
            val animation = Animatable(s.pointer - s.grab)
            animation.animateTo(target, tween(180)) {
                if (state.currentSession()?.group === s.group) state.updateSession { it.copy(settleY = value) }
            }
            if (state.currentSession()?.group === s.group) {
                if (save && s.order != s.source) s.group.commit(s.order)
                state.cancel()
            }
        }
    }
    LaunchedEffect(state.dragging) {
        if (!state.dragging) return@LaunchedEffect
        val band = with(density) { 64.dp.toPx() }
        val speed = with(density) { 420.dp.toPx() }
        var previous = withFrameNanos { it }
        while (state.dragging) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.04f); previous = now
            val y = state.pointer
            val factor = when {
                y < state.viewport.top + band -> -((state.viewport.top + band - y) / band).coerceIn(0f, 1f)
                y > state.viewport.bottom - band -> ((y - state.viewport.bottom + band) / band).coerceIn(0f, 1f)
                else -> 0f
            }
            state.move(scrolled = if (factor != 0f) scroll.scrollBy(factor * speed * seconds) else 0f)
        }
    }
    CompositionLocalProvider(LocalSettingsDrag provides state) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { state.viewport = it.boundsInWindow() }.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!state.canStart(down.position)) return@awaitEachGesture
                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                if (!state.begin(longPress.position)) return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                var dropped = false
                while (state.dragging) {
                    // Consume movement before child scrollables; switches/buttons retain short taps.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) { change.consume(); dropped = true; break }
                    state.move(change.positionChange().y)
                    change.consume()
                }
                finish(dropped)
            }
        }) {
            content()
            state.currentSession()?.let { s ->
                val y = if (s.settling) s.settleY else s.pointer - s.grab
                val scale by animateFloatAsState(if (s.settling) 1f else 1.025f, tween(120), label = "reorder-lift")
                Surface(
                    Modifier.offset { IntOffset((s.origin.left - state.viewport.left).roundToInt(), (y - state.viewport.top).roundToInt()) }
                        .width(with(density) { s.origin.width.toDp() }).height(with(density) { s.origin.height.toDp() })
                        .graphicsLayer { scaleX = scale; scaleY = scale }.clearAndSetSemantics {},
                    shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 12.dp,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConsoleIcon("drag")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s.group.items.firstOrNull { it.key == s.key }?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                            Text("放到第 ${s.target + 1} 位 · 共 ${s.source.size} 项", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
