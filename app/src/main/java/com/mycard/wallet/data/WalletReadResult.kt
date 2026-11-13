package com.mycard.wallet.data

/** Where a single stored record stopped decoding. */
enum class DamageStage { DECRYPT, PARSE, VALIDATE }

/**
 * One record that could not be read.
 *
 * Only the primary key (already visible as plaintext in SQLite) and the exception class name are
 * kept. Exception messages can quote decrypted JSON fragments, so they never leave the decoder.
 */
data class DamagedRecord(
    val id: String,
    val stage: DamageStage,
    val exceptionType: String
) {
    init { require(exceptionType.matches(Regex("[A-Za-z0-9_.$]{1,200}"))) }
}

/** A complete or explicitly incomplete view of the wallet. */
data class WalletReadResult(val cards: List<WalletCard>, val damaged: List<DamagedRecord>) {
    val complete: Boolean get() = damaged.isEmpty()
}

/** Raised when a caller requires the whole wallet but some records are unreadable. */
class IncompleteWalletException(val damagedCount: Int) :
    IllegalStateException("Wallet has $damagedCount unreadable record(s)")

/** Raised when an unreadable record is the target of a write or a deletion. */
class DamagedRecordException(val id: String) :
    IllegalStateException("Record is unreadable; its ciphertext is preserved")

/** Raised when the failure is not attributable to a single record. */
class WalletUnavailableException(val reason: Reason, cause: Throwable? = null) :
    IllegalStateException("Wallet is unavailable: ${reason.name}", cause) {
    enum class Reason { KEY_UNAVAILABLE, ALL_RECORDS_UNREADABLE }
}