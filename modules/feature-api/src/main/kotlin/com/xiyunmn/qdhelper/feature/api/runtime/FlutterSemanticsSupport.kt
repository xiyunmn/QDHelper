package com.xiyunmn.qdhelper.feature.api.runtime

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.WeakHashMap

/** Flutter embedding contracts verified against the host, independent of Dart AOT addresses. */
object FlutterSemanticsSupport {
    data class Node(val id: Int, val label: String, val bounds: Rect, val actions: Int, val flags: Int, val identifier: String, val parentId: Int? = null) {
        val clickable: Boolean get() = actions and 1 != 0
        val image: Boolean get() = flags and (1 shl 14) != 0
    }

    private data class Client(
        val enabled: (Activity) -> Boolean,
        val updated: ((Activity, View, List<Node>) -> Unit)?,
        val longPress: ((Activity, View, Node) -> (() -> Unit)?)?,
        val tap: ((Activity, View, Node) -> (() -> Unit)?)?,
    )

    private data class PendingTap(val node: Node, val owner: String, val client: Client)

    private class Session(activity: Activity, view: View, val bridge: Any, val renderer: Any, val channel: Any, val messageHandler: Any) {
        val activity = WeakReference(activity)
        val view = WeakReference(view)
        var nodes: List<Node> = emptyList()
        var pending: Runnable? = null
        var tap: PendingTap? = null
        var swallowed = false
        var forwardingTouch = false
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var reported = false
        val reportedPresses = mutableSetOf<String>()
    }

    private val clients = java.util.concurrent.ConcurrentHashMap<String, Client>()
    private val sessions = WeakHashMap<Activity, Session>()
    private val handler = Handler(Looper.getMainLooper())
    private var lifecycle: AutoCloseable? = null
    private var installed = false
    private var tree: Field? = null
    private var label: Field? = null
    private var rect: Field? = null
    private var actions: Field? = null
    private var identifier: Field? = null
    private var flags: Field? = null
    private var parent: Field? = null
    private var nodeId: Field? = null
    private var viewBridge: Field? = null
    private var bridgeView: Field? = null
    private var bridgeChannel: Field? = null
    private var bridgeHandler: Field? = null
    private var channelHandler: Field? = null
    private var setMessageHandler: Method? = null
    private var semantics: Method? = null
    private var action: Method? = null
    private var failed = false

    fun register(
        owner: String,
        enabled: (Activity) -> Boolean,
        updated: ((Activity, View, List<Node>) -> Unit)? = null,
        longPress: ((Activity, View, Node) -> (() -> Unit)?)? = null,
        tap: ((Activity, View, Node) -> (() -> Unit)?)? = null,
    ) {
        clients[owner] = Client(enabled, updated, longPress, tap)
    }

