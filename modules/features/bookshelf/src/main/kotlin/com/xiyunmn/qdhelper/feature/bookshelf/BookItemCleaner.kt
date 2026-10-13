package com.xiyunmn.qdhelper.feature.bookshelf

import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport

import android.view.View
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.BookshelfTargetKeys
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 书籍条目活动标签、更新提示清理
 */
object BookItemCleaner {

    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0
        installedHooks += installOldBookshelfListItemClean(module, classLoader, phase)
        installedHooks += installNewBookshelfListItemClean(module, classLoader, phase)
        return installedHooks
    }

    private fun installOldBookshelfListItemClean(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOKSHELF_LIST_ONLINE_VIEW_HOLDER, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "bindView") ?: return 0
        return UiFeatureSupport.installAfter(
            module = module,
            id = "ui.bookshelf.oldListItem.clean",
            method = method,
            hookId = "qdhelper.p2.ui.bookshelf_old_list_item_clean",
            phase = phase,
        ) { holder ->
            val context = HostSessionStore.contextOrNull()
            var changed = false
            if (SettingsRuntime.isBookshelfBookActivityTagHidden(context)) {
                hideChildViewsByIdName(holder, "tagContent", "tv_activity", "topTagView", "rightTagView")
                changed = true
            }
            if (SettingsRuntime.isBookshelfUpdateNoticeHidden(context)) {
                hideChildViewsByIdName(holder, "viewNotice")
                changed = true
            }
            changed
        }
    }

    private fun installNewBookshelfListItemClean(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOKSHELF_NEW_LIST_BOOK_VIEW_HOLDER, classLoader)
        val method = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = BookshelfTargetKeys.newListBookBind,
        )
            ?: return 0
        return UiFeatureSupport.installAfter(
            module = module,
            id = "ui.bookshelf.newListItem.clean",
            method = method,
            hookId = "qdhelper.p2.ui.bookshelf_new_list_item_clean",
            phase = phase,
        ) { chain, _ ->
            val binding = chain.args.getOrNull(0)
            val context = HostSessionStore.contextOrNull()
            var changed = false
            if (SettingsRuntime.isBookshelfBookActivityTagHidden(context)) {
                hideChildViewsByIdName(binding, "crazyUpdateTag", "crazyUpdateTagLayout", "popActivityTag", "tagActivityText")
                changed = true
            }
            if (SettingsRuntime.isBookshelfUpdateNoticeHidden(context)) {
                hideChildViewsByIdName(
                    binding,
                    "viewNotice",
                    "updateNoticeArrow",
                    "updateNoticeLayout",
                    "ivUpdateNotice",
                    "ivUpdateNoticeClose",
                    "scUpdateNotice",
                )
                changed = true
            }
            changed
        }
    }

    private fun hideChildViewsByIdName(owner: Any?, vararg idNames: String) {
        val root = UiFeatureSupport.rootViewOf(owner) ?: return
        val resources = root.resources ?: return
        val packageName = root.context?.packageName ?: return
        idNames.forEach { idName ->
            val id = resources.getIdentifier(idName, "id", packageName)
            if (id != 0) {
                UiFeatureSupport.hideView(root.findViewById<View>(id))
            }
        }
    }

    private fun isNewBookshelfBindMethod(method: Method): Boolean {
        val params = method.parameterTypes
        val coroutineScope = params.getOrNull(3)
        return Modifier.isPublic(method.modifiers) &&
            Modifier.isFinal(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            params.size == 5 &&
            params[0].name == QDReaderHost.Classes.ITEM_BOOKSHELF_LIST_BINDING &&
            params[1] == Int::class.javaPrimitiveType &&
            params[2].name == QDReaderHost.Classes.BOOK_SHELF_ITEM &&
            coroutineScope?.isInterface == true &&
            FeatureHooks.allDeclaredMethods(coroutineScope).any { candidate ->
                candidate.parameterTypes.isEmpty() &&
                    candidate.returnType.name == "kotlin.coroutines.CoroutineContext"
            } &&
            params[4] == Int::class.javaPrimitiveType
    }
}
