package com.cursormobile.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val agentId: String,
    val pairId: String,
    val runtime: String,
    val model: String,
    val cwd: String?,
    val title: String?,
    val status: String,
    val createdAt: Long,
    val lastActivityAt: Long,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: String,
    val runId: String?,
    val role: String, // "user" | "assistant" | "tool" | "system" | "thinking"
    val text: String,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolId: String? = null,
    val toolStatus: String? = null,
    val remoteId: String? = null,
    val ts: Long,
    val pending: Boolean = false,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val envelopeId: String,
    val pairId: String,
    val type: String,
    val frameJson: String,
    val createdAt: Long,
)

@Dao
interface AgentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(agents: List<AgentEntity>)

    @Query("SELECT * FROM agents WHERE pairId = :pairId ORDER BY lastActivityAt DESC")
    fun observe(pairId: String): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE agentId = :id LIMIT 1")
    fun observeOne(id: String): Flow<AgentEntity?>

    @Query("DELETE FROM agents WHERE pairId = :pairId")
    suspend fun clearForPair(pairId: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY ts ASC")
    fun observe(agentId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET text = :text, pending = 0 WHERE id = :id")
    suspend fun finalize(id: Long, text: String)

    /**
     * Mark a pending assistant message as finished. If `replacement` is supplied
     * AND the current text is empty, we substitute it; otherwise we just clear
     * the pending flag so the streamed text is preserved.
     */
    @Query("""
        UPDATE messages
        SET text = CASE WHEN length(text) = 0 AND :replacement IS NOT NULL THEN :replacement ELSE text END,
            pending = 0
        WHERE id = :id
    """)
    suspend fun finalizePending(id: Long, replacement: String?)

    @Query("UPDATE messages SET text = text || :chunk WHERE id = :id")
    suspend fun appendChunk(id: Long, chunk: String)

    /**
     * Mark stale pending bubbles (older than `cutoff`) as finished so they
     * stop showing the streaming indicator after a daemon restart or crash.
     */
    @Query("UPDATE messages SET pending = 0 WHERE pending = 1 AND ts < :cutoff")
    suspend fun reapStalePending(cutoff: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE agentId = :agentId AND pending = 1")
    suspend fun countPending(agentId: String): Int

    @Query("DELETE FROM messages WHERE agentId = :agentId AND pending = 0")
    suspend fun deleteNonPending(agentId: String)

    @Query("SELECT id FROM messages WHERE agentId = :agentId AND toolId = :toolId LIMIT 1")
    suspend fun findToolMessageId(agentId: String, toolId: String): Long?

    @Query("""
        UPDATE messages
        SET toolOutput = :output, toolStatus = :status, text = :text, toolInput = COALESCE(:input, toolInput)
        WHERE id = :id
    """)
    suspend fun updateTool(id: Long, text: String, input: String?, output: String?, status: String)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE pairId = :pairId AND createdAt > :minCreatedAt ORDER BY createdAt ASC")
    suspend fun pending(pairId: String, minCreatedAt: Long): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE envelopeId = :id")
    suspend fun ack(id: String)

    @Query("DELETE FROM outbox WHERE createdAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("DELETE FROM outbox")
    suspend fun clearAll()
}

class Converters {
    @TypeConverter fun fromList(value: List<String>?): String? = value?.joinToString("\u0001")
    @TypeConverter fun toList(value: String?): List<String>? = value?.split("\u0001")?.filter { it.isNotEmpty() }
}

@Database(
    entities = [AgentEntity::class, MessageEntity::class, OutboxEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class CursorMobileDb : RoomDatabase() {
    abstract fun agents(): AgentDao
    abstract fun messages(): MessageDao
    abstract fun outbox(): OutboxDao
}
