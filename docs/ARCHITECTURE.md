# ROMAN PDF architecture

## Document storage

Import uses the Storage Access Framework and requests a persistable read grant when the provider supports it. `DocumentEntity` stores the URI, title, MIME/kind, page count, last page, and index state. The original PDF/image remains outside SQLite and is not duplicated. This keeps large files out of the database and avoids unnecessary storage growth.

App-private files are limited to:

- `files/thumbnails/` — small WebP first-page or overview thumbnails.
- `files/exports/` — one-page images and exported PDFs ready for sharing.

The database contains document metadata, compact vector strokes, typed notes, and the FTS4 search table. Stroke points are normalized to the page and encoded as a compact `x,y,pressure,time;…` string, avoiding one Room row per touch point.

## PDF rendering pipeline

`DocumentRenderEngine` opens one `ParcelFileDescriptor` and one `PdfRenderer` per reader. All renderer access is serialized because `PdfRenderer` is not a multi-reader object. A page view requests only its own display-sized bitmap from a coroutine on `Dispatchers.IO`. The reader uses a horizontal `RecyclerView` with `PagerSnapHelper`, so Android creates and binds only the visible page plus a small RecyclerView prefetch window.

Pages are closed immediately after rendering. Recycled page views cancel obsolete render jobs and release their bitmap. Image documents use sampled `BitmapFactory` decoding based on the view target size. A hard working bitmap cap prevents an unusually large page from creating an uncontrolled allocation.

## Annotation coordinate system

Every stored point is normalized to the fitted page rectangle: `(0,0)` is the page's top-left and `(1,1)` is its bottom-right. The canvas maps those coordinates into the current render rectangle, so rotation, display size, export resolution, and zoom do not change stroke placement. Pressure and event time are retained for future pressure-aware tools and recognition.

Pen/highlighter input stays in memory during a gesture and is inserted once on stroke completion. The compact toolbar cycles three pen widths with a pen long-press and three ink colors with a highlighter long-press. Erasing is stroke based: the eraser tests a small normalized radius against stored points and emits the IDs hit. Undo/redo records either an added stroke or the complete set removed by one eraser gesture, then updates Room asynchronously.

## Search and indexing

Room owns an SQLite FTS4 virtual table with document ID, page, source type, and content. User input is converted to conservative prefix terms (`word* AND another*`) before the `MATCH` query. Search results keep page/source routing fields and launch the reader at the matching page.

PDF display never depends on text extraction. After import, PDF indexing opens PDFBox once on a background coroutine, extracts one page at a time, inserts the page text, and yields progress. Index state is persisted so a completed document is not repeatedly scanned. Notes are reindexed only for their document/source. Recognized handwriting is stored on strokes and grouped per page when its FTS source is rebuilt.

## Handwriting recognition

ML Kit Digital Ink is an optional adapter. It is not initialized at application startup and is not invoked on touch movement. The explicit reader action checks for the English model; the user can download it once, and then recognition runs off the main thread. If the model is unavailable or recognition fails, reading, writing, local text search, and export continue normally.

## Export pipeline

Page image export renders one page, draws the normalized strokes onto that bitmap, writes PNG/JPEG, and releases the bitmap. Annotated PDF export loops page by page: render, start an `android.graphics.pdf.PdfDocument` page, draw the background and strokes, finish the page, recycle the bitmap, then continue. No full-document bitmap list is built. `FileProvider` grants read access to the app-private export and the Android Sharesheet handles external sharing.

## Caching and lifecycle

Thumbnails are generated only when a library/overview item is bound. A thumbnail job is cancelled when its view is recycled. Reader render jobs are cancelled on rebind. Activity recreation keeps the selected page through instance state and the database keeps the last settled page for process death/reopen. Renderer, page, descriptor, file stream, dialog, and bitmap lifetimes are explicitly closed or released.

The app has no periodic worker, foreground service, polling loop, analytics SDK, or always-on model. Expensive systems (PDFBox and ML Kit) are lazy, and document work uses small background concurrency appropriate for the target tablet.
