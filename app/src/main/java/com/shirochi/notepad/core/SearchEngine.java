package com.shirochi.notepad.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Literal/regular-expression search using UTF-16 offsets, as Android text widgets do. */
public final class SearchEngine {
  public static final int MAX_MATCHES = 10_000;
  public static final int MAX_OUTPUT_CHARACTERS = 2 * 1024 * 1024;
  private static final long REGEX_BUDGET_NANOS = 150_000_000L;
  private static final AtomicBoolean REGEX_BUSY = new AtomicBoolean();
  // The busy gate allows only one unfinished operation, with no waiting work.
  // A one-slot handoff queue covers the worker's final return to its idle loop.
  private static final ThreadPoolExecutor REGEX_WORKER =
      new ThreadPoolExecutor(
          1,
          1,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          runnable -> {
            Thread thread = new Thread(runnable, "notepad-regex");
            thread.setDaemon(true);
            return thread;
          });

  private SearchEngine() {}

  public static final class Match {
    public final int start;
    public final int end;

    public Match(int start, int end) {
      this.start = start;
      this.end = end;
    }
  }

  /** An empty query has no matches. Zero-width regex matches advance safely. */
  public static List<Match> findAll(
      String text, String query, boolean caseSensitive, boolean wholeWord, boolean regex)
      throws PatternSyntaxException {
    return regex
        ? runRegex(() -> findAllInternal(text, query, caseSensitive, wholeWord, true))
        : findAllInternal(text, query, caseSensitive, wholeWord, false);
  }

