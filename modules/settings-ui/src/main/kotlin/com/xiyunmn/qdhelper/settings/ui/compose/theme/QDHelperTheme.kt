package com.xiyunmn.qdhelper.settings.ui.compose.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.xiyunmn.qdhelper.shared.ui.UiColors
import kotlinx.coroutines.delay

internal data class HostPalette(
    val night: Boolean, val brand: Int, val surface: Int,
    val text: Int, val muted: Int, val divider: Int,
) {
    companion object {
        fun read(activity: Activity): HostPalette {
            UiColors.refresh(activity)
            return HostPalette(UiColors.IsNight, UiColors.Brand, UiColors.Panel, UiColors.Text, UiColors.Muted, UiColors.Divider)
        }
    }
}

@Composable
internal fun QDHelperTheme(
    activity: Activity,
    isVisible: () -> Boolean = { true },
    onPalette: (HostPalette) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var palette by remember(activity) { mutableStateOf(HostPalette.read(activity)) }
    LaunchedEffect(activity) {
        while (true) {
            delay(500)
            if (isVisible() && !activity.isDestroyed && !activity.isFinishing) palette = HostPalette.read(activity)
        }
    }
    val colors = remember(palette) {
        val surface = Color(palette.surface)
        val text = Color(palette.text)
        val brand = Color(palette.brand)
        val container = lerp(surface, brand, if (palette.night) .24f else .10f)
        val onBrand = if (brand.luminance() > .4f) Color.Black else Color.White
        val base = if (palette.night) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = brand, onPrimary = onBrand,
            primaryContainer = container, onPrimaryContainer = text,
            secondary = brand, onSecondary = onBrand, secondaryContainer = container, onSecondaryContainer = text,
            tertiary = brand, onTertiary = onBrand, tertiaryContainer = container, onTertiaryContainer = text,
            background = if (palette.night) surface else lerp(surface, Color(0xFFB7C4E1), .08f), onBackground = text, surface = surface, onSurface = text,
            surfaceVariant = lerp(surface, text, .06f), onSurfaceVariant = Color(palette.muted),
            surfaceTint = brand, outline = Color(palette.muted), outlineVariant = Color(palette.divider),
            surfaceContainerLowest = surface, surfaceContainerLow = if (palette.night) lerp(surface, text, .045f) else lerp(surface, Color.White, .7f),
            surfaceContainer = if (palette.night) lerp(surface, text, .065f) else lerp(surface, Color.White, .8f), surfaceContainerHigh = lerp(surface, text, .07f),
            surfaceContainerHighest = lerp(surface, text, .10f),
            inverseSurface = text, inverseOnSurface = surface, inversePrimary = brand,
            surfaceBright = lerp(surface, text, .04f), surfaceDim = lerp(surface, Color.Black, .04f),
            primaryFixed = container, primaryFixedDim = container, onPrimaryFixed = text, onPrimaryFixedVariant = text,
            secondaryFixed = container, secondaryFixedDim = container, onSecondaryFixed = text, onSecondaryFixedVariant = text,
            tertiaryFixed = container, tertiaryFixedDim = container, onTertiaryFixed = text, onTertiaryFixedVariant = text,
        )
    }
    SideEffect { onPalette(palette) }
    MaterialTheme(colorScheme = colors, content = content)
}
