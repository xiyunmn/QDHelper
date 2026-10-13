package com.xiyunmn.qdhelper.hook.framework

import java.lang.reflect.Method

/** Reflection used only for Android framework members needed before host gateways are composed. */
object FrameworkReflection {
    fun declaredMethod(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching {
            owner.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        }.getOrNull()
}
