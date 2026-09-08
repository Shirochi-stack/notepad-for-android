package com.shirochi.notepad;

import static org.junit.Assert.*;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.shirochi.notepad.editor.CodeEditor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Checks Android's installed intent filters and real incoming file deliveries. */
@RunWith(AndroidJUnit4.class)
public final class FileIntentTest {
  private ActivityScenario<MainActivity> scenario;
  private Intent scenarioLaunchIntent;
  private final List<Uri> fixtures = new ArrayList<>();

  @Before
  public void clearWorkspace() throws Exception {
    drainIo();
    assertTrue(
        context().getSharedPreferences("editor", Context.MODE_PRIVATE).edit().clear().commit());
    new SessionStore(context()).write(SessionStore.snapshot(Collections.emptyList(), 0));
  }

  @After
  public void closeWorkspace() throws Exception {
    if (scenario != null) {
      // ActivityScenario identifies lifecycle events by the original intent. Real onNewIntent
      // deliveries replace it, so restore the tracker identity after all assertions, for teardown.
      scenario.onActivity(activity -> activity.setIntent(scenarioLaunchIntent));
      scenario.close();
    }
    drainIo();
    for (Uri uri : fixtures) resolver().delete(uri, null, null);
  }

  @Test
  public void appearsInOpenWithAndEditForTextSourceAndUnknownFileTypes() {
    String[][] files = {
      {"index.html", "text/html"},
      {"index.html", "application/xhtml+xml"},
      {"index.html", "application/octet-stream"},
      {"page.htm", "text/html"},
      {"page.xhtml", "application/xhtml+xml"},
      {"style.css", "text/css"},
      {"app.js", "application/javascript"},
      {"module.mjs", "text/javascript"},
      {"data.json", "application/json"},
      {"config.xml", "application/xml"},
      {"icon.svg", "image/svg+xml"},
      {"data.csv", "text/csv"},
      {"data.tsv", "text/tab-separated-values"},
      {"README.md", "text/markdown"},
      {"server.log", "application/octet-stream"},
      {"settings.ini", "application/octet-stream"},
      {"settings.conf", "application/octet-stream"},
      {"config.yaml", "application/yaml"},
      {"config.toml", "application/toml"},
      {"main.py", "text/x-python"},
      {"main.cpp", "text/x-c++src"},
      {"Main.java", "text/x-java-source"},
      {"Main.kt", "application/octet-stream"},
      {"main.rs", "text/rust"},
      {"main.go", "text/x-go"},
      {"script.sh", "application/x-sh"},
      {"script.ps1", "application/octet-stream"},
      {"index.php", "application/x-httpd-php"},
      {"query.sql", "application/sql"},
      {"Dockerfile", "application/octet-stream"},
      {"LICENSE", "text/plain"},
      {"custom.arbitraryextension", "application/x-custom-format"},
      {"unknown.extension", "*/*"}
    };
    for (String action : new String[] {Intent.ACTION_VIEW, Intent.ACTION_EDIT}) {
      for (String scheme : new String[] {"content", "file"}) {
        for (String[] file : files) {
          Uri uri = resolverUri(scheme, file[0]);
          assertResolves(new Intent(action).setDataAndType(uri, file[1]), true);
        }
      }
    }
  }

  @Test
  public void appearsForLocalFilesWithoutReportedMimeType() {
    for (String action : new String[] {Intent.ACTION_VIEW, Intent.ACTION_EDIT}) {
      for (String scheme : new String[] {"content", "file"}) {
        for (String name :
            new String[] {
              "index.html", "page.htm", "page.xhtml", "custom.unknown", "README", ".gitignore"
            }) {
          assertResolves(new Intent(action).setData(resolverUri(scheme, name)), true);
        }
      }
    }
  }

  @Test
  public void doesNotClaimWebNavigationEvenWithHtmlMimeType() {
    for (String action : new String[] {Intent.ACTION_VIEW, Intent.ACTION_EDIT}) {
      for (String scheme : new String[] {"http", "https"}) {
        for (String mime : new String[] {null, "text/html", "application/xhtml+xml", "*/*"}) {
          assertResolves(
              new Intent(action)
                  .setDataAndType(Uri.parse(scheme + "://example.org/index.html"), mime),
              false);
          assertResolves(
              new Intent(action)
                  .addCategory(Intent.CATEGORY_BROWSABLE)
                  .setDataAndType(Uri.parse(scheme + "://example.org/index.html"), mime),
              false);
        }
      }
    }
  }

