package com.xiyunmn.qdhelper.shared.compose

import android.content.pm.ApplicationInfo

/** Only the composition root supplies the module APK identity; never retain a host Activity. */
object ModuleComposeEnvironment {
    @Volatile internal var applicationInfo: ApplicationInfo? = null
        private set

    val moduleApkPath: String? get() = applicationInfo?.sourceDir

    fun configure(info: ApplicationInfo) {
        applicationInfo = ApplicationInfo(info)
    }
}
