package com.shirochi.notepad.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import com.shirochi.notepad.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dependency-free, accessible text editor. Text stays in the native EditText so selection, the
 * Android keyboard, clipboard and hardware keyboards work normally. Decorations are spans and
 * drawing only; they never replace the document text.
 */
public class CodeEditor extends EditText {
  public static final int DEFAULT_FONT_SIZE_SP = 14;

  public interface SelectionListener {
    void onSelectionChanged(int start, int end);
  }

  public interface UndoRedoListener {
    void onUndoRedo(boolean redo);
  }

  // Bound decoration work without restricting how much text the user can edit.
  private static final int MAX_HIGHLIGHT_CHARACTERS = 180_000;
  private static final int MAX_SYNTAX_SPANS = 12_000;
  private static final int MAX_SEARCH_SPANS = 6_000;
  private static final long HIGHLIGHT_DELAY_MS = 180L;

  private static final String NUMBER =
      "\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b";
  // Possessive repetition prevents Java's regex engine from growing its stack
  // once per character when a document contains an unusually long string.
  private static final String QUOTED =
      "\"(?:\\\\[\\s\\S]|[^\"\\\\\\n"
          + "])*+(?:\"|(?=\\n"
          + ")|\\z)|'(?:\\\\[\\s\\S]|[^'\\\\\\n"
          + "])*+(?:'|(?=\\n"
          + ")|\\z)";
  private static final String CODE_KEYWORDS =
      "\\b(?:abstract|as|assert|async|await|boolean|break|byte|case|catch|char|class|const|constructor|continue|data|debugger|default|delete|do|double|else|enum|export|extends|false|final|finally|float|for|from|fun|function|get|if|implements|import|in|instanceof|int|interface|internal|is|let|long|native|new|null|object|of|open|override|package|private|protected|public|return|sealed|set|short|static|super|switch|synchronized|this|throw|throws|transient|true|try|type|typeof|undefined|val|var|void|volatile|when|while|with|yield)\\b";
  private static final String PYTHON_KEYWORDS =
      "\\b(?:False|None|True|and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield)\\b";

