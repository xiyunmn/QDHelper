package com.xiyunmn.qdhelper.feature.glassbars

import android.content.Context
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class GlassSettingsObservationTest {
    @After fun tearDown() = ConfigRuntime.clear()

    @Test
    fun observerCoversBatchedImportResetAndClearAndCanBeReleased() {
        val context = RuntimeEnvironment.getApplication()
        val snapshots = mutableListOf<GlassBarsConfiguration>()
        val listener = ConfigRuntime.observeUserChanges(context, GlassBarsConfiguration.observedKeys) {
            snapshots += GlassBarsConfiguration.read(context)
        }
        // Archives commit through the same preference store. Exercise a multi-key atomic write.
        context.getSharedPreferences(SettingIds.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(SettingIds.KEY_MODULE_ENABLED, true)
            .putBoolean(SettingIds.KEY_GLASS_BARS_ENABLED, true)
            .putBoolean(SettingIds.KEY_GLASS_MAIN_ENABLED, true)
            .putString(SettingIds.KEY_GLASS_MAIN_HEIGHT, "96")
            .putString(SettingIds.KEY_GLASS_DETAIL_WIDTH, "70")
            .putString(SettingIds.KEY_GLASS_DIRECTORY_BOTTOM_GAP, "36")
            .commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(snapshots.isNotEmpty())
        assertEquals(setOf(GlassBarKind.MAIN), snapshots.last().enabled)
        assertEquals(96, snapshots.last().dimensionsFor(GlassBarKind.MAIN).heightDp)
        assertEquals(70, snapshots.last().dimensionsFor(GlassBarKind.DETAIL).widthPercent)
        assertEquals(36, snapshots.last().dimensionsFor(GlassBarKind.DIRECTORY).bottomGapDp)
        SettingsRuntime.resetDefaults(context, GlassBarsConfiguration.observedKeys - SettingIds.KEY_MODULE_ENABLED)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(snapshots.last().enabled.isEmpty())
        assertTrue(snapshots.last().effects.blur)
        assertEquals(64, snapshots.last().dimensionsFor(GlassBarKind.MAIN).heightDp)
        assertEquals(0, snapshots.last().dimensionsFor(GlassBarKind.DETAIL).widthPercent)
        assertEquals(-1, snapshots.last().dimensionsFor(GlassBarKind.DIRECTORY).bottomGapDp)
        listener.close()
        val count = snapshots.size
        ConfigRuntime.putBoolean(SettingIds.KEY_GLASS_MAIN_ENABLED, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, snapshots.size)
    }
}
