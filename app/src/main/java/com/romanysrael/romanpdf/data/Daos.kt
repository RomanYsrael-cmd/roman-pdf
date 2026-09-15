package com.romanysrael.romanpdf.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY lastOpenedAt DESC, id DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY lastOpenedAt DESC, id DESC")
    suspend fun getAll(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("UPDATE documents SET lastPage = :page, lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun updateLastPage(id: Long, page: Int, openedAt: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET textIndexComplete = :complete, textIndexFailed = :failed WHERE id = :id")
    suspend fun setTextIndexState(id: Long, complete: Boolean, failed: Boolean)

    @Delete
    suspend fun delete(document: DocumentEntity)
}

@Dao
interface StrokeDao {
    @Query("SELECT * FROM strokes WHERE documentId = :documentId AND pageIndex = :pageIndex ORDER BY createdAt, id")
    suspend fun getForPage(documentId: Long, pageIndex: Int): List<StrokeEntity>

    @Query("SELECT * FROM strokes WHERE documentId = :documentId ORDER BY pageIndex, createdAt, id")
    suspend fun getForDocument(documentId: Long): List<StrokeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: StrokeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(strokes: List<StrokeEntity>)

    @Query("DELETE FROM strokes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM strokes WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: Long)

    @Query("UPDATE strokes SET recognizedText = :text, recognitionPending = 0 WHERE id = :id")
    suspend fun setRecognizedText(id: Long, text: String?)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE documentId = :documentId ORDER BY updatedAt DESC")
    suspend fun getForDocument(documentId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE documentId = :documentId AND pageIndex = :pageIndex ORDER BY updatedAt DESC")
    suspend fun getForPage(documentId: Long, pageIndex: Int): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Query("DELETE FROM notes WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: Long)
}

@Dao
interface SearchDao {
    @Insert
    suspend fun insert(entry: SearchEntryFts)

    @Insert
    suspend fun insertAll(entries: List<SearchEntryFts>)

    @Query("DELETE FROM search_entries_fts WHERE document_id = :documentId")
    suspend fun deleteForDocument(documentId: String)

    @Query("DELETE FROM search_entries_fts WHERE document_id = :documentId AND source_type = :sourceType")
    suspend fun deleteForSource(documentId: String, sourceType: String)

    @Query("SELECT rowid, document_id, page_index, source_type, content FROM search_entries_fts WHERE search_entries_fts MATCH :matchQuery ORDER BY rowid DESC LIMIT 100")
    suspend fun search(matchQuery: String): List<SearchHit>
}