  // Capture groups always represent: comment, string, keyword, number.
  private static final Pattern CODE_PATTERN =
      Pattern.compile(
          "(//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|\\z))|("
              + QUOTED
              + "|`(?:\\\\[\\s\\S]|[^`\\\\])*+(?:`|\\z))|("
              + CODE_KEYWORDS
              + ")|("
              + NUMBER
              + ")");
  private static final Pattern PYTHON_PATTERN =
      Pattern.compile(
          "(#[^\\n]*)|('''[\\s\\S]*?(?:'''|\\z)|\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|"
              + QUOTED
              + ")|("
              + PYTHON_KEYWORDS
              + ")|("
              + NUMBER
              + ")");
  private static final Pattern JSON_PATTERN =
      Pattern.compile("(?!x)(x)|(" + QUOTED + ")|(\\b(?:true|false|null)\\b)|(" + NUMBER + ")");
  private static final Pattern HTML_PATTERN =
      Pattern.compile(
          "(<!--[\\s\\S]*?(?:-->|\\z))|("
              + QUOTED
              + ")|(</?[A-Za-z][\\w:.-]*|/?>)|(&[A-Za-z0-9#]+;)");
  private static final Pattern CSS_PATTERN =
      Pattern.compile(
          "(/\\*[\\s\\S]*?(?:\\*/|\\z))|("
              + QUOTED
              + ")|((?:^|(?<=[;{]))\\s*[\\w-]++(?=\\s*:)|@[\\w-]++)|("
              + NUMBER
              + "|#[0-9a-fA-F]{3,8}\\b)");
  private static final Pattern MARKDOWN_PATTERN =
      Pattern.compile(
          "(<!--[\\s\\S]*?(?:-->|\\z))|(```[\\s\\S]*?(?:```|\\z)|`[^`\\n"
              + "]+`)|(^#{1,6}[^\\n"
              + "]*|\\*\\*[^\\n"
              + "]+?\\*\\*)|(\\[[^\\[\\]\\n"
              + "]*+\\]\\([^\\n"
              + ")\\[]*+\\))",
          Pattern.MULTILINE);
  private static final Pattern SQL_PATTERN =
      Pattern.compile(
          "(--[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|\\z))|("
              + QUOTED
              + ")|(\\b(?:add|all|alter|and|as|asc|begin|between|by|case|check|column|commit|constraint|create|cross|database|default|delete|desc|distinct|drop|else|end|except|exists|explain|foreign|from|full|group|having|in|index|inner|insert|intersect|into|is|join|key|left|like|limit|not|null|offset|on|or|order|outer|primary|references|replace|returning|right|rollback|select|set|table|then|transaction|trigger|truncate|union|unique|update|using|values|view|when|where|with)\\b)|("
              + NUMBER
              + "|\\b(?:true|false)\\b)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern SHELL_PATTERN =
      Pattern.compile(
          "(#[^\\n]*)|("
              + QUOTED
              + "|`(?:\\\\[\\s\\S]|[^`\\\\])*+(?:`|\\z))"
              + "|(\\b(?:if|then|else|elif|fi|for|while|do|done|case|esac|in|function|select|until|time|coproc|local|export|readonly|return|exit|break|continue|true|false)\\b)"
              + "|("
              + NUMBER
              + "|\\$\\{[^{}\\n]*+(?:\\}|\\z)|\\$[A-Za-z_][A-Za-z0-9_]*+|\\$[0-9@#?$!*_-])");
  private static final Pattern YAML_PATTERN =
      Pattern.compile(
          "(#[^\\n]*)|("
              + QUOTED
              + ")|(^[\\t ]*(?:-[\\t ]*)?[A-Za-z0-9_.-]++(?=[\\t ]*:)|[&*][A-Za-z_][\\w-]*+)"
              + "|("
              + NUMBER
              + "|\\b(?:true|false|null|yes|no|on|off)\\b)",
          Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

  private Paint gutterPaint;
  private Paint numberPaint;
  private Paint currentLinePaint;
  private Paint dividerPaint;
  private int[] logicalLineStarts = {0};
  private int gutterWidth;
  private int gutterDigits;
  private boolean showLineNumbers = true;
  private boolean wordWrap = true;
  private boolean darkTheme = true;
  private String language = "plain";
  private SelectionListener selectionListener;
  private UndoRedoListener undoRedoListener;
  private List<int[]> searchRanges = Collections.emptyList();
  private int activeSearchIndex = -1;
  private int keywordColor;
  private int stringColor;
  private int commentColor;
  private int numberColor;
  private int searchColor;
  private int activeSearchColor;

  private final Runnable highlightRunnable = this::applySyntaxHighlighting;

  public CodeEditor(Context context) {
    this(context, null);
  }

  public CodeEditor(Context context, AttributeSet attrs) {
    // A zero defStyleAttr lets our native scrollbar resources take effect even when the host
    // theme defines editTextStyle. The default style still inherits the platform EditText widget.
    this(context, attrs, 0);
  }

  public CodeEditor(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr, R.style.Widget_Notepad_CodeEditor);
    initialize();
  }

  private void initialize() {
    gutterPaint = new Paint();
    numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    numberPaint.setTypeface(Typeface.MONOSPACE);
    numberPaint.setTextAlign(Paint.Align.RIGHT);
    currentLinePaint = new Paint();
    dividerPaint = new Paint();
    dividerPaint.setStrokeWidth(dp(1));

    setTypeface(Typeface.MONOSPACE);
    setTextSize(TypedValue.COMPLEX_UNIT_SP, DEFAULT_FONT_SIZE_SP);
    setGravity(Gravity.TOP | Gravity.START);
    setTextDirection(TEXT_DIRECTION_LTR);
    setLayoutDirection(LAYOUT_DIRECTION_LTR);
    setSingleLine(false);
    setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    setTypeface(Typeface.MONOSPACE);
    // Some keyboards otherwise send a fullscreen editing UI in landscape.
    setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
    setIncludeFontPadding(false);
    setLineSpacing(dp(4), 1f);
    setHorizontallyScrolling(false);
    setHorizontalScrollBarEnabled(false);
    setVerticalScrollBarEnabled(true);
    setScrollbarFadingEnabled(false);
    setScrollBarSize(dp(8));
    setScrollBarStyle(SCROLLBARS_INSIDE_INSET);
    setVerticalScrollbarPosition(SCROLLBAR_POSITION_RIGHT);
    setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
    setBackground(null);
    setHint("Start typing…");
    setEditorTheme(true);
    rebuildLineIndex(getText());
    updateGutterWidth(true);

    addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            updateLineIndex(s, start, before, count);
            updateGutterWidth(false);
          }

          @Override
          public void afterTextChanged(Editable text) {
            // Stored results belong to the old text. The activity can supply new
            // search ranges from its own TextWatcher after this callback.
            clearSearchSpans(text);
            searchRanges = Collections.emptyList();
            activeSearchIndex = -1;
            scheduleHighlighting();
            invalidate();
          }
        });
  }

  public void setSelectionListener(SelectionListener listener) {
    selectionListener = listener;
  }

  /** Route native context-menu actions through the same per-document history. */
  public void setUndoRedoListener(UndoRedoListener listener) {
    undoRedoListener = listener;
  }

  @Override
  public boolean onTextContextMenuItem(int id) {
    if (undoRedoListener != null && (id == android.R.id.undo || id == android.R.id.redo)) {
      undoRedoListener.onUndoRedo(id == android.R.id.redo);
      return true;
    }
    return super.onTextContextMenuItem(id);
  }

  @Override
  protected void onSelectionChanged(int selStart, int selEnd) {
    super.onSelectionChanged(selStart, selEnd);
    invalidate();
    if (selectionListener != null) {
      selectionListener.onSelectionChanged(selStart, selEnd);
    }
  }

  public void setWordWrap(boolean enabled) {
    wordWrap = enabled;
    setHorizontallyScrolling(!enabled);
    setHorizontalScrollBarEnabled(!enabled);
    if (enabled) scrollTo(0, getScrollY());
    requestLayout();
    invalidate();
  }

  public boolean isWordWrap() {
    return wordWrap;
  }

  public void setShowLineNumbers(boolean show) {
    showLineNumbers = show;
    updateGutterWidth(true);
  }

  public boolean isShowingLineNumbers() {
    return showLineNumbers;
  }

  public void setEditorFontSize(float sizeSp) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(8f, Math.min(40f, sizeSp)));
  }

  @Override
  public void setTextSize(int unit, float size) {
    super.setTextSize(unit, size);
    if (numberPaint != null) updateGutterWidth(true);
  }

  /** Accepts a language name, extension (with or without a dot), or filename. */
  public void setLanguage(String value) {
    String normalized = value == null ? "plain" : value.toLowerCase(Locale.ROOT).trim();
    int dot = normalized.lastIndexOf('.');
    if (dot >= 0) normalized = normalized.substring(dot + 1);
    switch (normalized) {
      case "js":
      case "jsx":
      case "javascript":
      case "ts":
      case "tsx":
      case "typescript":
      case "java":
      case "kt":
      case "kts":
      case "kotlin":
      case "c":
      case "h":
      case "cpp":
      case "hpp":
      case "cc":
      case "cxx":
      case "cs":
      case "c#":
      case "go":
      case "rs":
      case "swift":
      case "dart":
        language = "code";
        break;
      case "py":
      case "pyw":
      case "python":
        language = "python";
        break;
      case "json":
      case "jsonc":
        language = "json";
        break;
      case "html":
      case "htm":
      case "xhtml":
      case "xht":
      case "xml":
      case "svg":
        language = "html";
        break;
      case "css":
      case "scss":
      case "sass":
      case "less":
        language = "css";
        break;
      case "md":
      case "markdown":
      case "mdx":
        language = "markdown";
        break;
      case "sql":
        language = "sql";
        break;
      case "sh":
      case "shell":
      case "bash":
      case "zsh":
      case "bashrc":
      case "zshrc":
        language = "shell";
        break;
      case "yaml":
      case "yml":
        language = "yaml";
        break;
      default:
        language = "plain";
    }
    scheduleHighlighting();
  }

  public void setEditorTheme(boolean dark) {
    darkTheme = dark;
    setBackgroundColor(Color.parseColor(dark ? "#141B29" : "#FFFFFF"));
    setTextColor(Color.parseColor(dark ? "#DEE6F3" : "#233047"));
    setHintTextColor(Color.parseColor(dark ? "#65758E" : "#8491A4"));
    setHighlightColor(Color.parseColor(dark ? "#526B9EBF" : "#6B9DDCFF"));
    gutterPaint.setColor(Color.parseColor(dark ? "#111826" : "#F3F6FA"));
    numberPaint.setColor(Color.parseColor(dark ? "#697C99" : "#8A96AA"));
    currentLinePaint.setColor(Color.parseColor(dark ? "#202C41" : "#EDF4FF"));
    dividerPaint.setColor(Color.parseColor(dark ? "#28364D" : "#DCE3ED"));
    keywordColor = Color.parseColor(dark ? "#B79CFF" : "#7043BD");
    stringColor = Color.parseColor(dark ? "#8EDDB6" : "#187A54");
    commentColor = Color.parseColor(dark ? "#7C91AA" : "#758399");
    numberColor = Color.parseColor(dark ? "#F2C68C" : "#A76511");
    searchColor = Color.parseColor(dark ? "#67552A" : "#FFE19A");
    activeSearchColor = Color.parseColor(dark ? "#9C6126" : "#FFBC55");
    if (Build.VERSION.SDK_INT >= 29) {
      GradientDrawable cursor = new GradientDrawable();
      cursor.setColor(Color.parseColor(dark ? "#7CA9FF" : "#2865D6"));
      cursor.setSize(dp(2), Math.max(dp(18), (int) getTextSize()));
      setTextCursorDrawable(cursor);
      int thumb = Color.parseColor(dark ? "#A0B8D9" : "#506682");
      int track = Color.parseColor(dark ? "#28364D" : "#E2E8F0");
      setVerticalScrollbarThumbDrawable(scrollbarDrawable(thumb));
      setHorizontalScrollbarThumbDrawable(scrollbarDrawable(thumb));
      setVerticalScrollbarTrackDrawable(scrollbarDrawable(track));
      setHorizontalScrollbarTrackDrawable(scrollbarDrawable(track));
    }
    applySearchSpans();
    scheduleHighlighting();
    invalidate();
  }

  private GradientDrawable scrollbarDrawable(int color) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(4));
    drawable.setSize(dp(8), dp(8));
    return drawable;
  }

  public boolean isDarkTheme() {
    return darkTheme;
  }

  /**
   * Ranges are UTF-16 [start, end) pairs, matching native EditText offsets. The active match
   * remains highlighted even after the decorative span limit.
   */
  public void setSearchMatches(List<int[]> ranges, int activeIndex) {
    ArrayList<int[]> safeRanges = new ArrayList<>();
    int safeActive = -1;
    int length = length();
    if (ranges != null) {
      int decorativeCount = Math.min(ranges.size(), MAX_SEARCH_SPANS);
      for (int i = 0; i < decorativeCount; i++) {
        int[] range = ranges.get(i);
        if (range == null
            || range.length < 2
            || range[0] < 0
            || range[1] > length
            || range[0] >= range[1]) continue;
        if (i == activeIndex) safeActive = safeRanges.size();
        safeRanges.add(new int[] {range[0], range[1]});
      }
      if (activeIndex >= decorativeCount && activeIndex < ranges.size()) {
        int[] active = ranges.get(activeIndex);
        if (active != null
            && active.length >= 2
            && active[0] >= 0
            && active[1] <= length
            && active[0] < active[1]) {
          safeActive = safeRanges.size();
          safeRanges.add(new int[] {active[0], active[1]});
        }
      }
    }
    searchRanges = safeRanges;
    activeSearchIndex = safeActive;
    applySearchSpans();
  }

  public int getLogicalLineCount() {
    return logicalLineStarts.length;
  }

  /** Returns a one-based logical line number for an offset. */
  public int getLogicalLineForOffset(int offset) {
    return Math.max(1, upperBound(logicalLineStarts, Math.max(0, Math.min(length(), offset))));
  }

  /** Returns a clamped offset at the start of a one-based logical line. */
  public int getOffsetForLogicalLine(int oneBasedLine) {
    return logicalLineStarts[Math.max(0, Math.min(logicalLineStarts.length - 1, oneBasedLine - 1))];
  }

  private void updateGutterWidth(boolean force) {
    if (numberPaint == null) return;
    int digits = Math.max(2, Integer.toString(logicalLineStarts.length).length());
    if (!force && digits == gutterDigits) return;
    gutterDigits = digits;
    numberPaint.setTextSize(getTextSize() * 0.85f);
    gutterWidth =
        showLineNumbers ? (int) Math.ceil(numberPaint.measureText("0") * digits) + dp(22) : 0;
    setPadding(gutterWidth + dp(12), dp(14), dp(16), dp(28));
    requestLayout();
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    Layout layout = getLayout();
    int scrollX = getScrollX();
    int scrollY = getScrollY();
    int paddingTop = getTotalPaddingTop();
    if (layout != null && getSelectionStart() >= 0) {
      int selectedLine = layout.getLineForOffset(Math.min(length(), getSelectionStart()));
      canvas.drawRect(
          scrollX + gutterWidth,
          layout.getLineTop(selectedLine) + paddingTop,
          scrollX + getWidth(),
          layout.getLineBottom(selectedLine) + paddingTop,
          currentLinePaint);
    }

    // Horizontal scrolling must not draw text through the fixed gutter.
    int saved = canvas.save();
    canvas.clipRect(scrollX + gutterWidth, scrollY, scrollX + getWidth(), scrollY + getHeight());
    super.onDraw(canvas);
    canvas.restoreToCount(saved);

    if (showLineNumbers && layout != null) {
      canvas.drawRect(scrollX, scrollY, scrollX + gutterWidth, scrollY + getHeight(), gutterPaint);
      canvas.drawLine(
          scrollX + gutterWidth - dp(1),
          scrollY,
          scrollX + gutterWidth - dp(1),
          scrollY + getHeight(),
          dividerPaint);

      int firstDisplayLine = layout.getLineForVertical(Math.max(0, scrollY - paddingTop));
      int lastDisplayLine =
          layout.getLineForVertical(Math.max(0, scrollY + getHeight() - paddingTop));
      int logicalIndex =
          Math.max(0, upperBound(logicalLineStarts, layout.getLineStart(firstDisplayLine)) - 1);
      for (int displayLine = firstDisplayLine; displayLine <= lastDisplayLine; displayLine++) {
        int start = layout.getLineStart(displayLine);
        while (logicalIndex + 1 < logicalLineStarts.length
            && logicalLineStarts[logicalIndex + 1] <= start) {
          logicalIndex++;
        }
        // Continuation rows of a wrapped line deliberately have no number.
        if (logicalLineStarts[logicalIndex] == start) {
          canvas.drawText(
              Integer.toString(logicalIndex + 1),
              scrollX + gutterWidth - dp(9),
              paddingTop + layout.getLineBaseline(displayLine),
              numberPaint);
        }
      }
    }
  }

  private void rebuildLineIndex(CharSequence text) {
    int count = 1;
    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
    logicalLineStarts = new int[count];
    int next = 1;
    for (int i = 0; i < text.length(); i++)
      if (text.charAt(i) == '\n') logicalLineStarts[next++] = i + 1;
  }

  private void updateLineIndex(CharSequence text, int start, int before, int count) {
    int kept = upperBound(logicalLineStarts, start);
    int trailing = upperBound(logicalLineStarts, start + before);
    int insertedLines = 0;
    for (int i = start; i < start + count; i++) if (text.charAt(i) == '\n') insertedLines++;
    int[] updated = new int[kept + insertedLines + logicalLineStarts.length - trailing];
    System.arraycopy(logicalLineStarts, 0, updated, 0, kept);
    int next = kept;
    for (int i = start; i < start + count; i++) if (text.charAt(i) == '\n') updated[next++] = i + 1;
    int delta = count - before;
    for (int i = trailing; i < logicalLineStarts.length; i++)
      updated[next++] = logicalLineStarts[i] + delta;
    logicalLineStarts = updated;
  }

  private static int upperBound(int[] values, int value) {
    int low = 0;
    int high = values.length;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (values[middle] <= value) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  private void scheduleHighlighting() {
    removeCallbacks(highlightRunnable);
    postDelayed(highlightRunnable, HIGHLIGHT_DELAY_MS);
  }

  private void applySyntaxHighlighting() {
    Editable text = getText();
    if (text == null) return;
    for (SyntaxColorSpan span : text.getSpans(0, text.length(), SyntaxColorSpan.class)) {
      text.removeSpan(span);
    }
    Pattern pattern;
    switch (language) {
      case "code":
        pattern = CODE_PATTERN;
        break;
      case "python":
        pattern = PYTHON_PATTERN;
        break;
      case "json":
        pattern = JSON_PATTERN;
        break;
      case "html":
        pattern = HTML_PATTERN;
        break;
      case "css":
        pattern = CSS_PATTERN;
        break;
      case "markdown":
        pattern = MARKDOWN_PATTERN;
        break;
      case "sql":
        pattern = SQL_PATTERN;
        break;
      case "shell":
        pattern = SHELL_PATTERN;
        break;
      case "yaml":
        pattern = YAML_PATTERN;
        break;
      default:
        return;
    }
    if (text.length() == 0) return;
    // Snapshot only the bounded prefix: regex never traverses an unbounded document.
    String snapshot =
        text.subSequence(0, Math.min(text.length(), MAX_HIGHLIGHT_CHARACTERS)).toString();
    Matcher matcher = pattern.matcher(snapshot);
    int count = 0;
    while (matcher.find() && count < MAX_SYNTAX_SPANS) {
      int color =
          matcher.group(1) != null
              ? commentColor
              : matcher.group(2) != null
                  ? stringColor
                  : matcher.group(3) != null ? keywordColor : numberColor;
      text.setSpan(
          new SyntaxColorSpan(color),
          matcher.start(),
          matcher.end(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      count++;
    }
  }

  private void clearSearchSpans(Editable text) {
    for (SearchColorSpan span : text.getSpans(0, text.length(), SearchColorSpan.class))
      text.removeSpan(span);
  }

  private void applySearchSpans() {
    Editable text = getText();
    if (text == null) return;
    clearSearchSpans(text);
    for (int i = 0; i < searchRanges.size(); i++) {
      int[] range = searchRanges.get(i);
      if (range[0] >= 0 && range[1] <= text.length() && range[0] < range[1]) {
        text.setSpan(
            new SearchColorSpan(i == activeSearchIndex ? activeSearchColor : searchColor),
            range[0],
            range[1],
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    invalidate();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    scheduleHighlighting();
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(highlightRunnable);
    super.onDetachedFromWindow();
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static final class SyntaxColorSpan extends ForegroundColorSpan {
    SyntaxColorSpan(int color) {
      super(color);
    }
  }

  private static final class SearchColorSpan extends BackgroundColorSpan {
    SearchColorSpan(int color) {
      super(color);
    }
  }
}
