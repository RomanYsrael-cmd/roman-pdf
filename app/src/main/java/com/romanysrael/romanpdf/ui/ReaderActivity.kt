package com.romanysrael.romanpdf.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import kotlin.math.max

class ReaderActivity : AppCompatActivity() {
    private val app: RomanPdfApplication get() = application as RomanPdfApplication
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private var documentId: Long = -1L
    private var document: DocumentEntity? = null
    private var engine: DocumentRenderEngine? = null
    private var pageAdapter: PdfPageAdapter? = null
    private var pageList: RecyclerView? = null
    private var controls: View? = null
    private var annotationTools: View? = null
    private var progress: ProgressBar? = null
    private lateinit var toolbar: Toolbar
    private lateinit var readerRoot: View
    private lateinit var modeButton: ImageButton
    private lateinit var modeIndicator: TextView
    private lateinit var colorButton: ImageButton
    private var pageCount = 1
    private var currentPage = 0
    private var restoredPage: Int? = null
    private var readerMode = ReaderMode.VIEW
    private var currentTool = AnnotationTools.NONE
    private var penWidthIndex = 1
    private var penColor = Color.BLACK
    private var highlighterColor = Color.YELLOW
    private var chromeVisible = true
    private var fullscreenEnabled = false
    private var bookmarkedPages: Set<Int> = emptySet()
    private val strokesByPage = HashMap<Int, MutableList<Stroke>>()
    private val loadedPages = HashSet<Int>()
    private val undoStack = ArrayDeque<EditAction>()
    private val redoStack = ArrayDeque<EditAction>()

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        readerRoot = findViewById(R.id.reader_root)
        installWindowInsets()
        toolbar = findViewById(R.id.reader_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }
        controls = findViewById(R.id.reader_controls)
        annotationTools = findViewById(R.id.annotation_tools_group)
        pageList = findViewById(R.id.page_list)
        progress = findViewById(R.id.reader_progress)
        modeButton = findViewById(R.id.mode_button)
        modeIndicator = findViewById(R.id.mode_indicator)
        colorButton = findViewById(R.id.color_button)

        penWidthIndex = preferences.getInt(PREF_PEN_WIDTH, 1).coerceIn(0, PEN_WIDTHS.lastIndex)
        penColor = preferences.getInt(PREF_PEN_COLOR, Color.BLACK)
        highlighterColor = preferences.getInt(PREF_HIGHLIGHT_COLOR, Color.YELLOW)
        readerMode = savedInstanceState?.getString(KEY_MODE)?.let { stored ->
            runCatching { ReaderMode.valueOf(stored) }.getOrNull()
        } ?: ReaderMode.VIEW
        currentTool = if (readerMode == ReaderMode.EDIT) AnnotationTools.PEN else AnnotationTools.NONE
        fullscreenEnabled = savedInstanceState?.getBoolean(KEY_FULLSCREEN)
            ?: preferences.getBoolean(PREF_FULLSCREEN, false)

        modeButton.setOnClickListener {
            setReaderMode(if (readerMode == ReaderMode.VIEW) ReaderMode.EDIT else ReaderMode.VIEW)
        }
        findViewById<ImageButton>(R.id.pen_button).apply {
            setOnClickListener { selectTool(AnnotationTools.PEN) }
            setOnLongClickListener {
                penWidthIndex = (penWidthIndex + 1) % PEN_WIDTHS.size
                preferences.edit().putInt(PREF_PEN_WIDTH, penWidthIndex).apply()
                pageAdapter?.setInkWidth(PEN_WIDTHS[penWidthIndex])
                Toast.makeText(this@ReaderActivity, getString(R.string.pen_width, penWidthIndex + 1), Toast.LENGTH_SHORT).show()
                true
            }
        }
        findViewById<ImageButton>(R.id.highlighter_button).apply {
            setOnClickListener { selectTool(AnnotationTools.HIGHLIGHT) }
            setOnLongClickListener {
                cycleHighlighterColor()
                true
            }
        }
        findViewById<ImageButton>(R.id.eraser_button).setOnClickListener { selectTool(AnnotationTools.ERASER) }
        findViewById<ImageButton>(R.id.undo_button).setOnClickListener { undo() }
        findViewById<ImageButton>(R.id.redo_button).setOnClickListener { redo() }
        findViewById<ImageButton>(R.id.note_button).setOnClickListener { showNoteEditor() }
        colorButton.setOnClickListener { showColorPalette() }

        documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L)
        if (documentId <= 0L) {
            finish()
            return
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.repository.observeBookmarks(documentId).collect { bookmarks ->
                    bookmarkedPages = bookmarks.map { it.pageIndex }.toSet()
                    invalidateOptionsMenu()
                }
            }
        }
        restoredPage = savedInstanceState?.getInt(KEY_PAGE)
        currentPage = restoredPage ?: 0
        configurePager()
        setReaderMode(readerMode, announce = false)
        setFullscreen(fullscreenEnabled, persist = false)
        loadDocument()
    }

    private fun installWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(readerRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val insetContent = !fullscreenEnabled
            view.setPadding(
                if (insetContent) max(bars.left, cutout.left) else 0,
                if (insetContent) max(bars.top, cutout.top) else 0,
                if (insetContent) max(bars.right, cutout.right) else 0,
                if (insetContent) max(bars.bottom, cutout.bottom) else 0
            )
            pageList?.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(readerRoot)
    }

    private fun setFullscreen(enabled: Boolean, persist: Boolean = true) {
        fullscreenEnabled = enabled
        if (persist) preferences.edit().putBoolean(PREF_FULLSCREEN, enabled).apply()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        setChromeVisible(!enabled)
        if (enabled) readerRoot.setPadding(0, 0, 0, 0)
        ViewCompat.requestApplyInsets(readerRoot)
        pageList?.requestLayout()
        pageList?.invalidate()
        invalidateOptionsMenu()
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        toolbar.visibility = if (visible) View.VISIBLE else View.GONE
        controls?.visibility = if (visible) View.VISIBLE else View.GONE
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
                pageAdapter?.setMode(readerMode)
                pageAdapter?.setTool(currentTool)
                pageAdapter?.setInkWidth(PEN_WIDTHS[penWidthIndex])
                pageAdapter?.setInkColor(activeInkColor())
                pageList?.adapter = pageAdapter
                val savedPage = (restoredPage
                    ?: intent.getIntExtra(KEY_PAGE, loaded.lastPage)).coerceIn(0, pageCount - 1)
                currentPage = savedPage
                pageList?.scrollToPosition(savedPage)
                onPageSettled(savedPage)
                progress?.visibility = View.GONE
            }.onFailure {
                progress?.visibility = View.GONE
                Toast.makeText(this@ReaderActivity, it.message ?: "Unable to open document", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun onPageSettled(page: Int) {
        currentPage = page.coerceIn(0, pageCount - 1)
        supportActionBar?.subtitle = getString(R.string.reader_page, currentPage + 1, pageCount)
        invalidateOptionsMenu()
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
        if (readerMode != ReaderMode.VIEW) return
        when (zone) {
            TapZone.LEFT -> goToPage(page - 1, NavigationSource.TAP)
            TapZone.RIGHT -> goToPage(page + 1, NavigationSource.TAP)
            TapZone.CENTER -> toggleControls()
        }
    }

    private fun goToPage(page: Int, source: NavigationSource = NavigationSource.PAGE_JUMP) {
        if (page !in 0 until pageCount) return
        if (ReaderInteractionPolicy.usesImmediatePositioning(source)) {
            pageList?.scrollToPosition(page)
            onPageSettled(page)
        } else {
            pageList?.smoothScrollToPosition(page)
        }
    }

    private fun setReaderMode(newMode: ReaderMode, announce: Boolean = true) {
        readerMode = newMode
        currentTool = if (newMode == ReaderMode.EDIT) {
            currentTool.takeUnless { it == AnnotationTools.NONE } ?: AnnotationTools.PEN
        } else {
            AnnotationTools.NONE
        }
        pageAdapter?.setMode(newMode)
        pageAdapter?.setTool(currentTool)
        pageAdapter?.setInkWidth(PEN_WIDTHS[penWidthIndex])
        pageAdapter?.setInkColor(activeInkColor())
        updateModeUi()
        if (announce) {
            Toast.makeText(
                this,
                if (newMode == ReaderMode.EDIT) R.string.edit_mode else R.string.view_mode,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun selectTool(tool: String) {
        if (readerMode != ReaderMode.EDIT) setReaderMode(ReaderMode.EDIT, announce = false)
        currentTool = tool
        pageAdapter?.setTool(currentTool)
        pageAdapter?.setInkWidth(PEN_WIDTHS[penWidthIndex])
        pageAdapter?.setInkColor(activeInkColor())
        updateModeUi()
    }

    private fun updateModeUi() {
        modeButton.setImageResource(if (readerMode == ReaderMode.EDIT) R.drawable.ic_pen else R.drawable.ic_view)
        modeButton.contentDescription = getString(if (readerMode == ReaderMode.EDIT) R.string.switch_to_view else R.string.switch_to_edit)
        modeIndicator.text = getString(if (readerMode == ReaderMode.EDIT) R.string.edit_mode else R.string.view_mode)
        annotationTools?.visibility = if (readerMode == ReaderMode.EDIT) View.VISIBLE else View.GONE
        val selectedColor = Color.rgb(220, 230, 250)
        styleToggle(modeButton, readerMode == ReaderMode.EDIT, selectedColor)
        styleToggle(findViewById(R.id.pen_button), currentTool == AnnotationTools.PEN, selectedColor)
        styleToggle(findViewById(R.id.highlighter_button), currentTool == AnnotationTools.HIGHLIGHT, selectedColor)
        styleToggle(findViewById(R.id.eraser_button), currentTool == AnnotationTools.ERASER, selectedColor)
        colorButton.imageTintList = ColorStateList.valueOf(activeInkColor())
        colorButton.contentDescription = getString(R.string.annotation_color, colorName(activeInkColor()))
        colorButton.backgroundTintList = ColorStateList.valueOf(selectedColor)
    }

    private fun styleToggle(button: ImageButton, selected: Boolean, selectedColor: Int) {
        button.isSelected = selected
        button.backgroundTintList = ColorStateList.valueOf(if (selected) selectedColor else Color.TRANSPARENT)
        button.imageTintList = ColorStateList.valueOf(if (selected) getColor(R.color.roman_blue) else getColor(R.color.roman_ink))
    }

    private fun activeInkColor(): Int = if (currentTool == AnnotationTools.HIGHLIGHT) highlighterColor else penColor

    private fun chooseColor(color: Int) {
        if (currentTool == AnnotationTools.HIGHLIGHT) {
            highlighterColor = color
            preferences.edit().putInt(PREF_HIGHLIGHT_COLOR, color).apply()
        } else {
            penColor = color
            preferences.edit().putInt(PREF_PEN_COLOR, color).apply()
        }
        pageAdapter?.setInkColor(color)
        updateModeUi()
    }

    private fun cycleHighlighterColor() {
        val current = INK_PALETTE.indexOfFirst { it.value == highlighterColor }.coerceAtLeast(0)
        val next = INK_PALETTE[(current + 1) % INK_PALETTE.size].value
        highlighterColor = next
        preferences.edit().putInt(PREF_HIGHLIGHT_COLOR, next).apply()
        if (currentTool == AnnotationTools.HIGHLIGHT) pageAdapter?.setInkColor(next)
        updateModeUi()
        Toast.makeText(this, getString(R.string.annotation_color, colorName(next)), Toast.LENGTH_SHORT).show()
    }

    private fun showColorPalette() {
        if (readerMode != ReaderMode.EDIT) return
        var popup: PopupWindow? = null
        val grid = GridLayout(this).apply {
            columnCount = 5
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val selected = activeInkColor()
        INK_PALETTE.forEach { paletteColor ->
            val swatch = TextView(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(44)
                    height = dp(44)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                gravity = Gravity.CENTER
                text = if (paletteColor.value == selected) "✓" else ""
                textSize = 16f
                setTextColor(if (isLightColor(paletteColor.value)) Color.DKGRAY else Color.WHITE)
                contentDescription = paletteColor.label
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(paletteColor.value)
                    setStroke(if (paletteColor.value == selected) dp(3) else dp(1), if (paletteColor.value == Color.WHITE) Color.DKGRAY else Color.WHITE)
                }
                setOnClickListener {
                    chooseColor(paletteColor.value)
                    popup?.dismiss()
                }
            }
            grid.addView(swatch)
        }
        popup = PopupWindow(grid, dp(5 * 52 + 16), dp(2 * 52 + 16), true).apply {
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            isOutsideTouchable = true
            showAsDropDown(colorButton, -dp(176), -dp(132))
        }
    }

    private fun toggleControls() {
        setChromeVisible(!chromeVisible)
    }

    private fun showNoteEditor() {
        val input = EditText(this).apply {
            hint = getString(R.string.note_hint)
            minLines = 4
            gravity = Gravity.TOP
            setPadding(16, 12, 16, 12)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.page_note, currentPage + 1))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val content = input.text.toString().trim()
                if (content.isNotBlank()) {
                    lifecycleScope.launch {
                        app.repository.saveNote(NoteEntity(documentId = documentId, pageIndex = currentPage, content = content))
                        Toast.makeText(this@ReaderActivity, R.string.note_saved, Toast.LENGTH_SHORT).show()
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
            .setTitle(R.string.go_to_page)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.go) { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page != null && page in 1..pageCount) goToPage(page - 1, NavigationSource.PAGE_JUMP)
                else Toast.makeText(this, getString(R.string.page_range, pageCount), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun toggleCurrentBookmark() {
        val page = currentPage
        val shouldBookmark = page !in bookmarkedPages
        bookmarkedPages = if (shouldBookmark) bookmarkedPages + page else bookmarkedPages - page
        invalidateOptionsMenu()
        lifecycleScope.launch {
            runCatching { app.repository.setBookmark(documentId, page, shouldBookmark) }
                .onSuccess {
                    Toast.makeText(
                        this@ReaderActivity,
                        getString(if (shouldBookmark) R.string.bookmark_page else R.string.remove_bookmark),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .onFailure {
                    bookmarkedPages = if (shouldBookmark) bookmarkedPages - page else bookmarkedPages + page
                    invalidateOptionsMenu()
                    Toast.makeText(this@ReaderActivity, it.message ?: getString(R.string.save_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showBookmarks() {
        val pages = bookmarkedPages.sorted()
        if (pages.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks)
                .setMessage(R.string.no_bookmarks)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bookmarks)
            .setItems(pages.map { getString(R.string.bookmark_page_item, it + 1) }.toTypedArray()) { _, which ->
                goToPage(pages[which], NavigationSource.PAGE_JUMP)
            }
            .setNegativeButton(android.R.string.cancel, null)
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
                goToPage(page, NavigationSource.THUMBNAIL)
            }
        }
        dialog.setTitle(R.string.page_overview)
        dialog.setContentView(list)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.86f).toInt())
    }

    private fun showExportChooser() {
        AlertDialog.Builder(this)
            .setTitle(R.string.export)
            .setItems(arrayOf(getString(R.string.export_annotated_pdf), getString(R.string.export_page_png), getString(R.string.export_page_jpeg))) { _, which ->
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
        Toast.makeText(this, R.string.exporting_page, Toast.LENGTH_SHORT).show()
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
                .onFailure { Toast.makeText(this@ReaderActivity, it.message ?: getString(R.string.export_failed), Toast.LENGTH_LONG).show() }
        }
    }

    private fun exportPdf() {
        val loadedDocument = document ?: return
        val renderEngine = engine ?: return
        Toast.makeText(this, R.string.exporting_pdf, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                ExportManager.exportAnnotatedPdf(
                    this@ReaderActivity,
                    loadedDocument,
                    renderEngine,
                    strokesForPage = { page -> app.repository.strokesForPage(documentId, page) }
                )
            }.onSuccess { shareFile(it, "application/pdf") }
                .onFailure { Toast.makeText(this@ReaderActivity, it.message ?: getString(R.string.export_failed), Toast.LENGTH_LONG).show() }
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
                getString(R.string.share_export)
            )
        )
    }

    private fun recognizeCurrentPage() {
        val strokes = strokesByPage[currentPage].orEmpty()
        if (strokes.isEmpty()) {
            Toast.makeText(this, R.string.write_first, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val manager = HandwritingRecognitionManager(applicationContext)
            if (!manager.isEnglishModelDownloaded()) {
                AlertDialog.Builder(this@ReaderActivity)
                    .setTitle(R.string.handwriting_model)
                    .setMessage(R.string.handwriting_model_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.download) { _, _ -> downloadAndRecognize(manager) }
                    .show()
                return@launch
            }
            runRecognition(manager, strokes)
        }
    }

    private fun downloadAndRecognize(manager: HandwritingRecognitionManager) {
        Toast.makeText(this, R.string.downloading_model, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            if (!manager.downloadEnglishModel()) {
                Toast.makeText(this@ReaderActivity, R.string.model_download_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            runRecognition(manager, strokesByPage[currentPage].orEmpty())
        }
    }

    private suspend fun runRecognition(manager: HandwritingRecognitionManager, strokes: List<Stroke>) {
        val recognized = manager.recognize(strokes)?.trim().orEmpty()
        if (recognized.isBlank()) {
            Toast.makeText(this, R.string.no_handwriting, Toast.LENGTH_SHORT).show()
            return
        }
        withContext(Dispatchers.IO) {
            strokes.forEachIndexed { index, stroke ->
                app.repository.markStrokeRecognized(stroke.id, recognized.takeIf { index == 0 })
            }
            app.repository.rebuildHandwritingIndex(documentId)
        }
        Toast.makeText(this, R.string.handwriting_indexed, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_fullscreen)?.title = getString(
            if (fullscreenEnabled) R.string.exit_fullscreen else R.string.enter_fullscreen
        )
        menu.findItem(R.id.action_bookmark)?.apply {
            val isBookmarked = currentPage in bookmarkedPages
            icon = getDrawable(if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline)
            title = getString(if (isBookmarked) R.string.remove_bookmark else R.string.bookmark_page)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_overview -> { showPageOverview(); true }
        R.id.action_bookmark -> { toggleCurrentBookmark(); true }
        R.id.action_bookmarks -> { showBookmarks(); true }
        R.id.action_export -> { showExportChooser(); true }
        R.id.action_recognize -> { recognizeCurrentPage(); true }
        R.id.action_jump -> { showPageJump(); true }
        R.id.action_fullscreen -> { setFullscreen(!fullscreenEnabled); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_PAGE, currentPage)
        outState.putString(KEY_MODE, readerMode.name)
        outState.putBoolean(KEY_FULLSCREEN, fullscreenEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pageAdapter?.setMode(ReaderMode.VIEW)
        engine?.close()
        engine = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isLightColor(color: Int): Boolean =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 180

    private fun colorName(color: Int): String = INK_PALETTE.firstOrNull { it.value == color }?.label ?: "custom"

    private sealed interface EditAction {
        data class Added(val stroke: Stroke) : EditAction
        data class Removed(val strokes: List<Stroke>) : EditAction
    }

    private fun onStrokeCommitted(input: Stroke) {
        val stroke = input.copy(
            documentId = documentId,
            width = if (input.tool == AnnotationTools.PEN) PEN_WIDTHS[penWidthIndex] else input.width
        )
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

    private data class PaletteColor(val label: String, val value: Int)

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        private const val KEY_PAGE = "page"
        private const val KEY_MODE = "reader_mode"
        private const val KEY_FULLSCREEN = "reader_fullscreen"
        private const val PREFERENCES = "reader_preferences"
        private const val PREF_FULLSCREEN = "fullscreen"
        private const val PREF_PEN_WIDTH = "pen_width"
        private const val PREF_PEN_COLOR = "pen_color"
        private const val PREF_HIGHLIGHT_COLOR = "highlighter_color"
        private val PEN_WIDTHS = floatArrayOf(0.0028f, 0.0045f, 0.0075f)
        private val INK_PALETTE = listOf(
            PaletteColor("Black", Color.BLACK),
            PaletteColor("Dark gray", Color.rgb(70, 70, 70)),
            PaletteColor("Red", Color.rgb(198, 40, 40)),
            PaletteColor("Orange", Color.rgb(230, 126, 34)),
            PaletteColor("Yellow", Color.YELLOW),
            PaletteColor("Green", Color.rgb(46, 125, 50)),
            PaletteColor("Blue", Color.rgb(34, 74, 150)),
            PaletteColor("Purple", Color.rgb(123, 63, 160)),
            PaletteColor("White", Color.WHITE)
        )
    }
}
