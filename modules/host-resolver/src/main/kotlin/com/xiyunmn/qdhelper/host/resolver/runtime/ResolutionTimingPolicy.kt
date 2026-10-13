package com.xiyunmn.qdhelper.host.resolver.runtime

import com.xiyunmn.qdhelper.host.resolver.catalog.ResolutionTiming

internal fun ResolutionTiming.allowsSynchronousScan(): Boolean = when (this) {
    ResolutionTiming.RUNTIME_INSTALLABLE, ResolutionTiming.ON_ACTION -> true
    ResolutionTiming.EARLY_STARTUP, ResolutionTiming.PROBE_ONLY -> false
}

internal fun ResolutionTiming.publishesCurrentProcessRetry(): Boolean =
    this == ResolutionTiming.RUNTIME_INSTALLABLE
