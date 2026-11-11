package com.xiyunmn.qdhelper.feature.contenttools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.ViewLongPressCallbacks
import com.xiyunmn.qdhelper.feature.contenttools.media.MediaExportPanel
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.foundation.runtime.RuntimeCapabilities
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.MediaExportTargetKeys
import com.xiyunmn.qdhelper.shared.ui.dp
import com.xiyunmn.qdhelper.shared.ui.setPressedAlpha
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

object CommentVoiceExportFeature : InstallableFeature {
    override val name = "CommentVoiceExportFeature"
    private const val VOICE_VIEW = "com.qidian.QDReader.ui.view.chapter_review.VoicePlayerView"
    private const val MENU_TAG = "qdhelper.comment.voice.export"
    private val targets = Collections.synchronizedMap(WeakHashMap<View, Target>())
    private val menuButtons = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val activeMenuContent = ThreadLocal<MenuContent?>()
    private var lifecycle: AutoCloseable? = null
    private var failureLogged = false
    private const val CAPABILITY = "content.comment-voice-export"

    private class Target(val audioUrl: String) {
        var registration: AutoCloseable? = null
    }

    private class MenuContent(val source: View, val target: Target) {
        var menuAppeared = false
    }

    private data class PopupShape(val listener: Field, val callback: Method)

