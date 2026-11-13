package com.mycard.wallet.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** No bank names, suffixes, notes or image paths are stored as plaintext in SQLite/WAL. */
@Entity(tableName = "cards")
data class EncryptedCard(@PrimaryKey val id: String, @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards") fun observe(): Flow<List<EncryptedCard>>
    @Query("SELECT * FROM cards") suspend fun all(): List<EncryptedCard>
    /** Row count only: write guards must know whether ciphertext exists without reading it. */
    @Query("SELECT COUNT(*) FROM cards") suspend fun count(): Int
    @Upsert suspend fun upsert(cards: List<EncryptedCard>)
    @Query("DELETE FROM cards WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM cards") suspend fun clear()
}

@Database(entities = [EncryptedCard::class], version = 1, exportSchema = true)
abstract class WalletDatabase : RoomDatabase() { abstract fun cards(): CardDao }
