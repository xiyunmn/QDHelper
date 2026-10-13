package com.xiyunmn.qdhelper.host.resolver.dexkit.reflection

import java.lang.reflect.Method
import org.luckypray.dexkit.wrap.DexMethod

/** Keeps LuckyPray descriptor materialization inside the DexKit boundary. */
internal object DexMethodCodec {
    fun serialize(method: Method): String = DexMethod(method).serialize()

    fun materialize(value: String, classLoader: ClassLoader): Method? = runCatching {
        DexMethod.deserialize(value).getMethodInstance(classLoader).apply { isAccessible = true }
    }.getOrNull()
}