    init {
        FeatureResources.register("comment-voice-export") {
            clearTargets()
            val buttons = synchronized(menuButtons) { menuButtons.keys.toList().also { menuButtons.clear() } }
            buttons.forEach { button -> button.post { (button.parent as? ViewGroup)?.removeView(button) } }
            lifecycle?.close()
            lifecycle = null
            activeMenuContent.remove()
            failureLogged = false
            MediaExportPanel.closeAll()
            RuntimeCapabilities.removePrefix(CAPABILITY)
        }
    }

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        if (!HostSymbols.isMainProcess(processName)) return
        val context = HostSessionStore.contextOrNull() ?: return
        if (!SettingsRuntime.isCommentVoiceExportEnabled(context)) return
        val containerId = context.resources.getIdentifier("container", "id", context.packageName)
        if (containerId == 0) {
            RuntimeCapabilities.record(CAPABILITY, "章评语音导出", false, "语音控件未匹配")
            return
        }
        val methods = (
            HostTargetResolver.resolveMethods(context, classLoader, processName, MediaExportTargetKeys.voiceListBind) +
                HostTargetResolver.resolveMethods(context, classLoader, processName, MediaExportTargetKeys.voiceDetailBind)
            ).distinct()
        if (methods.isEmpty()) {
            RuntimeCapabilities.record(CAPABILITY, "章评语音导出", false, "章评语音绑定入口未匹配")
            return
        }
        installMenuHook(module, classLoader)
        if (lifecycle == null) {
            lifecycle = ActivityLifecycleCallbacks.subscribe(
                owner = OwnerId("content-tools"),
                id = "qdhelper.comment.voice.export.lifecycle",
                resumed = { if (!SettingsRuntime.isCommentVoiceExportEnabled(it)) clearTargets() },
                destroyed = { activity ->
                    val removed = synchronized(targets) {
                        targets.entries.filter { it.key.context.activity() === activity }
                            .map { it.key }.mapNotNull(targets::remove)
                    }
                    removed.forEach { it.registration?.close() }
                },
            )
        }
        var installed = 0
        methods.forEach { method ->
            val getAudioUrl = method.parameterTypes.first().methods.singleOrNull {
                it.name == "getAudioUrl" && it.parameterCount == 0 && it.returnType == String::class.java &&
                    !Modifier.isStatic(it.modifiers)
            } ?: return@forEach
            val field = generateSequence(method.declaringClass) { it.superclass }
                .flatMap { FeatureHooks.allDeclaredFields(it).asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) && it.type.name == VOICE_VIEW }
                .singleOrNull() ?: return@forEach
            if (FeatureHooks.hookAfter(
                    module, method, "comment.voiceExport:holder bind",
                    "qdhelper.comment.voice.export.bind." + method.toGenericString().hashCode().toUInt().toString(16),
                ) { chain, _ ->
                    if (Looper.myLooper() != Looper.getMainLooper() || !SettingsRuntime.isCommentVoiceExportEnabled(context)) return@hookAfter
                    val holder = chain.thisObject ?: return@hookAfter
                    try {
                        val voice = FeatureHooks.read(field, holder) as? View ?: return@hookAfter
                        val container = voice.findViewById<View>(containerId) ?: return@hookAfter
                        targets.remove(container)?.registration?.close()
                        val model = chain.args.firstOrNull() ?: return@hookAfter
                        val url = (FeatureHooks.invoke(getAudioUrl, model) as? String).orEmpty()
                        attach(container, url)
                    } catch (error: Exception) {
                        logFailure(error)
                    }
                }
            ) installed++
        }
        FeatureHooks.i("Comment voice export installed: phase=$phase, holders=$installed")
        RuntimeCapabilities.record(CAPABILITY, "章评语音导出", installed > 0,
            if (installed > 0) "已接入 $installed 个章评语音绑定入口" else "章评语音入口未安装")
    }

    private fun attach(source: View, audioUrl: String) {
        val state = Target(audioUrl)
        val result = ViewLongPressCallbacks.register(
            source,
            LongPressRegistration(OwnerId("comment-voice-export"), "voice", priority = 50),
        ) { target, proceed ->
            val view = target as View
            if (!SettingsRuntime.isCommentVoiceExportEnabled(view.context)) {
                targets.remove(view)?.registration?.close()
                return@register LongPressResult.Pass
            }
            if (targets[view] !== state || !view.isShown) return@register LongPressResult.Pass
            val content = MenuContent(view, state)
            val previous = activeMenuContent.get()
            activeMenuContent.set(content)
            val originalResult = try {
                proceed()
            } finally {
                if (previous == null) activeMenuContent.remove() else activeMenuContent.set(previous)
            }
            if (!content.menuAppeared) {
                showPanel(view, state)
                LongPressResult.Return(true)
            } else {
                LongPressResult.Return(originalResult)
            }
        }
        if (result is LongPressRegistrationResult.Registered) {
            state.registration = result.registration
            targets[source] = state
        }
    }

    private fun installMenuHook(module: HookModule, classLoader: ClassLoader) {
        val popupClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.CHAPTER_COMMENT_POP_VIEW, classLoader)
            ?: return
        val listeners = FeatureHooks.allDeclaredFields(popupClass).mapNotNull { field ->
            if (!field.type.isInterface || Modifier.isStatic(field.modifiers)) return@mapNotNull null
            val callback = field.type.methods.filter { Modifier.isAbstract(it.modifiers) }.singleOrNull()
                ?.takeIf {
                    it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                } ?: return@mapNotNull null
            PopupShape(field, callback)
        }
        val shape = listeners.singleOrNull()
        val legacy = listOf("int", "int", "boolean", "boolean", "int", "int", "int", "int", "boolean")
        FeatureHooks.allDeclaredMethods(popupClass).filter {
            val parameters = it.parameterTypes.map { type -> type.name }
            it.name == "initShow" && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                (parameters == legacy || parameters == legacy + HostSymbols.Classes.SPOILER_ACTION)
        }.forEach { method ->
            FeatureHooks.hookAfter(
                module, method, "comment.voiceExport:popup initializer",
                "qdhelper.comment.voice.export.popup." + method.parameterCount,
            ) { chain, _ ->
                val popup = chain.thisObject as? View ?: return@hookAfter
                val content = activeMenuContent.get()
                descendants(popup).firstOrNull { it.tag == MENU_TAG }?.visibility = View.GONE
                if (!SettingsRuntime.isCommentVoiceExportEnabled(popup.context)) return@hookAfter
                if (content == null) return@hookAfter
                content.menuAppeared = true
                if (shape == null) return@hookAfter
                try {
                    injectMenu(popup, content, shape)
                } catch (error: Exception) {
                    logFailure(error)
                }
            }
        }
    }

    private fun injectMenu(popup: View, content: MenuContent, shape: PopupShape) {
        val container = descendants(popup).filterIsInstance<HorizontalScrollView>()
            .mapNotNull { it.getChildAt(0) as? LinearLayout }
            .firstOrNull { it.orientation == LinearLayout.HORIZONTAL } ?: return
        val existing = (0 until container.childCount).map(container::getChildAt).firstOrNull { it.tag == MENU_TAG }
        val button = existing ?: run {
            val template = (0 until container.childCount).map(container::getChildAt).filterIsInstance<LinearLayout>()
                .firstOrNull { item ->
                    descendants(item).any { it is TextView } && descendants(item).any { it is ImageView }
                } ?: return
            val label = descendants(template).filterIsInstance<TextView>().first()
            val icon = descendants(template).filterIsInstance<ImageView>().first()
            LinearLayout(popup.context).apply {
                tag = MENU_TAG
                id = View.generateViewId()
                orientation = template.orientation
                gravity = template.gravity
                setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
                contentDescription = "导出语音"
                setPressedAlpha()
                addView(ExportIcon(context, label.currentTextColor), copyParams(icon.layoutParams, context.dp(20), context.dp(20)))
                addView(TextView(context).apply {
                    text = "导出语音"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, label.textSize)
                    setTextColor(label.currentTextColor)
                    typeface = label.typeface
                    gravity = label.gravity
                    includeFontPadding = label.includeFontPadding
                    letterSpacing = label.letterSpacing
                    setPadding(label.paddingLeft, label.paddingTop, label.paddingRight, label.paddingBottom)
                }, copyParams(label.layoutParams))
                container.addView(this, copyParams(template.layoutParams))
            }
        }
        button.visibility = View.VISIBLE
        menuButtons[button] = true
        val source = WeakReference(content.source)
        val state = content.target
        button.setOnClickListener {
            val view = source.get() ?: return@setOnClickListener
            if (!SettingsRuntime.isCommentVoiceExportEnabled(view.context) || targets[view] !== state) return@setOnClickListener
            try {
                val listener = FeatureHooks.read(shape.listener, popup) ?: return@setOnClickListener
                FeatureHooks.invoke(shape.callback, listener, button.id)
                showPanel(view, state)
            } catch (error: Exception) {
                logFailure(error)
            }
        }
    }

    private fun showPanel(source: View, state: Target) {
        if (!SettingsRuntime.isCommentVoiceExportEnabled(source.context) || targets[source] !== state) return
        val activity = source.context.activity()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        if (state.audioUrl.isBlank()) {
            Toast.makeText(activity, "当前章评没有可导出的语音地址", Toast.LENGTH_SHORT).show()
            return
        }
        val weakSource = WeakReference(source)
        MediaExportPanel.showVoice(activity, source, state.audioUrl) {
            val current = weakSource.get()
            current != null && targets[current] === state &&
                SettingsRuntime.isCommentVoiceExportEnabled(current.context)
        }
    }

    private fun clearTargets() {
        val previous = synchronized(targets) { targets.values.toList().also { targets.clear() } }
        previous.forEach { it.registration?.close() }
    }

    private fun logFailure(error: Exception) {
        if (!failureLogged) {
            failureLogged = true
            FeatureHooks.w("Comment voice export failed: " + error.javaClass.simpleName)
        }
    }

    private fun Context.activity(): Activity? {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return current as? Activity
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun copyParams(
        source: ViewGroup.LayoutParams?,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ): LinearLayout.LayoutParams =
        (source as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
            ?: LinearLayout.LayoutParams(width, height)

    private class ExportIcon(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val unit = resources.displayMetrics.density
            val x = width / 2f
            val y = height / 2f
            canvas.drawLine(x, y - 7 * unit, x, y + 3 * unit, paint)
            canvas.drawLine(x - 4 * unit, y - unit, x, y + 3 * unit, paint)
            canvas.drawLine(x + 4 * unit, y - unit, x, y + 3 * unit, paint)
            canvas.drawLine(x - 7 * unit, y + 4 * unit, x - 7 * unit, y + 7 * unit, paint)
            canvas.drawLine(x - 7 * unit, y + 7 * unit, x + 7 * unit, y + 7 * unit, paint)
            canvas.drawLine(x + 7 * unit, y + 7 * unit, x + 7 * unit, y + 4 * unit, paint)
        }
    }
}