  @Test
  public void acceptsSingleAndMultipleSharesRegardlessOfFileMimeType() {
    for (String action : new String[] {Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE}) {
      for (String mime :
          new String[] {
            null,
            "text/plain",
            "text/html",
            "application/xhtml+xml",
            "application/json",
            "application/xml",
            "image/svg+xml",
            "application/octet-stream",
            "application/x-custom-format",
            "*/*"
          }) {
        assertResolves(new Intent(action).setType(mime), true);
      }
    }
  }

  @Test
  public void coldOpenWithShowsHtmlSourceAndWritesOnlyOnSave() throws Exception {
    String html = "<!doctype html>\n<html><body><h1>Hello 🌿</h1></body></html>\n";
    Uri uri = fixture("html", "application/xhtml+xml", html);
    launch(fileIntent(Intent.ACTION_VIEW, uri, "application/xhtml+xml"));
    awaitDocument(uri);
    assertDocument(uri, html);
    assertEquals(html, text(uri));
    String edited = html.replace("Hello", "Edited");
    scenario.onActivity(activity -> editor(activity).setText(edited));
    assertEquals("Editing must not change the source until Save", html, text(uri));
    scenario.onActivity(activity -> save(activity));
    awaitSaved();
    assertEquals(edited, text(uri));
    scenario.onActivity(activity -> assertFalse(current(activity).dirty()));
  }

  @Test
  public void warmEditDeliveryUsesExistingActivityAndPreservesOtherDrafts() throws Exception {
    launch(new Intent(context(), MainActivity.class));
    AtomicReference<MainActivity> existing = new AtomicReference<>();
    scenario.onActivity(
        activity -> {
          existing.set(activity);
          editor(activity).setText("Keep this unsaved draft");
        });
    String html = "<html><body>Opened for editing</body></html>\n";
    Uri uri = fixture("html", "application/octet-stream", html);
    deliver(fileIntent(Intent.ACTION_EDIT, uri, "application/octet-stream"));
    awaitDocument(uri);
    assertDocument(uri, html);
    scenario.onActivity(
        activity -> {
          assertSame(
              "Android should deliver onNewIntent to the current activity",
              existing.get(),
              activity);
          assertEquals(2, documents(activity).size());
          assertEquals("Keep this unsaved draft", documents(activity).get(0).text);
          assertTrue(documents(activity).get(0).dirty());
          editor(activity).setText("<html><body>Saved edit</body></html>\n");
        });
    assertEquals(html, text(uri));
    scenario.onActivity(activity -> save(activity));
    awaitSaved();
    assertEquals("<html><body>Saved edit</body></html>\n", text(uri));
  }

  @Test
  public void coldEditOpensProviderFileWithoutMimeType() throws Exception {
    String source = "<html>Source with no MIME metadata</html>";
    Uri uri = fixture("html", "none", source);
    launch(fileIntent(Intent.ACTION_EDIT, uri, null));
    awaitDocument(uri);
    assertDocument(uri, source);
    assertEquals(source, text(uri));
  }

  @Test
  public void warmOpenWithReselectsExistingFileAndKeepsUnsavedEdits() throws Exception {
    String source = "<html>Original source</html>";
    Uri uri = fixture("html", "text/html", source);
    launch(fileIntent(Intent.ACTION_VIEW, uri, "text/html"));
    awaitDocument(uri);
    AtomicReference<MainActivity> existing = new AtomicReference<>();
    scenario.onActivity(
        activity -> {
          existing.set(activity);
          editor(activity).setText("<html>Unsaved source edit</html>");
          invoke(activity, "newDocument", new Class<?>[0]);
          editor(activity).setText("Another draft");
        });
    deliver(fileIntent(Intent.ACTION_VIEW, uri, "text/html"));
    awaitDocument(uri);
    scenario.onActivity(
        activity -> {
          assertSame(existing.get(), activity);
          assertEquals(2, documents(activity).size());
          assertEquals("<html>Unsaved source edit</html>", editor(activity).getText().toString());
          assertTrue(current(activity).dirty());
        });
    assertEquals(
        "Opening an existing file again must not overwrite either copy", source, text(uri));
  }

