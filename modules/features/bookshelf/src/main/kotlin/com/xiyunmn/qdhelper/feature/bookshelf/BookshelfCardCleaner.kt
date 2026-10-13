package com.xiyunmn.qdhelper.feature.bookshelf

import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport

import android.view.View
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule

/**
 * 书架阅读时长、签到卡片清理。
 */
object BookshelfCardCleaner {

    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0
        val readingTimeClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOKSHELF_READING_TIME_VIEW, classLoader)
        installedHooks += readingTimeClass?.let { clazz ->
            FeatureHooks.declaredMethodsNamed(clazz, "updateReadingTime")
                .singleOrNull { candidate -> candidate.parameterTypes.size == 1 }
                ?.let { method ->
                    UiFeatureSupport.installAfter(
                        module = module,
                        id = "ui.bookshelf.readingTimeCard.update",
                        method = method,
                        hookId = "qdhelper.p2.ui.bookshelf_reading_time_update",
                        phase = phase,
                    ) { view ->
                        hideReadingTimeCardIfNeeded(view)
                    }
                } ?: 0
        } ?: 0
        installedHooks += readingTimeClass?.let { clazz ->
            FeatureHooks.findDeclaredMethodOrNull(clazz, "setDefaultStyle")
                ?.let { method ->
                    UiFeatureSupport.installAfter(
                        module = module,
                        id = "ui.bookshelf.readingTimeCard.defaultStyle",
                        method = method,
                        hookId = "qdhelper.p2.ui.bookshelf_reading_time_default",
                        phase = phase,
                    ) { view ->
                        hideReadingTimeCardIfNeeded(view)
                    }
                } ?: 0
        } ?: 0
        installedHooks += readingTimeClass?.let { clazz ->
            FeatureHooks.findDeclaredMethodOrNull(
                clazz,
                "switchTeenagerMode",
                Boolean::class.javaPrimitiveType ?: Boolean::class.java,
            )?.let { method ->
                UiFeatureSupport.installAfter(
                    module = module,
                    id = "ui.bookshelf.readingTimeCard.teenager",
                    method = method,
                    hookId = "qdhelper.p2.ui.bookshelf_reading_time_teenager",
                    phase = phase,
                ) { view ->
                    hideReadingTimeCardIfNeeded(view)
                }
            } ?: 0
        } ?: 0

        val checkInClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOK_SHELF_CHECK_IN_VIEW, classLoader)
        installedHooks += installCheckInCardHideAfter(
            module = module,
            clazz = checkInClass,
            methodName = "updateCheckIn",
            parameterCount = 2,
            id = "ui.bookshelf.checkInCard.update",
            hookId = "qdhelper.p2.ui.bookshelf_check_in_update",
            phase = phase,
        )
        installedHooks += installCheckInCardHideAfter(
            module = module,
            clazz = checkInClass,
            methodName = "setupDailyReading",
            parameterCount = 1,
            id = "ui.bookshelf.checkInCard.daily",
            hookId = "qdhelper.p2.ui.bookshelf_check_in_daily",
            phase = phase,
        )
        installedHooks += installCheckInCardHideAfter(
            module = module,
            clazz = checkInClass,
            methodName = "setupDailyReadingEmpty",
            parameterCount = 0,
            id = "ui.bookshelf.checkInCard.dailyEmpty",
            hookId = "qdhelper.p2.ui.bookshelf_check_in_daily_empty",
            phase = phase,
        )
        installedHooks += installCheckInCardHideAfter(
            module = module,
            clazz = checkInClass,
            methodName = "onNotLogin",
            parameterCount = 0,
            id = "ui.bookshelf.checkInCard.notLogin",
            hookId = "qdhelper.p2.ui.bookshelf_check_in_not_login",
            phase = phase,
        )
        return installedHooks
    }

    private fun installCheckInCardHideAfter(
        module: HookModule,
        clazz: Class<*>?,
        methodName: String,
        parameterCount: Int,
        id: String,
        hookId: String,
        phase: String,
    ): Int {
        clazz ?: return 0
        val method = FeatureHooks.declaredMethodsNamed(clazz, methodName)
            .singleOrNull { candidate -> candidate.parameterTypes.size == parameterCount }
            ?: return 0
        return UiFeatureSupport.installAfter(
            module = module,
            id = id,
            method = method,
            hookId = hookId,
            phase = phase,
        ) { view ->
            hideReadingTimeCardIfNeeded(view)
        }
    }

    private fun hideReadingTimeCardIfNeeded(view: Any?): Boolean {
        if (!SettingsRuntime.isBookshelfReadingTimeCardHidden(HostSessionStore.contextOrNull())) {
            return false
        }
        UiFeatureSupport.hideView(view as? View)
        return true
    }
}
