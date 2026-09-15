# ROMAN PDF performance notes

## Device

Measured through ADB during this implementation:

- Model: Lenovo TB-X306X (Lenovo Tab M10 HD)
- Android: 11
- API: 30
- ABI: arm64-v8a
- Physical display: 800×1280
- `/proc/meminfo` at inspection: 3,852,040 kB total, 2,130,428 kB available
- Final debug APK: 46,323,262 bytes; R8/resource-shrunk release APK: 38,849,895 bytes (unsigned).

## Decisions made for the device

- `PdfRenderer` is the display path; PDFBox is limited to on-demand text indexing.
- The reader is a horizontally paged RecyclerView with no all-pages bitmap list.
- Page bitmaps are display-sized and capped at 24 MiB per render; a three-entry reference-counted reader cache retains the visible page and bounded neighbors.
- A single synchronized renderer plus a reader-local single-lane dispatcher serializes display and prefetch work and closes every `PdfRenderer.Page` immediately.
- Image pages use bounds-only inspection plus sampled decode; startup never loads every image page.
- Thumbnails are lazy and cached as lossy WebP; startup does not parse every library PDF or eagerly decode every grid tile.
- Touch events are accumulated in memory and strokes are inserted after a gesture, not on every MotionEvent.
- Indexing and recognition run only when needed; no periodic background service is used.

Tap navigation retains the previous displayed bitmap until the replacement page is ready. Each render has a binding generation, request ID, and target-size key; stale/cancelled work cannot install a frame or populate an active prefetch path. The cache recycles only unreferenced evicted bitmaps, after the display lease is released. A failed render uses a visible retry placeholder and one delayed recovery request. This removes the old `recycle -> null -> render` gap that exposed the dark background during rapid tap navigation without retaining a full-document bitmap set.

Multi-image documents keep only their source URI list in Room. The reader decodes the requested page at display size, and RecyclerView/lazy thumbnail binding provides a small previous/current/next-style working set rather than retaining all page bitmaps. Export is intentionally sequential and can request a higher-quality render one page at a time.

The edit gesture path does not poll or run a recognizer: one-finger strokes are kept in memory until commit, while two-finger transforms update only the view's scale and offset. The inverse transform maps touch points back to normalized page coordinates, so zooming and panning add no per-stroke bitmap or coordinate copy.

## Observations from the real-device reliability pass

The target device used the restored 146-document library and the 276-page `THE WILDS  8th Edition (1).pdf` sample. The pre-fix baseline reproduced the defect in 328 of 500 screenshot samples (65.6%) during 1,000 mixed tap/swipe/zoom operations. The first fixed-renderer pass recorded 0 of 500 blank signals; the final cache-ownership pass also recorded 0 of 500 blank signals and 0 process-death signals. Both runs kept `ReaderActivity` in the foreground. The final pass wrote screenshot CSV, periodic memory samples, and filtered logcat evidence under the ignored `work/` directory; the repeatable driver is `tools/device-navigation-stress.ps1`.

For the final corrected build, the stress trace's 50 PSS samples ranged from 132,740 kB to 139,600 kB (average 135,716 kB; last sample 138,249 kB). The post-run `dumpsys meminfo` snapshot was 137,629 kB total PSS, including approximately 44,897 kB graphics and 41,227 kB GL tracking. PSS includes the tablet's graphics/GL allocations and is not a Java-heap-only number. The cache remains bounded at three page entries and the final trace showed no monotonically increasing bitmap-retention pattern. Reliability takes precedence over forcing the preferred sub-100 MB reader target on this large, graphics-heavy sample; future profiling should revisit render scale and GPU texture pressure with representative PDFs.

Opening the long PDF after reinstall displayed a valid page image immediately. The operation-1,000 stress screenshot showed the light preparing state during an in-flight navigation request (never the old dark blank), and a five-second idle capture then showed a valid rendered page. Search, sort/view persistence, fullscreen recovery, image-document paging, annotation interactions, exports, and external PDF open were also smoke-tested on the target tablet. The stress runner found no `RomanPdfRender`, `FATAL EXCEPTION`, or `ANR in` line.

Cold start, import, reader open, paging, annotation persistence, FTS search, page PNG export, annotated PDF export, Sharesheet launch, list/grid switching, fullscreen recovery, external `ACTION_VIEW`, and a six-page image document were exercised on the tablet. Further profiling with large, complex PDFs is still recommended before treating those measurements as universal targets.

## Final build checklist

The final release of this milestone was rebuilt with both `assembleDebug` and `assembleRelease`; the resulting byte sizes are recorded in the device section above. The debug install remains separate from the release application ID through the `.debug` suffix.

## Known performance limits

PDFBox can use more memory while indexing unusually complex PDFs because it must parse the document structure. Indexing is therefore background and page-by-page, but the document remains a potential peak-memory workload. Export is intentionally sequential and may take visible time on a large PDF. Materializing a non-persistable external source necessarily performs a one-time sequential copy and uses additional app-private disk space. A scanned-PDF OCR pipeline is not included and would need separate battery/memory budgeting.
