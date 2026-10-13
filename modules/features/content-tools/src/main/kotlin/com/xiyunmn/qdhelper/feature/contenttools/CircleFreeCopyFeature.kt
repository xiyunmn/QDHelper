package com.xiyunmn.qdhelper.feature.contenttools

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.host.api.target.CircleTargetKeys
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.feature.api.runtime.ViewLongPressCallbacks
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.capability.freecopy.FreeCopyDialog
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object CircleFreeCopyFeature : InstallableFeature {
    override val name: String = "CircleFreeCopyFeature"

    private const val DIALOG_TITLE = "书友圈内容"
    private val copyTargets = Collections.synchronizedMap(WeakHashMap<View, CopyTarget>())
    private val detailContentByAdapter = Collections.synchronizedMap(WeakHashMap<Any, StyledCopyContent>())
    private val postAccessors = ConcurrentHashMap<Class<*>, PostAccessors>()
    private val richTextAccessors = ConcurrentHashMap<Class<*>, RichTextAccessors>()
    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return

        val listBind = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = CircleTargetKeys.listBind,
        )
        val listInstalled = FeatureHooks.hookAfter(
            module = module,
            executable = listBind,
            feature = "circle.freeCopy:list holder bind",
            id = "qdhelper.circle.free_copy.list_bind",
        ) { chain, _ ->
            attachListTarget(chain.thisObject, chain.args.getOrNull(0))
        }

        val detailDataMethod = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = CircleTargetKeys.detailData,
        )
        val detailDataInstalled = FeatureHooks.hookAfter(
            module = module,
            executable = detailDataMethod,
            feature = "circle.freeCopy:detail complete item list",
            id = "qdhelper.circle.free_copy.detail_data",
        ) { chain, _ ->
            attachDetailData(chain.thisObject, chain.args.getOrNull(0) as? List<*>)
        }

        val detailMethods = HostTargetResolver.resolveMethods(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = CircleTargetKeys.detailBindMethods,
        )
        var detailInstalled = 0
        detailMethods.forEach { method ->
            if (FeatureHooks.hookAfter(
                    module = module,
                    executable = method,
                    feature = "circle.freeCopy:${method.declaringClass.name} detail bind",
                    id = "qdhelper.circle.free_copy.detail_bind.${method.declaringClass.simpleName}",
                ) { chain, _ ->
                    attachDetailTargets(chain.thisObject, classLoader)
                }
            ) {
                detailInstalled += 1
            }
        }
        FeatureHooks.i(
            "Circle free copy phase finished: phase=$phase, process=$processName, " +
                "listInstalled=$listInstalled, detailDataInstalled=$detailDataInstalled, " +
                "detailTargets=${detailMethods.size}, " +
                "detailInstalled=$detailInstalled",
        )
    }

    private fun attachListTarget(holder: Any?, model: Any?) {
        if (holder == null || model == null) return
        val targetView = findViewField(holder, "layoutComment") ?: return
        val accessors = postAccessors.getOrPut(model.javaClass) {
            PostAccessors(
                title = findStringGetter(model.javaClass, "getTitle"),
                body = findStringGetter(model.javaClass, "getBody"),
            )
        }
        val content = StyledCopyContent.from(
            title = accessors.title?.let { getter -> FeatureHooks.invokeOrNull(getter, model) as? String },
            body = accessors.body?.let { getter -> FeatureHooks.invokeOrNull(getter, model) as? String },
        )
        if (content.isBlank()) {
            unregisterTarget(targetView)
            return
        }
        content.updateAppearance(
            titleView = findViewField(holder, "tvTitle") as? TextView,
            bodyView = findViewField(holder, "forum_body") as? TextView,
        )
        registerTarget(targetView, content, enableLongClick = false)
    }

    private fun attachDetailData(adapter: Any?, items: List<*>?) {
        if (adapter == null || items == null) return
        val text = extractDetailText(items)
        synchronized(detailContentByAdapter) {
            if (text.isBlank()) {
                detailContentByAdapter.remove(adapter)
            } else {
                detailContentByAdapter[adapter] = text
            }
        }
    }

    private fun extractDetailText(items: List<*>): StyledCopyContent {
        var title = ""
        val bodyParts = ArrayList<String>()
        items.forEach { item ->
            item ?: return@forEach
            val accessors = richTextAccessors.getOrPut(item.javaClass) {
                RichTextAccessors(
                    type = findNoArgMethod(item.javaClass, "getType", Int::class.javaPrimitiveType),
                    text = findStringGetter(item.javaClass, "getText"),
                )
            }
            val type = (accessors.type?.let { getter -> FeatureHooks.invokeOrNull(getter, item) } as? Number)?.toInt()
            val rawText = accessors.text?.let { getter -> FeatureHooks.invokeOrNull(getter, item) as? String }
            when (type) {
                10 -> if (title.isBlank()) title = CircleRichTextFilter.toPlainText(rawText)
                1 -> CircleRichTextFilter.toPlainText(rawText)
                    .takeIf { it.isNotBlank() }
                    ?.let(bodyParts::add)
            }
        }
        return StyledCopyContent(title.trim(), bodyParts.joinToString("\n\n").trim())
    }

    private fun attachDetailTargets(holder: Any?, classLoader: ClassLoader) {
        if (holder == null) return
        val targetClassName = when (holder.javaClass.name) {
            QDReaderHost.Classes.RICH_TEXT_TITLE_VIEW_HOLDER -> QDReaderHost.Classes.MESSAGE_TEXT_VIEW
            QDReaderHost.Classes.RICH_TEXT_HTML_VIEW_HOLDER -> QDReaderHost.Classes.RICH_CONTENT_TEXT_VIEW
            else -> return
        }
        val targetClass = FeatureHooks.findClassOrNull(targetClassName, classLoader) ?: return
        val targetViews = allFields(holder.javaClass)
            .filter { field -> targetClass.isAssignableFrom(field.type) }
            .mapNotNull { field ->
                runCatching {
                    FeatureHooks.read(field, holder) as? TextView
                }.onFailure { throwable ->
                    FeatureHooks.w(
                        "Circle free copy detail field access failed: class=${holder.javaClass.name}, " +
                            "type=${field.type.name}, error=${throwable.javaClass.name}",
                    )
                }.getOrNull()
            }
            .toList()
        if (targetViews.isEmpty()) return

        val adapter = invokeNoArg(holder, "getBindingAdapter")
        val content = adapter
            ?.takeIf { it.javaClass.name == QDReaderHost.Classes.CIRCLE_POST_DETAIL_ADAPTER }
            ?.let { circleAdapter ->
                synchronized(detailContentByAdapter) { detailContentByAdapter[circleAdapter] }
            }
        targetViews.forEach { textView ->
            if (content == null || content.isBlank() ||
                !SettingsRuntime.isCircleFreeCopyEnabled(HostSessionStore.contextOrNull())
            ) {
                unregisterTarget(textView)
            } else {
                runCatching {
                    if (holder.javaClass.name == QDReaderHost.Classes.RICH_TEXT_TITLE_VIEW_HOLDER) {
                        content.updateAppearance(titleView = textView, bodyView = null)
                    } else {
                        content.updateAppearance(titleView = null, bodyView = textView)
                    }
                    registerTarget(textView, content, enableLongClick = true)
                }.onFailure { throwable ->
                    FeatureHooks.w(
                        "Circle free copy detail target registration failed: " +
                            "class=${holder.javaClass.name}, error=${throwable.javaClass.name}",
                    )
                }
            }
        }
    }

    private fun registerTarget(view: View, content: StyledCopyContent, enableLongClick: Boolean) {
        unregisterTarget(view)
        synchronized(copyTargets) {
            val result = ViewLongPressCallbacks.register(
                view,
                LongPressRegistration(
                    OwnerId("circle-free-copy"),
                    "content",
                    priority = 50,
                    enableLongClick = enableLongClick,
                ),
            ) { target, _ ->
                val targetView = target as View
                val current = copyTargets[targetView] ?: return@register LongPressResult.Pass
                if (!SettingsRuntime.isCircleFreeCopyEnabled(HostSessionStore.contextOrNull())) {
                    unregisterTarget(targetView)
                    return@register LongPressResult.Pass
                }
                val styled = current.content.styledText()
                if (styled.isBlank()) return@register LongPressResult.Pass
                FreeCopyDialog.show(targetView, styled, DIALOG_TITLE)
                LongPressResult.Return(true)
            }
            if (result is LongPressRegistrationResult.Registered) {
                copyTargets[view] = CopyTarget(content, result.registration)
            }
        }
    }

    private fun unregisterTarget(view: View) {
        val removed = synchronized(copyTargets) { copyTargets.remove(view) } ?: return
        removed.registration.close()
    }

    private fun findStringGetter(type: Class<*>, name: String): Method? {
        return FeatureHooks.publicMethodsNamed(type, name, parameterCount = 0)
            .singleOrNull { method -> method.returnType == String::class.java }
    }

    private fun findNoArgMethod(type: Class<*>, name: String, returnType: Class<*>?): Method? {
        return FeatureHooks.publicMethodsNamed(type, name, parameterCount = 0)
            .singleOrNull { method -> returnType == null || method.returnType == returnType }
    }

    private fun invokeNoArg(owner: Any, name: String): Any? =
        FeatureHooks.invokeNoArgOrNull(owner, name)

    private fun findViewField(owner: Any, resourceEntryName: String): View? {
        allFields(owner.javaClass).forEach { field ->
            if (!View::class.java.isAssignableFrom(field.type)) return@forEach
            val view = FeatureHooks.read(field, owner) as? View ?: return@forEach
            val entryName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (entryName == resourceEntryName) return view
        }
        return null
    }

    private fun allFields(type: Class<*>): Sequence<java.lang.reflect.Field> {
        return generateSequence(type) { current -> current.superclass }
            .takeWhile { current -> current != Any::class.java }
            .flatMap { current -> FeatureHooks.allDeclaredFields(current).asSequence() }
    }

    private data class CopyTarget(
        val content: StyledCopyContent,
        val registration: AutoCloseable,
    )

    private data class PostAccessors(
        val title: Method?,
        val body: Method?,
    )

    private data class RichTextAccessors(
        val type: Method?,
        val text: Method?,
    )

    private class StyledCopyContent(
        val title: String,
        val body: String,
    ) {
        @Volatile
        private var titleTextSizePx: Float = 0f

        @Volatile
        private var bodyTextSizePx: Float = 0f

        fun isBlank(): Boolean = title.isBlank() && body.isBlank()

        fun updateAppearance(titleView: TextView?, bodyView: TextView?) {
            titleView?.textSize?.takeIf { it > 0f }?.let { titleTextSizePx = it }
            bodyView?.textSize?.takeIf { it > 0f }?.let { bodyTextSizePx = it }
        }

        fun styledText(): CharSequence {
            val plain = composeCircleCopyContent(title, body)
            if (title.isBlank() || plain.isBlank()) return plain
            val titleEnd = title.length.coerceAtMost(plain.length)
            val ratio = if (titleTextSizePx > 0f && bodyTextSizePx > 0f) {
                (titleTextSizePx / bodyTextSizePx).coerceIn(1.05f, 1.8f)
            } else {
                1.2f
            }
            return SpannableStringBuilder(plain).apply {
                setSpan(RelativeSizeSpan(ratio), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        companion object {
            fun from(title: String?, body: String?): StyledCopyContent {
                return StyledCopyContent(
                    title = CircleRichTextFilter.toPlainText(title),
                    body = CircleRichTextFilter.toPlainText(body),
                )
            }
        }
    }
}

internal fun composeCircleCopyContent(title: String?, body: String?): String {
    val normalizedTitle = CircleRichTextFilter.toPlainText(title)
    val normalizedBody = CircleRichTextFilter.toPlainText(body)
    return when {
        normalizedTitle.isEmpty() -> normalizedBody
        normalizedBody.isEmpty() || normalizedBody == normalizedTitle -> normalizedTitle
        else -> "$normalizedTitle\n\n$normalizedBody"
    }
}
