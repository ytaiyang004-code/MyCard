package com.mycard.wallet

import com.mycard.wallet.data.*
import org.junit.Assert.*
import org.junit.Test

class CardRulesTest {
    private fun card(bank: String = "招商银行", tail: String = "1234") = WalletCard(bank = bank, lastFour = tail)
    @Test fun emptyWallet() { assertTrue(CardRules.select(emptyList(), "", CardFilter.ALL, SortOrder.CUSTOM).isEmpty()) }
    @Test fun suffixOnlyAcceptsFourAsciiDigits() {
        assertEquals("1234", CardRules.digits("12a3 45678"))
        assertEquals("", CardRules.digits("１２٣四"))
        assertEquals("0001", CardRules.digits("0001"))
    }
    @Test fun incompleteSuffixRejected() {
        assertFalse(CardRules.valid(card(tail = "123")))
        assertFalse(CardRules.valid(card(tail = "12345")))
        assertTrue(CardRules.valid(card()))
    }
    @Test fun requiresBankAndValidImagePath() {
        assertFalse(CardRules.valid(card(bank = " ")))
        assertFalse(CardRules.valid(card().copy(faceMode = FaceMode.IMAGE)))
        assertFalse(CardRules.valid(card().copy(imagePath = "../../secret")))
        assertTrue(CardRules.valid(card().copy(faceMode = FaceMode.TEMPLATE, imagePath = null)))
    }
    @Test fun searchAcrossAllRequestedFields() {
        val cards = listOf(card().copy(network = Network.VISA, nickname = "旅行", tags = "海外 购物"))
        listOf("招商", "visa", "1234", "旅行", "海外").forEach {
            assertEquals(it, 1, CardRules.select(cards, it, CardFilter.ALL, SortOrder.CUSTOM).size)
        }
        assertTrue(CardRules.select(cards, "9999", CardFilter.ALL, SortOrder.CUSTOM).isEmpty())
    }
    @Test fun archivesAreExcludedUnlessRequested() {
        val cards = listOf(card(), card().copy(archived = true))
        assertEquals(1, CardRules.select(cards, "", CardFilter.ALL, SortOrder.CUSTOM).size)
        assertTrue(CardRules.select(cards, "", CardFilter.ARCHIVED, SortOrder.CUSTOM).single().archived)
    }
    @Test fun networkAndTypeFilters() {
        val cards = listOf(card().copy(network = Network.VISA, type = CardType.VIRTUAL),
            card().copy(network = Network.JCB, type = CardType.CREDIT))
        assertEquals(Network.VISA, CardRules.select(cards, "", CardFilter.VISA, SortOrder.CUSTOM).single().network)
        assertEquals(Network.JCB, CardRules.select(cards, "", CardFilter.OTHER, SortOrder.CUSTOM).single().network)
        assertEquals(CardType.VIRTUAL, CardRules.select(cards, "", CardFilter.VIRTUAL, SortOrder.CUSTOM).single().type)
        assertEquals(CardType.CREDIT, CardRules.select(cards, "", CardFilter.CREDIT, SortOrder.CUSTOM).single().type)
    }
    @Test fun pinnedBeforeCustomAndDateSort() {
        val pinned = card().copy(pinned = true, position = 99, createdAt = 0)
        val first = card().copy(position = 0, createdAt = 2)
        val second = card().copy(position = 1, createdAt = 1)
        val cards = listOf(second, pinned, first)
        assertEquals(listOf(pinned, first, second), CardRules.select(cards, "", CardFilter.ALL, SortOrder.CUSTOM))
        assertEquals(listOf(pinned, second, first), CardRules.select(cards, "", CardFilter.ALL, SortOrder.OLDEST))
        assertEquals(listOf(pinned, first, second), CardRules.select(cards, "", CardFilter.ALL, SortOrder.NEWEST))
    }
    @Test fun fiveThousandCardsRemainSortableAndSearchable() {
        val cards = (0 until 5000).map { card("银行$it", (it % 10000).toString().padStart(4, '0')).copy(position = it.toLong()) }
        assertEquals(5000, CardRules.select(cards, "", CardFilter.ALL, SortOrder.CUSTOM).size)
        assertEquals("银行4321", CardRules.select(cards, "4321", CardFilter.ALL, SortOrder.CUSTOM).single().bank)
    }
}
