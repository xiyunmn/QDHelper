package com.xiyunmn.qdhelper.foundation.android

import android.os.Build

/**
 * Runtime SDK policy for code injected into the host process.
 *
 * The module is built against API 37 and supports API 28-37. Newer Android
 * releases are intentionally allowed to continue through capability probing; the status is only
 * diagnostic so a future release does not become an accidental version blacklist.
 */
object AndroidApiSupport {
    const val MIN_SUPPORTED_API = 28
    const val MAX_VERIFIED_API = 37
    const val UNSUPPORTED_API = "unsupported_api"

    enum class Status {
        UNSUPPORTED,
        VERIFIED,
        UNVERIFIED,
    }

    fun status(sdkInt: Int = Build.VERSION.SDK_INT): Status = when {
        sdkInt < MIN_SUPPORTED_API -> Status.UNSUPPORTED
        sdkInt <= MAX_VERIFIED_API -> Status.VERIFIED
        else -> Status.UNVERIFIED
    }

    fun unsupportedReason(sdkInt: Int = Build.VERSION.SDK_INT): String? =
        UNSUPPORTED_API.takeIf { status(sdkInt) == Status.UNSUPPORTED }

    fun isSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        status(sdkInt) != Status.UNSUPPORTED

    fun summary(sdkInt: Int = Build.VERSION.SDK_INT): String =
        "sdk=$sdkInt;status=${status(sdkInt).name.lowercase()};" +
            "verifiedRange=$MIN_SUPPORTED_API-$MAX_VERIFIED_API"
}
