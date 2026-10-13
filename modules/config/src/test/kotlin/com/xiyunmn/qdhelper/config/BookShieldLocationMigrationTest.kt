package com.xiyunmn.qdhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BookShieldLocationMigrationTest {
    @Test
    fun legacyCategoryMovesToCurrentNativeBookstoreGroupAndRetiredTokensAreDropped() {
        val migrated = normalizeBookShieldLocations(
            "category,new_recommend,new_book,comic_other,old_daily_recommend,ranking,category_all",
        )

        assertEquals(setOf("bookstore_reborn", "ranking", "category_all"), migrated)
        assertFalse(migrated.any { it in setOf("category", "new_recommend", "new_book", "comic_other", "old_daily_recommend") })
    }

    @Test
    fun migrationPreservesEmptyAndAlreadyCurrentSelections() {
        assertEquals(emptySet<String>(), normalizeBookShieldLocations(""))
        assertEquals(
            setOf("selection_morphing", "bookstore_reborn"),
            normalizeBookShieldLocations("selection_morphing,bookstore_reborn,bookstore_reborn"),
        )
    }
}
