package com.xiyunmn.qdhelper.feature.contenttools

import android.view.View
import android.widget.TextView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookChain
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object BookFansRankFeature : InstallableFeature {
    override val name: String = "BookFansRankFeature"

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val modelClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_FANS_USER_VALUE, classLoader)
        if (modelClass == null) {
            FeatureHooks.w("Book fans rank skipped: user-value model missing, phase=$phase")
            return
        }
        val rankField = modelClass.fields.singleOrNull { field ->
            field.name == "FansRank" && field.type == Int::class.javaPrimitiveType
        }
        if (rankField == null) {
            FeatureHooks.w("Book fans rank skipped: semantic FansRank field missing, phase=$phase")
            return
        }

        var installed = 0
        listOf(
            QDReaderHost.Classes.FANS_LIST_BOTTOM_VIEW,
            QDReaderHost.Classes.BOOK_FANS_BOTTOM_VIEW,
        ).forEach { className ->
            if (installRankRenderHook(module, classLoader, className, modelClass, rankField)) {
                installed += 1
            }
        }
        FeatureHooks.i(
            "Book fans rank phase finished: phase=$phase, process=$processName, installed=$installed/2",
        )
    }

    private fun installRankRenderHook(
        module: HookModule,
        classLoader: ClassLoader,
        className: String,
        modelClass: Class<*>,
        rankField: Field,
    ): Boolean {
        val viewClass = FeatureHooks.findClassOrNull(className, classLoader) ?: return false
        val modelField = FeatureHooks.allDeclaredFields(viewClass).singleOrNull { field ->
            field.type == modelClass
        } ?: return false
        val renderMethod = FeatureHooks.allDeclaredMethods(viewClass).singleOrNull { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.isEmpty()
        } ?: return false

        return FeatureHooks.hookAround(
            module = module,
            executable = renderMethod,
            feature = "bookDetail.fansRank:${viewClass.simpleName} render",
            id = "qdhelper.book_detail.fans_rank.${viewClass.simpleName}",
        ) { chain ->
            renderWithExactRank(chain, modelField, rankField)
        }
    }

    private fun renderWithExactRank(
        chain: HookChain,
        modelField: Field,
        rankField: Field,
    ): Any? {
        val owner = chain.thisObject as? View ?: return chain.proceed()
        if (!SettingsRuntime.isBookDetailFansRankEnabled(owner.context)) return chain.proceed()
        val model = runCatching { modelField.get(owner) }.getOrNull() ?: return chain.proceed()
        val exactRank = runCatching { rankField.getInt(model) }.getOrNull() ?: return chain.proceed()
        if (!shouldRevealExactFanRank(exactRank)) return chain.proceed()

        return try {
            // Let the host render visibility, color, size and skew through its native <= 99 branch.
            rankField.setInt(model, nativeRenderableFanRank(exactRank))
            val result = chain.proceed()
            owner.findTextViewByEntryName("tvNum")?.text = exactRank.toString()
            result
        } finally {
            runCatching { rankField.setInt(model, exactRank) }
        }
    }

    private fun View.findTextViewByEntryName(entryName: String): TextView? {
        val resourceId = resources.getIdentifier(entryName, "id", context.packageName)
        if (resourceId == 0) return null
        return findViewById(resourceId) as? TextView
    }
}

internal fun shouldRevealExactFanRank(rank: Int): Boolean = rank > 99

internal fun nativeRenderableFanRank(rank: Int): Int = if (rank > 99) 99 else rank
