package com.romanysrael.romanpdf.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.romanysrael.romanpdf.R
import com.romanysrael.romanpdf.RomanPdfApplication
import com.romanysrael.romanpdf.core.DocumentRenderEngine
import com.romanysrael.romanpdf.core.ExportManager
import com.romanysrael.romanpdf.core.HandwritingRecognitionManager
import com.romanysrael.romanpdf.core.ThumbnailStore
import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.NoteEntity
import com.romanysrael.romanpdf.data.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : AppCompatActivity() {
    private val app: RomanPdfApplication get() = application as RomanPdfApplication
    private var documentId: Long = -1L
    private var document: DocumentEntity? = null
    private var engine: DocumentRenderEngine? = null
    private var pageAdapter: PdfPageAdapter? = null
    private var pageList: RecyclerView? = null
    private var controls: View? = null
    private var progress: ProgressBar? = null
    private var pageCount = 1
    private var currentPage = 0
    private var restoredPage: Int? = null
    private var currentTool = AnnotationTools.NONE
    private var penWidthIndex = 1
    private var inkColorIndex = 0
    private val strokesByPage = HashMap<Int, MutableList<Stroke>>()
    private val loadedPages = HashSet<Int>()
    private val undoStack = ArrayDeque<EditAction>()
    private val redoStack = ArrayDeque<EditAction>()
    private lateinit var toolbar: Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        toolbar = findViewById(R.id.reader_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }
        controls = findViewById(R.id.reader_controls)
        pageList = findViewById(R.id.page_list)
        progress = findViewById(R.id.reader_progress)

        findViewById<ImageButton>(R.id.pen_button).apply {
            setOnClickListener { selectTool(AnnotationTools.PEN) }
            setOnLongClickListener {
                penWidthIndex = (penWidthIndex + 1) % PEN_WIDTHS.size
                pageAdapter?.setInkWidth(PEN_WIDTHS[penWidthIndex])
                Toast.makeText(this@ReaderActivity, "Pen width ${penWidthIndex + 1}", Toast.LENGTH_SHORT).show()
                true
            }
        }
        findViewById<ImageButton>(R.id.highlighter_button).apply {
            setOnClickListener { selectTool(AnnotationTools.HIGHLIGHT) }
            setOnLongClickListener {
                inkColorIndex = (inkColorIndex + 1) % INK_COLORS.size
                pageAdapter?.setInkColor(INK_COLORS[inkColorIndex])
                Toast.makeText(this@ReaderActivity, "Ink color ${inkColorIndex + 1}", Toast.LENGTH_SHORT).show()
                true
            }
        }
        findViewById<ImageButton>(R.id.eraser_button).setOnClickListener { selectTool(AnnotationTools.ERASER) }
        findViewById<ImageButton>(R.id.undo_button).setOnClickListener { undo() }
        findViewById<ImageButton>(R.id.redo_button).setOnClickListener { redo() }
        findViewById<ImageButton>(R.id.note_button).setOnClickListener { showNoteEditor() }

        documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L)
        if (documentId <= 0L) {
            finish()
            return
        }
        restoredPage = savedInstanceState?.getInt(KEY_PAGE)
        currentPage = restoredPage ?: 0
        configurePager()
        loadDocument()
    }

    private fun configurePager() {
        pageList?.apply {
            layoutManager = LinearLayoutManager(this@ReaderActivity, RecyclerView.HORIZONTAL, false)
            setHasFixedSize(true)
            overScrollMode = View.OVER_SCROLL_NEVER
            PagerSnapHelper().attachToRecyclerView(this)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val position = layout.findFirstCompletelyVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
                            ?: layout.findFirstVisibleItemPosition()
                        if (position != RecyclerView.NO_POSITION) onPageSettled(position)
                    }
                }
            })
        }
    }

    private fun loadDocument() {
        progress?.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching {
                val loaded = withContext(Dispatchers.IO) { app.repository.getDocument(documentId) }
                    ?: error("Document is no longer available")
                val renderEngine = withContext(Dispatchers.IO) { DocumentRenderEngine(applicationContext, loaded) }
                val actualPageCount = withContext(Dispatchers.IO) { renderEngine.pageCount() }
                Triple(loaded, renderEngine, actualPageCount)
            }.onSuccess { (loaded, renderEngine, actualPageCount) ->
                document = loaded.copy(pageCount = actualPageCount)
                engine = renderEngine
                pageCount = actualPageCount.coerceAtLeast(1)
                supportActionBar?.title = loaded.title
                pageAdapter = PdfPageAdapter(
                    renderer = renderEngine,
                    pageCount = pageCount,
                    scope = lifecycleScope,
                    strokesForPage = { page -> strokesByPage[page]?.toList().orEmpty() },
                    onTap = ::onPageTap,
                    onStroke = ::onStrokeCommitted,
                    onErase = ::onStrokesErased
                )
                pageList?.adapter = pageAdapter
                val savedPage = (restoredPage
                    ?: intent.getIntExtra(KEY_PAGE, loaded.lastPage)).coerceIn(0, pageCount - 1)
                currentPage = savedPage
                pageList?.scrollToPosition(savedPage)
                onPageSettled(savedPage)
                progress?.visibility = View.GONE
            }.onFailure {
                progress?.visibility = View.GONE
                Toast.makeText(this@ReaderActivity, it.message ?: "Unable to open PDF", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun onPageSettled(page: Int) {
        currentPage = page.coerceIn(0, pageCount - 1)
        supportActionBar?.subtitle = "Page ${currentPage + 1} / $pageCount"
        lifecycleScope.launch { app.repository.updateLastPage(documentId, currentPage) }
        if (loadedPages.add(currentPage)) {
            lifecycleScope.launch {
                val loaded = app.repository.strokesForPage(documentId, currentPage)
                strokesByPage[currentPage] = loaded.toMutableList()
                pageAdapter?.updateStrokes(currentPage, loaded)
            }
        }
    }

    private fun onPageTap(page: Int, zone: TapZone) {
        when (zone) {
            TapZone.LEFT -> if (currentTool == AnnotationTools.NONE) goToPage(page - 1)
            TapZone.RIGHT -> if (currentTool == AnnotationTools.NONE) goToPage(page + 1)
            TapZone.CENTER -> toggleControls()
        }
    }

    private fun goToPage(page: Int) {
        if (page !in 0 until pageCount) return
        pageList?.smoothScrollToPosition(page)
    }

    private fun selectTool(tool: String) {
        currentTool = if (currentTool == tool) AnnotationTools.NONE else tool
        pageAdapter?.setInkWidth(PEN_WIDTHS[penWidthIndex])
        pageAdapter?.setInkColor(INK_COLORS[inkColorIndex])
        pageAdapter?.setTool(currentTool)
        Toast.makeText(
            this,
            if (currentTool == AnnotationTools.NONE) "Reading mode" else currentTool.lowercase().replaceFirstChar(Char::uppercase),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun onStrokeCommitted(input: Stroke) {
        val stroke = input.copy(documentId = documentId, width = if (input.tool == AnnotationTools.PEN) PEN_WIDTHS[penWidthIndex] else input.width)
        lifecycleScope.launch {
            val stored = app.repository.saveStroke(stroke)
            val pageStrokes = strokesByPage.getOrPut(stored.pageIndex) { ArrayList() }
            pageStrokes.add(stored)
            loadedPages.add(stored.pageIndex)
            pageAdapter?.updateStrokes(stored.pageIndex, pageStrokes)
            undoStack.addLast(EditAction.Added(stored))
            redoStack.clear()
        }
    }

    private fun onStrokesErased(page: Int, ids: List<Long>) {
        val pageStrokes = strokesByPage[page] ?: return
        val removed = pageStrokes.filter { it.id in ids }
        if (removed.isEmpty()) return
        pageStrokes.removeAll { it.id in ids }
        pageAdapter?.updateStrokes(page, pageStrokes)
        lifecycleScope.launch {
            removed.forEach { app.repository.deleteStroke(it.id) }
        }
        undoStack.addLast(EditAction.Removed(removed))
        redoStack.clear()
    }

    private fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        when (action) {
            is EditAction.Added -> {
                strokesByPage[action.stroke.pageIndex]?.removeAll { it.id == action.stroke.id }
                pageAdapter?.updateStrokes(action.stroke.pageIndex, strokesByPage[action.stroke.pageIndex].orEmpty())
                lifecycleScope.launch { app.repository.deleteStroke(action.stroke.id) }
            }
            is EditAction.Removed -> {
                strokesByPage.getOrPut(action.strokes.firstOrNull()?.pageIndex ?: currentPage) { ArrayList() }.addAll(action.strokes)
                action.strokes.firstOrNull()?.pageIndex?.let { pageAdapter?.updateStrokes(it, strokesByPage[it].orEmpty()) }
                lifecycleScope.launch { app.repository.restoreStrokes(action.strokes) }
            }
        }
        redoStack.addLast(action)
    }

    private fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        when (action) {
            is EditAction.Added -> {
                strokesByPage.getOrPut(action.stroke.pageIndex) { ArrayList() }.add(action.stroke)
                pageAdapter?.updateStrokes(action.stroke.pageIndex, strokesByPage[action.stroke.pageIndex].orEmpty())
                lifecycleScope.launch { app.repository.restoreStrokes(listOf(action.stroke)) }
            }
            is EditAction.Removed -> {
                val ids = action.strokes.map { it.id }.toSet()
                strokesByPage[action.strokes.firstOrNull()?.pageIndex]?.removeAll { it.id in ids }
                action.strokes.firstOrNull()?.pageIndex?.let { pageAdapter?.updateStrokes(it, strokesByPage[it].orEmpty()) }
                lifecycleScope.launch { action.strokes.forEach { app.repository.deleteStroke(it.id) } }
            }
        }
        undoStack.addLast(action)
    }

    private fun showNoteEditor() {
        val input = EditText(this).apply {
            hint = "Write a quick note"
            minLines = 4
            gravity = android.view.Gravity.TOP
            setPadding(16, 12, 16, 12)
        }
        AlertDialog.Builder(this)
            .setTitle("Page ${currentPage + 1} note")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val content = input.text.toString().trim()
                if (content.isNotBlank()) {
                    lifecycleScope.launch {
                        app.repository.saveNote(NoteEntity(documentId = documentId, pageIndex = currentPage, content = content))
                        Toast.makeText(this@ReaderActivity, "Note saved", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showPageJump() {
        val input = EditText(this).apply {
            hint = "1–$pageCount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Go") { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page != null && page in 1..pageCount) goToPage(page - 1)
                else Toast.makeText(this, "Enter a page from 1 to $pageCount", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showPageOverview() {
        val loadedDocument = document ?: return
        val renderEngine = engine ?: return
        val dialog = androidx.appcompat.app.AppCompatDialog(this)
        val list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ReaderActivity, 4)
            setPadding(8, 8, 8, 8)
            clipToPadding = false
            adapter = PageThumbnailAdapter(
                loadedDocument,
                renderEngine,
                ThumbnailStore(this@ReaderActivity),
                lifecycleScope
            ) { page ->
                dialog.dismiss()
                goToPage(page)
            }
        }
        dialog.setTitle(R.string.page_overview)
        dialog.setContentView(list)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.86f).toInt())
    }

    private fun showExportChooser() {
        AlertDialog.Builder(this)
            .setTitle("Export")
            .setItems(arrayOf("Annotated PDF", "Current page PNG", "Current page JPEG")) { _, which ->
                when (which) {
                    0 -> exportPdf()
                    1 -> exportImage(ExportManager.ImageFormat.PNG)
                    2 -> exportImage(ExportManager.ImageFormat.JPEG)
                }
            }
            .show()
    }

    private fun exportImage(format: ExportManager.ImageFormat) {
        val loadedDocument = document ?: return
        val renderEngine = engine ?: return
        Toast.makeText(this, "Exporting page…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                ExportManager.exportPageImage(
                    this@ReaderActivity,
                    loadedDocument,
                    renderEngine,
                    currentPage,
                    strokesByPage[currentPage] ?: app.repository.strokesForPage(documentId, currentPage),
                    format
                )
            }.onSuccess { shareFile(it, if (format == ExportManager.ImageFormat.PNG) "image/png" else "image/jpeg") }
                .onFailure { Toast.makeText(this@ReaderActivity, it.message ?: "Export failed", Toast.LENGTH_LONG).show() }
        }
    }

    private fun exportPdf() {
        val loadedDocument = document ?: return
        val renderEngine = engine ?: return
        Toast.makeText(this, "Exporting PDF…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                ExportManager.exportAnnotatedPdf(
                    this@ReaderActivity,
                    loadedDocument,
                    renderEngine,
                    strokesForPage = { page -> app.repository.strokesForPage(documentId, page) }
                )
            }.onSuccess { shareFile(it, "application/pdf") }
                .onFailure { Toast.makeText(this@ReaderActivity, it.message ?: "Export failed", Toast.LENGTH_LONG).show() }
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share export"
            )
        )
    }

    private fun recognizeCurrentPage() {
        val strokes = strokesByPage[currentPage].orEmpty()
        if (strokes.isEmpty()) {
            Toast.makeText(this, "Write on this page first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val manager = HandwritingRecognitionManager(applicationContext)
            if (!manager.isEnglishModelDownloaded()) {
                AlertDialog.Builder(this@ReaderActivity)
                    .setTitle("English handwriting model")
                    .setMessage("Recognition is optional. Download the English model once, then recognition works offline.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download") { _, _ -> downloadAndRecognize(manager) }
                    .show()
                return@launch
            }
            runRecognition(manager, strokes)
        }
    }

    private fun downloadAndRecognize(manager: HandwritingRecognitionManager) {
        Toast.makeText(this, "Downloading model…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            if (!manager.downloadEnglishModel()) {
                Toast.makeText(this@ReaderActivity, "Model download unavailable", Toast.LENGTH_LONG).show()
                return@launch
            }
            runRecognition(manager, strokesByPage[currentPage].orEmpty())
        }
    }

    private suspend fun runRecognition(manager: HandwritingRecognitionManager, strokes: List<Stroke>) {
        val recognized = manager.recognize(strokes)?.trim().orEmpty()
        if (recognized.isBlank()) {
            Toast.makeText(this, "No handwriting recognized", Toast.LENGTH_SHORT).show()
            return
        }
        withContext(Dispatchers.IO) {
            strokes.forEachIndexed { index, stroke ->
                app.repository.markStrokeRecognized(stroke.id, recognized.takeIf { index == 0 })
            }
            app.repository.rebuildHandwritingIndex(documentId)
        }
        Toast.makeText(this, "Handwriting indexed", Toast.LENGTH_SHORT).show()
    }

    private fun toggleControls() {
        val show = controls?.visibility != View.VISIBLE
        controls?.visibility = if (show) View.VISIBLE else View.GONE
        toolbar.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_overview -> { showPageOverview(); true }
        R.id.action_export -> { showExportChooser(); true }
        R.id.action_recognize -> { recognizeCurrentPage(); true }
        R.id.action_jump -> { showPageJump(); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_PAGE, currentPage)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pageAdapter?.setTool(AnnotationTools.NONE)
        engine?.close()
        engine = null
        super.onDestroy()
    }

    private sealed interface EditAction {
        data class Added(val stroke: Stroke) : EditAction
        data class Removed(val strokes: List<Stroke>) : EditAction
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        private const val KEY_PAGE = "page"
        private val PEN_WIDTHS = floatArrayOf(0.0028f, 0.0045f, 0.0075f)
        private val INK_COLORS = intArrayOf(Color.rgb(34, 74, 150), Color.rgb(180, 45, 45), Color.rgb(32, 120, 75))
    }
}
