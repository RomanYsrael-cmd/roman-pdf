# ROMAN PDF

ROMAN PDF is an offline-first Android PDF reader with lightweight annotation, notes, local search, and export. It is designed for a low-end tablet rather than for feature breadth.

## Features

- Library of imported PDFs and images with remembered last page and lazy first-page thumbnails.
- PDF pages rendered on demand with Android `PdfRenderer`; horizontal paging, left/right page taps, page jump, page overview, pinch zoom, and double-tap zoom.
- Vector pen and highlighter strokes with normalized coordinates, stroke eraser, undo/redo, pen-width cycling, a small ink-color palette, and persistence across restarts.
- Typed notes attached to the current page.
- Local SQLite FTS search across extracted PDF text, typed notes, and recognized handwriting.
- Incremental PDF text indexing through PDFBox-Android without using PDFBox for display.
- Optional English ML Kit Digital Ink recognition, started explicitly after a model is available.
- Page PNG/JPEG export, sequential annotated PDF export, and Android Sharesheet sharing through `FileProvider`.
- JPEG, PNG, and WebP image import as single-page documents.

## Design goals

Reliability, quick page display, low memory use, responsive ink, and no required account or cloud service. ROMAN PDF is designed to remain responsive on resource-constrained Android tablets by lazily rendering PDFs, minimizing background work, indexing searchable content locally, and avoiding heavyweight cross-platform frameworks.

## Target hardware

Primary validation device: Lenovo TB-X306X (marketed as Lenovo Tab M10 HD), arm64-v8a, Android 11/API 30, physical 800×1280 display, approximately 4 GB RAM.

## Architecture

- Kotlin and classic Android Views/XML; no Compose.
- `MainActivity` owns the library and Storage Access Framework import flows.
- `ReaderActivity` owns a horizontal `RecyclerView`/`PagerSnapHelper` page reader and annotation interaction.
- `DocumentRenderEngine` serializes a small `PdfRenderer` working set and sampled image decoding.
- Room stores document metadata, notes, compact serialized vector strokes, and an FTS4 search table.
- PDFBox-Android is initialized only when a PDF needs text indexing.
- ML Kit Digital Ink is initialized only from the explicit recognition action.
- Large source binaries remain at their SAF URI; thumbnails and exports are small app-private files.

## Build

From the repository root on Windows:

```text
gradlew.bat assembleDebug
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`. Release signing is intentionally not configured; a release build uses R8/resource shrinking when signing is supplied by the environment.

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
- An imported SAF URI must remain readable to Android; if a provider revokes access, the library reports an open error rather than duplicating a potentially massive file.
- Image imports are single-page documents.

## Privacy

PDFs, images, notes, and strokes stay on the device. There is no account, ads, telemetry, analytics, or upload path. Internet access is declared only so the user-requested ML Kit language model can be downloaded when needed; normal reading, annotation, search, and export work offline.

## Project status

The debug build, device installation, and core smoke flows have been validated on the target tablet. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/PERFORMANCE.md](docs/PERFORMANCE.md) for implementation and measurement notes.
