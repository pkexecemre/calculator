package com.simplecalculator.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val key: String,
    val sourceId: String,
    val vkey: String,
    val title: String,
    val thumb: String,
    val durationSec: Int,
    val views: Long,
    val uploaderSlug: String?,
    val uploaderName: String?,
    val cachedAt: Long,
    val category: String = "LIVE",
    val tags: String = ""
)

@Entity(tableName = "stars")
data class StarEntity(
    @PrimaryKey val slug: String,
    val sourceId: String,
    val rawSlug: String,
    val name: String,
    val avatar: String?,
    val followed: Boolean,
    val followedAt: Long,
    val hits: Int
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoKey: String,
    val uploaderSlug: String?,
    val kind: String,
    val weight: Float,
    val at: Long
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val key: String,
    val sourceId: String,
    val title: String,
    val thumb: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val key: String,
    val sourceId: String,
    val title: String,
    val thumb: String,
    val addedAt: Long
)

@Entity(
    tableName = "stt_cues",
    primaryKeys = ["videoKey", "lang", "startMs"]
)
data class SttCueEntity(
    val videoKey: String,
    val lang: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val createdAt: Long
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val key: String,
    val vkey: String,
    val title: String,
    val thumb: String,
    val filePath: String?,
    val state: String,
    val progress: Int,
    val quality: String?,
    val bytes: Long,
    val createdAt: Long,
    val error: String? = null
)
