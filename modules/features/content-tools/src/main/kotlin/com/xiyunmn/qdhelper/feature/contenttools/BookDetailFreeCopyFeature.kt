package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.TextView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.feature.api.runtime.ViewLongPressCallbacks
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.capability.freecopy.FreeCopyDialog
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

object BookDetailFreeCopyFeature : InstallableFeature {
    override val name: String = "BookDetailFreeCopyFeature"

    private const val INTRO_VIEW_ID = "tvIntro"
    private const val DIALOG_TITLE = "书籍简介"
    private val copyTargets = Collections.synchronizedMap(WeakHashMap<View, CopyTarget>())

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val collapsedTextClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QDUI_COLLAPSED_TEXT_VIEW, classLoader)
        if (collapsedTextClass == null) {
            FeatureHooks.w("Book detail free copy skipped: collapsed text class missing, phase=$phase")
            return
        }
        val setTextMethod = FeatureHooks.allDeclaredMethods(collapsedTextClass).singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(CharSequence::class.java, TextView.BufferType::class.java),
                )
        }
        if (setTextMethod == null) {
            FeatureHooks.w("Book detail free copy skipped: stable setText override missing, phase=$phase")
            return
        }

        val installed = FeatureHooks.hookAfter(
            module = module,
            executable = setTextMethod,
            feature = "bookDetail.freeCopy:collapsed intro setText",
            id = "qdhelper.book_detail.free_copy.intro_set_text",
        ) { chain, _ ->
            attachIntroTarget(chain.thisObject as? TextView, chain.args.getOrNull(0) as? CharSequence)
        }
        FeatureHooks.i(
            "Book detail free copy phase finished: phase=$phase, process=$processName, installed=$installed",
        )
    }

    private fun attachIntroTarget(textView: TextView?, source: CharSequence?) {
        textView ?: return
        if (textView.resourceEntryName() != INTRO_VIEW_ID || !isBookDetailContext(textView.context)) return
        val content = source?.toString()?.trim().orEmpty()
        if (content.isEmpty() || !SettingsRuntime.isBookDetailIntroFreeCopyEnabled(textView.context)) {
            unregisterTarget(textView)
            return
        }
        registerTarget(textView, content)
    }

    private fun registerTarget(view: View, content: String) {
        unregisterTarget(view)
        synchronized(copyTargets) {
            val result = ViewLongPressCallbacks.register(
                view,
                LongPressRegistration(OwnerId("book-detail-free-copy"), "intro", priority = 50),
            ) { target, _ ->
                val targetView = target as View
                val current = copyTargets[targetView] ?: return@register LongPressResult.Pass
                if (!SettingsRuntime.isBookDetailIntroFreeCopyEnabled(targetView.context)) {
                    unregisterTarget(targetView)
                    return@register LongPressResult.Pass
                }
                FreeCopyDialog.show(targetView, current.content, DIALOG_TITLE)
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

    private fun isBookDetailContext(context: Context?): Boolean {
        var current = context
        while (current != null) {
            if (current.javaClass.name == QDReaderHost.Classes.BOOK_DETAIL_ACTIVITY) return true
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }

    private fun View.resourceEntryName(): String? {
        if (id == View.NO_ID) return null
        return runCatching { resources.getResourceEntryName(id) }.getOrNull()
    }

    private data class CopyTarget(
        val content: String,
        val registration: AutoCloseable,
    )
}
