# Lint review

`lintDebug` was run on 2026-09-16 after the reliability and library UI changes. It completed with 0 errors and 21 warnings.

## Resolved in this milestone

- Updated the app target SDK from 35 to 36 (`OldTargetApi`).
- Replaced platform drawable lookup in the reader menu with `AppCompatResources` (`UseCompatLoadingForDrawables`).
- Converted page-count and selected-image copy to Android plurals, and explicitly marked the page-title numeric format as intentional (`PluralsCandidate`).
- Replaced the library's view-mode `notifyDataSetChanged()` with a range rebind (`NotifyDataSetChanged`).
- Added adaptive-icon monochrome metadata (`MonochromeLauncherIcon`).
- Removed the unused legacy library-filter string (`UnusedResources`).
- Removed redundant activity-root backgrounds where the theme already paints the same surface (`Overdraw`).
- Corrected the cache indentation issue found while linting (`SuspiciousIndentation`).

## Remaining warnings and rationale

- 13 `GradleDependency` warnings identify newer versions of AndroidX Core, AppCompat, RecyclerView, Activity, Lifecycle, DocumentFile, Room, and AndroidX test libraries. The current versions are intentionally pinned for this milestone; upgrading this many UI/database libraries together is a separate compatibility change and should be followed by a fresh device pass.
- 3 `TrustAllX509TrustManager` warnings come from the transitive Bouncy Castle `bcpkix` JAR used by PDFBox, not from application source. ROMAN PDF does not install a custom trust manager. Replacing that dependency requires a PDFBox compatibility review.
- 1 `ObsoleteSdkInt` warning remains for `mipmap-anydpi-v26`. Android adaptive-icon XML must remain in the API-26-qualified resource directory for the Android resource toolchain; the app's minimum SDK is already 26, so this is a packaging advisory with no older-device branch to remove.
- 1 `KaptUsageInsteadOfKsp` warning remains because Room is still using the stable, working KAPT setup. Moving to KSP is a build-pipeline migration, not a runtime reliability fix.
- 3 `Overdraw` warnings are the intentional List/Grid/search card surfaces. Those cards use `roman_surface` to contrast with the paper theme, so removing the backgrounds would remove the visual grouping; the activity-root duplicates were removed.

No warning in this remaining set indicates an application crash, stale render, unreadable search state, or unsafe bitmap ownership.
