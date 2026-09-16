# ROMAN PDF performance notes

## Device

Measured through ADB during this implementation:

- Model: Lenovo TB-X306X (Lenovo Tab M10 HD)
- Android: 11
- API: 30
- ABI: arm64-v8a
- Physical display: 800×1280
- `/proc/meminfo` at inspection: 3,852,040 kB total, 2,130,428 kB available
- Final debug APK: 46,217,799 bytes; signed release APK: 38,865,601 bytes; release AAB: 23,903,966 bytes. Release signing is optional; this verification used the ignored local QA key and it is never committed.

## Decisions made for the device

- `PdfRenderer` is the display path; PDFBox is limited to on-demand text indexing.
- The reader is a horizontally paged RecyclerView with no all-pages bitmap list.
- Page bitmaps are display-sized and capped at 16 MiB per display render; a two-entry reference-counted reader cache retains the visible page and one direction-aware neighbor. Export keeps a separate 32 MiB cap for quality.
- A single synchronized renderer plus a reader-local single-lane dispatcher serializes display and prefetch work and closes every `PdfRenderer.Page` immediately.
- Image pages use bounds-only inspection plus sampled decode; startup never loads every image page.
- Thumbnails are lazy and cached as lossy WebP; PDF thumbnails target 192×256 and use RGB_565, while startup does not parse every library PDF or eagerly decode every grid tile.
- Touch events are accumulated in memory and strokes are inserted after a gesture, not on every MotionEvent.
- Indexing and recognition run only when needed; no periodic background service is used.

Tap navigation retains the previous displayed bitmap until the replacement page is ready. Each render has a binding generation, request ID, and target-size key; stale/cancelled work cannot install a frame or populate an active prefetch path. The cache recycles only unreferenced evicted bitmaps, after the display lease is released. A failed render uses a visible retry placeholder and one delayed recovery request. This removes the old `recycle -> null -> render` gap that exposed the dark background during rapid tap navigation without retaining a full-document bitmap set.

Multi-image documents keep only their source URI list in Room. The reader decodes the requested page at display size, and RecyclerView/lazy thumbnail binding provides a small previous/current/next-style working set rather than retaining all page bitmaps. Export is intentionally sequential and can request a higher-quality render one page at a time.

The edit gesture path does not poll or run a recognizer: one-finger strokes are kept in memory until commit, while two-finger transforms update only the view's scale and offset. The inverse transform maps touch points back to normalized page coordinates, so zooming and panning add no per-stroke bitmap or coordinate copy.

## Observations from the real-device reliability pass

The target device used the restored 146-document library and the 276-page `THE WILDS  8th Edition (1).pdf` sample. The pre-fix baseline reproduced the defect in 328 of 500 screenshot samples (65.6%) during 1,000 mixed tap/swipe/zoom operations. The first fixed-renderer pass recorded 0 of 500 blank signals; the final cache-ownership pass also recorded 0 of 500 blank signals and 0 process-death signals. Both runs kept `ReaderActivity` in the foreground. The final pass wrote screenshot CSV, periodic memory samples, and filtered logcat evidence under the ignored `work/` directory; the repeatable driver is `tools/device-navigation-stress.ps1`.

The current milestone repeats the same workload on the 222-page `music-theory.pdf` sample after reducing the display budget, page cache, prefetch breadth, and thumbnail working size. The 1,000-operation trace completed with 500 screenshot samples, 0 blank signals, 0 process-death signals, and 0 filtered reliability log lines. Its 50 PSS samples ranged from 104,135 kB to 124,751 kB (average 115,376 kB; last sample 118,747 kB); the post-run `dumpsys meminfo` snapshot was 115,057 kB. Compared with the prior 137,629 kB post-run snapshot, this is a 22,572 kB reduction (16.4%); compared with the prior 135,716 kB stress average, the new average is 20,340 kB lower (15.0%). PSS includes graphics/GL allocations and is not a Java-heap-only number. The preferred sub-100 MB target remains an aspiration for this graphics-heavy sample, while the observed average and post-run values stay in the accepted 110–125 MB band; reliability takes precedence and the cache is intentionally bounded at two page entries.

Opening the PDF through a real tablet `content://` provider URI displayed a valid page image immediately. Fullscreen hid the status/navigation bars and app chrome, center tap restored temporary chrome, chrome auto-hide returned to a valid page, and wide left/right tap zones moved between page 1 and page 2. Search, sort/view persistence, image-document paging, annotation interactions, exports, and external PDF open were smoke-tested on the target tablet. The final stress runner result is recorded below and checks for `RomanPdfRender`, `FATAL EXCEPTION`, and `ANR in` lines.

Cold start, import, reader open, paging, annotation persistence, FTS search, page PNG export, annotated PDF export, Sharesheet launch, list/grid switching, fullscreen recovery, external `ACTION_VIEW`, and a six-page image document were exercised on the tablet. Further profiling with large, complex PDFs is still recommended before treating those measurements as universal targets.

## Final build checklist

The final release of this milestone is rebuilt with `assembleDebug` and `assembleRelease`; `bundleRelease` and `apksigner verify` are also used when the local validation key is available. The final debug APK is 46,217,799 bytes, the signed release APK is 38,865,601 bytes, and the release AAB is 23,903,966 bytes. The APK verifies with v2 signing using the ignored local QA key; production distribution must use a protected organization key. The debug install remains separate from the release application ID through the `.debug` suffix.

## Known performance limits

PDFBox can use more memory while indexing unusually complex PDFs because it must parse the document structure. Indexing is therefore background and page-by-page, but the document remains a potential peak-memory workload. Export is intentionally sequential and may take visible time on a large PDF. Materializing a non-persistable external source necessarily performs a one-time sequential copy and uses additional app-private disk space. A scanned-PDF OCR pipeline is not included and would need separate battery/memory budgeting.
