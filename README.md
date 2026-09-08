# Notepad Studio for Android

A native, offline text and code editor inspired by desktop editors such as Notepad++. Built in Java for **Android 8.0 and later**. Independent software; not affiliated with Notepad++.

<p>
  <img src="docs/screenshots/editor.png" width="280" alt="Notepad Studio with document tabs and JavaScript syntax highlighting" />
  <img src="docs/screenshots/search-replace.png" width="280" alt="Find and replace with match highlights" />
</p>

## Install

Download `Notepad-Studio-1.0.0-debug.apk` from the private [Releases page](https://github.com/Shirochi-stack/notepad-studio-android/releases). On your Android device, open the APK and allow installation from the app you used to download it when Android prompts you.

This is an installable, debug-signed development build. It is not a Google Play release. The source, APK, and build reports are private to people with repository access.

## Features

- Multiple document tabs with independent undo/redo, cursor positions, and unsaved-change indicators.
- New, open multiple files, save, save as, save all, recent files, share text, and Android “Open with” support.
- Android's system file picker supports local storage and installed document providers. No broad storage permission is requested.
- Atomic, app-private draft recovery after reopening. Draft recovery never automatically overwrites source files. Unsaved tabs prompt before closing.
- Save conflict detection: a file changed outside the app requires an explicit overwrite choice. Failed saves keep the draft.
- Find and replace, next/previous match, match counts, highlighted results, case sensitivity, Unicode whole-word search, regular expressions, and capture-group replacement such as `$1`.
- Line numbers, current-line highlight, go to line, word wrap, horizontal scrolling, font sizes, light/dark themes, and document statistics.
- Lightweight syntax highlighting for JavaScript, TypeScript, Java, Kotlin, C/C++, Python, JSON, HTML/XML, CSS, Markdown, SQL, shell scripts, and YAML.
- Auto indent, tab or four-space indentation, block indent/unindent, duplicate line/selection, toggle line comments, selected-text case conversion, and quick bracket insertion.
- Native Android text selection, copy, cut, paste, and physical keyboard support.
- UTF-8, UTF-16 LE/BE, and Windows-1252; BOM detection; LF, CRLF, and CR output. Encoding conversion rejects unrepresentable characters instead of silently replacing them.
- No accounts, ads, analytics, runtime network dependencies, or internet permission. Drafts and settings are excluded from app backups and device transfer.

## Keyboard shortcuts

| Shortcut | Action |
| --- | --- |
| Ctrl+N / Ctrl+O | New tab / open files |
| Ctrl+S / Ctrl+Shift+S | Save / save as |
| Ctrl+W | Close tab |
| Ctrl+Tab / Ctrl+Shift+Tab | Next / previous tab |
| Ctrl+F / Ctrl+H | Find / replace |
| F3 / Shift+F3 | Next / previous match |
| Escape | Close search |
| Ctrl+G | Go to line |
| Ctrl+Z | Undo |
| Ctrl+Y / Ctrl+Shift+Z | Redo |
| Ctrl+A / C / X / V | Select all / copy / cut / paste |
| Tab / Shift+Tab | Indent / unindent |
| Ctrl+D | Duplicate line or selected text |
| Ctrl+/ | Toggle line comment |
| Ctrl+Plus / Ctrl+Minus | Change editor font size |

All primary actions are also available through touch controls. Replacement text is literal unless regex mode is enabled. An empty replacement deletes matches. A first tap on **Replace** selects a match if the editor does not already have one selected.

## Build on Windows

Install JDK 17 and Android SDK platform 35 / build tools 35.0.0. Set `ANDROID_HOME`, or create an ignored `local.properties` containing your SDK path:

```properties
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

The second command needs an Android emulator or attached test device. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

On Linux/macOS use `./gradlew` instead. The project pins Android Gradle Plugin 8.9.2, Gradle 8.11.1, and the official Gradle distribution checksum. Gradle downloads build dependencies on the first run.

## GitHub Actions

The **Android build** workflow runs unit tests, Android lint, and APK compilation on pushes and pull requests. It can also be started manually. Successful runs publish the installable debug APK as `Notepad-Studio-debug-APK`; verification reports are separate artifacts. APK artifacts expire after 30 days; a release attachment remains available until removed.

Android instrumentation tests are run locally with the command above; the workflow does not provision an emulator.

## Scope and practical limits

This is a focused mobile editor, not a port of the desktop Notepad++ plugin ecosystem. It does not include plugins, language servers, code execution, terminal access, folding, a directory project tree, or multi-file search.

- Up to 12 open tabs, 2 MiB per file, and 2,097,152 UTF-16 text units per document. An oversized insertion is rejected in full. Encoded output must also fit the file limit.
- Undo has at most 100 snapshots and an 8-million-character budget per tab. Undo history resets after process recreation; the document content and selection recover.
- Search navigation is limited to 10,000 results; refine the query above that threshold. Replace all can process more literal matches. Very complex regex operations are rejected after a bounded wait; if the native matcher is still finishing, literal search remains available.
- Syntax highlighting covers the first 180,000 characters and up to 12,000 tokens. Search decoration displays up to 6,000 results plus the active match. Editing remains available beyond the highlighting limit.
- Imported files are normalized to LF internally; the predominant original line ending is used when saving. Mixed line endings become consistent on save. UTF-16 without a BOM uses a heuristic; ambiguous legacy files should be verified before saving.
- Android providers control their own write behavior. An interrupted provider write may require recovery from the local draft; source-file writes cannot be made transactional across every provider. If access to a moved or deleted file expires, use Save as.
- Drafts are written shortly after edits and when the app pauses. Abrupt termination before the latest recovery write completes can lose the most recent keystrokes. Explicitly save important documents.
- The interface is currently English.

## Project structure

`MainActivity` owns the workspace and Android file-picker flow. `SessionStore` persists atomic draft snapshots. `CodeEditor` provides native editing, line-number drawing, and bounded syntax/search decoration. Pure Java classes in `core` implement search, encoding, history, and line transforms, with JUnit tests. Android instrumentation tests exercise the real editor and document flows.

For distribution beyond development, configure a durable release signing key outside version control and build/sign the release variant. Do not commit keystores, signing passwords, local SDK paths, or document drafts.
