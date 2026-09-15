package com.romanysrael.romanpdf.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, StrokeEntity::class, NoteEntity::class, BookmarkEntity::class, SearchEntryFts::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun strokeDao(): StrokeDao
    abstract fun noteDao(): NoteDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun searchDao(): SearchDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "roman_pdf.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN page_sources TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE documents ADD COLUMN source_key TEXT NOT NULL DEFAULT ''")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_documents_source_key ON documents(source_key)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS bookmarks (
                        documentId INTEGER NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(documentId, pageIndex),
                        FOREIGN KEY(documentId) REFERENCES documents(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_documentId ON bookmarks(documentId)")
            }
        }
    }
}
