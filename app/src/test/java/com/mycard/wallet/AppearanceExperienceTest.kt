package com.mycard.wallet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.*
import androidx.core.graphics.ColorUtils
import com.mycard.wallet.data.*
import com.mycard.wallet.data.Preferences
import com.mycard.wallet.ui.*
import com.mycard.wallet.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import androidx.compose.ui.graphics.toArgb

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var original: WalletSettings
    @Before fun snapshotPreferences() { original = runBlocking { Preferences(RuntimeEnvironment.getApplication()).flow.first() } }
    @After fun restorePreferences(): Unit = runBlocking {
        val prefs=Preferences(RuntimeEnvironment.getApplication())
        prefs.text("theme",original.theme.name); prefs.boolean("stacked",original.stacked)
        prefs.boolean("tailBadge",original.tailBadge); prefs.boolean("animations",original.animations)
        prefs.boolean("privacyLock",original.privacyLock); prefs.text("sort",original.sort.name)
        original.paletteOverride?.let { prefs.text("palette",it.name) }
        original.followSystem?.let { prefs.boolean("followSystem",it) }
        original.layoutOverride?.let { prefs.layout(it) }
    }

    @Test fun legacyPreferencesAndUnknownChoicesRemainReadable() {
        assertEquals(AppPalette.WHITE, Preferences.decode(emptyPreferences()).palette)
        for(theme in ThemeMode.entries) for(stack in listOf(true,false)) {
            val value = Preferences.decode(mutablePreferencesOf(stringPreferencesKey("theme") to theme.name, booleanPreferencesKey("stacked") to stack))
            assertEquals(theme, value.theme)
            assertEquals(theme==ThemeMode.SYSTEM, value.usesSystem)
            assertEquals(if(stack) CardLayout.STACK else CardLayout.LIST, value.layout)
        }
        val bad=Preferences.decode(mutablePreferencesOf(stringPreferencesKey("palette") to "FUTURE",stringPreferencesKey("layout") to "FUTURE"))
        assertEquals(AppPalette.WHITE,bad.palette); assertEquals(CardLayout.STACK,bad.layout)
    }
    @Test fun removedGalleryArrangementFallsBackToAlbumAndOtherPreferencesSurvive() {
        val decoded = Preferences.decode(mutablePreferencesOf(
            stringPreferencesKey("layout") to Preferences.LEGACY_GALLERY,
            booleanPreferencesKey("stacked") to false,
            stringPreferencesKey("theme") to ThemeMode.DARK.name,
            booleanPreferencesKey("tailBadge") to true))
        assertEquals(CardLayout.ALBUM, decoded.layoutOverride)
        assertEquals(CardLayout.ALBUM, decoded.layout)
        assertEquals(ThemeMode.DARK, decoded.theme)
        assertTrue(decoded.tailBadge)
        // The album also wins when the legacy stacked flag disagreed with the stored arrangement.
        assertEquals(CardLayout.ALBUM, Preferences.decode(mutablePreferencesOf(
            stringPreferencesKey("layout") to Preferences.LEGACY_GALLERY,
            booleanPreferencesKey("stacked") to true)).layout)
        // Unknown future values still fall back to the legacy stacked flag.
        assertEquals(CardLayout.LIST, Preferences.decode(mutablePreferencesOf(
            stringPreferencesKey("layout") to "FUTURE", booleanPreferencesKey("stacked") to false)).layout)
    }
    @Test fun manualThemeOverridesSystemAndAllPaletteTextHasAccessibleContrast() {
        for(p in AppPalette.entries) for(dark in listOf(false,true)) {
            val colors=walletColors(p,dark)
            assertTrue(ColorUtils.calculateContrast(colors.onSurface.toArgb(), colors.surface.toArgb()) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(colors.onSurfaceVariant.toArgb(), colors.surface.toArgb()) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(colors.onPrimary.toArgb(), colors.primary.toArgb()) >= 4.5)
            val manual=WalletSettings(paletteOverride=p,followSystem=false)
            assertEquals(p==AppPalette.BLACK,manual.dark(dark))
            assertEquals(dark,manual.copy(followSystem=true).dark(dark))
        }
    }
    @Test fun paletteAndLayoutPersistAtomicallyWithoutChangingOtherPreferences() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val prefs=Preferences(app)
        prefs.boolean("tailBadge",true); prefs.boolean("privacyLock",true)
        prefs.text("sort",SortOrder.OLDEST.name)
        for(palette in AppPalette.entries) {
            prefs.boolean("followSystem",true);prefs.palette(palette)
            val restored=Preferences(app).flow.first()
            assertEquals(palette,restored.palette);assertFalse(restored.usesSystem)
            assertTrue(restored.tailBadge);assertTrue(restored.privacyLock);assertEquals(SortOrder.OLDEST,restored.sort)
        }
        for(layout in CardLayout.entries) {
            prefs.layout(layout); assertEquals(layout, Preferences(app).flow.first().layout)
        }
        prefs.boolean("stacked",false); assertEquals(CardLayout.LIST,prefs.flow.first().layout)
    }
    @Test fun settingsAllFourThemesAreLiveAtNarrowWidthAndLargeFont() {
        val app=RuntimeEnvironment.getApplication()
        val vm=WalletViewModel(app,CardRepository(app,TestVault()))
        compose.setContent {
            val settings by vm.settings.collectAsState()
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                settings?.let { MyCardTheme(it.dark(false),it.palette) { SettingsScreen(vm,it,{},{}) } }
            }
        }
        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); vm.settings.value!=null }
        for(palette in AppPalette.entries) {
            compose.onNodeWithText(palette.label).performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); vm.settings.value?.palette==palette }
            compose.runOnIdle {
                val view=compose.activity.window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File("build/ui-previews/v1.2.0/settings-${palette.name}.png").apply{parentFile!!.mkdirs()}.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
        compose.onNodeWithText(Copy.backup).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(Copy.clear).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(Copy.changeLog).performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodesWithText(Copy.changeLog).onLast().assertIsDisplayed()
    }
    // The CITIC emblem test was removed from the public edition: that artwork is a third-party
    // trademark and is not redistributed here. Bank and network identities render as text instead;
    // see BRAND_NOTICE.md and docs/BRAND_ASSET_INVENTORY.md.
}
