package com.shirochi.notepad;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android AtomicFile regressions, isolated from the activity's draft directory. */
@RunWith(AndroidJUnit4.class)
public final class SessionStoreTest {
  private File directory;
  private SessionStore store;

  @Before
  public void createIsolatedStore() {
    Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    directory = new File(target.getCacheDir(), "session-test-" + UUID.randomUUID());
    assertTrue(directory.mkdir());
    store =
        new SessionStore(
            new ContextWrapper(target) {
              @Override
              public File getFilesDir() {
                return directory;
              }
            });
  }

  @After
  public void removeFixtures() {
    if (directory == null) return;
    File[] files = directory.listFiles();
    if (files != null) for (File file : files) assertTrue(file.delete());
    assertTrue(directory.delete());
  }

  @Test
  public void corruptedSessionIsPreservedBeforeFreshDraftWrite() throws Exception {
    byte[] original = new byte[] {'{', '"', 'd', 'o', 'c', (byte) 0xff, '"', ':', '['};
    File base = new File(directory, "session.json");
    Files.write(base.toPath(), original);
    try {
      store.read();
      fail("Corrupted JSON should surface a recovery error.");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("session-recovery-"));
    }
    File[] backups =
        directory.listFiles(
            (parent, name) -> name.startsWith("session-recovery-") && name.endsWith(".json"));
    assertNotNull(backups);
    assertEquals(1, backups.length);
    assertArrayEquals(original, Files.readAllBytes(backups[0].toPath()));
    assertArrayEquals(original, Files.readAllBytes(base.toPath()));
    Document fresh = new Document();
    fresh.text = "Fresh draft after recovery";
    store.write(SessionStore.snapshot(Collections.singletonList(fresh), 0));
    assertEquals(fresh.text, store.read().documents.get(0).text);
    assertArrayEquals(
        "A new draft must not overwrite the recovery copy",
        original,
        Files.readAllBytes(backups[0].toPath()));
  }

  @Test
  public void reversedSelectionEndpointsSurviveRoundTripAndHistoryReset() throws Exception {
    Document original = new Document();
    original.text = "0123456789";
    original.start = 8;
    original.end = 2;
    original.scrollY = 140;
    store.write(SessionStore.snapshot(Collections.singletonList(original), 0));
    Document restored = store.read().documents.get(0);
    assertEquals(8, restored.start);
    assertEquals(2, restored.end);
    assertEquals(140, restored.scrollY);
    assertEquals(8, restored.history.current().start);
    assertEquals(2, restored.history.current().end);
    assertEquals(original.text, restored.text);
  }

  @Test
  public void interruptedLegacyWriteRecoversBackupWhenBaseIsMissing() throws Exception {
    Document original = new Document();
    original.text = "Draft from interrupted legacy write 🌿";
    byte[] snapshot =
        SessionStore.snapshot(Collections.singletonList(original), 0)
            .getBytes(StandardCharsets.UTF_8);
    File base = new File(directory, "session.json");
    File backup = new File(directory, "session.json.bak");
    Files.write(backup.toPath(), snapshot);
    assertFalse(base.exists());
    SessionStore.Session restored = store.read();
    assertEquals(1, restored.documents.size());
    assertEquals(original.text, restored.documents.get(0).text);
    assertArrayEquals(snapshot, Files.readAllBytes(base.toPath()));
    assertFalse(backup.exists());
  }
}
