package com.xiyunmn.qdhelper.feature.navigation

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.junit.Assert.*
import org.junit.Test

class AccountTabBadgeShapeTest {
    @Test fun acceptsLegacyAndNewUnreadConditionWithoutMatchingUnrelatedMethods() {
        val legacy = listOf(HostSymbols.Classes.MAIN_GROUP_ACTIVITY, "int", "int")
        assertTrue(isAccountTagTextSignature(legacy))
        assertTrue(isAccountTagTextSignature(legacy + "boolean"))
        assertFalse(isAccountTagTextSignature(legacy + "int"))
        assertFalse(isAccountTagTextSignature(listOf("java.lang.Object", "int", "int")))
        assertFalse(isAccountTagTextSignature(legacy + listOf("boolean", "int", "java.lang.Object")))
    }
}
