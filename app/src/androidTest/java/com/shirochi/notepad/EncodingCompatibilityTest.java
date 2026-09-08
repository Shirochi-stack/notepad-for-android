package com.shirochi.notepad;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.shirochi.notepad.core.TextCodec;
import com.shirochi.notepad.editor.CodeEditor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

/** Verifies actual Android charset providers and reading a source file again in the editor. */
@RunWith(AndroidJUnit4.class)
public final class EncodingCompatibilityTest {
  private ActivityScenario<MainActivity> scenario;
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
    if (scenario != null) scenario.close();
    drainIo();
    for (Uri uri : fixtures) resolver().delete(uri, null, null);
  }

  @Test
  public void deviceEncodingChoicesAreCanonicalUniqueAndWritable() throws Exception {
    List<String> encodings = TextCodec.availableEncodings();
    assertEquals("UTF-8", encodings.get(0));
    assertEquals(encodings.size(), new HashSet<>(encodings).size());
    for (String name :
        new String[] {
          "UTF-16LE",
          "UTF-16BE",
          "windows-1251",
          "windows-1252",
          "Shift_JIS",
          "EUC-JP",
          "GB18030",
          "Big5",
          "EUC-KR",
          "KOI8-R"
        }) {
      assertTrue(name, encodings.contains(Charset.forName(name).name()));
    }
    for (String name : encodings) {
      assertEquals(name, Charset.forName(name).name());
      assertTrue(name, Charset.forName(name).canEncode());
      assertNotNull(TextCodec.encode("", name, "LF", false));
    }
    assertFalse(encodings.contains("UTF-16"));
    assertFalse(encodings.contains("UTF-32"));
  }

  @Test
  public void deviceLegacyCharsetsPreserveInternationalSourceBytes() throws Exception {
    String[][] samples = {
      {"windows-1251", "Привет мир"},
      {"windows-1256", "العربية"},
      {"ISO-8859-15", "Café €"},
      {"Shift_JIS", "日本語のメモ"},
      {"EUC-JP", "日本語のメモ"},
      {"GB18030", "中文笔记 \uD83D\uDE00"},
      {"Big5", "中文筆記"},
      {"EUC-KR", "한국어 메모"},
      {"KOI8-R", "Русский текст"}
    };
    for (String[] sample : samples) {
      byte[] bytes = (sample[1] + "\r\n").getBytes(Charset.forName(sample[0]));
      TextCodec.Decoded decoded = TextCodec.decode(bytes, sample[0]);
      assertEquals(sample[0], sample[1] + "\n", decoded.text);
      assertEquals(Charset.forName(sample[0]).name(), decoded.encoding);
      assertEquals("CRLF", decoded.lineEnding);
      assertFalse(decoded.bom);
      assertArrayEquals(
          bytes, TextCodec.encode(decoded.text, decoded.encoding, decoded.lineEnding, decoded.bom));
    }
  }

  @Test
  public void deviceDecodersAndEncodersRejectLossyConversions() throws Exception {
    for (String encoding : new String[] {"UTF-8", "Shift_JIS", "not-a-real-charset"}) {
      try {
        TextCodec.decode(new byte[] {(byte) 0x82}, encoding);
        fail("Invalid input must not be replaced or decoded with a fallback: " + encoding);
      } catch (IOException expected) {
        assertNotNull(expected.getMessage());
      }
    }
    try {
      TextCodec.encode("日本語", "windows-1252", "LF", false);
      fail("Saving must preserve unrepresentable characters by reporting an error");
    } catch (IOException expected) {
      assertNotNull(expected.getMessage());
    }
  }

  @Test
  public void reopeningWithSelectedEncodingCorrectsAutomaticGuessWithoutWritingSource()
      throws Exception {
    String original = "Привет мир\r\nВторая строка\r\n";
    byte[] bytes = original.getBytes(Charset.forName("windows-1251"));
    Uri uri = fixture(bytes);
    launch(uri);
    AtomicReference<Document> opened = new AtomicReference<>();
    scenario.onActivity(
        activity -> {
          Document document = current(activity);
          opened.set(document);
          assertEquals("windows-1252", document.encoding);
          assertNotEquals(original.replace("\r\n", "\n"), document.text);
          reopen(activity, document, "windows-1251");
        });
    awaitActivity(
        activity -> "windows-1251".equals(current(activity).encoding), "selected encoding reload");
    scenario.onActivity(
        activity -> {
          Document document = current(activity);
          assertSame(opened.get(), document);
          assertEquals(uri.toString(), document.uri);
          assertEquals(original.replace("\r\n", "\n"), document.text);
          assertEquals(document.text, editor(activity).getText().toString());
          assertEquals("CRLF", document.lineEnding);
          assertFalse(document.bom);
          assertFalse(document.dirty());
        });
    assertArrayEquals("Reopening must not write to the source", bytes, bytes(uri));
  }

  @Test
  public void failedReopenKeepsUnsavedDraftMetadataAndOriginalBytes() throws Exception {
    byte[] bytes = "Привет\r\n".getBytes(Charset.forName("windows-1251"));
    Uri uri = fixture(bytes);
    launch(uri);
    AtomicReference<Document> opened = new AtomicReference<>();
    AtomicReference<String> savedText = new AtomicReference<>();
    AtomicReference<String> savedFormat = new AtomicReference<>();
    AtomicReference<String> diskHash = new AtomicReference<>();
    String draft = "An unsaved draft with 日本語";
    scenario.onActivity(
        activity -> {
          Document document = current(activity);
          opened.set(document);
          savedText.set(document.savedText);
          savedFormat.set(document.savedFormat);
          diskHash.set(document.diskHash);
          editor(activity).setText(draft);
          assertTrue(document.dirty());
        });
    for (String encoding : new String[] {"not-a-real-charset", "UTF-8"}) {
      scenario.onActivity(activity -> reopen(activity, current(activity), encoding));
      drainIo();
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      onView(withText("Cannot reopen file")).inRoot(isDialog()).check(matches(isDisplayed()));
      scenario.onActivity(
          activity -> {
            Document document = current(activity);
            assertSame(opened.get(), document);
            assertEquals(uri.toString(), document.uri);
            assertEquals(draft, document.text);
            assertEquals(draft, editor(activity).getText().toString());
            assertEquals("windows-1252", document.encoding);
            assertEquals(savedText.get(), document.savedText);
            assertEquals(savedFormat.get(), document.savedFormat);
            assertEquals(diskHash.get(), document.diskHash);
            assertTrue(document.dirty());
          });
      assertArrayEquals("A failed reload must never alter the original file", bytes, bytes(uri));
      onView(withText("OK")).inRoot(isDialog()).perform(click());
    }
  }

  @Test
  public void reopenDialogKeepsDirtyDraftUntilDiscardIsExplicitlyConfirmed() throws Exception {
    String original = "Привет\n";
    byte[] bytes = original.getBytes(Charset.forName("windows-1251"));
    Uri uri = fixture(bytes);
    launch(uri);
    String draft = "Keep my unsaved changes";
    scenario.onActivity(
        activity -> {
          editor(activity).setText(draft);
          invoke(activity, "confirmReopen", new Class<?>[] {Document.class}, current(activity));
        });
    onData(equalTo("windows-1251")).inRoot(isDialog()).perform(click());
    onView(withText("Discard unsaved changes?")).inRoot(isDialog()).check(matches(isDisplayed()));
    scenario.onActivity(activity -> assertEquals(draft, current(activity).text));
    onView(withText("Cancel")).inRoot(isDialog()).perform(click());
    scenario.onActivity(
        activity -> {
          assertEquals(draft, editor(activity).getText().toString());
          assertTrue(current(activity).dirty());
          invoke(activity, "confirmReopen", new Class<?>[] {Document.class}, current(activity));
        });
    assertArrayEquals(bytes, bytes(uri));
    onData(equalTo("windows-1251")).inRoot(isDialog()).perform(click());
    onView(withText("Discard and reopen")).inRoot(isDialog()).perform(click());
    awaitActivity(
        activity -> "windows-1251".equals(current(activity).encoding), "confirmed reload");
    scenario.onActivity(
        activity -> {
          assertEquals(original, editor(activity).getText().toString());
          assertFalse(current(activity).dirty());
        });
    assertArrayEquals("Discarding a draft for reload never changes the source", bytes, bytes(uri));
  }

  private void launch(Uri uri) {
    Intent intent =
        new Intent(Intent.ACTION_VIEW)
            .setPackage(context().getPackageName())
            .setDataAndType(uri, "text/plain")
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    scenario = ActivityScenario.launch(intent);
    awaitActivity(
        activity ->
            !((Boolean) field(activity, "loading"))
                && current(activity) != null
                && uri.toString().equals(current(activity).uri),
        "source document");
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

  private Uri fixture(byte[] contents) throws Exception {
    Uri uri =
        new Uri.Builder()
            .scheme("content")
            .authority(TestFileProvider.AUTHORITY)
            .appendPath(UUID.randomUUID() + ".txt")
            .build();
    fixtures.add(uri);
    try (OutputStream stream = resolver().openOutputStream(uri, "wt")) {
      assertNotNull(stream);
      stream.write(contents);
    }
    return uri;
  }

  private static byte[] bytes(Uri uri) throws Exception {
    try (InputStream stream = resolver().openInputStream(uri);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      assertNotNull(stream);
      byte[] buffer = new byte[4096];
      int read;
      while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
      return out.toByteArray();
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

  private static void reopen(MainActivity activity, Document document, String encoding) {
    invoke(
        activity,
        "reopenDocument",
        new Class<?>[] {Document.class, String.class},
        document,
        encoding);
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
