package com.romanysrael.romanpdf.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.romanysrael.romanpdf.R
import com.romanysrael.romanpdf.RomanPdfApplication
import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.SearchHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {
    private val app: RomanPdfApplication get() = application as RomanPdfApplication
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private lateinit var adapter: SearchResultAdapter
    private var documents: Map<Long, DocumentEntity> = emptyMap()
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        val toolbar = findViewById<Toolbar>(R.id.search_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }

        input = findViewById(R.id.search_input)
        empty = findViewById(R.id.search_empty)
        adapter = SearchResultAdapter(
            titleFor = { hit -> documents[hit.documentId.toLongOrNull()]?.title ?: "Document" },
            onClick = ::openResult
        )
        findViewById<RecyclerView>(R.id.search_results).apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            adapter = this@SearchActivity.adapter
        }
        input.doAfterTextChanged { scheduleSearch(it?.toString().orEmpty()) }
        lifecycleScope.launch {
            documents = app.database.documentDao().getAll().associateBy { it.id }
            scheduleSearch(input.text.toString())
        }
        input.requestFocus()
    }

    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(160)
            val results = app.repository.search(query)
            adapter.submitList(results)
            empty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openResult(hit: SearchHit) {
        val page = hit.pageIndex.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, hit.documentId.toLong())
                .putExtra("page", page)
        )
    }
}
