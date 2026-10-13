package com.xiyunmn.qdhelper.feature.contenttools

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import java.lang.reflect.Method
import java.util.ArrayList

internal fun realSectionBean(
    bean: Any?,
    listGetter: Method,
    listSetter: Method,
    countSetter: Method,
    countGetter: Method,
): Any? {
    if (bean == null) return null
    val list = FeatureHooks.invoke(listGetter, bean) as? List<*>
    val count = (FeatureHooks.invoke(countGetter, bean) as? Number)?.toInt() ?: 0
    if (list.isNullOrEmpty()) return null
    if (count <= 0) FeatureHooks.invoke(countSetter, bean, list.size)
    val previewLimit = ProfilePrivacyPreloadPolicy.HOME_PAGE_PREVIEW_LIMIT
    if (list.size > previewLimit) {
        FeatureHooks.invoke(listSetter, bean, ArrayList(list.take(previewLimit)))
    }
    return bean
}

internal fun sectionDebug(bean: Any?, listGetter: Method, countGetter: Method): String {
    if (bean == null) return "null"
    val list = FeatureHooks.invoke(listGetter, bean) as? List<*>
    val count = (FeatureHooks.invoke(countGetter, bean) as? Number)?.toInt()
    return "{count=$count,list=${list?.size ?: "null"}}"
}
