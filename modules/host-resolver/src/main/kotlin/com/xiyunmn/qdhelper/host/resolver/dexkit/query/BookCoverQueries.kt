package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal object BookCoverQueries {
    val bookManagerInstance = DexKitMethodQuery(
        id = "content_tools.cover.book_manager.instance",
        purpose = "book cover BookManager singleton",
        query = {
            searchPackages("com.qidian.QDReader.component.bll.manager")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramCount(0)
            }
        },
        verify = { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType.name.startsWith("com.qidian.QDReader.component.bll.manager") &&
                method.returnType == method.declaringClass &&
                ReflectionAccess.methodsDeclaredBy(method.returnType).any {
                    it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                        it.returnType.name == HostSymbols.Classes.BOOK_ITEM
                }
        },
    )

    val bookInfoById = DexKitMethodQuery(
        id = "content_tools.cover.book_manager.book_info_by_id",
        purpose = "book cover BookManager book item lookup",
        query = {
            searchPackages("com.qidian.QDReader.component.bll.manager")
            matcher {
                paramTypes(Long::class.javaPrimitiveType)
                returnType(HostSymbols.Classes.BOOK_ITEM)
            }
        },
        verify = { method ->
            method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                method.returnType.name == HostSymbols.Classes.BOOK_ITEM
        },
    )

    val coverUrl600 = DexKitMethodQuery(
        id = "content_tools.cover.url_600",
        purpose = "book cover Qidian cover URL builder anchored by the qdbimg format",
        query = {
            searchPackages("com.qd.ui.component.util")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                returnType(String::class.java)
                invokeMethods {
                    add {
                        paramTypes(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        returnType(String::class.java)
                        addUsingString("qdbimg/349573/%1\$d/%2\$d", StringMatchType.Contains)
                    }
                }
            }
        },
        verify = { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)) &&
                method.returnType == String::class.java &&
                method.declaringClass.name.startsWith("com.qd.ui.component.util.")
        },
    )
}
