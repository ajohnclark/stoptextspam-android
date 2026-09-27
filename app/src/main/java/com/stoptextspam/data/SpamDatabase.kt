package com.stoptextspam.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "spam_messages")
data class SpamMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val reason: String,
    val timestamp: Long,
    val archived: Boolean = false
)

@Dao
interface SpamMessageDao {
    @Query("SELECT * FROM spam_messages WHERE archived = 0 ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<SpamMessage>>

    @Query("SELECT * FROM spam_messages ORDER BY timestamp DESC")
    suspend fun getAll(): List<SpamMessage>

    @Insert
    suspend fun insert(message: SpamMessage)

    @Query("DELETE FROM spam_messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM spam_messages")
    suspend fun getCount(): Int

    @Query("UPDATE spam_messages SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("SELECT COUNT(*) FROM spam_messages")
    fun getTotalCountFlow(): Flow<Int>

    @Query("SELECT * FROM spam_messages WHERE archived = 1 ORDER BY timestamp DESC")
    fun getArchivedFlow(): Flow<List<SpamMessage>>
}

@Database(entities = [SpamMessage::class], version = 2, exportSchema = false)
abstract class SpamDatabase : RoomDatabase() {
    abstract fun spamMessageDao(): SpamMessageDao

    companion object {
        @Volatile
        private var INSTANCE: SpamDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE spam_messages ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): SpamDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SpamDatabase::class.java,
                    "spam_database"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
