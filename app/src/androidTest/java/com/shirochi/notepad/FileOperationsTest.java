package com.shirochi.notepad;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.shirochi.notepad.core.TextCodec;
import com.shirochi.notepad.editor.CodeEditor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

/** Exercises real content-provider bytes and asynchronous save safety on Android. */
@RunWith(AndroidJUnit4.class)
public final class FileOperationsTest {
  private ActivityScenario<MainActivity> scenario;
  private final List<Uri> fixtures = new ArrayList<>();
  private Instrumentation.ActivityMonitor pickerMonitor;

  @Before
  public void launchCleanWorkspace() throws Exception {
    drainIo();
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(
        context.getSharedPreferences("editor", Context.MODE_PRIVATE).edit().clear().commit());
    new SessionStore(context).write(SessionStore.snapshot(Collections.emptyList(), 0));
    scenario = ActivityScenario.launch(MainActivity.class);
    awaitReady();
  }

  @After
  public void closeWorkspace() throws Exception {
    if (pickerMonitor != null)
      InstrumentationRegistry.getInstrumentation().removeMonitor(pickerMonitor);
    if (scenario != null) scenario.close();
    drainIo();
    for (Uri uri : fixtures) resolver().delete(uri, null, null);
  }

  @Test
  public void opensUtf8AndSavesExactBomAndCrLfBytes() throws Exception {
    String original = "First line\nمرحبا 🌿\n";
    Uri uri = fixture(TextCodec.encode(original, "UTF-8", "CRLF", true));
    open(uri);
    scenario.onActivity(
        activity -> {
          assertEquals(original, editor(activity).getText().toString());
          Document document = current(activity);
          assertEquals(uri.getLastPathSegment(), document.name);
          assertEquals("UTF-8", document.encoding);
          assertEquals("CRLF", document.lineEnding);
          assertTrue(document.bom);
          assertFalse(document.dirty());
          editor(activity).setText("Saved café\n日本語 🌿\n");
          assertTrue(document.dirty());
          save(activity, false, null);
        });
    awaitSaved();
    assertArrayEquals(TextCodec.encode("Saved café\n日本語 🌿\n", "UTF-8", "CRLF", true), bytes(uri));
    scenario.onActivity(activity -> assertFalse(current(activity).dirty()));
  }

  @Test
  public void utf16SavePreservesEncodingAndBom() throws Exception {
    Uri uri = fixture(TextCodec.encode("Before\n日本語", "UTF-16BE", "LF", true));
    open(uri);
    scenario.onActivity(
        activity -> {
          assertEquals("UTF-16BE", current(activity).encoding);
          editor(activity).setText("After\nمرحبا");
          save(activity, false, null);
        });
    awaitSaved();
    assertArrayEquals(TextCodec.encode("After\nمرحبا", "UTF-16BE", "LF", true), bytes(uri));
  }

  @Test
  public void saveAsResultWritesOriginalTabAfterUserSwitchesTabs() throws Exception {
    Uri target = fixture(new byte[0]);
    Intent result =
        new Intent()
            .setData(target)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    pickerMonitor =
        InstrumentationRegistry.getInstrumentation()
            .addMonitor(
                new IntentFilter(Intent.ACTION_CREATE_DOCUMENT),
                new Instrumentation.ActivityResult(Activity.RESULT_OK, result),
                true);
    AtomicReference<Document> original = new AtomicReference<>();
    AtomicBoolean callback = new AtomicBoolean();
    scenario.onActivity(
        activity -> {
          editor(activity).setText("Save this first tab 🌿");
          original.set(current(activity));
          save(activity, true, () -> callback.set(true));
          assertEquals(original.get().id, field(activity, "pendingSaveId"));
          invoke(activity, "newDocument", new Class<?>[0]);
          editor(activity).setText("Leave this second tab alone");
          assertNotSame(original.get(), current(activity));
        });
    awaitActivity(activity -> callback.get() && saving(activity).isEmpty(), "Save As callback");
    assertEquals(1, pickerMonitor.getHits());
    assertArrayEquals("Save this first tab 🌿".getBytes(StandardCharsets.UTF_8), bytes(target));
    scenario.onActivity(
        activity -> {
          assertEquals(target.toString(), original.get().uri);
          assertFalse(original.get().dirty());
          assertEquals("Leave this second tab alone", editor(activity).getText().toString());
          assertEquals("", current(activity).uri);
          assertTrue(current(activity).dirty());
        });
  }

