package com.simplecalculator.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        VideoEntity::class,
        StarEntity::class,
        EventEntity::class,
        DownloadEntity::class,
        HistoryEntity::class,
        FavoriteEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {

    abstract fun videos(): VideoDao
    abstract fun stars(): StarDao
    abstract fun events(): EventDao
    abstract fun downloads(): DownloadDao
    abstract fun history(): HistoryDao
    abstract fun favorites(): FavoriteDao

    companion object {
        fun build(context: Context): AppDb = Room.databaseBuilder(
            context.applicationContext,
            AppDb::class.java,
            "calc.db"
        )
            .addMigrations(DOWNLOAD_ERROR_COLUMN)
            .fallbackToDestructiveMigration()
            .build()

    private val DOWNLOAD_ERROR_COLUMN = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN error TEXT")
        }
    }
    }
}
