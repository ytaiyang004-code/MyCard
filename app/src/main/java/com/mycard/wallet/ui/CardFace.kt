package com.mycard.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.cardShadow

const val CARD_RATIO = 1.586f

/** Card artwork ink. Kept as named colours so the contrast model has exactly two anchors. */
val CardInkDark = Color(0xFF17231F)
val CardInkLight = Color(0xFFF4F5ED)

/** A card is fixed-ratio artwork: scale its complete geometry together, not individual fields.
 * App controls and the text information below the card still follow the system font size. */
@Composable
fun CardFace(card: WalletCard, settings: WalletSettings, repository: CardRepository?,
             modifier: Modifier = Modifier, previewImage: ByteArray? = null,
             elevation: Dp = WalletDesign.cardElevationRest) {
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(CARD_RATIO).cardShadow(elevation)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scale = maxWidth.value / 353f
        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(density.density * scale, 1f)) {
            CardFaceContent(card, settings, repository, Modifier.fillMaxSize(), previewImage)
        }
    }
}

@Composable
private fun CardFaceContent(card: WalletCard, settings: WalletSettings, repository: CardRepository?,
                           modifier: Modifier, previewImage: ByteArray?) {
    val bytes by produceState<ByteArray?>(previewImage, card.imagePath, previewImage, card.faceMode) {
        value = if (card.faceMode == FaceMode.IMAGE) previewImage ?: card.imagePath?.let { runCatching { repository?.image(it) }.getOrNull() } else null
    }
    val useImage = card.faceMode == FaceMode.IMAGE && bytes != null
    // Contrast is decided per text zone against the gradient the card actually draws: one average
    // colour for the whole face cannot serve both the top-left name and the bottom-right tail.
    val headerBackgrounds = cardZoneBackgrounds(card.primary, card.secondary, card.gradient, CardTextZone.HEADER)
    val headerInk = cardInkFor(headerBackgrounds)
    val headerTypeAlpha = inkAlphaFor(headerBackgrounds, headerInk, .78f)
    val headerScrim = scrimAlphaFor(headerBackgrounds, headerInk)
    val tagsBackgrounds = cardZoneBackgrounds(card.primary, card.secondary, card.gradient, CardTextZone.TAGS)
    val tagsInk = cardInkFor(tagsBackgrounds)
    val tagsAlpha = inkAlphaFor(tagsBackgrounds, tagsInk, CardTextZone.TAGS.preferredAlpha)
    val footerBackgrounds = cardZoneBackgrounds(card.primary, card.secondary, card.gradient, CardTextZone.FOOTER)
    val footerInk = cardInkFor(footerBackgrounds)
    val footerAlpha = inkAlphaFor(footerBackgrounds, footerInk, CardTextZone.FOOTER.preferredAlpha)
    val tailInk = cardInkFor(cardZoneBackgrounds(card.primary, card.secondary, card.gradient, CardTextZone.TAIL))
    Box(modifier.fillMaxWidth().aspectRatio(CARD_RATIO).clip(WalletDesign.cardShape)) {
        if (useImage) {
            val context = LocalContext.current
            AsyncImage(model = remember(bytes) { ImageRequest.Builder(context).data(bytes)
                // The byte array comes from the bounded in-memory cache, so decoding the same card
                // again is served by Coil's memory cache; nothing decoded is ever written to disk.
                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.DISABLED).build() },
                contentDescription = card.bank, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            MaterialBackground(card, Modifier.fillMaxSize())
            val logoTop = card.logoPosition == LogoPosition.TOP_RIGHT && !(card.network == Network.AMEX && card.amexLayout)
            val logoLeft = card.logoPosition == LogoPosition.BOTTOM_LEFT && !(card.network == Network.AMEX && card.amexLayout)
            val amex = card.network == Network.AMEX && card.amexLayout
            val reserveRight = card.showNotes || logoTop || card.tailPosition == TailPosition.TOP
            Column(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 18.dp)
                .fillMaxWidth(if (reserveRight) .58f else .85f)
                // A light scrim only appears where the header zone crosses the gradient; on flat or
                // dark faces the contrast model already picks a readable ink.
                .then(if (headerScrim > 0f) Modifier.clip(RoundedCornerShape(10.dp))
                    .background(scrimColorFor(headerInk).copy(alpha = headerScrim)).padding(horizontal = 6.dp, vertical = 4.dp)
                    else Modifier)) {
                BankIdentity(card.bank, headerInk, Modifier.fillMaxWidth())
                Text(card.type.label, color = headerInk.copy(alpha = headerTypeAlpha), fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp), maxLines = 1)
            }
            if (card.tags.isNotBlank()) Text(card.tags, color = tagsInk.copy(alpha = tagsAlpha), fontSize = 9.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp).fillMaxWidth(.50f))
            Column(Modifier.align(Alignment.BottomStart).padding(start = if (logoLeft) 110.dp else 20.dp, bottom = 17.dp)
                .fillMaxWidth(if (logoLeft) .52f else .50f)) {
                if (card.nickname.isNotBlank()) Text(card.nickname, color = footerInk.copy(alpha = footerAlpha), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.showWordmark) Text("MyCard", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium, fontSize = 13.sp, color = footerInk.copy(alpha = footerAlpha), letterSpacing = .5.sp)
            }
            val logoAlign = when { amex -> Alignment.CenterEnd; logoTop -> Alignment.TopEnd; logoLeft -> Alignment.BottomStart; else -> Alignment.BottomEnd }
            NetworkIdentity(card, if (logoTop) headerInk else tailInk, Modifier.align(logoAlign).padding(horizontal = 20.dp, vertical = 17.dp)
                .size(if (amex) 44.dp else 66.dp, if (amex) 44.dp else 31.dp))
            val tailAlignment = when (card.tailPosition) {
                TailPosition.LEFT -> Alignment.BottomStart
                TailPosition.RIGHT -> Alignment.BottomEnd
                TailPosition.TOP -> Alignment.TopEnd
            }
            Text("••••  " + card.lastFour.ifBlank { "0000" }, color = tailInk, fontFamily = FontFamily.Monospace,
                fontSize = 15.sp, letterSpacing = .5.sp, maxLines = 1,
                modifier = Modifier.align(tailAlignment).padding(start = 20.dp, end = 20.dp,
                    top = if (card.tailPosition == TailPosition.TOP) (if (logoTop) 100.dp else if (card.showNotes) 76.dp else 66.dp) else 0.dp,
                    bottom = if (card.tailPosition == TailPosition.LEFT) 62.dp else if (card.tailPosition == TailPosition.RIGHT) (if (amex || logoTop || logoLeft) 20.dp else 62.dp) else 0.dp))
        }
        // Real artwork remains untouched; only explicit notes and optional tail badges are overlaid.
        if (card.showNotes && card.notes.isNotBlank()) {
            val noteTop = if (!useImage && card.logoPosition == LogoPosition.TOP_RIGHT && !(card.network == Network.AMEX && card.amexLayout)) 56.dp else 18.dp
            Text(card.notes, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, lineHeight = 13.sp,
                color = if (useImage) Color.White else headerInk,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = noteTop, end = 20.dp).fillMaxWidth(.35f)
                    .then(if (useImage) Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .58f)).padding(5.dp) else Modifier))
        }
        val badge = card.tailMode == TailMode.BADGE || (card.tailMode == TailMode.DEFAULT && settings.tailBadge)
        if (useImage && badge && card.lastFour.isNotEmpty()) {
            val alignment = when (card.tailPosition) { TailPosition.LEFT -> Alignment.BottomStart; TailPosition.RIGHT -> Alignment.BottomEnd; TailPosition.TOP -> Alignment.TopEnd }
            Text("•• " + card.lastFour, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(alignment).padding(start = 14.dp, end = 14.dp, bottom = 14.dp,
                    top = if (card.tailPosition == TailPosition.TOP && card.showNotes && card.notes.isNotBlank()) 70.dp else 14.dp)
                    .clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .62f)).padding(horizontal = 9.dp, vertical = 4.dp))
        }
    }
}
