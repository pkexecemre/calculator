package com.simplecalculator.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<VideoEntity>)

    @Query("SELECT * FROM videos WHERE key = :key LIMIT 1")
    suspend fun byKey(key: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE category = :category ORDER BY cachedAt DESC LIMIT 60")
    suspend fun byCategory(category: String): List<VideoEntity>

    @Query("DELETE FROM videos WHERE key = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM videos WHERE uploaderSlug = :slug ORDER BY views DESC LIMIT 60")
    suspend fun byUploader(slug: String): List<VideoEntity>
}

@Dao
interface StarDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(star: StarEntity)

    @Query("SELECT * FROM stars WHERE followed = 1 ORDER BY followedAt DESC")
    fun followed(): Flow<List<StarEntity>>

    @Query("SELECT * FROM stars WHERE followed = 1")
    suspend fun followedNow(): List<StarEntity>

    @Query("SELECT * FROM stars WHERE slug = :slug LIMIT 1")
    suspend fun bySlug(slug: String): StarEntity?

    @Query("DELETE FROM stars WHERE slug = :slug")
    suspend fun delete(slug: String)
}

@Dao
interface EventDao {

    @Insert
    suspend fun add(event: EventEntity)

    @Query("SELECT uploaderSlug FROM events WHERE uploaderSlug IS NOT NULL ORDER BY at DESC LIMIT 400")
    suspend fun recentUploaders(): List<String>

    @Query("SELECT videoKey FROM events ORDER BY at DESC LIMIT 600")
    suspend fun recentVideos(): List<String>

    @Query("DELETE FROM events")
    suspend fun clear()
}

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT 200")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE key = :key LIMIT 1")
    suspend fun byKey(key: String): HistoryEntity?

    @Query("DELETE FROM history WHERE key = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: FavoriteEntity)

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE key = :key LIMIT 1")
    suspend fun byKey(key: String): FavoriteEntity?

    @Query("DELETE FROM favorites WHERE key = :key")
    suspend fun remove(key: String)
}

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE key = :key LIMIT 1")
    suspend fun byKey(key: String): DownloadEntity?

    @Query("UPDATE downloads SET progress = :progress, state = :state WHERE key = :key")
    suspend fun updateProgress(key: String, progress: Int, state: String)

    @Query("UPDATE downloads SET state = :state, filePath = :path, bytes = :bytes, progress = 100 WHERE key = :key")
    suspend fun markDone(key: String, state: String, path: String, bytes: Long)

    @Query("UPDATE downloads SET state = :state WHERE key = :key")
    suspend fun markState(key: String, state: String)

    @Query("UPDATE downloads SET state = 'running', error = NULL WHERE key = :key")
    suspend fun markRunning(key: String)

    @Query("UPDATE downloads SET state = 'failed', error = :error WHERE key = :key")
    suspend fun markFailed(key: String, error: String?)

    @Query("DELETE FROM downloads WHERE key = :key")
    suspend fun remove(key: String)
}
