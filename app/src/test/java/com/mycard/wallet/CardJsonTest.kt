package com.mycard.wallet

import com.mycard.wallet.data.*
import org.junit.Assert.*
import org.junit.Test

class CardJsonTest {
    private val card = WalletCard(bank = "自定义发卡机构", network = Network.OTHER, type = CardType.VIRTUAL,
        lastFour = "0007", nickname = "旅行", tags = "海外", notes = "仅收藏", faceMode = FaceMode.IMAGE,
        imagePath = "12345678-1234-1234-1234-123456789abc.bin", template = 5, primary = 0xFF123456,
        secondary = 0xFFABCDEF, gradient = false, texture = false, tailMode = TailMode.BADGE, tailPosition = TailPosition.TOP,
        pinned = true, archived = true, position = 17, createdAt = 1000, updatedAt = 2000)
    @Test fun allFieldsRoundTripWithoutLoss() { assertEquals(card, CardJson.decode(CardJson.encode(card))) }
    @Test fun switchingModesRetainsTemplateAndImage() {
        val switched = card.copy(faceMode = FaceMode.TEMPLATE).copy(faceMode = FaceMode.IMAGE)
        assertEquals(card, CardJson.decode(CardJson.encode(switched)))
    }
    @Test(expected = IllegalArgumentException::class) fun maliciousImagePathRejected() {
        CardJson.decode(CardJson.encode(card).put("imagePath", "../wallet.db"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidNetworkRejected() {
        CardJson.decode(CardJson.encode(card).put("network", "BAD"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidTailRejected() {
        CardJson.decode(CardJson.encode(card).put("lastFour", "1234567812345678"))
    }
}
