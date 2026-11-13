package com.mycard.wallet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.AppPalette

/** App chrome only. CardFace keeps the user's original stored colors. */
object WalletDesign {
    val touch = 48.dp
    val pageInset = 20.dp
    val groupRadius = 24.dp
    val gap = 12.dp
    // Card artwork geometry. A card is drawn as one 353dp-wide reference card and then scaled, so
    // these stay dp here and are converted inside each draw scope instead of at every call site.
    val cardRadius = 22.dp
    val cardStrokeWidth = 1.dp
    val cardStrokeAlpha = .12f
    // Edge light: a bright top-left and a faint bottom-right instead of one flat white hairline.
    val cardEdgeLight: Color = Color.White.copy(alpha = .16f)
    val cardEdgeShade: Color = Color.Black.copy(alpha = .10f)
    // Elevation scale. Cards stay flat by default; the collection lifts the selected card and the
    // detail page lifts its card, so the stack never pays for a shadow on every face.
    val cardElevationRest = 0.dp
    val cardElevationSelected = 6.dp
    val cardElevationDragging = 12.dp
    // Soft neutral shadows. A near-black background needs a stronger one to keep the card readable.
    val cardShadowAmbient: Color = Color.Black.copy(alpha = .05f)
    val cardShadowSpot: Color = Color.Black.copy(alpha = .10f)
    val cardShadowAmbientDark: Color = Color.Black.copy(alpha = .26f)
    val cardShadowSpotDark: Color = Color.Black.copy(alpha = .38f)
    // Home choreography (UI V2). These values only describe app chrome: card artwork, the card
    // aspect ratio and the stack advance keep their existing geometry.
    val homeTopGap = 7.dp
    val homeToolGap = 3.dp
    val homeControlRadius = 5.dp
    val homeBadgeMinWidth = 24.dp
    val homeLayoutSwitchWidth = 168.dp
    val homeSearchGap = 6.dp
    val homeChipGap = 2.dp
    val homeFilterHeight = 52.dp
    val homeFilterRadius = 24.dp
    val homeFilterWidthFraction = .4f
    val homeFilterMinWidth = 120.dp
    val homeFilterMinWidthLargeText = 140.dp
    val homeFilterAlpha = .38f
    val homeFilterBorderAlpha = .38f
    val homeFilterPillHeight = 36.dp
    val homeFilterPillInset = 14.dp
    val homeFilterItemGap = 2.dp
    val homeFilterContentInset = 6.dp
    val homeFabVisual = 40.dp
    val homeSettingsVisual = 38.dp
    val homeFabEndInset = 12.dp
    val homeFabBottomInset = 144.dp
    val homeBottomActionInset = 16.dp
    val shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(groupRadius), extraLarge = RoundedCornerShape(28.dp))
    val cardShape: Shape = continuousCornerShape(cardRadius)
}

/** Elevation with a theme-aware soft shadow; zero elevation keeps the card flat and layer-free. */
@Composable
fun Modifier.cardShadow(elevation: Dp): Modifier {
    if (elevation <= 0.dp) return this
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return shadow(elevation, WalletDesign.cardShape, clip = false,
        ambientColor = if (dark) WalletDesign.cardShadowAmbientDark else WalletDesign.cardShadowAmbient,
        spotColor = if (dark) WalletDesign.cardShadowSpotDark else WalletDesign.cardShadowSpot)
}

internal fun walletColors(palette: AppPalette, dark: Boolean): ColorScheme {
    val accent = when (palette) {
        AppPalette.GLACIER -> if(dark) 0xFFAACDE3 else 0xFF315E79
        AppPalette.CHAMPAGNE -> if(dark) 0xFFE5C69F else 0xFF765333
        else -> if(dark) 0xFFF0F0F2 else 0xFF242428
    }.let { Color(it) }
    val background = Color(if(dark) when(palette) {
        AppPalette.GLACIER -> 0xFF0E171E; AppPalette.CHAMPAGNE -> 0xFF1B1713; else -> 0xFF101012
    } else when(palette) {
        AppPalette.GLACIER -> 0xFFEFF6FA; AppPalette.CHAMPAGNE -> 0xFFF7F2E9; else -> 0xFFF6F6F8
    })
    val surface = Color(if(dark) when(palette) {
        AppPalette.GLACIER -> 0xFF182630; AppPalette.CHAMPAGNE -> 0xFF29231D; else -> 0xFF1D1D20
    } else when(palette) {
        AppPalette.GLACIER -> 0xFFFAFDFF; AppPalette.CHAMPAGNE -> 0xFFFFFCF6; else -> 0xFFFFFFFF
    })
    val container = Color(if(dark) when(palette) {
        AppPalette.GLACIER -> 0xFF293D4B; AppPalette.CHAMPAGNE -> 0xFF41352A; else -> 0xFF323236
    } else when(palette) {
        AppPalette.GLACIER -> 0xFFDDEBF4; AppPalette.CHAMPAGNE -> 0xFFEEE2D1; else -> 0xFFE9E9ED
    })
    val ink = Color(if(dark) 0xFFF2F2F4 else 0xFF202126)
    val muted = Color(if(dark) 0xFFC2C3CA else 0xFF5C5F67)
    val base = if(dark) darkColorScheme() else lightColorScheme()
    return base.copy(primary = accent, onPrimary = if(dark) Color(0xFF17191D) else Color.White,
        primaryContainer = container, onPrimaryContainer = ink, inversePrimary = if(dark) Color(0xFF333338) else Color(0xFFEEEEF2),
        secondary = accent, onSecondary = if(dark) Color(0xFF17191D) else Color.White,
        secondaryContainer = container, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = if(dark) Color(0xFF17191D) else Color.White,
        tertiaryContainer = container, onTertiaryContainer = ink,
        background = background, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = container, onSurfaceVariant = muted, surfaceTint = Color.Transparent,
        surfaceContainerLowest = background, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = container, surfaceContainerHighest = container,
        surfaceBright = surface, surfaceDim = background,
        outline = if(dark) Color(0xFF92949D) else Color(0xFF747780),
        outlineVariant = if(dark) Color(0xFF42454D) else Color(0xFFD7D9DF))
}

@Composable
fun MyCardTheme(darkTheme: Boolean = false, palette: AppPalette = if(darkTheme) AppPalette.BLACK else AppPalette.WHITE,
                content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = walletColors(palette, darkTheme), shapes = WalletDesign.shapes,
        typography = Typography, content = content)
}
