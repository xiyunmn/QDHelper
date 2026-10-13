package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.content.ContentValues
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal object MaintenanceQueries {
    val userLookupSearchRequest = DexKitMethodQuery(
        id = "maintenance.user_lookup.search_request",
        purpose = "native user-search request used by maintenance profile lookup",
        query = {
            searchPackages("com.qidian.QDReader.ui.presenter")
            matcher {
                modifiers(Modifier.PUBLIC)
                paramTypes(
                    String::class.java.name,
                    ContentValues::class.java.name,
                    "int",
                )
                returnType(Void.TYPE)
                addUsingString("OKR_SearchResult_RequestStart", StringMatchType.Equals)
            }
        },
        verify = MaintenanceMethodContracts::isUserLookupSearchRequest,
    )
}
