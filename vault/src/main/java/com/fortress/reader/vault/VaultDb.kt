package com.fortress.reader.vault

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** File name is stored encrypted (Keystore-wrapped) so the DB leaks no document titles. */
@Entity(tableName = "documents")
class DocumentEntity(
    @PrimaryKey val id: String,
    val nameBlob: ByteArray,
    val sizeBytes: Long,
    val addedAt: Long,
    val wrappedDek: ByteArray,
)

/** Title wrapped by the master key; body sealed with a per-note DEK (wrapped by the master key). */
@Entity(tableName = "notes")
class NoteEntity(
    @PrimaryKey val id: String,
    val titleBlob: ByteArray,
    val bodyBlob: ByteArray,
    val wrappedDek: ByteArray,
    val updatedAt: Long,
)

/** Encrypted photos stored in the digital safe. Name is Keystore-wrapped. */
@Entity(tableName = "photos")
class PhotoEntity(
    @PrimaryKey val id: String,
    val nameBlob: ByteArray,
    val sizeBytes: Long,
    val addedAt: Long,
    val wrappedDek: ByteArray,
    val mimeType: String, // e.g. "image/jpeg"
)

/**
 * Singleton row holding safe authentication material.
 * Real and duress verifiers are independent; matching either "unlocks".
 * On duress match the vault contents are crypto-erased before the UI is shown.
 *
 * v4: [failedAttempts] / [lockedUntil] drive the PIN entry throttle
 * (exponential backoff after repeated failures).
 */
@Entity(tableName = "safe_auth")
data class SafeAuthEntity(
    @PrimaryKey val id: Int = 1,
    val realSalt: ByteArray,
    val realHash: ByteArray,
    val duressSalt: ByteArray,
    val duressHash: ByteArray,
    val setupAt: Long,
    @ColumnInfo(defaultValue = "0") val failedAttempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val lockedUntil: Long = 0,
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY addedAt DESC") fun observeAll(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: String): DocumentEntity?
    @Query("SELECT id FROM documents") suspend fun ids(): List<String>
    @Insert suspend fun insert(e: DocumentEntity)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM documents") suspend fun deleteAll()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun observeAll(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun get(id: String): NoteEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: NoteEntity)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM notes") suspend fun deleteAll()
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY addedAt DESC") fun observeAll(): Flow<List<PhotoEntity>>
    @Query("SELECT * FROM photos WHERE id = :id") suspend fun get(id: String): PhotoEntity?
    @Query("SELECT id FROM photos") suspend fun ids(): List<String>
    @Insert suspend fun insert(e: PhotoEntity)
    @Query("DELETE FROM photos WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM photos") suspend fun deleteAll()
}

@Dao
interface SafeAuthDao {
    @Query("SELECT * FROM safe_auth WHERE id = 1 LIMIT 1") suspend fun get(): SafeAuthEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: SafeAuthEntity)
    @Query("DELETE FROM safe_auth") suspend fun clear()
}

@Database(
    entities = [DocumentEntity::class, NoteEntity::class, PhotoEntity::class, SafeAuthEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): DocumentDao
    abstract fun notes(): NoteDao
    abstract fun photos(): PhotoDao
    abstract fun safeAuth(): SafeAuthDao

    companion object {
        // Exact Room-generated SQL for tables introduced in v2 / v3, so migrated
        // databases pass Room's schema validation.
        private const val CREATE_NOTES =
            "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `titleBlob` BLOB NOT NULL, " +
                "`bodyBlob` BLOB NOT NULL, `wrappedDek` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        private const val CREATE_PHOTOS =
            "CREATE TABLE IF NOT EXISTS `photos` (`id` TEXT NOT NULL, `nameBlob` BLOB NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `wrappedDek` BLOB NOT NULL, " +
                "`mimeType` TEXT NOT NULL, PRIMARY KEY(`id`))"
        private const val CREATE_SAFE_AUTH_V3 =
            "CREATE TABLE IF NOT EXISTS `safe_auth` (`id` INTEGER NOT NULL, `realSalt` BLOB NOT NULL, " +
                "`realHash` BLOB NOT NULL, `duressSalt` BLOB NOT NULL, `duressHash` BLOB NOT NULL, " +
                "`setupAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_NOTES)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_PHOTOS)
                db.execSQL(CREATE_SAFE_AUTH_V3)
            }
        }

        /** v3 -> v4: PIN throttle columns, added without touching any user data. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `safe_auth` ADD COLUMN `failedAttempts` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `safe_auth` ADD COLUMN `lockedUntil` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Explicit migrations only — no destructive fallback. A schema mismatch
         * fails loudly instead of silently wiping the vault.
         */
        fun create(context: Context) = Room.databaseBuilder(context, VaultDatabase::class.java, "vault.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }
}