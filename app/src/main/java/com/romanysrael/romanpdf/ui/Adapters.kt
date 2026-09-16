package com.romanysrael.romanpdf.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.romanysrael.romanpdf.R
import com.romanysrael.romanpdf.core.DocumentRenderEngine
import com.romanysrael.romanpdf.core.ThumbnailStore
import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.SearchHit
import com.romanysrael.romanpdf.data.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class LibraryAdapter(
    private val scope: CoroutineScope,
    private val thumbnailStore: ThumbnailStore,
    private val onClick: (DocumentEntity) -> Unit,
    private val onLongClick: (DocumentEntity) -> Unit
) : ListAdapter<DocumentEntity, LibraryAdapter.Holder>(DIFF) {
    private val allItems = ArrayList<DocumentEntity>()
    private var gridMode = false
    private var query = ""
    private var sort = LibrarySort.RECENTLY_OPENED

    fun setAll(items: List<DocumentEntity>) {
        allItems.clear()
        allItems.addAll(items)
        refresh()
    }

    fun filter(query: String) {
        this.query = query.trim()
        refresh()
    }

    fun setSort(sort: LibrarySort) {
        if (this.sort == sort) return
        this.sort = sort
        refresh()
    }

    private fun refresh() {
        val sorted = LibrarySortPolicy.sort(allItems, sort)
        val normalized = query.lowercase(Locale.ROOT)
        submitList(if (normalized.isBlank()) sorted else sorted.filter { it.title.lowercase(Locale.ROOT).contains(normalized) })
    }

    fun setGridMode(enabled: Boolean) {
        if (gridMode == enabled) return
        gridMode = enabled
        // Every visible item changes view type; range invalidation forces the correct holder shape.
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemViewType(position: Int): Int = if (gridMode) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(
            if (viewType == VIEW_TYPE_GRID) R.layout.item_document_grid else R.layout.item_document,
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val document = getItem(position)
        holder.title.text = document.title
        holder.meta.text = holder.itemView.context.resources.getQuantityString(
            R.plurals.document_meta,
            document.pageCount,
            document.kind.lowercase().replaceFirstChar(Char::uppercase),
            document.pageCount,
            document.lastPage + 1,
            relativeTime(document.lastOpenedAt)
        )
        holder.job?.cancel()
        holder.clearThumbnail()
        holder.job = scope.launch(Dispatchers.IO) {
            val renderer = runCatching { DocumentRenderEngine(holder.itemView.context.applicationContext, document) }.getOrNull()
            val bitmap = renderer?.let { engine ->
                runCatching { thumbnailStore.getOrCreate(document, engine) }.getOrNull().also { engine.close() }
            }
            withContext(Dispatchers.Main) {
                if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION && getItem(holder.bindingAdapterPosition).id == document.id && bitmap != null) {
                    holder.setThumbnail(bitmap)
                } else {
                    bitmap?.recycleIfNeeded()
                }
            }
        }
        holder.itemView.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                val clicked = getItem(currentPosition)
                onClick(clicked)
            }
        }
        holder.itemView.setOnLongClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) onLongClick(getItem(currentPosition))
            true
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.clearThumbnail()
        super.onViewRecycled(holder)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.document_thumbnail)
        val title: TextView = view.findViewById(R.id.document_title)
        val meta: TextView = view.findViewById(R.id.document_meta)
        var job: Job? = null

        fun clearThumbnail() {
            thumbnailBitmap?.recycleIfNeeded()
            thumbnailBitmap = null
            thumbnail.setImageDrawable(ColorDrawable(thumbnail.context.getColor(R.color.roman_thumbnail_placeholder)))
        }

        fun setThumbnail(value: Bitmap) {
            thumbnailBitmap?.recycleIfNeeded()
            thumbnailBitmap = value
            thumbnail.setImageBitmap(value)
        }

        private var thumbnailBitmap: Bitmap? = null
    }

    companion object {
        private const val VIEW_TYPE_LIST = 0
        private const val VIEW_TYPE_GRID = 1
        private val DIFF = object : DiffUtil.ItemCallback<DocumentEntity>() {
            override fun areItemsTheSame(oldItem: DocumentEntity, newItem: DocumentEntity): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: DocumentEntity, newItem: DocumentEntity): Boolean = oldItem == newItem
        }

        private fun relativeTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
    }
}