  private static List<Match> findAllInternal(
      String text, String query, boolean caseSensitive, boolean wholeWord, boolean regex) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(query, "query");
    if (query.isEmpty()) return Collections.emptyList();
    Matcher matcher = matcher(text, query, caseSensitive, regex);
    List<Match> matches = new ArrayList<>();
    while (find(matcher)) {
      if (!wholeWord || hasWordBoundaries(text, matcher.start(), matcher.end())) {
        if (matches.size() == MAX_MATCHES) {
          throw new IllegalArgumentException(
              "More than 10,000 matches. Use a more specific search.");
        }
        matches.add(new Match(matcher.start(), matcher.end()));
      }
    }
    return matches;
  }

  /**
   * Literal mode treats both query and replacement literally. Regex mode uses Java replacement
   * syntax ($1 and ${name}); invalid group references throw instead of returning partially changed
   * text. The caller should display that error.
   */
  public static String replaceAll(
      String text,
      String query,
      String replacement,
      boolean caseSensitive,
      boolean wholeWord,
      boolean regex) {
    return regex
        ? runRegex(
            () -> replaceAllInternal(text, query, replacement, caseSensitive, wholeWord, true))
        : replaceAllInternal(text, query, replacement, caseSensitive, wholeWord, false);
  }

  private static String replaceAllInternal(
      String text,
      String query,
      String replacement,
      boolean caseSensitive,
      boolean wholeWord,
      boolean regex) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(replacement, "replacement");
    if (query.isEmpty()) return text;
    Matcher matcher = matcher(text, query, caseSensitive, regex);
    StringBuilder result = new StringBuilder(Math.min(text.length(), MAX_OUTPUT_CHARACTERS));
    int appended = 0;
    while (find(matcher)) {
      if (!wholeWord || hasWordBoundaries(text, matcher.start(), matcher.end())) {
        appendBounded(result, text, appended, matcher.start());
        appendReplacement(matcher, text, result, replacement, regex);
        appended = matcher.end();
      }
    }
    appendBounded(result, text, appended, text.length());
    return result.toString();
  }

  /** Replace one current search result while preserving regex capture expansion. */
  public static String replaceOne(
      String text,
      String query,
      String replacement,
      boolean caseSensitive,
      boolean wholeWord,
      boolean regex,
      int matchStart,
      int matchEnd) {
    return regex
        ? runRegex(
            () ->
                replaceOneInternal(
                    text, query, replacement, caseSensitive, wholeWord, true, matchStart, matchEnd))
        : replaceOneInternal(
            text, query, replacement, caseSensitive, wholeWord, false, matchStart, matchEnd);
  }

  private static String replaceOneInternal(
      String text,
      String query,
      String replacement,
      boolean caseSensitive,
      boolean wholeWord,
      boolean regex,
      int matchStart,
      int matchEnd) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(replacement, "replacement");
    if (query.isEmpty()) throw new IllegalArgumentException("Enter a search query first.");
    Matcher matcher = matcher(text, query, caseSensitive, regex);
    while (find(matcher)) {
      if (matcher.start() == matchStart
          && matcher.end() == matchEnd
          && (!wholeWord || hasWordBoundaries(text, matchStart, matchEnd))) {
        StringBuilder result = new StringBuilder(Math.min(text.length(), MAX_OUTPUT_CHARACTERS));
        appendBounded(result, text, 0, matcher.start());
        appendReplacement(matcher, text, result, replacement, regex);
        appendBounded(result, text, matcher.end(), text.length());
        return result.toString();
      }
    }
    throw new IllegalArgumentException(
        "The selection is no longer an exact search result. Find the text again.");
  }

  private static Matcher matcher(String text, String query, boolean caseSensitive, boolean regex) {
    try {
      Pattern pattern =
          Pattern.compile(
              regex ? query : Pattern.quote(query),
              caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      return pattern.matcher(regex ? new TimedSequence(text) : text);
    } catch (StackOverflowError error) {
      throw complexPattern();
    }
  }

  private static boolean find(Matcher matcher) {
    try {
      return matcher.find();
    } catch (StackOverflowError error) {
      throw complexPattern();
    }
  }

  private static IllegalArgumentException complexPattern() {
    return new IllegalArgumentException("Pattern is too complex. Simplify the regular expression.");
  }

  /** Expand each capture directly into the bounded output, never a huge temporary string. */
  private static void appendReplacement(
      Matcher matcher, String text, StringBuilder result, String replacement, boolean regex) {
    if (!regex) {
      appendBounded(result, replacement, 0, replacement.length());
      return;
    }
    int cursor = 0;
    while (cursor < replacement.length()) {
      char value = replacement.charAt(cursor++);
      if (value == '\\') {
        if (cursor == replacement.length()) {
          throw new IllegalArgumentException("Replacement ends with an unescaped backslash.");
        }
        appendBounded(result, replacement, cursor, cursor + 1);
        cursor++;
      } else if (value == '$') {
        if (cursor == replacement.length())
          throw new IllegalArgumentException("Replacement has an incomplete capture reference.");
        int start;
        int end;
        if (replacement.charAt(cursor) == '{') {
          int closing = replacement.indexOf('}', cursor + 1);
          if (closing < 0)
            throw new IllegalArgumentException(
                "Replacement capture name is missing its closing brace.");
          String name = replacement.substring(cursor + 1, closing);
          start = matcher.start(name);
          end = matcher.end(name);
          cursor = closing + 1;
        } else {
          int group = replacement.charAt(cursor++) - '0';
          if (group < 0 || group > 9 || group > matcher.groupCount()) {
            throw new IllegalArgumentException(
                "Replacement refers to a capture group that does not exist.");
          }
          while (cursor < replacement.length()) {
            int digit = replacement.charAt(cursor) - '0';
            if (digit < 0 || digit > 9) break;
            long candidate = (long) group * 10 + digit;
            if (candidate > matcher.groupCount()) break;
            group = (int) candidate;
            cursor++;
          }
          start = matcher.start(group);
          end = matcher.end(group);
        }
        if (start >= 0) appendBounded(result, text, start, end);
      } else {
        int start = cursor - 1;
        while (cursor < replacement.length()
            && replacement.charAt(cursor) != '\\'
            && replacement.charAt(cursor) != '$') cursor++;
        appendBounded(result, replacement, start, cursor);
      }
    }
  }

  private static void appendBounded(StringBuilder result, String text, int start, int end) {
    if ((long) result.length() + end - start > MAX_OUTPUT_CHARACTERS) {
      throw new IllegalArgumentException(
          "Replacement exceeds the 2 million character limit. Use a shorter replacement or fewer"
              + " matches.");
    }
    result.append(text, start, end);
  }

  /**
   * Android's native ICU matcher copies CharSequence, bypassing charAt checks. Keep matching on one
   * daemon worker and bound the caller's wait to 200 ms. Timed-out native work may finish later;
   * until then new regex work is refused instead of accumulating threads or queued work. Literal
   * search stays usable.
   */
  private static <T> T runRegex(Callable<T> operation) {
    if (!REGEX_BUSY.compareAndSet(false, true)) {
      throw new IllegalArgumentException(
          "Regular expression search is still busy. Simplify the pattern or use literal search.");
    }
    RegexJob<T> job = new RegexJob<>(operation);
    try {
      REGEX_WORKER.execute(job);
    } catch (RuntimeException error) {
      REGEX_BUSY.set(false);
      throw error;
    }
    try {
      return job.result.get(200, TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      job.cancel();
      throw complexPattern();
    } catch (InterruptedException error) {
      job.cancel();
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("Search was interrupted. Try again.", error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new IllegalArgumentException("Search could not finish.", cause);
    }
  }

  private static final class RegexJob<T> implements Runnable {
    final CompletableFuture<T> result = new CompletableFuture<>();
    final Callable<T> operation;
    volatile Thread executing;
    volatile boolean cancelled;

    RegexJob(Callable<T> operation) {
      this.operation = operation;
    }

    @Override
    public void run() {
      synchronized (this) {
        executing = Thread.currentThread();
      }
      T value = null;
      Throwable failure = null;
      try {
        if (!cancelled) value = operation.call();
      } catch (Throwable error) {
        failure = error;
      } finally {
        synchronized (this) {
          executing = null;
          Thread.interrupted();
        }
        REGEX_BUSY.set(false);
      }
      if (failure == null) result.complete(value);
      else result.completeExceptionally(failure);
    }

    synchronized void cancel() {
      cancelled = true;
      Thread thread = executing;
      if (thread != null) thread.interrupt();
    }
  }

  private static boolean hasWordBoundaries(String text, int start, int end) {
    return (start == 0 || !isWordCodePoint(text.codePointBefore(start)))
        && (end == text.length() || !isWordCodePoint(text.codePointAt(end)));
  }

  private static boolean isWordCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isLetterOrDigit(codePoint)
        || type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK
        || type == Character.CONNECTOR_PUNCTUATION;
  }

  /** Checks during backtracking, rather than only after Matcher.find returns. */
  private static final class TimedSequence implements CharSequence {
    private final String text;
    private final long deadline;
    private int accesses;

    TimedSequence(String text) {
      this(text, System.nanoTime() + REGEX_BUDGET_NANOS);
    }

    TimedSequence(String text, long deadline) {
      this.text = text;
      this.deadline = deadline;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      if ((++accesses & 0xff) == 0
          && (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)) {
        throw complexPattern();
      }
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new TimedSequence(text.substring(start, end), deadline);
    }

    @Override
    public String toString() {
      return text;
    }
  }
}
