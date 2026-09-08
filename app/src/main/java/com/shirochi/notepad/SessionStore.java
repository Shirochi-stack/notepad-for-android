package com.shirochi.notepad;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Atomic, app-private draft recovery; source documents are never autosaved. */
final class SessionStore {
  private final AtomicFile file;
  private boolean writesBlocked;

  SessionStore(Context context) {
    file = new AtomicFile(new File(context.getFilesDir(), "session.json"));
  }

  static final class Session {
    final List<Document> documents = new ArrayList<>();
    int selected;
  }

  synchronized Session read() throws Exception {
    // AtomicFile must see a legacy .bak file even when session.json itself is
    // absent, which can happen if an Android 8 write was interrupted.
    writesBlocked = true;
    byte[] bytes;
    try {
      bytes = file.readFully();
    } catch (FileNotFoundException missing) {
      File base = file.getBaseFile();
      if (!base.exists() && !new File(base.getPath() + ".bak").exists()) {
        writesBlocked = false;
        return new Session();
      }
      throw new IOException(
          "The saved session could not be read. Automatic draft writes are paused to preserve it.",
          missing);
    } catch (IOException failure) {
      throw new IOException(
          "The saved session could not be read. Automatic draft writes are paused to preserve it.",
          failure);
    }
    try {
      Session recovered = parseSnapshot(bytes);
      writesBlocked = false;
      return recovered;
    } catch (Exception unreadable) {
      File backup;
      try {
        backup = preserveUnreadable(bytes);
      } catch (IOException backupFailure) {
        backupFailure.addSuppressed(unreadable);
        throw new IOException(
            "The saved session was unreadable and its recovery copy could not be written. Automatic"
                + " draft writes are paused to preserve the original.",
            backupFailure);
      }
      writesBlocked = false;
      throw new IOException(
          "The saved session was unreadable. Its original contents were preserved as "
              + backup.getName()
              + " in app-private storage.",
          unreadable);
    }
  }

  private static Session parseSnapshot(byte[] bytes) throws Exception {
    Session result = new Session();
    JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    JSONArray items = root.getJSONArray("documents");
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      Document d = new Document();
      d.id = item.getString("id");
      d.name = item.getString("name");
      d.uri = item.optString("uri");
      d.text = item.getString("text");
      d.savedText = item.optString("savedText");
      d.diskHash = item.optString("diskHash");
      d.encoding = item.optString("encoding", "UTF-8");
      d.lineEnding = item.optString("lineEnding", "LF");
      d.bom = item.optBoolean("bom");
      d.savedFormat = item.optString("savedFormat", d.format());
      d.language = item.optString("language", "Auto");
      d.start = Math.max(0, Math.min(d.text.length(), item.optInt("start")));
      d.end = Math.max(0, Math.min(d.text.length(), item.optInt("end")));
      d.scrollY = item.optInt("scrollY");
      d.history.reset(d.text, d.start, d.end);
      result.documents.add(d);
    }
    result.selected = Math.max(0, Math.min(result.documents.size() - 1, root.optInt("selected")));
    return result;
  }

  private File preserveUnreadable(byte[] bytes) throws IOException {
    File directory = file.getBaseFile().getParentFile();
    String stem = "session-recovery-" + System.currentTimeMillis();
    File backup = new File(directory, stem + ".json");
    int suffix = 1;
    while (!backup.createNewFile()) backup = new File(directory, stem + "-" + suffix++ + ".json");
    try (FileOutputStream out = new FileOutputStream(backup)) {
      out.write(bytes);
      out.flush();
      out.getFD().sync();
    }
    return backup;
  }

  static String snapshot(List<Document> documents, int selected) throws Exception {
    JSONArray items = new JSONArray();
    for (Document d : documents) {
      items.put(
          new JSONObject()
              .put("id", d.id)
              .put("name", d.name)
              .put("uri", d.uri)
              .put("text", d.text)
              .put("savedText", d.savedText)
              .put("diskHash", d.diskHash)
              .put("encoding", d.encoding)
              .put("lineEnding", d.lineEnding)
              .put("bom", d.bom)
              .put("savedFormat", d.savedFormat)
              .put("language", d.language)
              .put("start", d.start)
              .put("end", d.end)
              .put("scrollY", d.scrollY));
    }
    return new JSONObject()
        .put("version", 1)
        .put("selected", selected)
        .put("documents", items)
        .toString();
  }

  synchronized void write(String json) throws Exception {
    if (writesBlocked)
      throw new IOException(
          "Automatic draft writes are paused to preserve the unreadable saved session. Save your"
              + " documents to files.");
    FileOutputStream stream = null;
    try {
      stream = file.startWrite();
      stream.write(json.getBytes(StandardCharsets.UTF_8));
      file.finishWrite(stream);
    } catch (Exception e) {
      if (stream != null) file.failWrite(stream);
      throw e;
    }
  }
}
