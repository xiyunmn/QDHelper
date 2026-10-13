package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPlazaUnlockPolicyTest {
    @Test
    fun acceptsOnlyExactQidianCardPlazaUrls() {
        assertTrue(CardPlazaUnlockPolicy.isCardPlazaUrl("https://h5.if.qidian.com/h5/roleCard/cardPlaza"))
        assertTrue(CardPlazaUnlockPolicy.isCardPlazaUrl("https://h5.if.qidian.com/h5/roleCard/cardPlaza/?cardType=1"))
        assertTrue(CardPlazaUnlockPolicy.isCardPlazaUrl("https://H5.IF.QIDIAN.COM/h5/roleCard/cardPlaza#role"))

        assertFalse(CardPlazaUnlockPolicy.isCardPlazaUrl("http://h5.if.qidian.com/h5/roleCard/cardPlaza"))
        assertFalse(CardPlazaUnlockPolicy.isCardPlazaUrl("https://h5.if.qidian.com.evil.test/h5/roleCard/cardPlaza"))
        assertFalse(CardPlazaUnlockPolicy.isCardPlazaUrl("https://h5.if.qidian.com/roleCard/cardPlaza"))
        assertFalse(CardPlazaUnlockPolicy.isCardPlazaUrl("https://h5.if.qidian.com/h5/roleCard/cardPlazaPreview"))
        assertFalse(CardPlazaUnlockPolicy.isCardPlazaUrl("QDReader://app/openRoleCardMainPage"))
        assertFalse(CardPlazaUnlockPolicy.isCardPlazaUrl(null))
    }

    @Test
    fun supportsBothBrowserActivityVariants() {
        assertTrue(CardPlazaUnlockPolicy.isSupportedBrowserActivity("com.qidian.QDReader.ui.activity.QDBrowserActivity"))
        assertTrue(CardPlazaUnlockPolicy.isSupportedBrowserActivity("com.qidian.QDReader.ui.activity.TabBrowserActivity"))
        assertFalse(CardPlazaUnlockPolicy.isSupportedBrowserActivity("com.qidian.QDReader.ui.activity.MainGroupActivity"))
    }
}
