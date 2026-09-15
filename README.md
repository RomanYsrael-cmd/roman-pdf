# ROMAN PDF

ROMAN PDF is an offline-first Android PDF reader with lightweight annotation, notes, local search, and export. It is designed for a low-end tablet rather than for feature breadth.

## Features

- Library of imported PDFs and image documents with remembered last page and lazy first-page thumbnails.
- PDF pages rendered on demand with Android `PdfRenderer`; horizontal paging, left/right page taps, page jump, page overview, pinch zoom, and double-tap zoom.
- Explicit View/Edit reader modes: one-finger navigation in View, one-finger pen/highlighter/eraser input in Edit, and two-finger pan/pinch in either mode.
- Vector pen and highlighter strokes with normalized coordinates, stroke eraser, undo/redo, pen-width cycling, independent pen/highlighter color palettes, and persistence across restarts.
- Typed notes attached to the current page.
- Local SQLite FTS search across extracted PDF text, typed notes, and recognized handwriting.
- Incremental PDF text indexing through PDFBox-Android without using PDFBox for display.
- Optional English ML Kit Digital Ink recognition, started explicitly after a model is available.
- Page PNG/JPEG export, sequential annotated PDF export, and Android Sharesheet sharing through `FileProvider`, with collision-safe output names.
- JPEG, PNG, and WebP image import as single-page documents or one ordered multi-image document.
- Branded library toolbar with native search, overflow imports, persistent List/Grid choice, and eight persistent sort choices (name, opened/imported time, and page count).
- Search results with readable query states, clear/back behavior, snippets, and highlighted matches.
- Adaptive List/Grid library layouts, remembered per device, and an immersive reader mode with recoverable system bars.
- Reliable rapid paging: serialized `PdfRenderer` access, cancellation/generation guards, lease-owned page bitmaps, adjacent prefetch, and a visible retry state instead of a blank page.
- PDF `ACTION_VIEW` handling so ROMAN PDF appears as an Android PDF “Open with” target.

## Design goals

Reliability, quick page display, low memory use, responsive ink, and no required account or cloud service. ROMAN PDF is designed to remain responsive on resource-constrained Android tablets by lazily rendering PDFs, minimizing background work, indexing searchable content locally, and avoiding heavyweight cross-platform frameworks.

## Target hardware

Primary validation device: Lenovo TB-X306X (marketed as Lenovo Tab M10 HD), arm64-v8a, Android 11/API 30, physical 800×1280 display, approximately 4 GB RAM.

## Architecture

- Kotlin and classic Android Views/XML; no Compose.
- `MainActivity` owns the library and Storage Access Framework import flows.
- `ReaderActivity` owns a horizontal `RecyclerView`/`PagerSnapHelper` page reader and annotation interaction.
- `DocumentEntity` uses stable Room IDs plus a source key for import identity. Multi-image documents store an ordered, Base64-url encoded list of `IMAGE_PAGE` source URIs; legacy single-image rows fall back to their original URI.
- External PDF `ACTION_VIEW` inputs are persisted when possible and copied to app-private storage when a durable content/file URI is not available. Opening starts the reader before background indexing.
- `DocumentRenderEngine` serializes a small `PdfRenderer` working set and sampled image decoding.
- Room stores document metadata, notes, compact serialized vector strokes, and an FTS4 search table.
- PDFBox-Android is initialized only when a PDF needs text indexing.
- ML Kit Digital Ink is initialized only from the explicit recognition action.
- Large source binaries remain at their SAF URI when access can be persisted. Non-persistable external sources are materialized once in `files/source_cache/`; thumbnails and exports are small app-private files.

## Build

From the repository root on Windows:

```text
gradlew.bat testDebugUnitTest
gradlew.bat connectedDebugAndroidTest
gradlew.bat lintDebug
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`. Release signing is intentionally not configured; a release build uses R8/resource shrinking when signing is supplied by the environment.

For a repeatable real-device reader stress run, open a long document in the debug app and run Windows PowerShell (the legacy Windows PowerShell host is used for screenshot sampling):

```text
powershell.exe -ExecutionPolicy Bypass -File tools\device-navigation-stress.ps1 -Serial <adb-serial> -Operations 1000 -CaptureDirectory work\device-stress
```

The runner records screenshot blank signals, process-death signals, periodic PSS memory, the foreground activity, and a filtered logcat snapshot. It requires `ReaderActivity` to already be foreground because that activity is intentionally private to the app.

## Install and run

```text
adb install -r app\\build\\outputs\\apk\\debug\\app-debug.apk
adb shell am start -n com.romanysrael.romanpdf.debug/com.romanysrael.romanpdf.ui.MainActivity
```

The release application ID is `com.romanysrael.romanpdf`; the debug build uses the `.debug` suffix so it can coexist with a signed release.

## ADB checks

```text
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell wm size
adb shell dumpsys meminfo com.romanysrael.romanpdf.debug
adb shell dumpsys gfxinfo com.romanysrael.romanpdf.debug
```

Unit tests run with:

```text
gradlew.bat testDebugUnitTest
```

The Room instrumentation test can be run on a connected device with:

```text
gradlew.bat connectedDebugAndroidTest
```

## Current limitations

- Exported PDFs are stable flattened renderings; original selectable PDF text is not preserved in the exported file.
- Scanned-PDF OCR is not included in this version.
- Handwriting recognition requires the optional English ML Kit model and is explicit/on-demand rather than continuous.
- The V1 eraser removes complete vector strokes rather than editing a stroke segment.
- A persisted SAF URI must remain readable to Android. External non-persistable URIs are copied into app-private storage at import/open time; very large files may therefore take longer to materialize.
- The V1 image-document model preserves picker order but does not yet expose page reordering after import.

## Privacy

PDFs, images, notes, and strokes stay on the device. There is no account, ads, telemetry, analytics, or upload path. Internet access is declared only so the user-requested ML Kit language model can be downloaded when needed; normal reading, annotation, search, and export work offline.

## Project status

The debug build, device installation, image-document flow, immersive reader flow, PDF `ACTION_VIEW` flow, core annotation smoke flows, library search/sort/view persistence, and the reliability stress runner have been validated on the target tablet. The final reader pass completed 1,000 mixed navigation operations and 500 screenshot samples with no blank signals, process-death signals, or app render/fatal/ANR log lines. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/PERFORMANCE.md](docs/PERFORMANCE.md), and [docs/LINT.md](docs/LINT.md) for implementation and measurement notes.
