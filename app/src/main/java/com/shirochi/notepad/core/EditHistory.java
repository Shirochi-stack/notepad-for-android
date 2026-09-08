package com.shirochi.notepad.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded full-document history; each tab owns a separate instance. */
public final class EditHistory {
  public static final class Snapshot {
    public final String text;
    public final int start;
    public final int end;

    public Snapshot(String text, int start, int end) {
      this.text = Objects.requireNonNull(text, "text");
      this.start = Math.max(0, Math.min(start, text.length()));
      this.end = Math.max(0, Math.min(end, text.length()));
    }
  }

  private final int maxEntries;
  private final long maxCharacters;
  private final List<Snapshot> entries = new ArrayList<>();
  private int position = -1;
  private long characters;

  public EditHistory(int maxEntries) {
    this(maxEntries, 8_000_000L);
  }

  /** Keeps the current snapshot even when it alone exceeds the character budget. */
  public EditHistory(int maxEntries, long maxCharacters) {
    if (maxEntries < 2) throw new IllegalArgumentException("History needs at least two entries.");
    if (maxCharacters < 1) throw new IllegalArgumentException("Character budget must be positive.");
    this.maxEntries = maxEntries;
    this.maxCharacters = maxCharacters;
  }

  public void reset(String text, int start, int end) {
    entries.clear();
    entries.add(new Snapshot(text, start, end));
    position = 0;
    characters = text.length();
  }

  /**
   * Call after a text edit. Recording unchanged text updates selection without creating a spurious
   * undo step or discarding redo. Call updateSelection before an edit when the cursor has moved
   * since the previous text snapshot.
   */
  public void record(String text, int start, int end) {
    Snapshot next = new Snapshot(text, start, end);
    if (position >= 0 && entries.get(position).text.equals(text)) {
      entries.set(position, next);
      return;
    }
    while (entries.size() > position + 1) {
      characters -= entries.remove(entries.size() - 1).text.length();
    }
    entries.add(next);
    position++;
    characters += text.length();
    while (entries.size() > 1 && (entries.size() > maxEntries || characters > maxCharacters)) {
      characters -= entries.remove(0).text.length();
      position--;
    }
  }

  public void updateSelection(int start, int end) {
    if (position >= 0) {
      entries.set(position, new Snapshot(entries.get(position).text, start, end));
    }
  }

  public Snapshot undo() {
    if (!canUndo()) return null;
    return entries.get(--position);
  }

  public Snapshot redo() {
    if (!canRedo()) return null;
    return entries.get(++position);
  }

  public Snapshot current() {
    return position < 0 ? null : entries.get(position);
  }

  public boolean canUndo() {
    return position > 0;
  }

  public boolean canRedo() {
    return position >= 0 && position + 1 < entries.size();
  }
}