  @Test
  public void sharedStreamOpensAttachmentInsteadOfItsCaption() throws Exception {
    String source = "<html><body>Attachment source</body></html>";
    Uri uri = fixture("html", "text/html", source);
    Intent intent = shareIntent(Intent.ACTION_SEND, "text/html");
    intent.putExtra(Intent.EXTRA_STREAM, uri);
    intent.putExtra(Intent.EXTRA_TEXT, "A caption describing the attachment");
    intent.setClipData(ClipData.newRawUri("HTML source", uri));
    launch(intent);
    awaitDocument(uri);
    assertDocument(uri, source);
    scenario.onActivity(
        activity ->
            assertEquals("Duplicate stream/clip URI opens one tab", 1, documents(activity).size()));
    assertEquals(source, text(uri));
  }

  @Test
  public void warmClipDataOnlyShareOpensAttachment() throws Exception {
    launch(new Intent(context(), MainActivity.class));
    String source = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"5\"/></svg>";
    Uri uri = fixture("svg", "image/svg+xml", source);
    Intent intent = shareIntent(Intent.ACTION_SEND, "image/svg+xml");
    intent.setClipData(ClipData.newRawUri("SVG source", uri));
    deliver(intent);
    awaitDocument(uri);
    assertDocument(uri, source);
    assertEquals(source, text(uri));
  }

  @Test
  public void multipleShareMergesStreamAndClipAttachmentsWithoutDuplicateTabs() throws Exception {
    Uri html = fixture("html", "text/html", "<html>First attachment</html>");
    Uri json = fixture("json", "application/json", "{\"attachment\":2}");
    Uri custom = fixture("custom", "application/x-custom-format", "third = source\n");
    Intent intent = shareIntent(Intent.ACTION_SEND_MULTIPLE, "*/*");
    intent.putParcelableArrayListExtra(
        Intent.EXTRA_STREAM, new ArrayList<>(Arrays.asList(html, json)));
    ClipData clips = ClipData.newRawUri("Attachments", html);
    clips.addItem(new ClipData.Item(json));
    clips.addItem(new ClipData.Item(custom));
    intent.setClipData(clips);
    launch(intent);
    awaitActivity(
        activity ->
            hasDocument(activity, html)
                && hasDocument(activity, json)
                && hasDocument(activity, custom),
        "all shared attachments");
    scenario.onActivity(
        activity -> {
          assertEquals(3, documents(activity).size());
          assertEquals("<html>First attachment</html>", document(activity, html).text);
          assertEquals("{\"attachment\":2}", document(activity, json).text);
          assertEquals("third = source\n", document(activity, custom).text);
          for (Document document : documents(activity)) assertFalse(document.dirty());
        });
    assertEquals("<html>First attachment</html>", text(html));
    assertEquals("{\"attachment\":2}", text(json));
    assertEquals("third = source\n", text(custom));
  }

  @Test
  public void plainTextShareStillCreatesEditableDraft() {
    Intent intent = shareIntent(Intent.ACTION_SEND, "text/plain");
    intent.putExtra(Intent.EXTRA_TEXT, "Shared text without an attached file 🌿");
    launch(intent);
    awaitActivity(
        activity ->
            "Shared text without an attached file 🌿".equals(editor(activity).getText().toString()),
        "shared text draft");
    scenario.onActivity(
        activity -> {
          assertEquals("", current(activity).uri);
          assertTrue(current(activity).dirty());
          assertEquals(1, documents(activity).size());
        });
  }

  private void assertResolves(Intent intent, boolean expected) {
    // Package targeting isolates our entry; setting a component would bypass the manifest filters.
    intent.setPackage(context().getPackageName()).addCategory(Intent.CATEGORY_DEFAULT);
    List<ResolveInfo> matches =
        context()
            .getPackageManager()
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
    boolean found = false;
    for (ResolveInfo match : matches) {
      if (MainActivity.class.getName().equals(match.activityInfo.name)) found = true;
    }
    assertEquals("Installed manifest resolution: " + intent, expected, found);
  }