    fun install(module: HookModule, classLoader: ClassLoader, phase: String) {
        if (installed || failed) return
        val viewClass = FeatureHooks.findClassOrNull("io.flutter.embedding.android.FlutterView", classLoader) ?: return
        val bridgeClass = FeatureHooks.findClassOrNull("io.flutter.view.AccessibilityBridge", classLoader) ?: return
        val nodeClass = FeatureHooks.findClassOrNull("io.flutter.view.AccessibilityBridge\$SemanticsNode", classLoader) ?: return
        val rendererClass = FeatureHooks.findClassOrNull("io.flutter.embedding.engine.renderer.FlutterRenderer", classLoader) ?: return
        val engineClass = FeatureHooks.findClassOrNull("io.flutter.embedding.engine.FlutterEngine", classLoader) ?: return
        tree = FeatureHooks.findFieldOrNull(bridgeClass, "flutterSemanticsTree")
        label = FeatureHooks.findFieldOrNull(nodeClass, "label")
        rect = FeatureHooks.findFieldOrNull(nodeClass, "globalRect")
        actions = FeatureHooks.findFieldOrNull(nodeClass, "actions")
        flags = FeatureHooks.findFieldOrNull(nodeClass, "flags")
        parent = FeatureHooks.findFieldOrNull(nodeClass, "parent")
        nodeId = FeatureHooks.findFieldOrNull(nodeClass, "id")
        viewBridge = FeatureHooks.findFieldOrNull(viewClass, "accessibilityBridge")
        bridgeView = FeatureHooks.findFieldOrNull(bridgeClass, "rootAccessibilityView")
        bridgeChannel = FeatureHooks.findFieldOrNull(bridgeClass, "accessibilityChannel")
        bridgeHandler = FeatureHooks.findFieldOrNull(bridgeClass, "accessibilityMessageHandler")
        val channelClass = bridgeChannel?.type
        val handlerClass = bridgeHandler?.type
        channelHandler = channelClass?.let { FeatureHooks.findFieldOrNull(it, "handler") }
        setMessageHandler = if (channelClass != null && handlerClass != null)
            FeatureHooks.findDeclaredMethodOrNull(channelClass, "setAccessibilityMessageHandler", handlerClass) else null
        semantics = FeatureHooks.findDeclaredMethodOrNull(rendererClass, "setSemanticsEnabled", Boolean::class.javaPrimitiveType!!)
        action = FeatureHooks.findDeclaredMethodOrNull(bridgeClass, "performAction", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, android.os.Bundle::class.java)
        val update = FeatureHooks.findDeclaredMethodOrNull(bridgeClass, "updateSemantics", ByteBuffer::class.java, Array<String>::class.java, Array<ByteBuffer>::class.java)
        identifier = FeatureHooks.findFieldOrNull(nodeClass, "identifier")
        val touch = FeatureHooks.findDeclaredMethodOrNull(viewClass, "onTouchEvent", MotionEvent::class.java)
        val attachedMethod = FeatureHooks.findDeclaredMethodOrNull(viewClass, "attachToFlutterEngine", engineClass)
        if (listOf(tree, label, rect, actions, flags, viewBridge, bridgeView, semantics, action, update, touch,
                bridgeChannel, bridgeHandler, channelHandler, setMessageHandler, attachedMethod).any { it == null }) {
            failed = true
            FeatureHooks.w("Flutter semantics unavailable: embedding contract missing, phase=$phase")
            return
        }
        listOfNotNull(tree, label, rect, actions, flags, viewBridge, bridgeView, identifier, parent, nodeId,
            bridgeChannel, bridgeHandler, channelHandler).forEach { it.isAccessible = true }
        val observed = FeatureHooks.hookAfter(module, update, "flutter.semantics.update", "qdhelper.flutter.semantics.update") { chain, _ ->
            val bridge = chain.thisObject ?: return@hookAfter
            val session = sessions.values.firstOrNull { it.bridge === bridge } ?: return@hookAfter
            readNodes(session)
        }
        val touched = FeatureHooks.hookAround(module, touch, "flutter.semantics.touch", "qdhelper.flutter.semantics.touch") { chain ->
            val session = sessions.values.firstOrNull { it.view.get() === chain.thisObject }
            val event = chain.args.firstOrNull() as? MotionEvent
            if (session != null && !session.forwardingTouch && event != null && observeTouch(session, event)) true else chain.proceed()
        }
        val attached = FeatureHooks.hookAfter(module, attachedMethod, "flutter.semantics.attached", "qdhelper.flutter.semantics.attached") { chain, _ ->
            val view = chain.thisObject as? View ?: return@hookAfter
            var context = view.context
            while (context is ContextWrapper && context !is Activity) {
                val next = context.baseContext
                if (next === context) break
                context = next
            }
            (context as? Activity)?.let { activity -> handler.post { refresh(activity) } }
        }
        installed = observed && touched && attached
        if (!installed) {
            failed = true
            FeatureHooks.w("Flutter semantics unavailable: hooks incomplete, phase=$phase")
            return
        }
        lifecycle = ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("flutter-semantics"), id = "qdhelper.flutter.semantics.activities",
            resumed = { activity -> activity.window.decorView.post { refresh(activity) } },
            windowFocusChanged = { activity, focused -> if (focused) refresh(activity) else cancel(sessions[activity]) },
            paused = { activity -> release(activity) },
            destroyed = { activity -> release(activity) },
        )
        FeatureResources.register("flutter-semantics") {
            lifecycle?.close()
            lifecycle = null
            val cleanup = Runnable {
                sessions.keys.toList().forEach(::release)
                clients.clear()
                installed = false
                failed = false
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else handler.post(cleanup)
        }
        handler.post { ActivityLifecycleCallbacks.currentActivities().filter { it.hasWindowFocus() }.forEach(::refresh) }
        FeatureHooks.i("Flutter semantics bridge installed: phase=$phase")
    }

