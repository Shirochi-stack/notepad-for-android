package com.shirochi.notepad.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Line-oriented editing commands. Input uses normalized LF line endings. */
public final class TextTransforms {
  private TextTransforms() {}

  public static final class Result {
    public final String text;
    public final int start;
    public final int end;

    public Result(String text, int start, int end) {
      this.text = text;
      this.start = start;
      this.end = end;
    }
  }

  public static Result indent(String text, int start, int end, String unit) {
    validateUnit(unit);
    Range range = range(text, start, end);
    List<Edit> edits = new ArrayList<>();
    for (int line : lineStarts(text, range)) edits.add(new Edit(line, 0, unit));
    return apply(text, start, end, edits);
  }

  /** Removes one tab or up to unit.length() spaces from each affected line. */
  public static Result unindent(String text, int start, int end, String unit) {
    validateUnit(unit);
    Range range = range(text, start, end);
    List<Edit> edits = new ArrayList<>();
    int width = "\t".equals(unit) ? 4 : unit.length();
    for (int line : lineStarts(text, range)) {
      int remove = 0;
      if (line < text.length() && text.charAt(line) == '\t') remove = 1;
      else
        while (remove < width && line + remove < text.length() && text.charAt(line + remove) == ' ')
          remove++;
      if (remove > 0) edits.add(new Edit(line, remove, ""));
    }
    return apply(text, start, end, edits);
  }

  /** Duplicates all touched lines and moves the selection into the new copy. */
  public static Result duplicateLine(String text, int start, int end) {
    Range range = range(text, start, end);
    boolean terminated = range.end < text.length();
    int insertion = terminated ? range.end + 1 : range.end;
    String block = text.substring(range.start, insertion);
    String added = terminated ? block : "\n" + block;
    int copyStart = terminated ? insertion : insertion + 1;
    String result = text.substring(0, insertion) + added + text.substring(insertion);
    return new Result(result, copyStart + start - range.start, copyStart + end - range.start);
  }

  /**
   * Adds a comment after indentation. If every nonblank selected line is already commented, removes
   * one prefix and one optional following space instead. Blank lines are left unchanged.
   */
  public static Result toggleLineComment(String text, int start, int end, String prefix) {
    Objects.requireNonNull(prefix, "prefix");
    if (prefix.isEmpty() || prefix.indexOf('\n') >= 0 || prefix.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(
          "Comment prefix must be nonempty and contain no line breaks.");
    }
    Range range = range(text, start, end);
    List<Integer> contentStarts = new ArrayList<>();
    boolean allCommented = true;
    for (int line : lineStarts(text, range)) {
      int content = line;
      while (content < text.length()
          && (text.charAt(content) == ' ' || text.charAt(content) == '\t')) content++;
      if (content == text.length() || text.charAt(content) == '\n') continue;
      contentStarts.add(content);
      if (!text.startsWith(prefix, content)) allCommented = false;
    }
    List<Edit> edits = new ArrayList<>();
    for (int content : contentStarts) {
      if (allCommented) {
        int remove = prefix.length();
        if (content + remove < text.length() && text.charAt(content + remove) == ' ') remove++;
        edits.add(new Edit(content, remove, ""));
      } else {
        edits.add(new Edit(content, 0, prefix + " "));
      }
    }
    return apply(text, start, end, edits);
  }

  private static void validateUnit(String unit) {
    Objects.requireNonNull(unit, "unit");
    if (!unit.equals("\t") && !unit.matches(" +")) {
      throw new IllegalArgumentException("Indentation must be a tab or one or more spaces.");
    }
  }

  private static Range range(String text, int start, int end) {
    Objects.requireNonNull(text, "text");
    if (start < 0 || end < 0 || start > text.length() || end > text.length()) {
      throw new IllegalArgumentException("Selection is outside the document.");
    }
    int low = Math.min(start, end);
    int high = Math.max(start, end);
    int first = low == 0 ? 0 : text.lastIndexOf('\n', low - 1) + 1;
    int lastCharacter = high > low ? high - 1 : high;
    int last = text.indexOf('\n', lastCharacter);
    if (last == -1) last = text.length();
    return new Range(first, last);
  }

  private static List<Integer> lineStarts(String text, Range range) {
    List<Integer> starts = new ArrayList<>();
    int current = range.start;
    starts.add(current);
    while (current < range.end) {
      int newline = text.indexOf('\n', current);
      if (newline < 0 || newline >= range.end) break;
      current = newline + 1;
      starts.add(current);
    }
    return starts;
  }

  private static Result apply(String text, int start, int end, List<Edit> edits) {
    StringBuilder result = new StringBuilder(text.length());
    int cursor = 0;
    for (Edit edit : edits) {
      result.append(text, cursor, edit.position).append(edit.insert);
      cursor = edit.position + edit.remove;
    }
    result.append(text, cursor, text.length());
    return new Result(result.toString(), mapPosition(start, edits), mapPosition(end, edits));
  }

  private static int mapPosition(int offset, List<Edit> edits) {
    int mapped = offset;
    for (Edit edit : edits) {
      if (offset >= edit.position) {
        mapped += edit.insert.length() - Math.min(edit.remove, offset - edit.position);
      }
    }
    return mapped;
  }

  private static final class Range {
    final int start;
    final int end;

    Range(int start, int end) {
      this.start = start;
      this.end = end;
    }
  }

  private static final class Edit {
    final int position;
    final int remove;
    final String insert;

    Edit(int position, int remove, String insert) {
      this.position = position;
      this.remove = remove;
      this.insert = insert;
    }
  }
}