  private static Uri resolverUri(String scheme, String name) {
    return "file".equals(scheme)
        ? Uri.parse("file:///sdcard/Download/" + name)
        : Uri.parse("content://com.example.filemanager/document/" + name);
  }

  private static Intent fileIntent(String action, Uri uri, String mime) {
    return new Intent(action)
        .setPackage(context().getPackageName())
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }

  private static Intent shareIntent(String action, String mime) {
    return new Intent(action)
        .setPackage(context().getPackageName())
        .setType(mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }

  private void launch(Intent intent) {
    scenario = ActivityScenario.launch(intent);
    scenario.onActivity(activity -> scenarioLaunchIntent = new Intent(activity.getIntent()));
    awaitActivity(
        activity -> !((Boolean) field(activity, "loading")) && editor(activity) != null,
        "draft recovery");
  }

  private void deliver(Intent intent) {
    context()
        .startActivity(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private void assertDocument(Uri uri, String expected) {
    scenario.onActivity(
        activity -> {
          assertEquals(uri.toString(), current(activity).uri);
          assertEquals(uri.getLastPathSegment(), current(activity).name);
          assertEquals(expected, editor(activity).getText().toString());
          assertFalse(current(activity).dirty());
        });
  }

  private void awaitDocument(Uri uri) {
    awaitActivity(
        activity -> current(activity) != null && uri.toString().equals(current(activity).uri),
        "incoming file " + uri);
  }

  private void awaitSaved() {
    awaitActivity(activity -> ((List<?>) field(activity, "saving")).isEmpty(), "save completion");
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private void awaitActivity(Predicate<MainActivity> condition, String description) {
    long deadline = SystemClock.uptimeMillis() + 10_000;
    AtomicBoolean complete = new AtomicBoolean();
    do {
      scenario.onActivity(activity -> complete.set(condition.test(activity)));
      if (complete.get()) return;
      SystemClock.sleep(40);
    } while (SystemClock.uptimeMillis() < deadline);
    fail("Timed out waiting for " + description);
  }

  private Uri fixture(String extension, String mime, String contents) throws Exception {
    Uri uri =
        new Uri.Builder()
            .scheme("content")
            .authority(TestFileProvider.AUTHORITY)
            .appendPath(UUID.randomUUID() + "." + extension)
            .appendQueryParameter("mime", mime)
            .build();
    fixtures.add(uri);
    try (OutputStream stream = resolver().openOutputStream(uri, "wt")) {
      assertNotNull(stream);
      stream.write(contents.getBytes(StandardCharsets.UTF_8));
    }
    return uri;
  }

  private static String text(Uri uri) throws Exception {
    try (InputStream stream = resolver().openInputStream(uri);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      assertNotNull(stream);
      byte[] buffer = new byte[4096];
      int read;
      while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private static ContentResolver resolver() {
    return context().getContentResolver();
  }

  private static CodeEditor editor(MainActivity activity) {
    return activity.findViewById(R.id.editor);
  }

  private static Document current(MainActivity activity) {
    return (Document) invoke(activity, "current", new Class<?>[0]);
  }

  @SuppressWarnings("unchecked")
  private static List<Document> documents(MainActivity activity) {
    return (List<Document>) field(activity, "documents");
  }

  private static boolean hasDocument(MainActivity activity, Uri uri) {
    return document(activity, uri) != null;
  }

  private static Document document(MainActivity activity, Uri uri) {
    for (Document document : documents(activity)) {
      if (uri.toString().equals(document.uri)) return document;
    }
    return null;
  }

  private static void save(MainActivity activity) {
    invoke(activity, "saveCurrent", new Class<?>[] {boolean.class, Runnable.class}, false, null);
  }

  private static Object invoke(
      MainActivity activity, String name, Class<?>[] types, Object... args) {
    try {
      Method method = MainActivity.class.getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method.invoke(activity, args);
    } catch (InvocationTargetException error) {
      throw new AssertionError(error.getCause());
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static Object field(MainActivity activity, String name) {
    try {
      Field field = MainActivity.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(activity);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void drainIo() throws Exception {
    Field field = MainActivity.class.getDeclaredField("IO");
    field.setAccessible(true);
    ((ExecutorService) field.get(null)).submit(() -> {}).get(10, TimeUnit.SECONDS);
  }
}
