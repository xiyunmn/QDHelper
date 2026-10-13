package com.xiyunmn.qdhelper.feature.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.host.api.target.ReaderTargetKeys
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.feature.api.runtime.ViewLongPressCallbacks
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.capability.freecopy.FreeCopyDialog
import com.xiyunmn.qdhelper.shared.ui.dp
import com.xiyunmn.qdhelper.shared.ui.setPressedAlpha
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

object FreeCopyFeature : InstallableFeature {
    override val name: String = "FreeCopyFeature"

    private const val COPY_MENU_TAG = "qdhelper.free_copy.menu_button"
    private val menuCopyTargets = Collections.synchronizedMap(WeakHashMap<View, AutoCloseable>())
    private data class MenuContent(val source: View, val text: CharSequence)
    private val activeMenuContent = ThreadLocal<MenuContent?>()
    private var menuFailureLogged = false

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) {
            return
        }
        val messageTextClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.MESSAGE_TEXT_VIEW, classLoader)
        if (messageTextClass == null) {
            FeatureHooks.w("Free copy skipped: MessageTextView missing, phase=$phase")
            return
        }
        installMenuInjectionHook(module, classLoader)
        val methods = HostTargetResolver.resolveMethods(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = ReaderTargetKeys.chapterCommentCopyBindMethods,
        )
        var installedHooks = 0
        methods.forEach { method ->
            val hookId = "qdhelper.reader.free_copy.${Integer.toHexString(method.toGenericString().hashCode())}"
            if (FeatureHooks.hookAfter(
                    module = module,
                    executable = method,
                    feature = "reader.freeCopy:${method.declaringClass.name}.${method.name}",
                    id = hookId,
                ) { chain, _ ->
                    attachCopyTargets(chain.thisObject, messageTextClass)
                }
            ) {
                installedHooks += 1
            }
        }
        FeatureHooks.i(
            "Free copy phase finished: phase=$phase, process=$processName, " +
                "targets=${methods.size}, installedHooks=$installedHooks",
        )
    }

    private fun attachCopyTargets(holder: Any?, messageTextClass: Class<*>) {
        if (holder == null) return
        var current: Class<*>? = holder.javaClass
        while (current != null && current != Any::class.java) {
            FeatureHooks.allDeclaredFields(current).forEach { field ->
                if (!messageTextClass.isAssignableFrom(field.type)) return@forEach
                runCatching {
                    val textView = FeatureHooks.read(field, holder) as? TextView ?: return@runCatching
                    if (!textView.text.isNullOrBlank()) {
                        registerMenuCopyTarget(textView)
                    }
                }.onFailure { throwable ->
                    FeatureHooks.w(
                        "Free copy field access failed: ${current.name}#${field.name}, " +
                            "error=${throwable.javaClass.name}",
                    )
                }
            }
            current = current.superclass
        }
    }

    private fun registerMenuCopyTarget(textView: TextView) {
        synchronized(menuCopyTargets) { menuCopyTargets.remove(textView) }?.close()
        val result = ViewLongPressCallbacks.register(
            textView,
            LongPressRegistration(OwnerId("reader-free-copy"), "comment", priority = 50),
        ) { target, proceed ->
            val view = target as TextView
            if (!SettingsRuntime.isFreeCopyEnabled(HostSessionStore.contextOrNull())) {
                synchronized(menuCopyTargets) { menuCopyTargets.remove(view) }?.close()
                return@register LongPressResult.Pass
            }
            val content = view.text?.takeIf { it.isNotBlank() } ?: return@register LongPressResult.Pass
            if (!SettingsRuntime.isFreeCopyMenuButtonMode(HostSessionStore.contextOrNull())) {
                FreeCopyDialog.show(view, content)
                return@register LongPressResult.Return(true)
            }
            val previous = activeMenuContent.get()
            activeMenuContent.set(MenuContent(view, android.text.SpannedString(content)))
            val originalResult = try {
                proceed()
            } finally {
                if (previous == null) activeMenuContent.remove() else activeMenuContent.set(previous)
            }
            LongPressResult.Return(originalResult)
        }
        if (result is LongPressRegistrationResult.Registered) {
            menuCopyTargets[textView] = result.registration
        }
    }

    private fun installMenuInjectionHook(module: HookModule, classLoader: ClassLoader) {
        val popupClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.CHAPTER_COMMENT_POP_VIEW, classLoader)
            ?: return
        val initMethods = FeatureHooks.allDeclaredMethods(popupClass).filter { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                ChapterCommentMenuShape.matches(method.name, method.parameterTypes.map { it.name })
        }
        if (initMethods.isEmpty()) {
            FeatureHooks.w("Free copy menu injection skipped: popup initializer shape missing")
            return
        }
        initMethods.forEach { initMethod ->
            FeatureHooks.hookAfter(
                module = module,
                executable = initMethod,
                feature = "reader.freeCopy:chapter-comment popup initializer/${initMethod.parameterCount}",
                id = if (initMethod.parameterCount == 9) "qdhelper.reader.free_copy.popup_init"
                    else "qdhelper.reader.free_copy.popup_init_spoiler",
            ) { chain, _ ->
                val popup = chain.thisObject as? View ?: return@hookAfter
                val content = activeMenuContent.get() ?: return@hookAfter
                if (SettingsRuntime.isFreeCopyEnabled(HostSessionStore.contextOrNull()) &&
                    SettingsRuntime.isFreeCopyMenuButtonMode(HostSessionStore.contextOrNull())) {
                    runCatching { injectCopyMenuButton(popup, content) }.onFailure {
                        if (!menuFailureLogged) {
                            menuFailureLogged = true
                            FeatureHooks.w("Free copy menu injection failed: ${it.javaClass.name}")
                        }
                    }
                }
            }
        }
    }

    private fun injectCopyMenuButton(popup: View, content: MenuContent) {
        val container = checkNotNull(findMenuContainer(popup)) { "Copy menu container missing" }
        val existing = (0 until container.childCount).map(container::getChildAt).firstOrNull { it.tag == COPY_MENU_TAG }
        if (existing != null) {
            bindCopyMenuClick(existing, popup, content)
            return
        }
        val template = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<LinearLayout>()
            .firstOrNull { item -> findTextView(item) { it == "分享" } != null }
            ?: (0 until container.childCount)
                .map { container.getChildAt(it) }
                .filterIsInstance<LinearLayout>()
                .firstOrNull { item -> findTextView(item) { true } != null && findImageView(item) != null }
            ?: error("Copy menu template missing")
        val templateLabel = checkNotNull(findTextView(template) { true }) { "Copy menu label missing" }

        val templateIcon = findImageView(template)
        val textColor = templateLabel.currentTextColor
        val button = LinearLayout(popup.context).apply {
            tag = COPY_MENU_TAG
            id = View.generateViewId()
            orientation = template.orientation
            gravity = template.gravity
            setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
            contentDescription = "复制"
            isClickable = true
            setPressedAlpha()
            addView(
                CopyMenuIconView(context, textColor),
                copyLinearLayoutParams(templateIcon?.layoutParams, context.dp(20), context.dp(20)),
            )
            addView(TextView(context).apply {
                text = "复制"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, templateLabel.textSize)
                typeface = templateLabel.typeface
                gravity = templateLabel.gravity
                setTextColor(textColor)
                includeFontPadding = templateLabel.includeFontPadding
                letterSpacing = templateLabel.letterSpacing
                setPadding(
                    templateLabel.paddingLeft,
                    templateLabel.paddingTop,
                    templateLabel.paddingRight,
                    templateLabel.paddingBottom,
                )
            }, copyLinearLayoutParams(
                templateLabel.layoutParams,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        bindCopyMenuClick(button, popup, content)
        container.addView(button, copyLinearLayoutParams(template.layoutParams))
    }

    private fun bindCopyMenuClick(button: View, popup: View, content: MenuContent) {
        button.setOnClickListener {
            if (!SettingsRuntime.isFreeCopyEnabled(HostSessionStore.contextOrNull()) ||
                !SettingsRuntime.isFreeCopyMenuButtonMode(HostSessionStore.contextOrNull())) return@setOnClickListener
            FreeCopyDialog.show(content.source, content.text, beforeShow = {
                dismissHostPopupThroughListener(popup, button.id)
            })
        }
    }

    private fun dismissHostPopupThroughListener(popup: View, clickedId: Int) {
        runCatching {
            val field = FeatureHooks.allDeclaredFields(popup.javaClass).singleOrNull { candidate ->
                val methods = candidate.type.methods.filter { method -> Modifier.isAbstract(method.modifiers) }
                candidate.type.isInterface && methods.size == 1 &&
                    methods.single().returnType == Void.TYPE &&
                    methods.single().parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            } ?: return
            val listener = FeatureHooks.read(field, popup) ?: return
            val callback = field.type.methods.single { method ->
                Modifier.isAbstract(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }
            FeatureHooks.invoke(callback, listener, clickedId)
        }.onFailure { throwable ->
            FeatureHooks.w("Free copy popup dismiss callback failed: ${throwable.javaClass.name}")
        }
    }

    private fun findTextView(root: View, predicate: (String) -> Boolean): TextView? {
        if (root is TextView && predicate(root.text?.toString().orEmpty())) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextView(root.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun findMenuContainer(root: View): LinearLayout? {
        if (root is HorizontalScrollView && root.childCount == 1) {
            val child = root.getChildAt(0) as? LinearLayout
            if (child != null && child.orientation == LinearLayout.HORIZONTAL) return child
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findMenuContainer(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findImageView(root: View): ImageView? {
        if (root is ImageView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findImageView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun copyLinearLayoutParams(
        source: ViewGroup.LayoutParams?,
        fallbackWidth: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        fallbackHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ): LinearLayout.LayoutParams {
        val params = source as? LinearLayout.LayoutParams
        return if (params != null) {
            LinearLayout.LayoutParams(params)
        } else {
            LinearLayout.LayoutParams(fallbackWidth, fallbackHeight)
        }
    }

    private class CopyMenuIconView(context: android.content.Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = resources.displayMetrics.density
            val left = (width - 15f * unit) / 2f
            val top = (height - 16f * unit) / 2f
            val radius = 1.5f * unit
            canvas.drawRoundRect(RectF(left + 4f * unit, top, left + 15f * unit, top + 12f * unit), radius, radius, paint)
            canvas.drawRoundRect(RectF(left, top + 4f * unit, left + 11f * unit, top + 16f * unit), radius, radius, paint)
        }
    }
}
