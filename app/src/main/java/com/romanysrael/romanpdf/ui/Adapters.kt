package com.romanysrael.romanpdf.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class LibraryAdapter(
    private val scope: CoroutineScope,
    private val thumbnailStore: ThumbnailStore,
    private val onClick: (DocumentEntity) -> Unit,
    private val onLongClick: (DocumentEntity) -> Unit
) : ListAdapter<DocumentEntity, LibraryAdapter.Holder>(DIFF) {
    private val allItems = ArrayList<DocumentEntity>()

    fun setAll(items: List<DocumentEntity>) {
        allItems.clear()
        allItems.addAll(items)
        submitList(items)
    }

    fun filter(query: String) {
        val normalized = query.trim().lowercase()
        submitList(if (normalized.isBlank()) allItems.toList() else allItems.filter { it.title.lowercase().contains(normalized) })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val document = getItem(position)
        holder.title.text = document.title
        holder.meta.text = holder.itemView.context.getString(
            R.string.document_meta,
            document.kind.lowercase().replaceFirstChar(Char::uppercase),
            document.pageCount,
            document.lastPage + 1,
            relativeTime(document.lastOpenedAt)
        )
        holder.thumbnail.setImageDrawable(ColorDrawable(Color.rgb(232, 236, 242)))
        holder.job?.cancel()
        holder.job = scope.launch(Dispatchers.IO) {
            val renderer = runCatching { DocumentRenderEngine(holder.itemView.context.applicationContext, document) }.getOrNull()
            val bitmap = renderer?.let { engine ->
                runCatching { thumbnailStore.getOrCreate(document, engine) }.getOrNull().also { engine.close() }
            }
            withContext(Dispatchers.Main) {
                if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION && getItem(holder.bindingAdapterPosition).id == document.id && bitmap != null) {
                    holder.thumbnail.setImageBitmap(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }
        holder.itemView.setOnClickListener { onClick(document) }
        holder.itemView.setOnLongClickListener { onLongClick(document); true }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        super.onViewRecycled(holder)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.document_thumbnail)
        val title: TextView = view.findViewById(R.id.document_title)
        val meta: TextView = view.findViewById(R.id.document_meta)
        var job: Job? = null
    }

    companion object {
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
    private val toolByPage = HashMap<Int, String>()
    private var currentTool = AnnotationTools.NONE
    private var currentInkWidth = 0.0045f
    private var currentInkColor = Color.rgb(34, 74, 150)
    private var attachedRecycler: RecyclerView? = null

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        PdfPageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.pageView.bind(position, renderer, scope, strokesForPage(position))
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
        holder.image.setImageDrawable(ColorDrawable(Color.rgb(232, 236, 242)))
        holder.job?.cancel()
        holder.job = scope.launch(Dispatchers.IO) {
            val bitmap = runCatching { thumbnailStore.getOrCreate(document, renderer, position) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (holder.bindingAdapterPosition == position && bitmap != null) holder.image.setImageBitmap(bitmap)
                else bitmap?.recycle()
            }
        }
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        super.onViewRecycled(holder)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.page_thumbnail)
        val number: TextView = view.findViewById(R.id.page_number)
        var job: Job? = null
    }
}

class SearchResultAdapter(
    private val titleFor: (SearchHit) -> String,
    private val onClick: (SearchHit) -> Unit
) : ListAdapter<SearchHit, SearchResultAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val result = getItem(position)
        holder.title.text = titleFor(result)
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
        holder.snippet.text = result.content.trim().replace(Regex("\\s+"), " ").take(260)
        holder.itemView.setOnClickListener { onClick(result) }
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
