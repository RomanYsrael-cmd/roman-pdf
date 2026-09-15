package com.romanysrael.romanpdf.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private lateinit var adapter: LibraryAdapter
    private lateinit var emptyView: android.widget.TextView

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importUri(it, DocumentKinds.PDF, "application/pdf") }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importUri(it, DocumentKinds.IMAGE, null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.main_toolbar)
        setSupportActionBar(toolbar)

        emptyView = findViewById(R.id.library_empty)
        val list = findViewById<RecyclerView>(R.id.library_list)
        adapter = LibraryAdapter(
            scope = lifecycleScope,
            thumbnailStore = ThumbnailStore(this),
            onClick = { openDocument(it) },
            onLongClick = { showDocumentActions(it) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.documentDao().observeAll().collect { documents ->
                    adapter.setAll(documents)
                    emptyView.visibility = if (documents.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    private fun importUri(uri: Uri, kind: String, mimeType: String?) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                val id = app.repository.importUri(uri, mimeType, kind)
                if (kind == DocumentKinds.PDF) {
                    app.repository.indexDocument(id)
                }
            }.onSuccess {
                Toast.makeText(this@MainActivity, "Added to library", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, it.message ?: "Import failed", Toast.LENGTH_LONG).show()
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
            .setItems(arrayOf("Rename", "Remove from library")) { _, which ->
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
            .setTitle("Rename document")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
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
            .setTitle("Remove document?")
            .setMessage("Annotations and notes for this document will also be removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.repository.deleteDocument(document) }
                }
            }
            .show()
    }
}
