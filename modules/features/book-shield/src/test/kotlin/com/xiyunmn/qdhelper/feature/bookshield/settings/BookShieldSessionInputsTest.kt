package com.xiyunmn.qdhelper.feature.bookshield.settings

import com.xiyunmn.qdhelper.config.BookShieldRuleConfig
import com.xiyunmn.qdhelper.config.BookShieldRuleField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookShieldSessionInputsTest {
    @Test
    fun `typing replacement removes owned intermediate token`() {
        val first = applySessionDelta(
            BookShieldRuleConfig.EMPTY,
            BookShieldRuleField.TYPES,
            SessionSnapshot(emptyList(), listOf(token("玄")), emptySet()),
        )
        val second = applySessionDelta(
            first.config,
            BookShieldRuleField.TYPES,
            SessionSnapshot(listOf(token("玄")), listOf(token("玄幻")), first.ownedIdentities),
        )

        assertEquals(listOf("玄幻"), second.config.types)
        assertEquals(setOf("玄幻"), second.ownedIdentities)
    }

    @Test
    fun `removing duplicate typed rule does not remove preexisting file rule`() {
        val existing = BookShieldRuleConfig(authors = listOf("作者甲"))
        val added = applySessionDelta(
            existing,
            BookShieldRuleField.AUTHORS,
            SessionSnapshot(emptyList(), listOf(token("作者甲")), emptySet()),
        )
        val removed = applySessionDelta(
            added.config,
            BookShieldRuleField.AUTHORS,
            SessionSnapshot(listOf(token("作者甲")), emptyList(), added.ownedIdentities),
        )

        assertEquals(listOf("作者甲"), removed.config.authors)
        assertTrue(removed.ownedIdentities.isEmpty())
    }

    @Test
    fun `removing owned rule deletes only the session contribution`() {
        val added = applySessionDelta(
            BookShieldRuleConfig(authors = listOf("既有作者")),
            BookShieldRuleField.AUTHORS,
            SessionSnapshot(emptyList(), listOf(token("本次作者")), emptySet()),
        )
        val removed = applySessionDelta(
            added.config,
            BookShieldRuleField.AUTHORS,
            SessionSnapshot(listOf(token("本次作者")), emptyList(), added.ownedIdentities),
        )

        assertEquals(listOf("既有作者"), removed.config.authors)
        assertTrue(removed.ownedIdentities.isEmpty())
    }

    @Test
    fun `text fields split on newlines only and deduplicate case`() {
        val tokens = parseSessionTokens(BookShieldRuleField.AUTHORS, " Author \nauthor\n作者甲 ")

        assertEquals(listOf("Author", "作者甲"), tokens.map(SessionToken::display))
        assertEquals(listOf("author", "作者甲"), tokens.map(SessionToken::identity))
    }

    @Test
    fun `punctuation inside a title is kept as one rule`() {
        val tokens = parseSessionTokens(
            BookShieldRuleField.FULL_TITLES,
            "我，美国法外狂徒，打钱\n大明：让你死谏，你怎么真死啊？",
        )

        assertEquals(
            listOf("我，美国法外狂徒，打钱", "大明：让你死谏，你怎么真死啊？"),
            tokens.map(SessionToken::display),
        )
    }

    @Test
    fun `book ids still accept spaces and commas`() {
        val tokens = parseSessionTokens(BookShieldRuleField.BOOK_IDS, "1000001 1000002，1000003\n1000001")

        assertEquals(listOf("1000001", "1000002", "1000003"), tokens.map(SessionToken::display))
    }

    @Test
    fun `authoritative reconcile removes deleted entries and hides external additions`() {
        val result = reconcileSessionInput(
            config = BookShieldRuleConfig(authors = listOf("仍保留", "外部新增")),
            field = BookShieldRuleField.AUTHORS,
            rawText = "仍保留\n已删除",
            ownedIdentities = setOf("仍保留", "已删除"),
        )

        assertEquals("仍保留", result.rawText)
        assertEquals(listOf("仍保留"), result.tokens.map(SessionToken::display))
        assertEquals(setOf("仍保留"), result.ownedIdentities)
    }

    private fun token(value: String) = SessionToken(value, BookShieldRuleConfig.normalizeIdentity(value))
}
