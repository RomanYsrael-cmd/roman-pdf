package com.romanysrael.romanpdf.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

object DocumentKinds {
    const val PDF = "PDF"
    const val IMAGE = "IMAGE"
}

object SearchSources {
    const val PDF_TEXT = "PDF_TEXT"
    const val HANDWRITING = "HANDWRITING"
    const val NOTE = "NOTE"
}

@Entity(
    tableName = "documents",
    indices = [Index(value = ["uri"], unique = true)]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val uri: String,
    val mimeType: String,
    val kind: String,
    val pageCount: Int = 1,
    val lastPage: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val textIndexComplete: Boolean = false,
    val textIndexFailed: Boolean = false
)

@Entity(
    tableName = "strokes",
    indices = [Index(value = ["documentId", "pageIndex"])]
)
data class StrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val tool: String,
    val color: Int,
    val width: Float,
    val points: String,
    val recognizedText: String? = null,
    val recognitionPending: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notes",
    indices = [Index(value = ["documentId", "pageIndex"])]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int?,
    val title: String = "Note",
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/** FTS keeps only searchable text and small routing fields; PDFs and images stay in files/content URIs. */
@Fts4
@Entity(tableName = "search_entries_fts")
data class SearchEntryFts(
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "page_index") val pageIndex: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "content") val content: String
)

data class SearchHit(
    @ColumnInfo(name = "rowid") val rowId: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "page_index") val pageIndex: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "content") val content: String
)

data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val time: Long = 0L
)

data class Stroke(
    val id: Long = 0L,
    val documentId: Long,
    val pageIndex: Int,
    val tool: String,
    val color: Int,
    val width: Float,
    val points: List<InkPoint>,
    val recognizedText: String? = null
)

object StrokeCodec {
    /** Compact, locale-independent persistence for normalized points. */
    fun encode(points: List<InkPoint>): String = buildString(points.size * 24) {
        points.forEachIndexed { index, point ->
            if (index > 0) append(';')
            append(point.x).append(',')
                .append(point.y).append(',')
                .append(point.pressure).append(',')
                .append(point.time)
        }
    }

    fun decode(encoded: String): List<InkPoint> = encoded.split(';')
        .asSequence()
        .mapNotNull { row ->
            val values = row.split(',')
            if (values.size < 2) return@mapNotNull null
            runCatching {
                InkPoint(
                    x = values[0].toFloat(),
                    y = values[1].toFloat(),
                    pressure = values.getOrNull(2)?.toFloatOrNull() ?: 1f,
                    time = values.getOrNull(3)?.toLongOrNull() ?: 0L
                )
            }.getOrNull()
        }
        .toList()
}

fun StrokeEntity.toModel(): Stroke = Stroke(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    tool = tool,
    color = color,
    width = width,
    points = StrokeCodec.decode(points),
    recognizedText = recognizedText
)

fun Stroke.toEntity(recognitionPending: Boolean = false): StrokeEntity = StrokeEntity(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    tool = tool,
    color = color,
    width = width,
    points = StrokeCodec.encode(points),
    recognizedText = recognizedText,
    recognitionPending = recognitionPending
)
