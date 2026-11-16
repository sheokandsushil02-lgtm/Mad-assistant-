package com.rajat.madassistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "facts")
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {

    @Insert
    suspend fun addFact(fact: FactEntity): Long

    @Insert
    suspend fun addChat(message: ChatEntity): Long

    @Query("SELECT * FROM facts ORDER BY createdAt DESC LIMIT 20")
    suspend fun recentFacts(): List<FactEntity>

    @Query("SELECT * FROM facts WHERE content LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 10")
    suspend fun searchFacts(q: String): List<FactEntity>

    @Query("SELECT * FROM chat_messages ORDER BY createdAt DESC LIMIT 40")
    suspend fun recentChat(): List<ChatEntity>
}

@Database(entities = [FactEntity::class, ChatEntity::class], version = 1, exportSchema = false)
abstract class MemoryDb : RoomDatabase() {

    abstract fun dao(): MemoryDao

    companion object {
        @Volatile
        private var instance: MemoryDb? = null

        fun get(context: Context): MemoryDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDb::class.java,
                    "mad_memory.db"
                ).build().also { instance = it }
            }
    }
}
