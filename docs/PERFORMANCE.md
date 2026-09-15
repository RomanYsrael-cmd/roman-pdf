# ROMAN PDF performance notes

## Device

Measured through ADB during this implementation:

- Model: Lenovo TB-X306X (Lenovo Tab M10 HD)
- Android: 11
- API: 30
- ABI: arm64-v8a
- Physical display: 800×1280
- `/proc/meminfo` at inspection: 3,852,040 kB total, 2,130,428 kB available
- Final debug APK: 44,226,461 bytes; R8/resource-shrunk release APK: 37,411,183 bytes (unsigned)

## Decisions made for the device

- `PdfRenderer` is the display path; PDFBox is limited to on-demand text indexing.
- The reader is a horizontally paged RecyclerView with no all-pages bitmap list.
- Page bitmaps are display-sized and capped at 24 MiB per render.
- A single synchronized renderer serializes page work and closes every `PdfRenderer.Page` immediately.
- Image pages use bounds-only inspection plus sampled decode; startup never loads every image page.
- Thumbnails are lazy and cached as lossy WebP; startup does not parse every library PDF or eagerly decode every grid tile.
- Touch events are accumulated in memory and strokes are inserted after a gesture, not on every MotionEvent.
- Indexing and recognition run only when needed; no periodic background service is used.

Multi-image documents keep only their source URI list in Room. The reader decodes the requested page at display size, and RecyclerView/lazy thumbnail binding provides a small previous/current/next-style working set rather than retaining all page bitmaps. Export is intentionally sequential and can request a higher-quality render one page at a time.

The edit gesture path does not poll or run a recognizer: one-finger strokes are kept in memory until commit, while two-finger transforms update only the view's scale and offset. The inverse transform maps touch points back to normalized page coordinates, so zooming and panning add no per-stroke bitmap or coordinate copy.

## Observations from the real-device smoke pass

The test PDF was a 3-page text PDF. Import generated a small first-page thumbnail and completed text indexing. Opening it displayed page 1 without rendering the other pages up front. Horizontal swipes moved to pages 2 and 3, and right-side tapping advanced one page. The overview displayed the three lazily requested thumbnails.

The observed process memory from `dumpsys meminfo` was approximately:

- Empty library after a final cold start: 61,862 kB total PSS.
- Open reader after page rendering and several page changes: 76,327 kB total PSS.
- Earlier annotation pass: 96,076 kB total PSS, with no runaway growth observed.

The final cold start measured 2,308 ms (`am start -W`, `LaunchState: COLD`). During a two-page swipe sample, `dumpsys gfxinfo` reported 37 rendered frames and 10 janky frames (27.03%); the 50th percentile was 8 ms and the 90th percentile was 105 ms. The app reported 0.0% in the sampled idle `top` line after settling. The jank was concentrated in page render/transition work rather than an idle loop. The app showed no crash or ANR in the smoke logcat samples.

Cold start, import, reader open, paging, annotation persistence, FTS search, page PNG export, annotated PDF export, Sharesheet launch, list/grid switching, fullscreen recovery, external `ACTION_VIEW`, and a six-page image document were exercised on the tablet. Further profiling with large, complex PDFs is still recommended before treating those measurements as universal targets.

## Known performance limits

PDFBox can use more memory while indexing unusually complex PDFs because it must parse the document structure. Indexing is therefore background and page-by-page, but the document remains a potential peak-memory workload. Export is intentionally sequential and may take visible time on a large PDF. Materializing a non-persistable external source necessarily performs a one-time sequential copy and uses additional app-private disk space. A scanned-PDF OCR pipeline is not included and would need separate battery/memory budgeting.
