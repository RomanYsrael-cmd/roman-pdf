# ROMAN PDF architecture

## Document storage

Import uses the Storage Access Framework and requests a persistable read grant when the provider supports it. `DocumentEntity` stores the URI, title, MIME/kind, page count, last page, index state, and a stable source key. The Room primary key remains the document identity; the source key and URI are only import de-duplication aids, so same-named files cannot mix annotations, notes, search rows, or thumbnails.

An image document stores its ordered pages as `PageSource(type = IMAGE_PAGE, uri)` records encoded by `PageSourceCodec`. The encoded form is deliberately small and URL-safe, so commas, semicolons, spaces, and provider-specific query parameters do not change page boundaries. Existing V1 single-image rows have an empty page-source column and transparently fall back to their original URI. PDF documents continue to use one PDF source rendered by page index.

If a content URI cannot be persisted, or an external `file://` PDF arrives through `ACTION_VIEW`, the repository streams it into `files/source_cache/` before it is opened. This gives the reader, indexing, and future process restarts a durable local source without assuming a filesystem path for normal SAF content URIs.

App-private files are limited to:

- `files/thumbnails/` — small WebP first-page or overview thumbnails.
- `files/exports/` — one-page images and exported PDFs ready for sharing.
- `files/source_cache/` — materialized external sources whose URI grant cannot be made durable.

The database contains document metadata, compact vector strokes, typed notes, persistent page bookmarks, and the FTS4 search table. A bookmark is the unique `(documentId, pageIndex)` pair, so it remains valid for both PDF pages and image-document pages and is removed with its document. Stroke points are normalized to the page and encoded as a compact `x,y,pressure,time;…` string, avoiding one Room row per touch point.

## Branding

The launcher and round launcher resources use the supplied ROMAN PDF mark as the source artwork. The outer white margin is made transparent for adaptive-icon composition; the mark itself is not redrawn or renamed. The same mark is used at a restrained size in the empty-library state, and Android 12+ uses the branded adaptive icon for the system splash handoff.

## PDF rendering pipeline

`DocumentRenderEngine` opens one `ParcelFileDescriptor` and one `PdfRenderer` per PDF reader. All renderer access is serialized because `PdfRenderer` is not a multi-reader object. A page view requests only its own display-sized bitmap from a coroutine on a reader-local `Dispatchers.IO.limitedParallelism(1)` dispatcher. For image documents, the same page view asks `BitmapFactory` for bounds, chooses an `inSampleSize`, and decodes only the requested page. The reader uses a horizontal `RecyclerView` with `PagerSnapHelper`, so Android creates and binds only the visible page plus a small RecyclerView prefetch window.

Pages are closed immediately after rendering. Each reader owns a two-entry, reference-counted `PageBitmapCache` keyed by page and target size. A `Lease` is held by every displayed bitmap and released only when that view swaps or detaches. Eviction removes an entry from lookup immediately, but recycles the bitmap only after its lease count reaches zero; this prevents a cache eviction from invalidating a bitmap still being drawn. Clearing a reader cache follows the same rule. The visible page is retained while a replacement is rendered, and only the preferred next or previous neighbor is prefetched according to the last navigation direction. Recycled page views cancel obsolete work and release their cache reference. Image documents use sampled `BitmapFactory` decoding based on the view target size. Display rendering uses a 16 MiB working bitmap budget; print/export uses a separate 32 MiB budget.

Every request receives a binding generation, request ID, and target-size key. The main-thread install checks that token against the latest request, page, renderer, and bitmap usability before changing the displayed frame; stale or cancelled results release their lease and are ignored. Prefetch uses the same serial dispatcher and token gate, so an old page cannot populate the current request's cache path. The page state is explicit (`NOT_REQUESTED`, `RENDERING`, `READY`, or `FAILED`). A failed render draws a light retrying placeholder and schedules one bounded recovery attempt, keeping a recoverable failure visible rather than turning it into a dark blank canvas.

The blank-page defect was reproduced on the Lenovo with a 1,000-operation mixed navigation workload: the old path produced 328 blank signals across 500 samples. The failure was the combination of recycling/nulling a frame while a replacement was pending, stale asynchronous results, and overlapping renderer/prefetch work. The lease, token, serial-dispatcher, retained-frame, and recovery rules above remove those races.

## Annotation coordinate system

Every stored point is normalized to the fitted page rectangle: `(0,0)` is the page's top-left and `(1,1)` is its bottom-right. The canvas maps those coordinates into the current render rectangle, so rotation, display size, export resolution, and zoom do not change stroke placement. Pressure and event time are retained for future pressure-aware tools and recognition.

Pen/highlighter input stays in memory during a gesture and is inserted once on stroke completion. The compact toolbar cycles three pen widths with a pen long-press and opens a nine-color palette for pen/highlighter input. Pen and highlighter colors are remembered independently, and each stroke stores its ARGB color so existing annotations do not change when a new color is selected. Erasing is stroke based: the eraser tests a small normalized radius against stored points and emits the IDs hit. Undo/redo records either an added stroke or the complete set removed by one eraser gesture, then updates Room asynchronously.

