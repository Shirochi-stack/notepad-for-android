package com.shirochi.notepad;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Extracts file attachments without interpreting accompanying captions as file contents. */
final class IncomingFiles {
  private IncomingFiles() {}

  static List<Uri> collect(Intent intent, int limit) {
    LinkedHashSet<Uri> uris = new LinkedHashSet<>();
    add(uris, intent.getData(), limit);
    Bundle extras = intent.getExtras();
    Object stream = extras == null ? null : extras.get(Intent.EXTRA_STREAM);
    if (stream instanceof Uri) add(uris, (Uri) stream, limit);
    else if (stream instanceof Iterable<?>) {
      for (Object item : (Iterable<?>) stream) {
        if (item instanceof Uri) add(uris, (Uri) item, limit);
        if (uris.size() >= limit) break;
      }
    }
    ClipData clips = intent.getClipData();
    if (clips != null) {
      for (int i = 0; i < clips.getItemCount() && uris.size() < limit; i++) {
        add(uris, clips.getItemAt(i).getUri(), limit);
      }
    }
    return new ArrayList<>(uris);
  }

  static CharSequence sharedText(Intent intent) {
    CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    if (text != null) return text;
    String html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT);
    if (html != null) return html;
    ClipData clips = intent.getClipData();
    return clips != null && clips.getItemCount() > 0 ? clips.getItemAt(0).getText() : null;
  }

  static boolean isLocal(Uri uri) {
    return uri != null && ("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()));
  }

  private static void add(LinkedHashSet<Uri> uris, Uri uri, int limit) {
    if (uri != null && uris.size() < limit) uris.add(uri);
  }
}