class PdfPageAdapter(
    private val renderer: DocumentRenderEngine,
    private val pageCount: Int,
    private val scope: CoroutineScope,
    private val strokesForPage: (Int) -> List<Stroke>,
    private val onTap: (Int, TapZone) -> Unit,
    private val onStroke: (Stroke) -> Unit,
    private val onErase: (Int, List<Long>) -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.Holder>() {
    private val bitmapCache = PageBitmapCache(maxEntries = 2)
    /** All display and prefetch work for one reader is queued through one dispatcher. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val toolByPage = HashMap<Int, String>()
    private var currentMode = ReaderMode.VIEW
    private var currentTool = AnnotationTools.NONE
    private var currentInkWidth = 0.0045f
    private var currentInkColor = Color.rgb(34, 74, 150)
    private var prefetchDirection = 1
    private var attachedRecycler: RecyclerView? = null

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        PdfPageView(parent.context).apply {
            setBitmapCache(bitmapCache)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.pageView.bind(
            position,
            renderer,
            scope,
            strokesForPage(position),
            pageCount,
            renderDispatcher,
            prefetchDirection
        )
        holder.pageView.setMode(currentMode)
        holder.pageView.setTool(toolByPage[position] ?: currentTool)
        holder.pageView.setInkWidth(currentInkWidth)
        holder.pageView.setInkColor(currentInkColor)
        holder.pageView.onPageTap = onTap
        holder.pageView.onStrokeCommitted = onStroke
        holder.pageView.onStrokesErased = onErase
    }

    override fun onViewRecycled(holder: Holder) {
        holder.pageView.unbind()
        super.onViewRecycled(holder)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        attachedRecycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        attachedRecycler = null
        bitmapCache.clear()
    }

    fun updateStrokes(pageIndex: Int, strokes: List<Stroke>) {
        attachedRecycler?.findViewHolderForAdapterPosition(pageIndex)?.let { holder ->
            (holder as? Holder)?.pageView?.setStrokes(strokes)
        }
    }

    fun setTool(tool: String) {
        currentTool = tool
        attachedRecycler?.let { recycler ->
            for (index in 0 until itemCount) {
                (recycler.findViewHolderForAdapterPosition(index) as? Holder)?.pageView?.setTool(tool)
            }
        }
    }

    fun setMode(mode: ReaderMode) {
        currentMode = mode
        attachedRecycler?.let { recycler ->
            for (index in 0 until itemCount) {
                (recycler.findViewHolderForAdapterPosition(index) as? Holder)?.pageView?.setMode(mode)
            }
        }
    }

    fun setInkWidth(width: Float) {
        currentInkWidth = width
        attachedRecycler?.let { recycler ->
            for (index in 0 until itemCount) {
                (recycler.findViewHolderForAdapterPosition(index) as? Holder)?.pageView?.setInkWidth(width)
            }
        }
    }

    fun setInkColor(color: Int) {
        currentInkColor = color
        attachedRecycler?.let { recycler ->
            for (index in 0 until itemCount) {
                (recycler.findViewHolderForAdapterPosition(index) as? Holder)?.pageView?.setInkColor(color)
            }
        }
    }

    fun setPrefetchDirection(direction: Int) {
        prefetchDirection = if (direction < 0) -1 else 1
    }

    fun trimForBackground() {
        attachedRecycler?.let { recycler ->
            for (index in 0 until itemCount) {
                (recycler.findViewHolderForAdapterPosition(index) as? Holder)?.pageView?.trimForBackground()
            }
        }
        bitmapCache.clear()
    }

    fun resumeAfterBackground() {
        attachedRecycler?.let { recycler ->
            for (index in 0 until itemCount) {
                (recycler.findViewHolderForAdapterPosition(index) as? Holder)?.pageView?.requestRenderIfNeeded()
            }
        }
    }

    class Holder(val pageView: PdfPageView) : RecyclerView.ViewHolder(pageView)
}

class PageThumbnailAdapter(
    private val document: DocumentEntity,
    private val renderer: DocumentRenderEngine,
    private val thumbnailStore: ThumbnailStore,
    private val scope: CoroutineScope,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<PageThumbnailAdapter.Holder>() {
    override fun getItemCount(): Int = document.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_page_thumbnail, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.number.text = holder.itemView.context.getString(R.string.page_number, position + 1)
        holder.job?.cancel()
        holder.clearThumbnail()
        holder.job = scope.launch(Dispatchers.IO) {
            val bitmap = runCatching { thumbnailStore.getOrCreate(document, renderer, position) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (holder.bindingAdapterPosition == position && bitmap != null) holder.setThumbnail(bitmap)
                else bitmap?.recycleIfNeeded()
            }
        }
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.clearThumbnail()
        super.onViewRecycled(holder)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.page_thumbnail)
        val number: TextView = view.findViewById(R.id.page_number)
        var job: Job? = null

        fun clearThumbnail() {
            thumbnailBitmap?.recycleIfNeeded()
            thumbnailBitmap = null
            image.setImageDrawable(ColorDrawable(image.context.getColor(R.color.roman_thumbnail_placeholder)))
        }

        fun setThumbnail(value: Bitmap) {
            thumbnailBitmap?.recycleIfNeeded()
            thumbnailBitmap = value
            image.setImageBitmap(value)
        }

        private var thumbnailBitmap: Bitmap? = null
    }
}

private fun Bitmap.recycleIfNeeded() {
    if (!isRecycled) recycle()
}

class SearchResultAdapter(
    private val titleFor: (SearchHit) -> String,
    private val onClick: (SearchHit) -> Unit
) : ListAdapter<SearchHit, SearchResultAdapter.Holder>(DIFF) {
    private var query = ""

    fun setQuery(query: String) {
        this.query = query.trim()
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val result = getItem(position)
        holder.title.text = highlight(titleFor(result), holder.itemView.context)
        val page = result.pageIndex.toIntOrNull()
        holder.meta.text = if (page != null && page >= 0) {
            holder.itemView.context.getString(
                R.string.search_result_page,
                result.sourceType.replace('_', ' '),
                page + 1
            )
        } else {
            holder.itemView.context.getString(R.string.search_result_note, result.sourceType.replace('_', ' '))
        }
        holder.snippet.text = highlight(result.content.trim().replace(Regex("\\s+"), " ").take(260), holder.itemView.context)
        holder.itemView.setOnClickListener { onClick(result) }
    }

    private fun highlight(text: String, context: android.content.Context): CharSequence {
        if (query.isBlank() || text.isBlank()) return text
        val highlighted = SpannableString(text)
        query.split(Regex("\\s+")).filter(String::isNotBlank).distinct().forEach { term ->
            Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
                highlighted.setSpan(
                    BackgroundColorSpan(context.getColor(R.color.roman_search_highlight)),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                highlighted.setSpan(
                    ForegroundColorSpan(context.getColor(R.color.roman_on_surface)),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return highlighted
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.result_title)
        val meta: TextView = view.findViewById(R.id.result_meta)
        val snippet: TextView = view.findViewById(R.id.result_snippet)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SearchHit>() {
            override fun areItemsTheSame(oldItem: SearchHit, newItem: SearchHit): Boolean = oldItem.rowId == newItem.rowId
            override fun areContentsTheSame(oldItem: SearchHit, newItem: SearchHit): Boolean = oldItem == newItem
        }
    }
}
