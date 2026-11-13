package com.mycard.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.*
import com.mycard.wallet.data.*

@Composable
fun DetailScreen(id: String, vm: WalletViewModel, all: List<WalletCard>, settings: WalletSettings,
                 onBack: () -> Unit, onEdit: (WalletCard) -> Unit) {
    val archived = remember { all.find { it.id == id }?.archived ?: false }
    val cards = CardRules.select(all, "", if (archived) CardFilter.ARCHIVED else CardFilter.ALL, settings.sort)
    val pager = rememberPagerState(initialPage = cards.indexOfFirst { it.id == id }.coerceAtLeast(0), pageCount = { cards.size })
    var deleting by remember { mutableStateOf<WalletCard?>(null) }
    val context = LocalContext.current
    Scaffold(topBar = { PageTopBar(Copy.details, onBack) }) { padding ->
        if (cards.isEmpty()) {
            LaunchedEffect(Unit) { onBack() }
        } else {
            HorizontalPager(pager, Modifier.fillMaxSize().padding(padding), key = { cards[it].id }) { page ->
                val c = cards[page]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = WalletDesign.pageInset),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CardFace(c, settings, vm.repository, elevation = WalletDesign.cardElevationSelected)
                    Text("${page + 1} / ${cards.size}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(c.title, style = MaterialTheme.typography.headlineSmall)
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            DetailRow(Copy.bank, c.bank); DetailRow(Copy.network, c.networkName)
                            DetailRow(Copy.type, c.type.label); DetailRow(Copy.lastFour, c.lastFour)
                            if (c.nickname.isNotBlank()) DetailRow(Copy.nickname, c.nickname)
                            if (c.tags.isNotBlank()) DetailRow(Copy.tags, c.tags)
                            if (c.notes.isNotBlank()) DetailRow(Copy.notes, c.notes)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ onEdit(c) }, Modifier.weight(1f).heightIn(min = WalletDesign.touch)) { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(Copy.edit) }
                        OutlinedButton({
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(Copy.lastFour, c.lastFour)
                            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                            clipboard.setPrimaryClip(clip)
                            vm.runTask { vm.notices.send(Notice(Copy.copied)) }
                        }, Modifier.weight(1f).heightIn(min = WalletDesign.touch)) { Text(Copy.copy) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ vm.update(c.copy(pinned = !c.pinned)) }, Modifier.weight(1f).heightIn(min = WalletDesign.touch)) { Text(if (c.pinned) Copy.unpin else Copy.pin) }
                        OutlinedButton({ vm.save(c.copy(archived = !c.archived), onBack) }, Modifier.weight(1f).heightIn(min = WalletDesign.touch)) { Text(if (c.archived) Copy.unarchive else Copy.archive) }
                    }
                    TextButton({ deleting = c }, Modifier.fillMaxWidth().heightIn(min = WalletDesign.touch)) { Text(Copy.delete, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    deleting?.let { c -> ConfirmDialog(Copy.delete, Copy.deleteBody, Copy.delete, { deleting = null }) {
        deleting = null; vm.delete(c, onBack)
    } }
}
@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
