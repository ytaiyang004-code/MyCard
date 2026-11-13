package com.mycard.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.WalletViewModel
import com.mycard.wallet.data.*

@Composable
fun EditorScreen(vm: WalletViewModel, settings: WalletSettings, onBack: () -> Unit) {
    val draft by vm.draft.collectAsState()
    val card = draft ?: return
    val saving by vm.busy.collectAsState()
    val busy = saving || vm.importWorking.value
    var discard by remember { mutableStateOf(false) }
    fun change(value: WalletCard) { vm.draft.value = value }
    val leave = { if (vm.draft.value != vm.originalDraft || vm.draftImage.value != null) discard = true else { vm.discardDraft(); onBack() } }
    BackHandler(enabled = LocalWalletVisible.current) { leave() }
    Scaffold(topBar = { PageTopBar(if (vm.editingNew) Copy.add else Copy.edit, leave) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button({ vm.saveDraft(onBack) }, enabled = !busy && card.bank.isNotBlank() &&
                    card.lastFour.length == 4 && (card.faceMode == FaceMode.TEMPLATE || card.imagePath != null || vm.draftImage.value != null),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).heightIn(min = WalletDesign.touch)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(Copy.save)
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = WalletDesign.pageInset),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardFace(card, settings, vm.repository, previewImage = vm.draftImage.value)
            Choices(FaceMode.entries, card.faceMode, { if (it == FaceMode.IMAGE) Copy.realFace else Copy.template }) { change(card.copy(faceMode = it)) }
            if (card.faceMode == FaceMode.IMAGE) {
                ImageImportSection(vm)
                SectionTitle(Copy.tailMode)
                Choices(TailMode.entries, card.tailMode, { it.label }) { change(card.copy(tailMode = it)) }
            } else {
                SectionTitle(Copy.templates)
                Choices(TemplateCatalog.names.indices.toList(), card.template, { TemplateCatalog.names[it] }) { index ->
                    val colors = TemplateCatalog.colors[index]
                    change(card.copy(template = index, primary = colors.first, secondary = colors.second))
                }
                ColorSelector(Copy.primary, card.primary, { change(card.copy(primary = it)) }) { CardFace(card, settings, vm.repository) }
                ColorSelector(Copy.secondary, card.secondary, { change(card.copy(secondary = it)) }) { CardFace(card, settings, vm.repository) }
                ToggleRow(Copy.gradient, card.gradient) { change(card.copy(gradient = it)) }
                ToggleRow(Copy.texture, card.texture) { change(card.copy(texture = it)) }
                ToggleRow(Copy.showWordmark, card.showWordmark) { change(card.copy(showWordmark = it)) }
                SectionTitle(Copy.logoPosition)
                Choices(LogoPosition.entries, card.logoPosition, { it.label }, groupLabel = Copy.logoPosition) { change(card.copy(logoPosition = it)) }
                if (card.network == Network.AMEX) ToggleRow(Copy.amexLayout, card.amexLayout) { change(card.copy(amexLayout = it)) }
            }
            SectionTitle(Copy.tailPosition)
            Choices(TailPosition.entries, card.tailPosition, { it.label }, groupLabel = Copy.tailPosition) { change(card.copy(tailPosition = it)) }
            SectionTitle(Copy.details)
            Field(Copy.bank, card.bank, { change(card.copy(bank = it.take(80))) })
            if (card.bank.isEmpty() || BankPresets.any { it.contains(card.bank, true) && it != card.bank }) {
                val banks = BankPresets.filter { card.bank.isBlank() || it.contains(card.bank, true) }.take(8)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    banks.forEach { bank -> SuggestionChip({ change(card.copy(bank = bank)) }, label = { Text(bank) }) }
                }
            }
            SectionTitle(Copy.network)
            Choices(Network.entries, card.network, { it.label }) { change(card.copy(network = it)) }
            if (card.network == Network.OTHER) Field("自定义卡组织名称", card.customNetwork, { change(card.copy(customNetwork = it.take(40))) })
            SectionTitle(Copy.type)
            Choices(CardType.entries, card.type, { it.label }) { change(card.copy(type = it)) }
            OutlinedTextField(card.lastFour, { change(card.copy(lastFour = CardRules.digits(it))) },
                Modifier.fillMaxWidth(), label = { Text(Copy.lastFour) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(Copy.fourHint) }, isError = card.lastFour.isNotEmpty() && card.lastFour.length != 4)
            Field(Copy.nickname, card.nickname, { change(card.copy(nickname = it.take(80))) })
            Field(Copy.tags, card.tags, { change(card.copy(tags = it.take(160))) })
            OutlinedTextField(card.notes, { change(card.copy(notes = it.take(2000))) }, Modifier.fillMaxWidth(),
                label = { Text(Copy.notes) }, minLines = 3, maxLines = 6)
            ToggleRow(Copy.showNotes, card.showNotes) { change(card.copy(showNotes = it)) }
            Text(Copy.credentialsHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (discard) ConfirmDialog(Copy.unsaved, "", Copy.discard, { discard = false }) { vm.discardDraft(); onBack() }
}
@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}
