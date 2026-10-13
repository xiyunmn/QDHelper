package com.xiyunmn.qdhelper.composition

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.BookshelfTargetKeys
import com.xiyunmn.qdhelper.host.api.target.NetworkIdentityTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ReaderTargetKeys
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCatalogCompositionTest {
    @Test
    fun autoInvestKeepsAccountPrewarmWithoutAutoSignIn() {
        val owners = activeTargetOwners(context = null, featureIds = listOf("account.auto-invest"))

        assertEquals(setOf("account", "shared"), owners.map { it.value }.toSet())
        assertTrue(HostTargetCatalog.prewarmForProcess(HostSymbols.PACKAGE, owners).any {
            it.owner.value == "account"
        })
    }

    @Test
    fun lastPageBookShieldInstallsBeforeReaderClean() {
        val ids = FeatureCatalogComposition.selected(context = null, hotReload = false).map { it.id }
        val shieldIndex = ids.indexOf("book-shield.last-page")

        assertTrue(shieldIndex >= 0 && ids.indexOf("reader.clean") > shieldIndex)
        assertEquals(1, ids.count { it == "book-shield.last-page" })
    }

    @Test
    fun disabledHighRiskDomainsAreNeitherSelectedNorPrewarmedByDefault() {
        val selectedIds = FeatureCatalogComposition.selected(context = null, hotReload = false)
            .map { it.id }
            .toSet()
        val targetOwners = activeTargetOwners(context = null, featureIds = selectedIds).map { it.value }.toSet()

        setOf(
            "account.auto-sign-in",
            "account.auto-invest",
            "network-identity.core",
            "diagnostics.device-identity",
            "welfare.action",
            "welfare.probe",
        ).forEach { id -> assertFalse("Unexpected default high-risk feature: $id", id in selectedIds) }
        setOf("account", "network-identity", "welfare").forEach { owner ->
            assertFalse("Unexpected default prewarm owner: $owner", owner in targetOwners)
        }
        val prewarmTargetIds = HostTargetCatalog.prewarmForProcess(HostSymbols.PACKAGE, activeTargetOwners(null, selectedIds))
            .map { it.key.id }
            .toSet()
        val highRiskIds = (AccountTargetKeys.autoSignIn + AccountTargetKeys.autoInvest + NetworkIdentityTargetKeys.all + WelfareTargetKeys.all)
            .map { it.id }.toSet()
        assertFalse(prewarmTargetIds.any { it in highRiskIds })
    }

    @Test
    fun readTimePrewarmsAndRetriesSharedAccountIdentityWithoutAccountActions() {
        val owners = activeTargetOwners(null, listOf("read-time.multiplier"))
        val prewarmed = HostTargetCatalog.prewarmForProcess(HostSymbols.PACKAGE, owners).map { it.key }.toSet()
        assertTrue(prewarmed.containsAll(AccountTargetKeys.userSession))
        assertFalse(prewarmed.any { it in AccountTargetKeys.autoSignIn || it in AccountTargetKeys.autoInvest })
        assertEquals(
            listOf("read-time.multiplier"),
            FeatureCatalogComposition.selectedForResolvedTargets(null, setOf(AccountTargetKeys.userManagerUserId.id)).map { it.id },
        )
    }

    @Test
    fun resolvedTargetsSelectSingleOrSharedOwnersWithoutUnrelatedFeatures() {
        assertEquals(
            listOf("reader.clean"),
            FeatureCatalogComposition.selectedForResolvedTargets(
                context = null,
                targetIds = setOf(ReaderTargetKeys.chapterEndAppend.id),
            ).map { it.id },
        )
        assertEquals(
            listOf("bookshelf.customization", "book-shield.core"),
            FeatureCatalogComposition.selectedForResolvedTargets(
                context = null,
                targetIds = setOf(BookshelfTargetKeys.nativeNetBridgeRegister.id),
            ).map { it.id },
        )
    }
}
