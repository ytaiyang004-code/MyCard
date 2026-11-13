package com.mycard.wallet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.mycard.wallet.data.WalletCard

/**
 * Public edition: no bank or card-network artwork is bundled, so both identities render as text.
 *
 * The private edition cropped emblems out of official websites. Redistribution permission was never
 * verified, therefore the public source ships names only and keeps the original layout slots,
 * contrast rules and position settings. See BRAND_NOTICE.md and docs/BRAND_ASSET_INVENTORY.md.
 */
@Composable
fun BankIdentity(bank: String, ink: Color, modifier: Modifier = Modifier) {
    Text(bank.ifBlank { Copy.bank }, modifier = modifier, color = ink, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, maxLines = 2, lineHeight = 17.sp, overflow = TextOverflow.Ellipsis)
}

@Composable
fun NetworkIdentity(card: WalletCard, ink: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(card.networkName, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