    fun nodes(activity: Activity): List<Node> = sessions[activity]?.nodes.orEmpty()

    fun refreshCurrentActivities() {
        handler.post {
            ActivityLifecycleCallbacks.currentActivities().filter { it.hasWindowFocus() }.forEach { activity ->
                refresh(activity)
                val session = sessions[activity] ?: return@forEach
                val view = session.view.get() ?: return@forEach
                clients.values.filter { it.enabled(activity) }.forEach { it.updated?.invoke(activity, view, session.nodes) }
            }
        }
    }

    fun click(activity: Activity, nodeId: Int, touchWhenNoAction: Boolean = false): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || !activity.hasWindowFocus()) return false
        val session = sessions[activity] ?: return false
        val node = session.nodes.singleOrNull { it.id == nodeId } ?: return false
        if (node.clickable) return FeatureHooks.invokeOrNull(action, session.bridge, nodeId, 16, null) == true
        if (!touchWhenNoAction || session.pending != null || session.swallowed) return false
        val view = session.view.get()?.takeIf { it.isAttachedToWindow } ?: return false
        val x = node.bounds.exactCenterX()
        val y = node.bounds.exactCenterY()
        if (x < 0 || y < 0 || x >= view.width || y >= view.height) return false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        session.forwardingTouch = true
        return try {
            val accepted = view.onTouchEvent(down)
            if (!accepted) up.action = MotionEvent.ACTION_CANCEL
            view.onTouchEvent(up) && accepted
        } finally {
            session.forwardingTouch = false
            down.recycle()
            up.recycle()
        }
    }

    private fun refresh(activity: Activity) {
        if (activity.isDestroyed || activity.isFinishing || clients.values.none { it.enabled(activity) }) {
            release(activity)
            return
        }
        val view = findFlutterView(activity.window.decorView) ?: return
        val bridge = viewBridge?.get(view) ?: return
        val engine = FeatureHooks.invokeNoArgOrNull(view, "getAttachedFlutterEngine") ?: return
        val renderer = FeatureHooks.invokeNoArgOrNull(engine, "getRenderer") ?: return
        val channel = bridgeChannel?.get(bridge) ?: return
        val messageHandler = bridgeHandler?.get(bridge) ?: return
        val previous = sessions[activity]
        val session = if (previous != null && previous.view.get() === view && previous.bridge === bridge && previous.renderer === renderer) previous
        else {
            release(activity)
            Session(activity, view, bridge, renderer, channel, messageHandler).also { sessions[activity] = it }
        }
        // Flutter clears its JNI accessibility delegate independently of the renderer's semantics flag.
        if (channelHandler?.get(channel) !== messageHandler)
            FeatureHooks.invokeOrNull(setMessageHandler, channel, messageHandler)
        FeatureHooks.invokeOrNull(semantics, renderer, true)
        readNodes(session)
    }

    private fun readNodes(session: Session) {
        val activity = session.activity.get() ?: return
        val view = session.view.get() ?: return
        if (clients.values.none { it.enabled(activity) }) {
            release(activity)
            return
        }
        val nodes = tree?.get(session.bridge) as? Map<*, *> ?: return
        val snapshot = nodes.entries.mapNotNull { (id, value) ->
            if (id !is Int || value == null) return@mapNotNull null
            val bounds = rect?.get(value) as? Rect ?: return@mapNotNull null
            if (bounds.isEmpty) return@mapNotNull null
            val nodeFlags = flags?.getInt(value) ?: return@mapNotNull null
            if (nodeFlags and (1 shl 13) != 0) return@mapNotNull null
            val parentNode = parent?.get(value)
            Node(id, label?.get(value) as? String ?: "", Rect(bounds), actions?.getInt(value) ?: 0, nodeFlags,
                identifier?.get(value) as? String ?: "", parentNode?.let { nodeId?.getInt(it) })
        }
        if (snapshot == session.nodes) return
        session.nodes = snapshot
        if (!session.reported && snapshot.isNotEmpty()) {
            session.reported = true
            val identifiers = snapshot.map { it.identifier }.filter { it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}")) }.distinct().take(32)
            FeatureHooks.i("Flutter semantics ready: nodes=${snapshot.size}, images=${snapshot.count { it.image }}, actions=${snapshot.count { it.clickable }}, identifiers=$identifiers")
        }
        clients.values.filter { it.enabled(activity) }.toList().forEach { it.updated?.invoke(activity, view, snapshot) }
    }

    private fun observeTouch(session: Session, event: MotionEvent): Boolean {
        val activity = session.activity.get() ?: return false
        val view = session.view.get() ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancel(session)
        if (session.swallowed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) session.swallowed = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel(session)
                session.downX = event.x
                session.downY = event.y
                session.downTime = event.downTime
                val candidates = session.nodes.filter { it.bounds.contains(event.x.toInt(), event.y.toInt()) }
                    .sortedBy { it.bounds.width().toLong() * it.bounds.height() }
                session.tap = candidates.firstNotNullOfOrNull { node ->
                    clients.entries.filter { it.value.enabled(activity) }.firstNotNullOfOrNull { client ->
                        client.value.tap?.invoke(activity, view, node)?.let { PendingTap(node, client.key, client.value) }
                    }
                }
                val selected = candidates.firstNotNullOfOrNull { node ->
                    clients.entries.filter { it.value.enabled(activity) }.firstNotNullOfOrNull { client ->
                        client.value.longPress?.invoke(activity, view, node)?.let { Triple(node, client, it) }
                    }
                } ?: return false
                val pending = Runnable {
                    session.pending = null
                    session.tap = null
                    if (!view.isAttachedToWindow || !activity.hasWindowFocus() || !selected.second.value.enabled(activity) ||
                        session.nodes.none { it.id == selected.first.id && it.label == selected.first.label && it.bounds == selected.first.bounds }) return@Runnable
                    val cancellation = MotionEvent.obtain(session.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, session.downX, session.downY, 0)
                    try { view.onTouchEvent(cancellation) } finally { cancellation.recycle() }
                    session.swallowed = true
                    selected.third.invoke()
                    if (session.reportedPresses.add(selected.second.key))
                        FeatureHooks.i("Flutter long press dispatched: owner=${selected.second.key}")
                }
                session.pending = pending
                handler.postDelayed(pending, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                val dx = event.x - session.downX
                val dy = event.y - session.downY
                if (dx * dx + dy * dy > slop * slop) cancel(session)
            }
            MotionEvent.ACTION_UP -> {
                val tap = session.tap
                cancel(session)
                if (tap != null && activity.hasWindowFocus() && view.isAttachedToWindow &&
                    clients[tap.owner] === tap.client && tap.client.enabled(activity) &&
                    event.eventTime - session.downTime <= ViewConfiguration.getLongPressTimeout() &&
                    tap.node.bounds.contains(event.x.toInt(), event.y.toInt()) &&
                    session.nodes.any { it == tap.node }) {
                    val perform = tap.client.tap?.invoke(activity, view, tap.node) ?: return false
                    // Flutter saw DOWN so scrolling can start normally. Cancel its recognizer
                    // before consuming UP; the host preview must never open behind our window.
                    val cancellation = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    session.forwardingTouch = true
                    try { view.onTouchEvent(cancellation) } finally {
                        session.forwardingTouch = false
                        cancellation.recycle()
                    }
                    perform()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel(session)
        }
        return false
    }

    private fun cancel(session: Session?) {
        session?.pending?.let(handler::removeCallbacks)
        session?.pending = null
        session?.tap = null
        session?.swallowed = false
    }

    private fun release(activity: Activity) {
        val session = sessions.remove(activity) ?: return
        cancel(session)
        val manager = activity.getSystemService(AccessibilityManager::class.java)
        if (manager?.isEnabled != true && channelHandler?.get(session.channel) === session.messageHandler) {
            FeatureHooks.invokeOrNull(setMessageHandler, session.channel, null)
            FeatureHooks.invokeOrNull(semantics, session.renderer, false)
        }
    }

    private fun findFlutterView(root: View): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view.javaClass.name == "io.flutter.embedding.android.FlutterView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return null
    }
}
