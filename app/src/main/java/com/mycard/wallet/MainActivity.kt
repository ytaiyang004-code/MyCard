package com.mycard.wallet

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mycard.wallet.ui.WalletApp

class MainActivity : FragmentActivity() {
    private val vm: WalletViewModel by viewModels()
    private lateinit var prompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Reattach the callback on every Activity creation, including a rotation while authenticating.
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                vm.authenticating = false
                vm.unlocked.value = true
                vm.pendingLockChange?.let { vm.boolean("privacyLock", it) }
                vm.pendingLockChange = null
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                vm.authenticating = false
                vm.pendingLockChange = null
                Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
            }
        })
        setContent { WalletApp(vm, ::authenticate, onLockedBack = { moveTaskToBack(true) }) }
    }

    override fun onStop() {
        super.onStop()
        // Temporary focus loss (screenshot UI / IME) is not an app exit. Configuration changes
        // preserve the session. A system credential Activity can lock the UI; its prompt callback
        // remains attached and successful authentication unlocks the retained page normally.
        vm.onHostStopped(isChangingConfigurations)
    }

    private fun authenticate(lockChange: Boolean?) {
        if (vm.authenticating) return
        val types = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(types) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "请先在系统设置中启用指纹、人脸或锁屏密码，再使用隐私锁。", Toast.LENGTH_LONG).show()
            return
        }
        vm.pendingLockChange = lockChange
        vm.authenticating = true
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("解锁 MyCard")
            .setSubtitle("验证身份以访问私人卡包").setAllowedAuthenticators(types).build())
    }
}
