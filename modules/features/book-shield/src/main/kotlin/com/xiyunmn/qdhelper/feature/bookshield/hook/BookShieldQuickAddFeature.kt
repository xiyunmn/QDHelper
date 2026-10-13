package com.xiyunmn.qdhelper.feature.bookshield.hook

import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfo
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfoExtractor
import com.xiyunmn.qdhelper.feature.bookshield.ui.BookShieldQuickAddDialog
import android.app.Activity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule

object BookShieldQuickAddFeature : InstallableFeature {
    override val name: String = "BookShieldQuickAddFeature"

    @Volatile
    private var activitySubscription: AutoCloseable? = null

    init {
        FeatureResources.register("book-shield-quick-add") {
            activitySubscription?.close()
            activitySubscription = null
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val activityClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOK_DETAIL_ACTIVITY, classLoader)
            ?: return
        if (activitySubscription == null) {
            activitySubscription = ActivityLifecycleCallbacks.subscribe(
                owner = OwnerId("book-shield"),
                id = "book-shield.detail.quick-add.resume",
                resumed = { activity ->
                    if (activity.javaClass.name == QDReaderHost.Classes.BOOK_DETAIL_ACTIVITY &&
                        SettingsRuntime.isBookShieldQuickAddEnabled(activity)
                    ) {
                        activity.window?.decorView?.post { attachDetailQuickAdd(activity) }
                    }
                },
            )
        }
        val installedSubscriptions = if (activitySubscription != null) 1 else 0
        var installedHooks = 0
        listOf(
            FeatureHooks.findDeclaredMethodOrNull(
                activityClass,
                "notifyData",
                Boolean::class.javaPrimitiveType ?: Boolean::class.java,
            ) to "notifyData",
        ).forEach { (method, label) ->
            method ?: return@forEach
            val key = "shield.detail.quickAdd.$label:${method.toGenericString()}"
            val success = FeatureHooks.hookAround(
                module = module,
                executable = method,
                feature = "p2.bookShield.detail.quickAdd.$label",
                id = "qdhelper.p2.shield.detail_quick_add_$label",
            ) { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && SettingsRuntime.isBookShieldQuickAddEnabled(activity)) {
                    activity.window?.decorView?.post { attachDetailQuickAdd(activity) }
                }
                result
            }
            if (success) {
                installedHooks += 1
                FeatureHooks.i(
                    "Book shield installed detail quick add $label: " +
                        "${method.declaringClass.name}.${method.name}, phase=$phase",
                )
            }
        }
        FeatureHooks.i(
            "Book shield quick add phase finished: phase=$phase, process=$processName, " +
                "installed=${installedSubscriptions + installedHooks}/2",
        )
    }

    private fun attachDetailQuickAdd(activity: Activity) {
        val bookNameId = activity.resources.getIdentifier("tvBookName", "id", activity.packageName)
        val authorId = activity.resources.getIdentifier("tvAuthorName", "id", activity.packageName)
        listOf(bookNameId, authorId)
            .filter { it != 0 }
            .mapNotNull { id -> activity.findViewById<View>(id) }
            .forEach { view ->
                view.isHapticFeedbackEnabled = false
                view.setOnLongClickListener {
                    val info = currentDetailInfo(activity)
                    if (info == null) {
                        Toast.makeText(activity, "未读取到可屏蔽信息", Toast.LENGTH_SHORT).show()
                        return@setOnLongClickListener true
                    }
                    BookShieldQuickAddDialog.show(activity, info, view)
                    true
                }
            }
    }

    private fun currentDetailInfo(activity: Activity): BookInfo? {
        val detailInfo = BookInfoExtractor.extract(BookInfoExtractor.fieldValue(activity, "mBookDetail"))
        val itemInfo = BookInfoExtractor.extract(BookInfoExtractor.fieldValue(activity, "mBookItem"))
        val merged = BookInfo(
            id = detailInfo?.id ?: itemInfo?.id ?: activity.intent
                ?.getLongExtra("BOOK_ID", 0L)
                ?.takeIf { it > 0L }
                ?: (BookInfoExtractor.fieldValue(activity, "mBookId") as? Number)?.toLong(),
            title = firstNotBlank(detailInfo?.title, itemInfo?.title, textById(activity, "tvBookName")),
            author = firstNotBlank(detailInfo?.author, itemInfo?.author, textById(activity, "tvAuthorName")),
            category = firstNotBlank(detailInfo?.category, itemInfo?.category),
            subCategory = firstNotBlank(detailInfo?.subCategory, itemInfo?.subCategory),
            tags = firstNotBlank(detailInfo?.tags, itemInfo?.tags),
            type = firstNotBlank(detailInfo?.type, itemInfo?.type),
            typeCode = detailInfo?.typeCode ?: itemInfo?.typeCode,
            words = detailInfo?.words ?: itemInfo?.words,
            sourceClass = detailInfo?.sourceClass ?: itemInfo?.sourceClass ?: activity.javaClass.name,
        )
        return merged.takeUnless { it.title.isNullOrBlank() && it.author.isNullOrBlank() }
    }

    private fun textById(activity: Activity, name: String): String? {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        if (id == 0) return null
        return (activity.findViewById<View>(id) as? TextView)
            ?.text
            ?.toString()
            ?.replace("…", "")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun firstNotBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
