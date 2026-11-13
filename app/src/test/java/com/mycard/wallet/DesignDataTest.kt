package com.mycard.wallet

import com.mycard.wallet.data.*
import com.mycard.wallet.ui.ColorMath
import com.mycard.wallet.ui.HsvColor
import com.mycard.wallet.ui.TemplateCatalog
import org.junit.Assert.*
import org.junit.Test

class DesignDataTest {
    private val legacy = WalletCard(bank = "中国银行", lastFour = "0012", template = 4, primary = 0xFF123456, secondary = 0xFF987654,
        notes = "原有备注", nickname = "原有昵称", tags = "原有用途", pinned = true)
    @Test fun legacyPayloadDefaultsDoNotResetAnyExistingField() {
        val json = CardJson.encode(legacy)
        listOf("showWordmark", "showNotes", "logoPosition", "amexLayout").forEach(json::remove)
        val restored = CardJson.decode(json)
        assertEquals(legacy, restored)
        assertFalse(restored.showNotes)
        assertTrue(restored.showWordmark)
    }
    @Test fun designFieldsSurviveStorageEncodingAndModeChanges() {
        val card = legacy.copy(template = 15, showWordmark = false, showNotes = true,
            logoPosition = LogoPosition.TOP_RIGHT, amexLayout = true)
        assertEquals(card, CardJson.decode(CardJson.encode(card)))
        assertEquals(card, card.copy(faceMode = FaceMode.IMAGE).copy(faceMode = FaceMode.TEMPLATE))
    }
    @Test fun allSixOldIdsAndTenNewIdsStayValid() {
        assertEquals(listOf("极简纯色", "深色商务", "蓝色科技", "磨砂金属", "优雅金色", "几何纹理"), TemplateCatalog.names.take(6))
        assertEquals(16, TemplateCatalog.names.size)
        assertEquals(16, TemplateCatalog.colors.size)
        (0..15).forEach { assertTrue(CardRules.valid(legacy.copy(template = it))) }
        assertFalse(CardRules.valid(legacy.copy(template = 16)))
    }
    @Test fun hsvPrimaryColorsGrayAndBlackHaveCorrectValues() {
        assertEquals(0xFFFF0000, ColorMath.toArgb(HsvColor(0f, 1f, 1f)))
        assertEquals(0xFF00FF00, ColorMath.toArgb(HsvColor(120f, 1f, 1f)))
        assertEquals(0xFF0000FF, ColorMath.toArgb(HsvColor(240f, 1f, 1f)))
        assertEquals(0xFF808080, ColorMath.toArgb(HsvColor(90f, 0f, .5f)))
        assertEquals(0xFF000000, ColorMath.toArgb(HsvColor(270f, 1f, 0f)))
        assertEquals(0xFFFF0000, ColorMath.toArgb(HsvColor(360f, 1f, 1f)))
    }
    @Test fun allRecommendedColorsRoundTripThroughHsv() {
        TemplateCatalog.swatches.forEach { assertEquals(it, ColorMath.toArgb(ColorMath.fromArgb(it))) }
        assertEquals(0f, ColorMath.fromArgb(0xFF000000).saturation)
    }
    @Test(expected = IllegalArgumentException::class) fun unknownLogoPositionRejectedWithoutSilentReset() {
        CardJson.decode(CardJson.encode(legacy).put("logoPosition", "BAD"))
    }
}
