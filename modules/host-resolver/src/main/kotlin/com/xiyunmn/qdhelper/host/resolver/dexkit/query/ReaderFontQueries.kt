package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.graphics.Typeface
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.lang.reflect.Modifier

internal object ReaderFontQueries {
    val engineInstanceGetter = DexKitMethodQuery(
        id = "reader.font.engine_instance_getter",
        purpose = "reader draw-state singleton owning the cached body typeface",
        query = {
            searchPackages("com.qidian.QDReader.readerengine.manager")
            matcher { modifiers(Modifier.STATIC); paramCount(0) }
        },
        verify = { method ->
            Modifier.isStatic(method.modifiers) && method.parameterTypes.isEmpty() &&
                method.returnType == method.declaringClass &&
                ReflectionAccess.fieldsDeclaredBy(method.declaringClass).any { it.type == Typeface::class.java }
        },
    )

    val engineTypefaceReload = DexKitMethodQuery(
        id = "reader.font.engine_typeface_reload",
        purpose = "reload the reader body typeface and update its cached text paints",
        query = {
            searchPackages("com.qidian.QDReader.readerengine.manager")
            matcher {
                paramCount(0)
                returnType(Void.TYPE)
                invokeMethods {
                    add {
                        declaredClass(HostSymbols.Classes.READER_FONT_MANAGER)
                        name("loadFontTypeSync")
                        paramTypes(String::class.java)
                        returnType(Typeface::class.java)
                    }
                }
            }
        },
        verify = { method ->
            !Modifier.isStatic(method.modifiers) && method.parameterTypes.isEmpty() &&
                method.returnType == Void.TYPE &&
                ReflectionAccess.fieldsDeclaredBy(method.declaringClass).any { it.type == Typeface::class.java }
        },
    )

    val readerEngineTypefaceGetter = DexKitMethodQuery(
        id = "reader.font.engine_typeface_getter",
        purpose = "reader engine current typeface getter",
        query = {
            searchPackages("com.qidian.QDReader.readerengine.manager")
            matcher {
                returnType(Typeface::class.java)
                paramCount(0)
            }
        },
        verify = { method ->
            method.parameterTypes.isEmpty() && method.returnType == Typeface::class.java
        },
    )
}
