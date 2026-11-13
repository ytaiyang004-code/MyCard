package com.mycard.wallet

import com.mycard.wallet.data.*
import com.mycard.wallet.security.VaultCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorOperationTest {
    @Test fun delayedSaveDoesNotClearANewerDraftOrNavigateFromIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = TestVault()
        val vault = object : VaultCipher {
            override fun encrypt(bytes: ByteArray, context: String): ByteArray {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return delegate.encrypt(bytes, context)
            }
            override fun decrypt(bytes: ByteArray, context: String) = delegate.decrypt(bytes, context)
        }
        val repo = CardRepository(RuntimeEnvironment.getApplication(), vault)
        val vm = WalletViewModel(RuntimeEnvironment.getApplication(), repo)
        vm.beginEdit(null)
        vm.draft.value = vm.draft.value!!.copy(bank = "已提交保存", lastFour = "1234")
        var navigated = false
        try {
            vm.saveDraft { navigated = true }
            ShadowLooper.idleMainLooper()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            vm.discardDraft()
            vm.beginEdit(null)
            val newDraft = vm.draft.value!!.copy(bank = "新的草稿")
            vm.draft.value = newDraft
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (vm.busy.value && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper(); Thread.yield()
            }
            assertFalse(vm.busy.value)
            assertFalse(navigated)
            assertEquals(newDraft, vm.draft.value)
            assertEquals("已提交保存", runBlocking { repo.all().single().bank })
        } finally { release.countDown() }
    }
    @Test fun rotationKeepsSessionButBackgroundLocksEvenDuringAuthentication() {
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        vm.unlocked.value = true
        vm.onHostStopped(true)
        assertTrue(vm.unlocked.value)
        vm.authenticating = true
        vm.onHostStopped(false)
        assertFalse(vm.unlocked.value)
        assertTrue(vm.authenticating)
        vm.authenticating = false
        vm.onHostStopped(false)
        assertFalse(vm.unlocked.value)
    }
}
