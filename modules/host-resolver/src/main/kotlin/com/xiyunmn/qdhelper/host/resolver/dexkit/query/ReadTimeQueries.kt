package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier
import java.util.List

internal object ReadTimeQueries {
    /**
     * The reading-time DAO insert.
     *
     * The declaring class is obfuscated and its package drifts every release
     * across releases, so nothing here may reference it by name.
     * Two things are stable instead: the parameter type comes from the un-obfuscated
     * `readtimestatisticssdk` SDK, and the DAO bakes in the `user_book_read_time` table literal.
     */
    val statisticsInsert = DexKitMethodQuery(
        id = "read_time.statistics.insert",
        purpose = "reading-time session insert funnel (derives the encrypted copy from ReadTime)",
        query = {
            matcher {
                modifiers(Modifier.PUBLIC)
                paramTypes(HostSymbols.Classes.READ_TIME_ENTITY)
                returnType(Boolean::class.javaPrimitiveType!!)
                addUsingString(HostSymbols.Classes.READ_TIME_TABLE, StringMatchType.Equals)
            }
        },
        verify = HostMethodContracts::isReadTimeInsert,
    )

    /** Invoked only from the upload utility's HTTP-success callback to mark a reported batch. */
    val statisticsMarkReported = DexKitMethodQuery(
        id = "read_time.statistics.mark_reported",
        purpose = "reading-time network-success batch marker (sets Is_report=1)",
        query = {
            matcher {
                modifiers(Modifier.PUBLIC)
                paramTypes(List::class.java)
                returnType(Boolean::class.javaPrimitiveType!!)
                addUsingString(HostSymbols.Classes.READ_TIME_TABLE, StringMatchType.Equals)
                addUsingString(HostSymbols.Classes.READ_TIME_REPORT_FLAG, StringMatchType.Equals)
            }
        },
        verify = HostMethodContracts::isReadTimeMarkReported,
    )

    /** Read-only cloud reading-time page; used to verify that adjusted totals reached the server. */
    val cloudReadPageApi = retrofitExactPathQuery(
        id = "read_time.cloud.readpage_api",
        purpose = "cloud reading-time page API (today/week totals)",
        path = "argus/api/v2/readtime/readpage",
    )
}