  @Test
  public void externalModificationRequiresExplicitOverwrite() throws Exception {
    Uri uri = fixture("Original disk content".getBytes(StandardCharsets.UTF_8));
    open(uri);
    scenario.onActivity(activity -> editor(activity).setText("My unsaved document"));
    byte[] external = "Edited in another app".getBytes(StandardCharsets.UTF_8);
    write(uri, external);
    scenario.onActivity(activity -> save(activity, false, null));
    awaitSaved();
    onView(withText("File changed outside this app")).check(matches(isDisplayed()));
    assertArrayEquals(external, bytes(uri));
    scenario.onActivity(activity -> assertTrue(current(activity).dirty()));
    onView(withText("Keep editing")).perform(click());
    assertArrayEquals(external, bytes(uri));
    scenario.onActivity(activity -> save(activity, false, null));
    awaitSaved();
    onView(withText("File changed outside this app")).check(matches(isDisplayed()));
    onView(withText("Overwrite")).perform(click());
    awaitSaved();
    assertArrayEquals("My unsaved document".getBytes(StandardCharsets.UTF_8), bytes(uri));
    scenario.onActivity(activity -> assertFalse(current(activity).dirty()));
  }

  @Test
  public void providerSaveFailureKeepsOriginalBytesAndRecoverableDirtyDraft() throws Exception {
    byte[] original = "Original read-only file".getBytes(StandardCharsets.UTF_8);
    Uri writable = fixture(original);
    Uri readOnly = writable.buildUpon().appendQueryParameter("failWrite", "true").build();
    open(readOnly);
    scenario.onActivity(
        activity -> {
          editor(activity).setText("Keep my unsaved changes 🌿");
          save(activity, false, null);
        });
    awaitSaved();
    onView(withText("Could not save · draft kept")).check(matches(isDisplayed()));
    assertArrayEquals(original, bytes(writable));
    scenario.onActivity(
        activity -> {
          assertTrue(current(activity).dirty());
          assertEquals("Keep my unsaved changes 🌿", editor(activity).getText().toString());
          assertEquals("Original read-only file", current(activity).savedText);
        });
    onView(withText("OK")).perform(click());
    scenario.recreate();
    awaitReady();
    scenario.onActivity(
        activity -> {
          assertEquals("Keep my unsaved changes 🌿", editor(activity).getText().toString());
          assertEquals(readOnly.toString(), current(activity).uri);
          assertTrue(current(activity).dirty());
        });
    assertArrayEquals(original, bytes(writable));
  }

  private void open(Uri uri) {
    scenario.onActivity(
        activity -> invoke(activity, "openUri", new Class<?>[] {Uri.class, int.class}, uri, 0));
    awaitActivity(
        activity -> current(activity) != null && uri.toString().equals(current(activity).uri),
        "external file to open");
  }

  private void awaitReady() {
    awaitActivity(
        activity -> !((Boolean) field(activity, "loading")) && editor(activity) != null,
        "draft recovery");
  }

  private void awaitSaved() {
    awaitActivity(activity -> saving(activity).isEmpty(), "save completion");
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

  private Uri fixture(byte[] contents) throws Exception {
    Uri uri =
        new Uri.Builder()
            .scheme("content")
            .authority(TestFileProvider.AUTHORITY)
            .appendPath(UUID.randomUUID().toString() + ".txt")
            .build();
    fixtures.add(uri);
    write(uri, contents);
    return uri;
  }

  private static void write(Uri uri, byte[] contents) throws Exception {
    try (OutputStream stream = resolver().openOutputStream(uri, "wt")) {
      assertNotNull(stream);
      stream.write(contents);
    }
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

  private static ContentResolver resolver() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
  }

  private static CodeEditor editor(MainActivity activity) {
    return activity.findViewById(R.id.editor);
  }

  private static Document current(MainActivity activity) {
    return (Document) invoke(activity, "current", new Class<?>[0]);
  }

  private static List<?> saving(MainActivity activity) {
    return (List<?>) field(activity, "saving");
  }

  private static void save(MainActivity activity, boolean saveAs, Runnable callback) {
    invoke(
        activity, "saveCurrent", new Class<?>[] {boolean.class, Runnable.class}, saveAs, callback);
  }

  private static Object invoke(
      MainActivity activity, String name, Class<?>[] types, Object... arguments) {
    try {
      Method method = MainActivity.class.getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method.invoke(activity, arguments);
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
