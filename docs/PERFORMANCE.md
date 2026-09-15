# ROMAN PDF performance notes

## Device

Measured through ADB during this implementation:

- Model: Lenovo TB-X306X (Lenovo Tab M10 HD)
- Android: 11
- API: 30
- ABI: arm64-v8a
- Physical display: 800×1280
- `/proc/meminfo` at inspection: 3,852,040 kB total, 2,130,428 kB available
- Final debug APK: 46,315,738 bytes; R8/resource-shrunk release APK: 38,823,463 bytes (unsigned)

## Decisions made for the device

- `PdfRenderer` is the display path; PDFBox is limited to on-demand text indexing.
- The reader is a horizontally paged RecyclerView with no all-pages bitmap list.
- Page bitmaps are display-sized and capped at 24 MiB per render; a three-entry reference-counted reader cache retains the visible page and bounded neighbors.
- A single synchronized renderer serializes page work and closes every `PdfRenderer.Page` immediately.
- Image pages use bounds-only inspection plus sampled decode; startup never loads every image page.
- Thumbnails are lazy and cached as lossy WebP; startup does not parse every library PDF or eagerly decode every grid tile.
- Touch events are accumulated in memory and strokes are inserted after a gesture, not on every MotionEvent.
- Indexing and recognition run only when needed; no periodic background service is used.

Tap navigation retains the previous displayed bitmap until the replacement page is ready. The render job uses a page/generation check before swapping it, and prefetches only the adjacent in-range pages. This removes the old `recycle -> null -> render` gap that exposed the dark background during rapid tap navigation without retaining a full-document bitmap set.

Multi-image documents keep only their source URI list in Room. The reader decodes the requested page at display size, and RecyclerView/lazy thumbnail binding provides a small previous/current/next-style working set rather than retaining all page bitmaps. Export is intentionally sequential and can request a higher-quality render one page at a time.

The edit gesture path does not poll or run a recognizer: one-finger strokes are kept in memory until commit, while two-finger transforms update only the view's scale and offset. The inverse transform maps touch points back to normalized page coordinates, so zooming and panning add no per-stroke bitmap or coordinate copy.

## Observations from the real-device smoke pass

The final device pass used the restored 146-document library. Opening a multi-page PDF displayed only the requested page initially; neighboring pages were rendered through the bounded cache, horizontal paging and right-side tapping advanced correctly, and the overview remained lazy. A six-page image document was also opened and paged after importing six selected image files as one document.

The observed process memory from `dumpsys meminfo` was approximately:

- Cold library after restoring 146 PDFs: 74,742 kB total PSS.
- Settled one-page reader opened through the library: 105,700 kB total PSS.
- The larger eight-page reader sample settled between 113,661 and 121,962 kB while the visible/neighbor bitmap working set filled; no runaway growth was observed.

The final cold start measured 2,935 ms (`am start -W`, `LaunchState: COLD`). In a fresh paging sample after reset, `dumpsys gfxinfo` reported 45 rendered frames and 2 janky frames (4.44%); the 50th percentile was 8 ms and the 90th percentile was 9 ms. The transition capture stayed on page content at 11.66% dark-background pixels in every sampled frame, compared with the old blank-frame behavior. The only fatal exceptions retained in the tablet log were unrelated system picker/accessibility-service errors; no ROMAN PDF crash or ANR was observed.

Cold start, import, reader open, paging, annotation persistence, FTS search, page PNG export, annotated PDF export, Sharesheet launch, list/grid switching, fullscreen recovery, external `ACTION_VIEW`, and a six-page image document were exercised on the tablet. Further profiling with large, complex PDFs is still recommended before treating those measurements as universal targets.

## Known performance limits

PDFBox can use more memory while indexing unusually complex PDFs because it must parse the document structure. Indexing is therefore background and page-by-page, but the document remains a potential peak-memory workload. Export is intentionally sequential and may take visible time on a large PDF. Materializing a non-persistable external source necessarily performs a one-time sequential copy and uses additional app-private disk space. A scanned-PDF OCR pipeline is not included and would need separate battery/memory budgeting.