Reader input is explicitly arbitrated by `ReaderMode`. View mode gives one finger to zoomed panning and lets the parent pager receive a horizontal swipe at fit scale. Edit mode gives one finger to pen, highlighter, or eraser input even when zoomed. A second pointer cancels an active annotation cleanly and owns centroid pan plus scale; after multi-touch ends, the next single pointer starts a fresh interaction. Edit-mode double tap resets the page transform; View-mode double tap toggles a useful zoom. Tap-origin page changes use immediate RecyclerView positioning, while swipe-origin changes retain the pager snap animation.

The reader opens in a read-first immersive WindowInsets-based fullscreen state unless the user has chosen otherwise. `WindowCompat.setDecorFitsSystemWindows(window, false)` keeps the reader edge-to-edge, while `WindowInsetsControllerCompat` hides system bars with transient-by-swipe recovery. App chrome is hidden while the document remains full-bleed; fullscreen keeps the viewport padding at zero even while bars are transiently revealed, and exiting fullscreen reapplies the current bar/cutout insets. A center tap restores temporary app chrome, which auto-hides after a short idle interval in read mode. The left and right 32% tap zones page backward/forward; the center is reserved for chrome recovery. Edit mode keeps its tool tray visible, and an optional keep-screen-on preference is preserved across recreation.

Bookmarks are available from the reader toolbar. The current page toggles in one action, and the overflow menu presents the sorted persistent page list; selecting an entry performs an immediate page jump. The list is observed from Room, so the filled state and the page list survive activity recreation and process restart.

## Library layouts and external open

The library has one data source and one adapter for both List and adaptive Grid modes. Grid span count is computed from the measured width and density, and the choice is stored in `library_preferences`. The top ribbon uses the supplied mark beside `ROMAN PDF`; imports, sort, and view controls live in the overflow. Sort is a pure policy over title, last-opened time, import time, and page count, and its choice is stored alongside the layout. Thumbnails are still requested only when a bound tile needs one; switching layouts does not create an eager thumbnail batch. Long-press actions resolve the current adapter position at event time, so a resort cannot dispatch an old document.

`MainActivity` declares `application/pdf` for `ACTION_VIEW` and handles both cold starts and `singleTop` new intents. It determines the display name from provider metadata or the URI, allocates a duplicate-safe title, creates library metadata, opens `ReaderActivity`, and launches PDF text indexing separately. The in-app PDF picker accepts one or many PDFs; single-file imports retain the normal indexing path, while a large batch can complete metadata/page validation without blocking the migration on one unusually expensive text parser. This keeps external opening responsive and lets the same reader/annotation/export path handle files opened from Downloads or another file manager.

## Search and indexing

Room owns an SQLite FTS4 virtual table with document ID, page, source type, and content. User input is converted to conservative prefix terms (`word* AND another*`) before the `MATCH` query. Search results keep page/source routing fields and launch the reader at the matching page. Library search uses the native toolbar `SearchView` for filtering and submit-to-search; the full search screen uses explicit surface/ink colors, a clear action, readable empty state, and query-term spans in titles/snippets so both light and dark themes retain contrast.

PDF display never depends on text extraction. After import, PDF indexing opens PDFBox once on a background coroutine, extracts one page at a time, inserts the page text, and yields progress. Index state is persisted so a completed document is not repeatedly scanned. Notes are reindexed only for their document/source. Recognized handwriting is stored on strokes and grouped per page when its FTS source is rebuilt.

## Handwriting recognition

ML Kit Digital Ink is an optional adapter. It is not initialized at application startup and is not invoked on touch movement. The explicit reader action checks for the English model; the user can download it once, and then recognition runs off the main thread. If the model is unavailable or recognition fails, reading, writing, local text search, and export continue normally.

## Export pipeline

Page image export renders one page, draws the normalized strokes onto that bitmap, writes PNG/JPEG, and releases the bitmap. Annotated PDF export loops page by page: render, start an `android.graphics.pdf.PdfDocument` page, draw the background and strokes, finish the page, recycle the bitmap, then continue. No full-document bitmap list is built. Image-document export uses the same loop and therefore preserves the selected page order. Output names are sanitized and receive a numeric suffix when a prior export exists. `FileProvider` grants read access to the app-private export and the Android Sharesheet handles external sharing.

## Caching and lifecycle

Thumbnails are generated only when a library/overview item is bound. A thumbnail job is cancelled when its view is recycled. Reader render jobs are cancelled on rebind. Activity recreation keeps the selected page through instance state and the database keeps the last settled page for process death/reopen. Renderer, page, descriptor, file stream, dialog, and bitmap lifetimes are explicitly closed or released.

The app has no periodic worker, foreground service, polling loop, analytics SDK, or always-on model. Expensive systems (PDFBox and ML Kit) are lazy, and document work uses small background concurrency appropriate for the target tablet.
