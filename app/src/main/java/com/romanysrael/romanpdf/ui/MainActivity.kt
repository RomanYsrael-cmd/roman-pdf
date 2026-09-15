package com.romanysrael.romanpdf.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.doAfterTextChanged
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
    private lateinit var adapter: LibraryAdapter
    private lateinit var emptyView: android.widget.TextView
    private lateinit var libraryList: RecyclerView
    private lateinit var layoutToggle: RadioGroup

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importUri(it, DocumentKinds.PDF, "application/pdf") }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        if (uris.size == 1) importUri(uris.single(), DocumentKinds.IMAGE, null)
        else showImageDocumentTitleDialog(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.main_toolbar)
        setSupportActionBar(toolbar)

        emptyView = findViewById(R.id.library_empty)
        libraryList = findViewById(R.id.library_list)
        adapter = LibraryAdapter(
            scope = lifecycleScope,
            thumbnailStore = ThumbnailStore(this),
            onClick = { openDocument(it) },
            onLongClick = { showDocumentActions(it) }
        )
        libraryList.adapter = adapter

        findViewById<android.widget.Button>(R.id.import_pdf_button).setOnClickListener {
            pdfPicker.launch(arrayOf("application/pdf"))
        }
        findViewById<android.widget.Button>(R.id.import_image_button).setOnClickListener {
            imagePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
        }
        findViewById<android.widget.ImageButton>(R.id.global_search_button).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<EditText>(R.id.library_filter).doAfterTextChanged {
            adapter.filter(it?.toString().orEmpty())
        }

        layoutToggle = findViewById(R.id.library_layout_toggle)
        layoutToggle.setOnCheckedChangeListener { _, checkedId ->
            val selected = if (checkedId == R.id.grid_view_button) LibraryLayout.GRID else LibraryLayout.LIST
            applyLibraryLayout(selected, persist = true)
        }
        val storedLayout = LibraryLayout.fromStored(preferences.getString(LibraryLayoutPolicy.PREFERENCE_KEY, null))
        layoutToggle.check(if (storedLayout == LibraryLayout.GRID) R.id.grid_view_button else R.id.list_view_button)
        applyLibraryLayout(storedLayout, persist = false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.documentDao().observeAll().collect { documents ->
                    adapter.setAll(documents)
                    emptyView.visibility = if (documents.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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

    private fun applyLibraryLayout(layout: LibraryLayout, persist: Boolean) {
        if (persist) preferences.edit().putString(LibraryLayoutPolicy.PREFERENCE_KEY, layout.name).apply()
        adapter.setGridMode(layout == LibraryLayout.GRID)
        if (layout == LibraryLayout.GRID) {
            val width = libraryList.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val spanCount = LibraryLayoutPolicy.spanCount(width, resources.displayMetrics.density)
            libraryList.layoutManager = GridLayoutManager(this, spanCount)
            libraryList.post {
                val measuredWidth = libraryList.width.takeIf { it > 0 } ?: width
                val current = libraryList.layoutManager as? GridLayoutManager
                current?.spanCount = LibraryLayoutPolicy.spanCount(measuredWidth, resources.displayMetrics.density)
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

    private fun showImageDocumentTitleDialog(uris: List<Uri>) {
        lifecycleScope.launch {
            val suggested = app.repository.suggestImageDocumentTitle(uris)
            val input = EditText(this@MainActivity).apply {
                setText(suggested)
                selectAll()
                setSingleLine(true)
                hint = getString(R.string.image_document_title_hint)
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.image_document_title)
                .setMessage(getString(R.string.image_document_pages, uris.size))
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
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, document.id)
        )
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
