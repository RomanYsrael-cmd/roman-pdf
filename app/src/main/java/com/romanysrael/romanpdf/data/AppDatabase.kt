package com.romanysrael.romanpdf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, StrokeEntity::class, NoteEntity::class, SearchEntryFts::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun strokeDao(): StrokeDao
    abstract fun noteDao(): NoteDao
    abstract fun searchDao(): SearchDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "roman_pdf.db"
            ).build().also { INSTANCE = it }
        }
    }
}
