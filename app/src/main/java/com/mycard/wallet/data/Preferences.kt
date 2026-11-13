package com.mycard.wallet.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.walletPreferences by preferencesDataStore(name = "wallet_settings")
enum class AppPalette(val label: String) { WHITE("极简白"), BLACK("极简黑"), GLACIER("冰川蓝"), CHAMPAGNE("香槟米") }
enum class CardLayout(val label: String) { STACK("堆叠卡包"), LIST("完整列表"), ALBUM("翻页卡册") }

@androidx.compose.runtime.Immutable
data class WalletSettings(
    // Legacy keys remain readable; new choices are independent of card-face colors.
    val theme: ThemeMode = ThemeMode.LIGHT,
    val stacked: Boolean = true,
    val tailBadge: Boolean = false,
    val animations: Boolean = true,
    val sort: SortOrder = SortOrder.CUSTOM,
    val privacyLock: Boolean = false,
    val paletteOverride: AppPalette? = null,
    val followSystem: Boolean? = null,
    val layoutOverride: CardLayout? = null
) {
    val palette get() = paletteOverride ?: if (theme == ThemeMode.DARK) AppPalette.BLACK else AppPalette.WHITE
    val usesSystem get() = followSystem ?: (theme == ThemeMode.SYSTEM)
    val layout get() = layoutOverride ?: if (stacked) CardLayout.STACK else CardLayout.LIST
    fun dark(systemDark: Boolean) = if (usesSystem) systemDark else palette == AppPalette.BLACK
}

class Preferences(context: Context) {
    private val store = context.walletPreferences
    val flow = store.data.map(::decode)
    suspend fun boolean(key: String, value: Boolean) { store.edit {
        it[booleanPreferencesKey(key)] = value
        if (key == "stacked") it.remove(stringPreferencesKey("layout"))
    } }
    suspend fun text(key: String, value: String) { store.edit {
        it[stringPreferencesKey(key)] = value
        if (key == "theme") { it.remove(stringPreferencesKey("palette")); it.remove(booleanPreferencesKey("followSystem")) }
    } }
    suspend fun palette(value: AppPalette) { store.edit {
        it[stringPreferencesKey("palette")] = value.name
        it[booleanPreferencesKey("followSystem")] = false
        it[stringPreferencesKey("theme")] = if (value == AppPalette.BLACK) "DARK" else "LIGHT"
    } }
    suspend fun layout(value: CardLayout) { store.edit {
        it[stringPreferencesKey("layout")] = value.name
        it[booleanPreferencesKey("stacked")] = value == CardLayout.STACK
    } }
    companion object {
        /** 1.2.0 stored this value for the removed gallery arrangement; it now opens the album pages. */
        internal const val LEGACY_GALLERY = "GALLERY"
        internal fun decodeLayout(stored: String?): CardLayout? =
            CardLayout.entries.find { it.name == stored }
                ?: if (stored == LEGACY_GALLERY) CardLayout.ALBUM else null
        internal fun decode(p: androidx.datastore.preferences.core.Preferences) = WalletSettings(
            theme = ThemeMode.entries.find { it.name == p[stringPreferencesKey("theme")] } ?: ThemeMode.LIGHT,
            stacked = p[booleanPreferencesKey("stacked")] ?: true,
            tailBadge = p[booleanPreferencesKey("tailBadge")] ?: false,
            animations = p[booleanPreferencesKey("animations")] ?: true,
            sort = SortOrder.entries.find { it.name == p[stringPreferencesKey("sort")] } ?: SortOrder.CUSTOM,
            privacyLock = p[booleanPreferencesKey("privacyLock")] ?: false,
            paletteOverride = AppPalette.entries.find { it.name == p[stringPreferencesKey("palette")] },
            followSystem = p[booleanPreferencesKey("followSystem")],
            layoutOverride = decodeLayout(p[stringPreferencesKey("layout")])
        )
    }
}
