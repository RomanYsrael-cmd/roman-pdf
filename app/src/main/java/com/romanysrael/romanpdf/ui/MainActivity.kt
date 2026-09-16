package com.romanysrael.romanpdf.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.romanysrael.romanpdf.R
import com.romanysrael.romanpdf.RomanPdfApplication
import com.romanysrael.romanpdf.core.ThumbnailStore
import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.DocumentKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val app: RomanPdfApplication get() = application as RomanPdfApplication
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private lateinit var toolbar: Toolbar
    private lateinit var toolbarBrand: View
    private lateinit var libraryRoot: View
    private lateinit var adapter: LibraryAdapter
    private lateinit var emptyView: View
    private lateinit var libraryList: RecyclerView
    private var currentLayout = LibraryLayout.LIST
    private var currentSort = LibrarySort.RECENTLY_OPENED

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importPdfs(uris)
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        if (uris.size == 1) importUri(uris.single(), DocumentKinds.IMAGE, null)
        else showImageDocumentTitleDialog(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        libraryRoot = findViewById(R.id.main_root)
        toolbar = findViewById(R.id.main_toolbar)
        toolbarBrand = findViewById(R.id.main_toolbar_brand)
        installLibraryInsets()
        window.navigationBarColor = getColor(R.color.roman_paper)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        emptyView = findViewById(R.id.library_empty_container)
        libraryList = findViewById(R.id.library_list)
        adapter = LibraryAdapter(
            scope = lifecycleScope,
            thumbnailStore = ThumbnailStore(this),
            onClick = { openDocument(it) },
            onLongClick = { showDocumentActions(it) }
        )
        currentSort = LibrarySort.fromStored(preferences.getString(LibrarySortPolicy.PREFERENCE_KEY, null))
        currentLayout = LibraryLayout.fromStored(preferences.getString(LibraryLayoutPolicy.PREFERENCE_KEY, null))
        adapter.setSort(currentSort)
        libraryList.adapter = adapter
        applyLibraryLayout(currentLayout, persist = false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.documentDao().observeAll().collect { documents ->
                    adapter.setAll(documents)
                    emptyView.visibility = if (documents.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun installLibraryInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(libraryRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                maxOf(bars.left, cutout.left),
                maxOf(bars.top, cutout.top),
                maxOf(bars.right, cutout.right),
                maxOf(bars.bottom, cutout.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(libraryRoot)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu.findItem(R.id.action_library_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_documents)
        searchView.maxWidth = Int.MAX_VALUE
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                adapter.filter(newText)
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                val normalized = query.trim()
                if (normalized.isNotEmpty()) {
                    startActivity(Intent(this@MainActivity, SearchActivity::class.java).apply {
                        putExtra(SearchActivity.EXTRA_QUERY, normalized)
                    })
                    searchItem.collapseActionView()
                }
                return true
            }
        })
        searchView.setOnCloseListener {
            adapter.filter("")
            false
        }
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                toolbarBrand.visibility = View.GONE
                styleToolbarSearch(searchView)
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                toolbarBrand.visibility = View.VISIBLE
                adapter.filter("")
                return true
            }
        })
        styleToolbarSearch(searchView)
        updateMenuChecks(menu)
        return true
    }

    private fun styleToolbarSearch(searchView: SearchView) {
        val query = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
        query?.setTextColor(Color.WHITE)
        query?.setHintTextColor(Color.argb(190, 255, 255, 255))
        searchView.findViewById<View>(androidx.appcompat.R.id.search_close_btn)?.contentDescription = getString(R.string.clear_search)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateMenuChecks(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateMenuChecks(menu: Menu) {
        menu.findItem(R.id.action_list_view)?.isChecked = currentLayout == LibraryLayout.LIST
        menu.findItem(R.id.action_grid_view)?.isChecked = currentLayout == LibraryLayout.GRID
        val sortIds = mapOf(
            LibrarySort.NAME_ASC to R.id.sort_name_asc,
            LibrarySort.NAME_DESC to R.id.sort_name_desc,
            LibrarySort.RECENTLY_OPENED to R.id.sort_recently_opened,
            LibrarySort.OLDEST_OPENED to R.id.sort_oldest_opened,
            LibrarySort.RECENTLY_IMPORTED to R.id.sort_recently_imported,
            LibrarySort.OLDEST_IMPORTED to R.id.sort_oldest_imported,
            LibrarySort.PAGE_COUNT_ASC to R.id.sort_page_count_asc,
            LibrarySort.PAGE_COUNT_DESC to R.id.sort_page_count_desc
        )
        sortIds.values.forEach { id -> menu.findItem(id)?.isChecked = false }
        sortIds[currentSort]?.let { menu.findItem(it)?.isChecked = true }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import_pdf -> {
            pdfPicker.launch(arrayOf("application/pdf"))
            true
        }
        R.id.action_import_images -> {
            imagePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
            true
        }
        R.id.action_list_view -> {
            applyLibraryLayout(LibraryLayout.LIST, persist = true)
            invalidateOptionsMenu()
            true
        }
        R.id.action_grid_view -> {
            applyLibraryLayout(LibraryLayout.GRID, persist = true)
            invalidateOptionsMenu()
            true
        }
        R.id.sort_name_asc -> selectSort(LibrarySort.NAME_ASC)
        R.id.sort_name_desc -> selectSort(LibrarySort.NAME_DESC)
        R.id.sort_recently_opened -> selectSort(LibrarySort.RECENTLY_OPENED)
        R.id.sort_oldest_opened -> selectSort(LibrarySort.OLDEST_OPENED)
        R.id.sort_recently_imported -> selectSort(LibrarySort.RECENTLY_IMPORTED)
        R.id.sort_oldest_imported -> selectSort(LibrarySort.OLDEST_IMPORTED)
        R.id.sort_page_count_asc -> selectSort(LibrarySort.PAGE_COUNT_ASC)
        R.id.sort_page_count_desc -> selectSort(LibrarySort.PAGE_COUNT_DESC)
        else -> super.onOptionsItemSelected(item)
    }

    private fun selectSort(sort: LibrarySort): Boolean {
        currentSort = sort
        preferences.edit().putString(LibrarySortPolicy.PREFERENCE_KEY, sort.name).apply()
        adapter.setSort(sort)
        invalidateOptionsMenu()
        return true
    }

    private fun applyLibraryLayout(layout: LibraryLayout, persist: Boolean) {
        currentLayout = layout
        if (persist) preferences.edit().putString(LibraryLayoutPolicy.PREFERENCE_KEY, layout.name).apply()
        adapter.setGridMode(layout == LibraryLayout.GRID)
        if (layout == LibraryLayout.GRID) {
            val width = libraryList.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val spanCount = LibraryLayoutPolicy.spanCount(width, resources.displayMetrics.density)
            libraryList.layoutManager = GridLayoutManager(this, spanCount)
            libraryList.post {
                val measuredWidth = libraryList.width.takeIf { it > 0 } ?: width
                (libraryList.layoutManager as? GridLayoutManager)?.spanCount =
                    LibraryLayoutPolicy.spanCount(measuredWidth, resources.displayMetrics.density)
            }
        } else {
            libraryList.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun importUri(uri: Uri, kind: String, mimeType: String?) {
        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                val id = app.repository.importUri(uri, mimeType, kind)
                if (kind == DocumentKinds.PDF) app.repository.indexDocument(id)
            }.onSuccess {
                Toast.makeText(this@MainActivity, R.string.added_to_library, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, it.message ?: getString(R.string.import_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importPdfs(uris: List<Uri>) {
        val selected = uris.distinctBy(Uri::toString)
        Toast.makeText(this, getString(R.string.importing_pdf_count, selected.size), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                selected.forEach { uri ->
                    val id = app.repository.importUri(uri, "application/pdf", DocumentKinds.PDF)
                    if (selected.size == 1) app.repository.indexDocument(id)
                }
            }.onSuccess {
                Toast.makeText(this@MainActivity, getString(R.string.added_pdf_count, selected.size), Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, it.message ?: getString(R.string.import_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showImageDocumentTitleDialog(uris: List<Uri>) {
        lifecycleScope.launch {
            val suggested = app.repository.suggestImageDocumentTitle(uris)
            val input = EditText(this@MainActivity).apply {
                setText(suggested)
                selectAll()
                setSingleLine(true)
                hint = getString(R.string.image_document_title_hint)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.image_document_title)
                .setMessage(resources.getQuantityString(R.plurals.image_document_pages, uris.size, uris.size))
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> importImageDocument(uris, input.text.toString()) }
                .show()
        }
    }

    private fun importImageDocument(uris: List<Uri>, title: String) {
        Toast.makeText(this, R.string.importing_images, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching { app.repository.importImageUris(uris, title) }
                .onSuccess { Toast.makeText(this@MainActivity, R.string.image_document_added, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@MainActivity, it.message ?: getString(R.string.import_failed), Toast.LENGTH_LONG).show() }
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        val uri = incoming.data ?: return
        val type = incoming.type ?: contentResolver.getType(uri)
        val looksLikePdf = type.equals("application/pdf", ignoreCase = true) ||
            uri.toString().substringBefore('?').lowercase().endsWith(".pdf")
        if (!looksLikePdf) return

        Toast.makeText(this, R.string.opening_pdf, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                app.repository.importExternalPdf(uri, type)
            }.onSuccess { id ->
                startActivity(Intent(this@MainActivity, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, id))
                launch(Dispatchers.IO) { app.repository.indexDocument(id) }
            }.onFailure {
                Toast.makeText(this@MainActivity, it.message ?: getString(R.string.open_external_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openDocument(document: DocumentEntity) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, document.id))
    }

    private fun showDocumentActions(document: DocumentEntity) {
        AlertDialog.Builder(this)
            .setTitle(document.title)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.remove_from_library))) { _, which ->
                if (which == 0) showRenameDialog(document) else showRemoveDialog(document)
            }
            .show()
    }

    private fun showRenameDialog(document: DocumentEntity) {
        val input = EditText(this).apply {
            setText(document.title)
            selectAll()
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_document)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        app.database.documentDao().update(document.copy(title = title))
                    }
                }
            }
            .show()
    }

    private fun showRemoveDialog(document: DocumentEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_document_title)
            .setMessage(R.string.remove_document_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.repository.deleteDocument(document) }
                }
            }
            .show()
    }

    companion object {
        private const val PREFERENCES = "library_preferences"
    }
}
