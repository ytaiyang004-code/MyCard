package com.mycard.wallet.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.mycard.wallet.WalletViewModel
import com.mycard.wallet.data.ThemeMode
import com.mycard.wallet.ui.theme.MyCardTheme
import com.mycard.wallet.ui.theme.WalletMotion
import kotlinx.coroutines.flow.receiveAsFlow

// Keep page state mounted during locking, but remove its separate windows so none can cover the lock.
internal val LocalWalletVisible = compositionLocalOf { true }

/** A completion belongs to one entry, never whichever screen happens to be current later. */
internal fun NavHostController.returnFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id != entry.id) return
    if (previousBackStackEntry == null || !popBackStack()) {
        navigate("home") { popUpTo(graph.id); launchSingleTop = true }
    }
}

@Composable
fun WalletApp(vm: WalletViewModel, authenticate: (Boolean?) -> Unit, onLockedBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val loaded by vm.cardsLoaded.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val current = settings
    val locked = current?.privacyLock == true && !vm.unlocked.value
    val dark = current?.dark(isSystemInDarkTheme()) ?: false
    MyCardTheme(dark, current?.palette ?: com.mycard.wallet.data.AppPalette.WHITE) {
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.context as? android.app.Activity)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) {
            vm.notices.receiveAsFlow().collect { notice ->
                var undoRequested = false
                try {
                    val result = snackbar.showSnackbar(notice.message, if (notice.undo != null) Copy.undo else null,
                        withDismissAction = true, duration = if (notice.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) {
                        undoRequested = true
                        notice.undo?.let(vm::undo)
                    }
                } finally {
                    // Recreation can cancel showSnackbar before it returns a dismissal result.
                    if (!undoRequested) notice.undo?.let(vm::finishDelete)
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error ?: Copy.loading) }
            } else {
                CompositionLocalProvider(LocalWalletVisible provides !locked) {
                    Box(Modifier.fillMaxSize()) {
                        // Never remove the NavHost for a privacy-lock or a transient lifecycle event.
                        NavHost(nav, startDestination = "home",
                            enterTransition = { if (current.animations) fadeIn(tween(WalletMotion.enter, easing = WalletMotion.easing)) + slideInHorizontally(tween(WalletMotion.enter, easing = WalletMotion.easing)) { it / 20 } else EnterTransition.None },
                            exitTransition = { if (current.animations) fadeOut(tween(WalletMotion.quick, easing = WalletMotion.easing)) else ExitTransition.None },
                            popEnterTransition = { if(current.animations) fadeIn(tween(WalletMotion.popEnter, easing = WalletMotion.easing)) + slideInHorizontally(tween(WalletMotion.popEnter, easing = WalletMotion.easing)) { -it / 20 } else EnterTransition.None },
                            popExitTransition = { if(current.animations) fadeOut(tween(WalletMotion.quick, easing = WalletMotion.easing)) + slideOutHorizontally(tween(WalletMotion.quick, easing = WalletMotion.easing)) { it / 20 } else ExitTransition.None }) {
                            composable("home") {
                                HomeScreen(vm, cards, current,
                                    onAdd = { vm.beginEdit(null); nav.navigate("editor") { launchSingleTop = true } },
                                    onOpen = { nav.navigate("detail/$it") { launchSingleTop = true } },
                                    onSettings = { nav.navigate("settings") { launchSingleTop = true } },
                                    onReorder = { nav.navigate("reorder") { launchSingleTop = true } })
                            }
                            composable("editor") { entry ->
                                val draft by vm.draft.collectAsStateWithLifecycle()
                                // Missing in-memory drafts after process death return safely once.
                                if (draft == null) LaunchedEffect(entry.id) { nav.returnFrom(entry) }
                                else EditorScreen(vm, current) { nav.returnFrom(entry) }
                            }
                            composable("detail/{id}") { entry ->
                                if (loaded) DetailScreen(entry.arguments?.getString("id").orEmpty(), vm, cards, current,
                                    onBack = { nav.returnFrom(entry) },
                                    onEdit = { vm.beginEdit(it); nav.navigate("editor") { launchSingleTop = true } })
                                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(Copy.loading) }
                            }
                            composable("settings") { entry ->
                                SettingsScreen(vm, current, onBack = { nav.returnFrom(entry) }, onLockChange = { authenticate(it) })
                            }
                            composable("reorder") { entry -> ReorderScreen(vm, cards) { nav.returnFrom(entry) } }
                        }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
                        if (error != null && !locked) AlertDialog(onDismissRequest = { vm.error.value = null },
                            title = { Text("卡包读取异常") }, text = { Text(error.orEmpty()) },
                            confirmButton = { TextButton({ vm.error.value = null; nav.navigate("settings") { launchSingleTop = true } }) { Text("前往本地恢复") } })
                    }
                }
                if (locked) Dialog(onDismissRequest = onLockedBack,
                    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Rounded.Lock, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(24.dp))
                            Text("MyCard", style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(12.dp)); Text(Copy.locked)
                            Spacer(Modifier.height(28.dp))
                            Button({ authenticate(null) }) { Text(Copy.unlock) }
                        }
                    }
                }
            }
        }
    }
}
