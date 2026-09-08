package com.shirochi.notepad;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Real provider-backed files and deterministic write failures, test APK only. */
public final class TestFileProvider extends ContentProvider {
  public static final String AUTHORITY = "com.shirochi.notepad.test.files";

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public String getType(Uri uri) {
    return "text/plain";
  }

  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    File file = file(uri);
    String[] columns =
        projection == null
            ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
            : projection;
    MatrixCursor result = new MatrixCursor(columns);
    Object[] values = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
      else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
    }
    result.addRow(values);
    return result;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (mode.indexOf('w') >= 0 && uri.getBooleanQueryParameter("failWrite", false)) {
      throw new FileNotFoundException("Test provider denied write access.");
    }
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    return file(uri).delete() ? 1 : 0;
  }

  private File file(Uri uri) {
    String name = uri.getLastPathSegment();
    if (name == null || !name.matches("[a-zA-Z0-9_-]+\\.txt")) {
      throw new IllegalArgumentException("Invalid fixture file name.");
    }
    File directory = new File(getContext().getCacheDir(), "file-operation-fixtures");
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IllegalStateException("Cannot create fixture directory.");
    }
    return new File(directory, name);
  }
}
