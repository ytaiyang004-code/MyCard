package com.mycard.wallet

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.mycard.wallet.data.CardRepository
import com.mycard.wallet.data.WalletCard
import com.mycard.wallet.ui.Copy
import com.mycard.wallet.ui.WalletApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Actual snackbar actions against an isolated Robolectric database and software test key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataOperationUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val mounted = mutableStateOf(true)
    private val app get() = RuntimeEnvironment.getApplication()
    private val art get() = File(app.noBackupFilesDir, "card_art")

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000) {
        ShadowLooper.idleMainLooper(); condition()
    }
    private fun launch(): WalletViewModel {
        val repo = CardRepository(app, TestVault())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WalletViewModel(app, repo) as T
        }
        val vm = ViewModelProvider(store, factory)[WalletViewModel::class.java]
        compose.setContent { if (mounted.value) WalletApp(vm, {}, {}) else Text("Disposed wallet UI") }
        waitFor { vm.cardsLoaded.value && vm.settings.value != null }
        runBlocking { vm.preferences.boolean("privacyLock", false); vm.preferences.boolean("animations", false) }
        return vm
    }
    private fun delete(vm: WalletViewModel): String {
        val path = runBlocking {
            vm.repository.writeImage(byteArrayOf(1, 2, 3)).also {
                vm.repository.save(WalletCard(bank = "Synthetic snackbar card", lastFour = "1234", imagePath = it))
            }
        }
        compose.runOnIdle { vm.delete(runBlocking { vm.repository.all().single() }) { } }
        waitFor { compose.onAllNodesWithText(Copy.undo).fetchSemanticsNodes().isNotEmpty() }
        return path
    }
    @After fun clearModels() { store.clear(); ShadowLooper.idleMainLooper() }

    @Test fun clickingTheOldSnackbarAfterClearDoesNotRestoreData() {
        val vm = launch()
        delete(vm)
        runBlocking { vm.repository.clear() }
        compose.onNodeWithText(Copy.undo).performClick()
        waitFor { compose.onAllNodesWithText(Copy.undo).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertTrue(runBlocking { vm.repository.all().isEmpty() })
        assertTrue(art.listFiles().isNullOrEmpty())
    }

    @Test fun clickingTheOldSnackbarAfterRestoreKeepsRestoredData() {
        val vm = launch()
        delete(vm)
        val restored = WalletCard(bank = "Synthetic restored card", lastFour = "5678")
        runBlocking { vm.repository.merge(listOf(restored)) }
        compose.onNodeWithText(Copy.undo).performClick()
        waitFor { compose.onAllNodesWithText(Copy.undo).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertEquals(listOf(restored), runBlocking { vm.repository.all() })
    }

    @Test fun disposingASnackbarExpiresItsTokenAndReleasesUnusedArtwork() {
        val vm = launch()
        val path = delete(vm)
        assertTrue(File(art, path).isFile)
        compose.runOnIdle { mounted.value = false }
        waitFor { !File(art, path).exists() }
        assertTrue(runBlocking { vm.repository.all().isEmpty() })
    }
}
