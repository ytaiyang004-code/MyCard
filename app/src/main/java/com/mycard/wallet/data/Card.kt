package com.mycard.wallet.data

import org.json.JSONObject
import java.util.UUID

enum class FaceMode { TEMPLATE, IMAGE }
enum class Network(val label: String) {
    UNIONPAY("银联"), VISA("Visa"), MASTERCARD("Mastercard"), JCB("JCB"),
    AMEX("American Express"), DISCOVER("Discover"), OTHER("其他")
}
enum class CardType(val label: String) { DEBIT("借记卡"), CREDIT("信用卡"), PREPAID("预付卡"), VIRTUAL("虚拟卡"), OTHER("其他") }
enum class TailMode(val label: String) { DEFAULT("跟随设置"), HIDDEN("仅详情页"), BADGE("卡面标签") }
enum class TailPosition(val label: String) { LEFT("左下"), RIGHT("右下"), TOP("右上") }
enum class LogoPosition(val label: String) { BOTTOM_RIGHT("右下"), BOTTOM_LEFT("左下"), TOP_RIGHT("右上") }
enum class CardFilter(val label: String) {
    ALL("全部"), UNIONPAY("银联"), VISA("Visa"), MASTERCARD("Mastercard"), OTHER("其他"),
    DEBIT("借记卡"), CREDIT("信用卡"), VIRTUAL("虚拟卡"), ARCHIVED("已归档")
}
enum class SortOrder(val label: String) { CUSTOM("自定义顺序"), NEWEST("最新添加"), OLDEST("最早添加") }
enum class ThemeMode(val label: String) { DARK("深色"), LIGHT("浅色"), SYSTEM("跟随系统") }

@androidx.compose.runtime.Immutable
data class WalletCard(
    val id: String = UUID.randomUUID().toString(),
    val bank: String = "",
    val network: Network = Network.UNIONPAY,
    val customNetwork: String = "",
    val type: CardType = CardType.DEBIT,
    val lastFour: String = "",
    val nickname: String = "",
    val tags: String = "",
    val notes: String = "",
    val faceMode: FaceMode = FaceMode.TEMPLATE,
    val imagePath: String? = null,
    val template: Int = 0,
    val primary: Long = 0xFF304F46,
    val secondary: Long = 0xFF7B9B80,
    val gradient: Boolean = true,
    val texture: Boolean = true,
    val showWordmark: Boolean = true,
    val showNotes: Boolean = false,
    val logoPosition: LogoPosition = LogoPosition.BOTTOM_RIGHT,
    val amexLayout: Boolean = false,
    val tailMode: TailMode = TailMode.DEFAULT,
    val tailPosition: TailPosition = TailPosition.RIGHT,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val position: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val title get() = nickname.ifBlank { bank }
    val networkName get() = if (network == Network.OTHER && customNetwork.isNotBlank()) customNetwork else network.label
}

object CardRules {
    fun digits(value: String) = value.filter { it in '0'..'9' }.take(4)
    fun valid(card: WalletCard): Boolean =
        runCatching { UUID.fromString(card.id) }.isSuccess &&
        card.bank.isNotBlank() && card.bank.length <= 80 &&
        card.lastFour.matches(Regex("[0-9]{4}")) &&
        card.nickname.length <= 80 && card.customNetwork.length <= 40 && card.tags.length <= 160 && card.notes.length <= 2000 &&
        card.template in 0..15 && card.primary in 0..0xFFFFFFFFL && card.secondary in 0..0xFFFFFFFFL &&
        (card.imagePath == null || card.imagePath.matches(Regex("[a-f0-9-]{36}\\.bin"))) &&
        (card.faceMode != FaceMode.IMAGE || card.imagePath != null)

    fun select(cards: List<WalletCard>, query: String, filter: CardFilter, sort: SortOrder): List<WalletCard> {
        val q = query.trim()
        return cards.filter { c ->
            (if (filter == CardFilter.ARCHIVED) c.archived else !c.archived) &&
            when (filter) {
                CardFilter.UNIONPAY -> c.network == Network.UNIONPAY
                CardFilter.VISA -> c.network == Network.VISA
                CardFilter.MASTERCARD -> c.network == Network.MASTERCARD
                CardFilter.OTHER -> c.network !in listOf(Network.UNIONPAY, Network.VISA, Network.MASTERCARD)
                CardFilter.DEBIT -> c.type == CardType.DEBIT
                CardFilter.CREDIT -> c.type == CardType.CREDIT
                CardFilter.VIRTUAL -> c.type == CardType.VIRTUAL
                else -> true
            } && (q.isEmpty() || listOf(c.bank, c.networkName, c.lastFour, c.nickname, c.tags).any { it.contains(q, true) })
        }.sortedWith(compareByDescending<WalletCard> { it.pinned }.thenBy {
            when (sort) { SortOrder.CUSTOM -> it.position; SortOrder.NEWEST -> -it.createdAt; SortOrder.OLDEST -> it.createdAt }
        }.thenBy { it.id })
    }
}

object CardJson {
    fun encode(c: WalletCard) = JSONObject().apply {
        put("id", c.id); put("bank", c.bank); put("network", c.network.name); put("type", c.type.name)
        put("customNetwork", c.customNetwork)
        put("lastFour", c.lastFour); put("nickname", c.nickname); put("tags", c.tags); put("notes", c.notes)
        put("faceMode", c.faceMode.name); put("imagePath", c.imagePath ?: JSONObject.NULL)
        put("template", c.template); put("primary", c.primary); put("secondary", c.secondary)
        put("gradient", c.gradient); put("texture", c.texture); put("tailMode", c.tailMode.name)
        put("showWordmark", c.showWordmark); put("showNotes", c.showNotes)
        put("logoPosition", c.logoPosition.name); put("amexLayout", c.amexLayout)
        put("tailPosition", c.tailPosition.name); put("pinned", c.pinned); put("archived", c.archived)
        put("position", c.position); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
    }
    fun decode(j: JSONObject): WalletCard = WalletCard(
        id = j.getString("id"), bank = j.getString("bank"), network = Network.valueOf(j.getString("network")),
        customNetwork = j.optString("customNetwork", ""),
        type = CardType.valueOf(j.getString("type")), lastFour = j.getString("lastFour"),
        nickname = j.getString("nickname"), tags = j.getString("tags"), notes = j.getString("notes"),
        faceMode = FaceMode.valueOf(j.getString("faceMode")),
        imagePath = if (j.isNull("imagePath")) null else j.getString("imagePath"),
        template = j.getInt("template"), primary = j.getLong("primary"), secondary = j.getLong("secondary"),
        gradient = j.getBoolean("gradient"), texture = j.getBoolean("texture"),
        showWordmark = j.optBoolean("showWordmark", true), showNotes = j.optBoolean("showNotes", false),
        logoPosition = LogoPosition.valueOf(j.optString("logoPosition", LogoPosition.BOTTOM_RIGHT.name)),
        amexLayout = j.optBoolean("amexLayout", false),
        tailMode = TailMode.valueOf(j.getString("tailMode")), tailPosition = TailPosition.valueOf(j.getString("tailPosition")),
        pinned = j.getBoolean("pinned"), archived = j.getBoolean("archived"),
        position = j.getLong("position"), createdAt = j.getLong("createdAt"), updatedAt = j.getLong("updatedAt")
    ).also { require(CardRules.valid(it)) { "Invalid card" } }
}
