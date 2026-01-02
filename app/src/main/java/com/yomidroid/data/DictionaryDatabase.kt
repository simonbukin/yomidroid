package com.yomidroid.data

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return try {
            gson.fromJson(value, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@Entity(tableName = "lookup_history")
data class LookupHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "word")
    val word: String,

    @ColumnInfo(name = "reading")
    val reading: String,

    @ColumnInfo(name = "definition")
    val definition: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "screenshot_path")
    val screenshotPath: String? = null,

    @ColumnInfo(name = "sentence")
    val sentence: String? = null
)

@Dao
interface LookupHistoryDao {
    @Query("SELECT * FROM lookup_history ORDER BY timestamp DESC")
    suspend fun getAll(): List<LookupHistoryEntity>

    @Query("SELECT * FROM lookup_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LookupHistoryEntity>

    @Query("SELECT * FROM lookup_history WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<LookupHistoryEntity>

    @Query("SELECT * FROM lookup_history WHERE id = :id")
    suspend fun getById(id: Long): LookupHistoryEntity?

    @Insert
    suspend fun insert(record: LookupHistoryEntity): Long

    @Query("DELETE FROM lookup_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM lookup_history")
    suspend fun deleteAll()

    @Query("""
        SELECT * FROM lookup_history
        WHERE word LIKE '%' || :query || '%'
           OR reading LIKE '%' || :query || '%'
           OR definition LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
    """)
    suspend fun search(query: String): List<LookupHistoryEntity>
}

/**
 * Room database for app-managed data (history only).
 * The dictionary is stored separately in DictionaryDb.
 */
@Database(
    entities = [LookupHistoryEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): LookupHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lookup_history ADD COLUMN sentence TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yomidroid_history.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
