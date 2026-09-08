# Development guide

Notepad for Android is a native Java app for Android 8.0 and later (minimum SDK 26). See the [README](../README.md) for downloads and everyday use.

## Build and verify

Install JDK 17 and Android SDK platform 35 / build tools 35.0.0. Set `ANDROID_HOME`, or create an ignored `local.properties` containing the SDK path:

```properties
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

On Linux/macOS:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

The first command runs JVM unit tests, Android lint, and APK compilation. The second command runs instrumentation tests and needs an Android emulator or attached test device. The installable APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The project pins Android Gradle Plugin 8.9.2, Gradle 8.11.1, and the official Gradle distribution checksum. Gradle downloads build dependencies on the first run.

## GitHub Actions

The [Android build workflow](../.github/workflows/android.yml) runs unit tests, Android lint, and APK compilation on pushes and pull requests. It can also be started manually. Successful runs publish the installable debug APK as the `Notepad-for-Android-debug-APK` artifact; test and lint reports are separate artifacts.

APK workflow artifacts expire after 30 days, and verification reports after 14 days. Release attachments remain available until removed. The workflow does not provision an emulator; run instrumentation tests with the command above.

## Project structure

- `app/src/main/java/com/shirochi/notepad/MainActivity.java` owns the workspace, tabs, search controls, and Android file-picker flow.
- `SessionStore` persists atomic, app-private recovery snapshots.
- `editor/CodeEditor` provides native text editing, line-number drawing, and bounded syntax/search decoration.
- Pure Java classes in `core` implement search, encoding, history, and line transforms, with JVM tests in `app/src/test`.
- Android instrumentation tests in `app/src/androidTest` exercise the real editor and document flows.

## Editor limits

The editor bounds memory use and expensive operations to keep small-file editing practical on mobile devices:

- Up to 12 open tabs, 2 MiB per file, and 2,097,152 UTF-16 text units per document. An oversized insertion is rejected in full. Encoded output must also fit the file limit.
- Undo keeps at most 100 snapshots and an 8-million-character budget per tab. Undo history resets after process recreation; document content and selection recover from the saved session.
- Search navigation is limited to 10,000 results. Replace all can process more literal matches. Very complex regex operations are rejected after a bounded wait; if the native matcher is still finishing, literal search remains available.
- Syntax highlighting covers the first 180,000 characters and up to 12,000 tokens. Search decoration displays up to 6,000 results plus the active match. Editing remains available beyond the highlighting limit.

## File handling and recovery

`MainActivity` registers `ACTION_VIEW` and `ACTION_EDIT` for local `content:` and `file:` URIs with any MIME type, plus separate filters for URIs without a MIME type. There is no extension whitelist. HTTP/HTTPS browsing is not registered. `ACTION_SEND` and `ACTION_SEND_MULTIPLE` accept file attachments; `IncomingFiles` collects and deduplicates URIs from intent data, `EXTRA_STREAM`, and `ClipData`. File contents are validated by the text codec, and imports retain the file-size and tab limits above. Broad intent matching makes the app available for text/code files reported with generic or vendor-specific MIME types; it does not add binary document conversion or guarantee inclusion in a file manager's custom chooser.

Imported files are normalized to LF internally; the predominant original line ending is used when saving. Mixed line endings become consistent on save. Automatic decoding uses BOM detection, UTF-16 heuristics, strict UTF-8, and a Windows-1252 fallback. It does not detect every legacy encoding. **Document format → Reopen with encoding…** lets users decode the original file using an explicit encoding, and **Encoding** selects the save encoding. Available choices come from platform-supported text charsets, including common legacy encodings where available. Encoding conversion rejects unrepresentable characters rather than silently replacing them. Syntax highlighting is independent of which text files can be opened.

Recovery snapshots are atomic within app-private storage and never automatically overwrite source files. Drafts are written shortly after edits and when the app pauses. Abrupt termination before the latest recovery write completes can lose the most recent keystrokes.

The app detects external file changes before saving and asks before overwriting. Failed saves keep the local draft. Android document providers control their own write behavior: interrupted writes can require recovery from a draft, and source-file writes cannot be made transactional across every provider. If access to a moved or deleted file expires, use **Save as**.

## Signing and distribution

The current downloadable APK is a debug-signed preview. Local builds and GitHub Actions builds may use different debug signing keys; Android accepts an in-place update only when the application ID and signing identity match and the version is compatible. Preserve the signing identity for published updates.

Before distributing a release variant, configure a durable signing key outside version control. Do not commit keystores, signing passwords, local SDK paths, or document drafts. Keep user-facing release notes focused on changes, installation, compatibility, and known limitations; keep build reports in workflow artifacts.

## Contributions

Use [GitHub issues](https://github.com/Shirochi-stack/notepad-for-android/issues) for reproducible bugs and feature proposals. Include Android version, app version, expected behavior, and reproduction steps. Remove private content from sample files and screenshots.

Run the relevant unit tests and lint for code changes, and instrumentation tests for changes to editor interactions or Android document flows. The project is licensed under the [MIT License](../LICENSE).
