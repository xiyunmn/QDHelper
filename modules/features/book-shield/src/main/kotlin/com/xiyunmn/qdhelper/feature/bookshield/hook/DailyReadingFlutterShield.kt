package com.xiyunmn.qdhelper.feature.bookshield.hook

import com.xiyunmn.qdhelper.feature.api.runtime.DailyReadingDataRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldLocations

internal object DailyReadingFlutterShield {
    fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        DailyReadingDataRuntime.register("daily-reading-book-shield", priority = 50) { response ->
            BookStoreFlutterShield.filterValue(
                raw = response,
                path = "methodCall.getDailyReadingData",
                location = BookShieldLocations.DAILY_READING_FLUTTER,
            )
        }
        return DailyReadingDataRuntime.install(module, classLoader, phase)
    }
}
