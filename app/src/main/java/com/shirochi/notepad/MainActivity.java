package com.shirochi.notepad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import com.shirochi.notepad.core.EditHistory;
import com.shirochi.notepad.core.SearchEngine;
import com.shirochi.notepad.core.TextCodec;
import com.shirochi.notepad.core.TextTransforms;
import com.shirochi.notepad.editor.CodeEditor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native, offline document editor. All provider and draft I/O is serialized off the UI thread. */
public final class MainActivity extends Activity {
  private static final int OPEN = 10, CREATE = 11, MAX_BYTES = 2 * 1024 * 1024, MAX_TABS = 12;
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final List<Document> documents = new ArrayList<>();
  private final List<String> saving = new ArrayList<>();
  private final LinkedHashSet<String> openingUris = new LinkedHashSet<>();
  private final List<Intent> deferredIntents = new ArrayList<>();
  private final List<TextView> tabLabels = new ArrayList<>();
  private final List<SearchEngine.Match> matches = new ArrayList<>();
  private SharedPreferences prefs;
  private SessionStore sessions;
  private int selected, activeMatch = -1, untitled = 1;
  private boolean loading = true,
      applying,
      searchVisible,
      replaceVisible,
      suppressSearch,
      dark,
      wrap,
      numbers,
      autoIndent;
  private int bg, surface, elevated, fg, muted, accent, border, fontSize;
  private String pendingSaveId;
  private Runnable pendingSaveCallback;
  private Intent deferredResultData;
  private int deferredRequest = -1, deferredResultCode;
  private LinearLayout root, tabs, searchPanel, replaceRow;
  private HorizontalScrollView tabScroll;
  private CodeEditor editor;
  private TextView subtitle, status, searchCount;
  private EditText findInput, replaceInput;
  private CheckBox caseBox, wordBox, regexBox;
  private View undoButton, redoButton;
  private final Runnable persistTask = this::persistSession;
  private final Runnable searchTask = this::refreshSearch;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("editor", MODE_PRIVATE);
    sessions = new SessionStore(this);
    dark = prefs.getBoolean("dark", true);
    wrap = prefs.getBoolean("wrap", true);
    numbers = prefs.getBoolean("numbers", true);
    autoIndent = prefs.getBoolean("indent", true);
    fontSize = prefs.getInt("fontSize", CodeEditor.DEFAULT_FONT_SIZE_SP);
    if (state != null) pendingSaveId = state.getString("pendingSaveId");
    buildUi();
    if (state == null) deferredIntents.add(getIntent());
    IO.execute(
        () -> {
          SessionStore.Session recovered = null;
          Exception error = null;
          try {
            recovered = sessions.read();
          } catch (Exception e) {
            error = e;
          }
          final SessionStore.Session session = recovered;
          final Exception failure = error;
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                if (session != null) {
                  documents.addAll(session.documents);
                  selected = session.selected;
                }
                loading = false;
                editor.setEnabled(true);
                if (documents.isEmpty()) newDocument();
                else displayDocument();
                if (failure != null)
                  showError(
                      "Draft recovery",
                      "The saved session could not be read. "
                          + readableError(failure)
                          + " Your external files are unchanged.");
                if (deferredRequest != -1) {
                  onActivityResult(deferredRequest, deferredResultCode, deferredResultData);
                  deferredRequest = -1;
                  deferredResultData = null;
                }
                List<Intent> incoming = new ArrayList<>(deferredIntents);
                deferredIntents.clear();
                for (Intent request : incoming) handleIntent(request);
              });
        });
  }

  private void colors() {
    bg = Color.parseColor(dark ? "#10191D" : "#F5F8F8");
    surface = Color.parseColor(dark ? "#172329" : "#FFFFFF");
    elevated = Color.parseColor(dark ? "#22333A" : "#E7F0F0");
    fg = Color.parseColor(dark ? "#E5EEEF" : "#162E34");
    muted = Color.parseColor(dark ? "#93A9B1" : "#556F78");
    accent = Color.parseColor(dark ? "#72DAC1" : "#006D5B");
    border = Color.parseColor(dark ? "#2B3D44" : "#CFDDDF");
  }

  private void buildUi() {
    setTheme(dark ? R.style.Theme_NotepadStudio : R.style.Theme_NotepadStudio_Light);
    colors();
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    root = column();
    root.setBackgroundColor(bg);
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
          });
    } else {
      root.setFitsSystemWindows(true);
    }
    setContentView(root);
    LinearLayout header = row();
    header.setPadding(dp(12), dp(6), dp(6), dp(6));
    IconView logo = new IconView(this, "note", accent);
    header.addView(logo, lp(44, 48));
    LinearLayout branding = column();
    TextView title = label(getString(R.string.app_name), 18, fg);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    branding.addView(title);
    subtitle = label("A little space to think.", 11, muted);
    branding.addView(subtitle);
    header.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(icon("open", "Open file · Ctrl+O", this::openPicker));
    header.addView(icon("new", "New document · Ctrl+N", this::newDocument));
    View menu = icon("menu", "More options", () -> {});
    menu.setOnClickListener(v -> showMenu(v));
    header.addView(menu);
    root.addView(header);

    tabScroll = new HorizontalScrollView(this);
    tabScroll.setHorizontalScrollBarEnabled(false);
    tabs = row();
    tabs.setPadding(dp(12), dp(3), dp(12), dp(7));
    tabScroll.addView(tabs);
    root.addView(tabScroll);
    divider(root);
    HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
    toolbarScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout toolbar = row();
    toolbar.setPadding(dp(12), dp(5), dp(12), dp(5));
    Button save = button("Save", () -> saveCurrent(false, null));
    save.setTextColor(dark ? bg : Color.WHITE);
    save.setBackground(round(accent, 12));
    toolbar.addView(save, lp(84, 42));
    undoButton = icon("undo", "Undo · Ctrl+Z", () -> undo(false));
    toolbar.addView(undoButton);
    redoButton = icon("redo", "Redo · Ctrl+Y", () -> undo(true));
    toolbar.addView(redoButton);
    toolbar.addView(icon("find", "Find · Ctrl+F", () -> showSearch(false)));
    toolbar.addView(button("Replace", () -> showSearch(true)), lp(90, 44));
    toolbarScroll.addView(toolbar);
    root.addView(toolbarScroll);
    buildSearch();
    root.addView(searchPanel);
    divider(root);
    editor = new CodeEditor(this);
    editor.setId(R.id.editor);
    editor.setContentDescription("Text editor");
    // Drafts live in an atomic file, never the size-limited Activity state Bundle.
    editor.setSaveEnabled(false);
    editor.setEnabled(!loading);
    editor.setEditorTheme(dark);
    editor.setWordWrap(wrap);
    editor.setShowLineNumbers(numbers);
    editor.setEditorFontSize(fontSize);
    editor.setHint("Start writing…");
    editor.setHintTextColor(muted);
    editor.setFilters(
        new InputFilter[] {
          (source, start, end, dest, dstart, dend) -> {
            if (dest.length() - (dend - dstart) + end - start <= MAX_BYTES) return null;
            toast("This edit exceeds the 2 million character limit. Nothing was inserted.");
            return dest.subSequence(dstart, dend);
          }
        });
    root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
    attachEditor();
    divider(root);
    HorizontalScrollView accessoryScroll = new HorizontalScrollView(this);
    accessoryScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout accessory = row();
    accessory.setPadding(dp(8), 0, dp(8), 0);
    accessory.addView(key("Tab", () -> indent(false)), lp(58, 44));
    accessory.addView(key("⇤", () -> indent(true)), lp(48, 44));
    accessory.addView(key("←", () -> moveCursor(-1)), lp(48, 44));
    accessory.addView(key("→", () -> moveCursor(1)), lp(48, 44));
    accessory.addView(key("( )", () -> insertPair("(", ")")), lp(48, 44));
    accessory.addView(key("[ ]", () -> insertPair("[", "]")), lp(48, 44));
    accessory.addView(key("{ }", () -> insertPair("{", "}")), lp(48, 44));
    accessory.addView(key("\" \"", () -> insertPair("\"", "\"")), lp(48, 44));
    accessoryScroll.addView(accessory);
    root.addView(accessoryScroll);
    status = label("Opening workspace…", 11, muted);
    status.setPadding(dp(16), dp(8), dp(16), dp(8));
    status.setBackgroundColor(surface);
    status.setMinHeight(dp(38));
    status.setOnClickListener(v -> documentInfo());
    status.setContentDescription("Document status. Tap for details.");
    root.addView(status);
    searchPanel.setVisibility(searchVisible ? View.VISIBLE : View.GONE);
  }

  private void buildSearch() {
    searchPanel = column();
    searchPanel.setBackgroundColor(surface);
    searchPanel.setPadding(dp(12), dp(4), dp(8), dp(8));
    LinearLayout findRow = row();
    findInput = field("Find in document", R.id.find_input);
    findRow.addView(findInput, new LinearLayout.LayoutParams(0, dp(46), 1));
    findRow.addView(icon("up", "Previous match · Shift+F3", () -> findNext(true)));
    findRow.addView(icon("down", "Next match · F3", () -> findNext(false)));
    findRow.addView(icon("close", "Close search · Escape", this::closeSearch));
    searchPanel.addView(findRow);
    LinearLayout options = row();
    caseBox = check("Aa", "Match case");
    wordBox = check("Word", "Whole word");
    regexBox = check(".*", "Regular expression");
    options.addView(caseBox);
    options.addView(wordBox);
    options.addView(regexBox);
    searchCount = label("Type to search", 11, muted);
    searchCount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    options.addView(searchCount, new LinearLayout.LayoutParams(0, dp(42), 1));
    searchPanel.addView(options);
    replaceRow = row();
    replaceInput = field("Replace with", R.id.replace_input);
    replaceRow.addView(replaceInput, new LinearLayout.LayoutParams(0, dp(46), 1));
    replaceRow.addView(button("Replace", this::replaceOne), lp(84, 46));
    replaceRow.addView(button("All", this::replaceAll), lp(52, 46));
    replaceRow.setVisibility(replaceVisible ? View.VISIBLE : View.GONE);
    searchPanel.addView(replaceRow);
    findInput.addTextChangedListener(watcher(() -> scheduleSearch()));
    for (CheckBox box : new CheckBox[] {caseBox, wordBox, regexBox})
      box.setOnCheckedChangeListener((v, checked) -> scheduleSearch());
    findInput.setOnEditorActionListener(
        (v, id, event) -> {
          findNext(false);
          return true;
        });
  }

  private void attachEditor() {
    editor.setUndoRedoListener(this::undo);
    editor.setSelectionListener(
        (start, end) -> {
          Document d = current();
          if (d == null || applying) return;
          d.start = start;
          d.end = end;
          updateStatus();
        });
    editor.addTextChangedListener(
        new TextWatcher() {
          private boolean addIndent;
          private int insertion;
          private String indentation;

          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (applying || current() == null) return;
            current()
                .history
                .record(s.toString(), editor.getSelectionStart(), editor.getSelectionEnd());
          }

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            addIndent = false;
            if (!applying && autoIndent && count == 1 && before == 0 && s.charAt(start) == '\n') {
              int lineStart = start;
              while (lineStart > 0 && s.charAt(lineStart - 1) != '\n') lineStart--;
              int p = lineStart;
              while (p < start && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
              indentation = s.subSequence(lineStart, p).toString();
              insertion = start + 1;
              addIndent = !indentation.isEmpty();
            }
          }

          @Override
          public void afterTextChanged(Editable text) {
            if (applying || current() == null) return;
            if (addIndent) {
              addIndent = false;
              applying = true;
              text.insert(insertion, indentation);
              editor.setSelection(Math.min(text.length(), insertion + indentation.length()));
              applying = false;
            }
            Document d = current();
            d.text = text.toString();
            d.start = Math.max(0, editor.getSelectionStart());
            d.end = Math.max(0, editor.getSelectionEnd());
            d.history.record(d.text, d.start, d.end);
            updateTabLabels();
            updateStatus();
            schedulePersist();
            scheduleSearch();
          }
        });
  }

  private Document current() {
    return selected >= 0 && selected < documents.size() ? documents.get(selected) : null;
  }

  private Document byId(String id) {
    for (Document d : documents) if (d.id.equals(id)) return d;
    return null;
  }

  private void newDocument() {
    if (loading) return;
    if (documents.size() >= MAX_TABS) {
      toast("Close a tab before opening another (12 tabs maximum).");
      return;
    }
    capture();
    Document d = new Document();
    do {
      d.name = "Untitled " + untitled++ + ".txt";
    } while (nameExists(d.name));
    d.history.reset("", 0, 0);
    documents.add(d);
    selected = documents.size() - 1;
    displayDocument();
    schedulePersist();
  }

  private boolean nameExists(String name) {
    for (Document d : documents) if (d.name.equals(name)) return true;
    return false;
  }

  private void capture() {
    Document d = current();
    if (d != null && editor != null) {
      d.start = Math.max(0, editor.getSelectionStart());
      d.end = Math.max(0, editor.getSelectionEnd());
      d.scrollY = editor.getScrollY();
    }
  }

  private void selectDocument(int index) {
    capture();
    selected = index;
    displayDocument();
    schedulePersist();
  }

  private void displayDocument() {
    Document d = current();
    if (d == null) return;
    applying = true;
    editor.setText(d.text);
    editor.setLanguage(d.syntax());
    editor.setSelection(Math.min(d.start, d.text.length()), Math.min(d.end, d.text.length()));
    applying = false;
    editor.post(
        () -> {
          if (current() == d) editor.scrollTo(0, d.scrollY);
        });
    renderTabs();
    updateStatus();
    refreshSearch();
  }

  private void renderTabs() {
    tabs.removeAllViews();
    tabLabels.clear();
    for (int i = 0; i < documents.size(); i++) {
      final int index = i;
      Document d = documents.get(i);
      LinearLayout chip = row();
      chip.setBackground(round(i == selected ? elevated : bg, 10));
      LinearLayout.LayoutParams params = lp(-2, 44);
      params.setMargins(0, 0, dp(6), 0);
      tabs.addView(chip, params);
      TextView title = label(tabTitle(d), 12, i == selected ? accent : muted);
      title.setSingleLine();
      title.setMaxWidth(dp(195));
      title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
      title.setPadding(dp(13), 0, dp(2), 0);
      title.setGravity(Gravity.CENTER_VERTICAL);
      title.setTypeface(Typeface.DEFAULT, i == selected ? Typeface.BOLD : Typeface.NORMAL);
      title.setOnClickListener(v -> selectDocument(index));
      chip.addView(title, lp(-2, 44));
      tabLabels.add(title);
      View close = icon("close", "Close " + d.name, () -> closeDocument(d));
      chip.addView(close, lp(44, 44));
      if (i == selected)
        chip.post(() -> tabScroll.smoothScrollTo(Math.max(0, chip.getLeft() - dp(12)), 0));
    }
  }

  private String tabTitle(Document d) {
    return (d.dirty() ? "●  " : "") + d.name;
  }

  private void updateTabLabels() {
    for (int i = 0; i < tabLabels.size() && i < documents.size(); i++)
      tabLabels.get(i).setText(tabTitle(documents.get(i)));
  }

  private void updateStatus() {
    Document d = current();
    if (d == null) return;
    int pos = Math.min(d.text.length(), Math.max(0, editor.getSelectionStart())),
        line = 1,
        last = -1;
    for (int i = 0; i < pos; i++)
      if (d.text.charAt(i) == '\n') {
        line++;
        last = i;
      }
    int column = d.text.codePointCount(last + 1, pos) + 1;
    int selection = Math.abs(editor.getSelectionEnd() - editor.getSelectionStart());
    status.setText(
        "Ln "
            + line
            + ", Col "
            + column
            + (selection > 0 ? "  ·  Sel " + selection : "")
            + "   |   "
            + d.encoding
            + "   ·   "
            + d.lineEnding
            + "   ·   "
            + d.syntax().toUpperCase(Locale.ROOT));
    subtitle.setText(
        saving.contains(d.id)
            ? "Saving…"
            : d.dirty()
                ? "Unsaved changes · draft recovery on"
                : d.uri.isEmpty() ? "Local draft · ready when you are" : "Saved · " + d.name);
    undoButton.setEnabled(d.history.canUndo());
    undoButton.setAlpha(d.history.canUndo() ? 1f : .3f);
    redoButton.setEnabled(d.history.canRedo());
    redoButton.setAlpha(d.history.canRedo() ? 1f : .3f);
  }

  private void closeDocument(Document d) {
    if (saving.contains(d.id) || d.id.equals(pendingSaveId)) {
      toast("Wait for this file to finish saving.");
      return;
    }
    if (!d.dirty()) {
      removeDocument(d);
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Save changes to " + d.name + "?")
        .setMessage("Discard removes this tab and its recovered draft.")
        .setPositiveButton(
            "Save", (dialog, which) -> saveDocument(d, false, () -> removeDocument(d)))
        .setNegativeButton("Discard", (dialog, which) -> removeDocument(d))
        .setNeutralButton("Cancel", null)
        .show();
  }

  private void removeDocument(Document d) {
    int index = documents.indexOf(d);
    if (index < 0) return;
    capture();
    documents.remove(index);
    if (selected > index) selected--;
    selected = Math.max(0, Math.min(selected, documents.size() - 1));
    if (documents.isEmpty()) newDocument();
    else displayDocument();
    schedulePersist();
  }

  private void showSearch(boolean replace) {
    if (current() == null) return;
    searchVisible = true;
    replaceVisible = replace;
    searchPanel.setVisibility(View.VISIBLE);
    replaceRow.setVisibility(replace ? View.VISIBLE : View.GONE);
    int a = Math.min(editor.getSelectionStart(), editor.getSelectionEnd()),
        b = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
    if (b > a && b - a < 500 && !editor.getText().subSequence(a, b).toString().contains("\n"))
      findInput.setText(editor.getText().subSequence(a, b));
    findInput.requestFocus();
    findInput.selectAll();
    keyboard(findInput);
    refreshSearch();
  }

  private void closeSearch() {
    searchVisible = false;
    searchPanel.setVisibility(View.GONE);
    editor.setSearchMatches(new ArrayList<>(), -1);
    editor.requestFocus();
  }

  private void scheduleSearch() {
    if (suppressSearch) return;
    handler.removeCallbacks(searchTask);
    if (searchVisible) handler.postDelayed(searchTask, 160);
  }

  private void refreshSearch() {
    handler.removeCallbacks(searchTask);
    matches.clear();
    activeMatch = -1;
    if (!searchVisible || current() == null) {
      if (editor != null) editor.setSearchMatches(new ArrayList<>(), -1);
      return;
    }
    String query = findInput.getText().toString();
    if (query.isEmpty()) {
      searchCount.setText("Type to search");
      editor.setSearchMatches(new ArrayList<>(), -1);
      return;
    }
    try {
      matches.addAll(
          SearchEngine.findAll(
              current().text,
              query,
              caseBox.isChecked(),
              wordBox.isChecked(),
              regexBox.isChecked()));
      for (int i = 0; i < matches.size(); i++)
        if (matches.get(i).start == editor.getSelectionStart()
            && matches.get(i).end == editor.getSelectionEnd()) {
          activeMatch = i;
          break;
        }
      drawMatches();
    } catch (IllegalArgumentException e) {
      searchCount.setText("Invalid search");
      findInput.setError(e.getMessage());
      editor.setSearchMatches(new ArrayList<>(), -1);
    }
  }

  private void drawMatches() {
    findInput.setError(null);
    ArrayList<int[]> spans = new ArrayList<>();
    for (SearchEngine.Match match : matches) spans.add(new int[] {match.start, match.end});
    editor.setSearchMatches(spans, activeMatch);
    searchCount.setText(
        matches.isEmpty()
            ? "No matches"
            : activeMatch < 0
                ? matches.size() + " matches"
                : (activeMatch + 1) + " / " + matches.size());
  }

  private void findNext(boolean previous) {
    if (current() == null) return;
    if (!searchVisible) {
      showSearch(false);
      return;
    }
    refreshSearch();
    if (matches.isEmpty()) return;
    if (activeMatch >= 0)
      activeMatch = (activeMatch + (previous ? -1 : 1) + matches.size()) % matches.size();
    else {
      int cursor = editor.getSelectionStart();
      activeMatch = previous ? matches.size() - 1 : 0;
      if (previous) {
        for (int i = matches.size() - 1; i >= 0; i--)
          if (matches.get(i).start < cursor) {
            activeMatch = i;
            break;
          }
      } else {
        for (int i = 0; i < matches.size(); i++)
          if (matches.get(i).start >= cursor) {
            activeMatch = i;
            break;
          }
      }
    }
    SearchEngine.Match match = matches.get(activeMatch);
    editor.setSelection(match.start, match.end);
    editor.requestFocus();
    drawMatches();
  }

  private void replaceOne() {
    refreshSearch();
    if (matches.isEmpty() || current() == null) return;
    if (activeMatch < 0) {
      findNext(false);
      return;
    }
    SearchEngine.Match match = matches.get(activeMatch);
    String before = current().text;
    try {
      String after =
          SearchEngine.replaceOne(
              before,
              findInput.getText().toString(),
              replaceInput.getText().toString(),
              caseBox.isChecked(),
              wordBox.isChecked(),
              regexBox.isChecked(),
              match.start,
              match.end);
      int cursor = match.start + after.length() - before.length() + match.end - match.start;
      if (!applyText(after, cursor, cursor)) return;
      // A zero-width match must advance or Replace would repeatedly insert at one offset.
      if (match.start == match.end && cursor < after.length())
        cursor = after.offsetByCodePoints(cursor, 1);
      refreshSearch();
      if (!matches.isEmpty()) {
        activeMatch = 0;
        for (int i = 0; i < matches.size(); i++)
          if (matches.get(i).start >= cursor) {
            activeMatch = i;
            break;
          }
        SearchEngine.Match next = matches.get(activeMatch);
        editor.setSelection(next.start, next.end);
        drawMatches();
      }
    } catch (IllegalArgumentException e) {
      showError("Cannot replace", e.getMessage());
    }
  }

  private void replaceAll() {
    if (current() == null || findInput.getText().length() == 0) return;
    try {
      String after =
          SearchEngine.replaceAll(
              current().text,
              findInput.getText().toString(),
              replaceInput.getText().toString(),
              caseBox.isChecked(),
              wordBox.isChecked(),
              regexBox.isChecked());
      if (after.equals(current().text)) {
        toast("No text changed.");
        return;
      }
      if (!applyText(
          after,
          Math.min(editor.getSelectionStart(), after.length()),
          Math.min(editor.getSelectionStart(), after.length()))) return;
      refreshSearch();
      toast("Replaced all matches. Undo is available.");
    } catch (IllegalArgumentException e) {
      showError("Cannot replace", e.getMessage());
    }
  }

  private boolean applyText(String text, int start, int end) {
    Document d = current();
    if (d == null) return false;
    if (text.length() > MAX_BYTES) {
      toast("This edit exceeds the 2 million character limit. No changes made.");
      return false;
    }
    d.history.record(d.text, editor.getSelectionStart(), editor.getSelectionEnd());
    applying = true;
    editor.setText(text);
    editor.setSelection(
        Math.max(0, Math.min(start, text.length())), Math.max(0, Math.min(end, text.length())));
    applying = false;
    d.text = text;
    d.start = editor.getSelectionStart();
    d.end = editor.getSelectionEnd();
    d.history.record(text, d.start, d.end);
    updateTabLabels();
    updateStatus();
    schedulePersist();
    scheduleSearch();
    return true;
  }

  private void undo(boolean redo) {
    Document d = current();
    if (d == null) return;
    EditHistory.Snapshot snapshot = redo ? d.history.redo() : d.history.undo();
    if (snapshot == null) return;
    applying = true;
    editor.setText(snapshot.text);
    editor.setSelection(snapshot.start, snapshot.end);
    applying = false;
    d.text = snapshot.text;
    d.start = snapshot.start;
    d.end = snapshot.end;
    updateTabLabels();
    updateStatus();
    scheduleSearch();
    schedulePersist();
  }

  private void indent(boolean unindent) {
    if (current() == null) return;
    int a = editor.getSelectionStart(), b = editor.getSelectionEnd();
    String unit = prefs.getBoolean("tabs", false) ? "\t" : "    ";
    if (!unindent && a == b) {
      editor.getText().replace(a, b, unit);
      return;
    }
    TextTransforms.Result r =
        unindent
            ? TextTransforms.unindent(current().text, a, b, unit)
            : TextTransforms.indent(current().text, a, b, unit);
    applyText(r.text, r.start, r.end);
  }

  private void duplicateLine() {
    if (current() == null) return;
    TextTransforms.Result r =
        TextTransforms.duplicateLine(
            current().text, editor.getSelectionStart(), editor.getSelectionEnd());
    applyText(r.text, r.start, r.end);
  }

  private void toggleComment() {
    if (current() == null) return;
    String lang = current().syntax().toLowerCase(Locale.ROOT);
    String prefix =
        lang.equals("py")
                || lang.equals("python")
                || lang.equals("sh")
                || lang.equals("yaml")
                || lang.equals("yml")
            ? "#"
            : "//";
    TextTransforms.Result r =
        TextTransforms.toggleLineComment(
            current().text, editor.getSelectionStart(), editor.getSelectionEnd(), prefix);
    applyText(r.text, r.start, r.end);
  }

  private void breakParagraphLines() {
    Document d = current();
    if (d == null) return;
    TextTransforms.Result result =
        TextTransforms.breakAfterParagraphTags(
            d.text, editor.getSelectionStart(), editor.getSelectionEnd());
    int added = result.text.length() - d.text.length();
    if (added == 0) {
      toast("No line breaks needed.");
      return;
    }
    if (applyText(result.text, result.start, result.end)) {
      editor.requestFocus();
      toast(
          added
              + (added == 1 ? " line break added." : " line breaks added.")
              + " Undo is available.");
    }
  }

  private void insertPair(String left, String right) {
    if (current() == null) return;
    int a = Math.min(editor.getSelectionStart(), editor.getSelectionEnd()),
        b = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
    String inside = editor.getText().subSequence(a, b).toString();
    applyText(
        current().text.substring(0, a) + left + inside + right + current().text.substring(b),
        a + left.length(),
        b + left.length());
    editor.requestFocus();
  }

  private void moveCursor(int direction) {
    if (current() == null) return;
    int at = Math.max(0, editor.getSelectionStart());
    if (direction < 0 && at > 0) at = current().text.offsetByCodePoints(at, -1);
    if (direction > 0 && at < current().text.length())
      at = current().text.offsetByCodePoints(at, 1);
    editor.setSelection(at);
    editor.requestFocus();
  }

  private void openPicker() {
    if (loading) return;
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*");
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    try {
      startActivityForResult(intent, OPEN);
    } catch (Exception e) {
      showError("File picker unavailable", e.getMessage());
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (loading) {
      deferredRequest = request;
      deferredResultCode = result;
      deferredResultData = data;
      return;
    }
    if (result != RESULT_OK || data == null) {
      if (request == CREATE) {
        pendingSaveId = null;
        pendingSaveCallback = null;
      }
      return;
    }
    if (request == OPEN) {
      openIncomingFiles(data);
    }
    if (request == CREATE && data.getData() != null) {
      Document d = byId(pendingSaveId);
      Runnable callback = pendingSaveCallback;
      pendingSaveId = null;
      pendingSaveCallback = null;
      if (d != null) {
        retainPermission(data.getData(), data.getFlags());
        writeDocument(d, data.getData(), true, callback);
      }
    }
  }

  private void retainPermission(Uri uri, int flags) {
    if (!"content".equalsIgnoreCase(uri.getScheme())) return;
    try {
      int grants =
          flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      getContentResolver().takePersistableUriPermission(uri, grants);
    } catch (SecurityException | IllegalArgumentException ignored) {
      /* Some share providers grant access for this launch only. */
    }
  }

  private void openUri(Uri uri, int flags) {
    openUri(uri, flags, null);
  }

  private void openUri(Uri uri, int flags, String encoding) {
    if (uri == null) return;
    if (!IncomingFiles.isLocal(uri)) {
      showError(
          "Cannot open file", "Choose a file stored on your device or shared by a file provider.");
      return;
    }
    for (int i = 0; i < documents.size(); i++)
      if (documents.get(i).uri.equals(uri.toString())) {
        selectDocument(i);
        return;
      }
    String key = uri.toString();
    if (openingUris.contains(key)) return;
    capture();
    boolean reusableBlank =
        documents.size() == 1
            && current().uri.isEmpty()
            && current().text.isEmpty()
            && !current().dirty();
    if (documents.size() + openingUris.size() - (reusableBlank ? 1 : 0) >= MAX_TABS) {
      toast("Maximum of 12 open tabs.");
      return;
    }
    retainPermission(uri, flags);
    openingUris.add(key);
    toast("Opening file…");
    IO.execute(
        () -> {
          try {
            byte[] bytes = readUri(uri);
            TextCodec.Decoded decoded;
            try {
              decoded =
                  encoding == null ? TextCodec.decode(bytes) : TextCodec.decode(bytes, encoding);
            } catch (java.io.IOException e) {
              runOnUiThread(
                  () -> {
                    openingUris.remove(key);
                    if (isDestroyed()) return;
                    new AlertDialog.Builder(this)
                        .setTitle("Cannot read as text")
                        .setMessage(readableError(e))
                        .setPositiveButton(
                            "Choose encoding…",
                            (dialog, which) ->
                                chooseEncoding(
                                    "Open with encoding",
                                    false,
                                    selectedEncoding -> openUri(uri, flags, selectedEncoding)))
                        .setNegativeButton("Cancel", null)
                        .show();
                  });
              return;
            }
            String name = displayName(uri);
            String hash = hash(bytes);
            runOnUiThread(
                () -> {
                  openingUris.remove(key);
                  if (isDestroyed()) return;
                  for (int i = 0; i < documents.size(); i++)
                    if (documents.get(i).uri.equals(uri.toString())) {
                      selectDocument(i);
                      return;
                    }
                  if (documents.size() >= MAX_TABS) {
                    toast("Maximum of 12 open tabs.");
                    return;
                  }
                  capture();
                  Document d = new Document();
                  d.name = name;
                  d.uri = uri.toString();
                  d.text = decoded.text;
                  d.encoding = decoded.encoding;
                  d.lineEnding = decoded.lineEnding;
                  d.bom = decoded.bom;
                  d.diskHash = hash;
                  d.markSaved(d.text, d.format());
                  d.history.reset(d.text, 0, 0);
                  // Reuse the untouched blank tab created at startup.
                  if (documents.size() == 1
                      && current().uri.isEmpty()
                      && current().text.isEmpty()
                      && !current().dirty()) documents.clear();
                  documents.add(d);
                  selected = documents.size() - 1;
                  displayDocument();
                  addRecent(d);
                  schedulePersist();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  openingUris.remove(key);
                  if (!isDestroyed()) showError("Cannot open file", readableError(e));
                });
          }
        });
  }

  private byte[] readUri(Uri uri) throws Exception {
    try (InputStream in = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (in == null) throw new java.io.IOException("The provider did not return a file.");
      byte[] buffer = new byte[16384];
      int count;
      while ((count = in.read(buffer)) != -1) {
        if (out.size() + count > MAX_BYTES)
          throw new java.io.IOException(
              "Files up to 2 MiB are supported. Choose a smaller text file.");
        out.write(buffer, 0, count);
      }
      return out.toByteArray();
    }
  }

  private String displayName(Uri uri) {
    try (Cursor c =
        getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        String name = c.getString(0);
        if (name != null && !name.isEmpty()) return name;
      }
    } catch (Exception ignored) {
    }
    String last = uri.getLastPathSegment();
    return last == null ? "Document.txt" : last.substring(last.lastIndexOf('/') + 1);
  }

  private static String hash(byte[] bytes) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder b = new StringBuilder();
    for (byte x : digest) b.append(String.format(Locale.ROOT, "%02x", x & 255));
    return b.toString();
  }

  private void saveCurrent(boolean saveAs, Runnable callback) {
    Document d = current();
    if (d != null) saveDocument(d, saveAs, callback);
  }

  private void saveDocument(Document d, boolean saveAs, Runnable callback) {
    if (saving.contains(d.id)) {
      toast("This file is already saving.");
      return;
    }
    if (saveAs || d.uri.isEmpty()) {
      if (pendingSaveId != null) {
        toast("Finish choosing a save location first.");
        return;
      }
      pendingSaveId = d.id;
      pendingSaveCallback = callback;
      Intent intent =
          new Intent(Intent.ACTION_CREATE_DOCUMENT)
              .addCategory(Intent.CATEGORY_OPENABLE)
              .setType("text/plain")
              .putExtra(Intent.EXTRA_TITLE, d.name);
      intent.addFlags(
          Intent.FLAG_GRANT_READ_URI_PERMISSION
              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      try {
        startActivityForResult(intent, CREATE);
      } catch (Exception e) {
        pendingSaveId = null;
        pendingSaveCallback = null;
        showError("Cannot save", e.getMessage());
      }
    } else writeDocument(d, Uri.parse(d.uri), false, callback);
  }

  private void writeDocument(Document d, Uri target, boolean force, Runnable callback) {
    final String text = d.text,
        encoding = d.encoding,
        lineEnding = d.lineEnding,
        format = d.format(),
        originalHash = d.diskHash;
    final boolean bom = d.bom;
    saving.add(d.id);
    updateStatus();
    IO.execute(
        () -> {
          try {
            byte[] bytes = TextCodec.encode(text, encoding, lineEnding, bom);
            if (bytes.length > MAX_BYTES)
              throw new java.io.IOException(
                  "The encoded file exceeds 2 MiB. Reduce its size before saving.");
            if (!force && !originalHash.isEmpty() && !hash(readUri(target)).equals(originalHash)) {
              runOnUiThread(
                  () -> {
                    saving.remove(d.id);
                    if (isDestroyed()) return;
                    updateStatus();
                    new AlertDialog.Builder(this)
                        .setTitle("File changed outside this app")
                        .setMessage(
                            "Choose whether to overwrite the external changes with this tab, or"
                                + " keep editing and use Save as to preserve both versions.")
                        .setPositiveButton(
                            "Overwrite",
                            (dialog, which) -> writeDocument(d, target, true, callback))
                        .setNegativeButton("Keep editing", null)
                        .show();
                  });
              return;
            }
            try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
              if (out == null)
                throw new java.io.IOException("No writable file returned. Try Save as.");
              out.write(bytes);
              out.flush();
            }
            String name = displayName(target), digest = hash(bytes);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  saving.remove(d.id);
                  d.uri = target.toString();
                  d.name = name;
                  d.diskHash = digest;
                  d.markSaved(text, format);
                  renderTabs();
                  updateStatus();
                  if (current() == d) editor.setLanguage(d.syntax());
                  addRecent(d);
                  schedulePersist();
                  toast("Saved " + name);
                  if (callback != null && !d.dirty()) callback.run();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  saving.remove(d.id);
                  if (isDestroyed()) return;
                  updateStatus();
                  showError(
                      "Could not save · draft kept",
                      readableError(e) + "\n\nUse Save as to choose another location.");
                });
          }
        });
  }

  private void saveAll() {
    List<String> ids = new ArrayList<>();
    for (Document d : documents) ids.add(d.id);
    saveAll(ids, 0);
  }

  private void saveAll(List<String> ids, int index) {
    if (index >= ids.size()) {
      toast("Save all finished.");
      return;
    }
    Document d = byId(ids.get(index));
    if (d == null || (!d.dirty() && !d.uri.isEmpty())) saveAll(ids, index + 1);
    else saveDocument(d, false, () -> saveAll(ids, index + 1));
  }

  private void schedulePersist() {
    handler.removeCallbacks(persistTask);
    handler.postDelayed(persistTask, 650);
  }

  private void persistSession() {
    if (loading) return;
    capture();
    try {
      String json = SessionStore.snapshot(documents, selected);
      IO.execute(
          () -> {
            try {
              sessions.write(json);
            } catch (Exception e) {
              runOnUiThread(() -> toast("Draft recovery could not be updated. Save your files."));
            }
          });
    } catch (Exception e) {
      toast("Draft recovery could not be updated.");
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    handler.removeCallbacks(persistTask);
    persistSession();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("pendingSaveId", pendingSaveId);
    persistSession();
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    root.requestApplyInsets();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
  }

  private void handleIntent(Intent intent) {
    if (intent == null) return;
    if (loading) {
      if (deferredIntents.size() < MAX_TABS) deferredIntents.add(intent);
      else toast("Finish opening these files before sharing more.");
      return;
    }
    String action = intent.getAction();
    boolean viewing = Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action);
    boolean sharing =
        Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
    if (!viewing && !sharing) return;
    try {
      // A file's caption or preview text must never replace its actual source contents.
      if (openIncomingFiles(intent)) return;
      if (!sharing) {
        toast("No file was attached. Choose Open to select a file.");
        return;
      }
      CharSequence text = IncomingFiles.sharedText(intent);
      if (text != null) {
        if (text.length() > MAX_BYTES) {
          toast("Shared text is too large.");
          return;
        }
        boolean blank = current() != null && current().text.isEmpty() && current().uri.isEmpty();
        if (!blank) {
          if (documents.size() >= MAX_TABS) {
            toast("Close a tab before importing shared text.");
            return;
          }
          newDocument();
        }
        applyText(text.toString(), 0, 0);
      } else toast("No file or text was attached.");
    } catch (RuntimeException error) {
      showError(
          "Cannot open attachment",
          "The sending app did not provide a readable file. Try Open and select the file"
              + " directly.");
    }
  }

  private boolean openIncomingFiles(Intent intent) {
    List<Uri> uris = IncomingFiles.collect(intent, MAX_TABS + 1);
    if (uris.isEmpty()) return false;
    for (int i = 0; i < Math.min(uris.size(), MAX_TABS); i++)
      openUri(uris.get(i), intent.getFlags());
    if (uris.size() > MAX_TABS) toast("Only the first 12 files can be opened at once.");
    return true;
  }

  private void addRecent(Document d) {
    try {
      JSONArray old = new JSONArray(prefs.getString("recent", "[]")), next = new JSONArray();
      next.put(new JSONObject().put("name", d.name).put("uri", d.uri));
      for (int i = 0; i < old.length() && next.length() < 20; i++) {
        JSONObject item = old.getJSONObject(i);
        if (!item.getString("uri").equals(d.uri)) next.put(item);
      }
      prefs.edit().putString("recent", next.toString()).apply();
    } catch (Exception ignored) {
    }
  }

  private void showRecent() {
    try {
      JSONArray recent = new JSONArray(prefs.getString("recent", "[]"));
      if (recent.length() == 0) {
        toast("Opened and saved files will appear here.");
        return;
      }
      String[] names = new String[recent.length()];
      for (int i = 0; i < names.length; i++) names[i] = recent.getJSONObject(i).getString("name");
      new AlertDialog.Builder(this)
          .setTitle("Recent files")
          .setItems(
              names,
              (dialog, index) -> {
                try {
                  openUri(Uri.parse(recent.getJSONObject(index).getString("uri")), 0);
                } catch (Exception e) {
                  showError("Cannot open", e.getMessage());
                }
              })
          .setNeutralButton("Clear list", (d, w) -> prefs.edit().remove("recent").apply())
          .setNegativeButton("Cancel", null)
          .show();
    } catch (Exception e) {
      showError("Recent files", e.getMessage());
    }
  }

  private void showMenu(View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    String[] titles = {
      "Save as…",
      "Save all",
      "Recent files",
      "Go to line…",
      "Select all",
      "Duplicate line / selection",
      "Toggle line comment",
      "Convert case…",
      "Line break after </p>",
      "Document format…",
      "Syntax language…",
      "Editor settings",
      "Share text",
      "Keyboard shortcuts",
      "About " + getString(R.string.app_name)
    };
    for (int i = 0; i < titles.length; i++) menu.getMenu().add(0, i, i, titles[i]);
    menu.setOnMenuItemClickListener(
        item -> {
          switch (item.getItemId()) {
            case 0:
              saveCurrent(true, null);
              break;
            case 1:
              saveAll();
              break;
            case 2:
              showRecent();
              break;
            case 3:
              goToLine();
              break;
            case 4:
              editor.requestFocus();
              editor.selectAll();
              break;
            case 5:
              duplicateLine();
              break;
            case 6:
              toggleComment();
              break;
            case 7:
              convertCase();
              break;
            case 8:
              breakParagraphLines();
              break;
            case 9:
              formatDialog();
              break;
            case 10:
              languageDialog();
              break;
            case 11:
              settings();
              break;
            case 12:
              share();
              break;
            case 13:
              shortcuts();
              break;
            case 14:
              about();
              break;
          }
          return true;
        });
    menu.show();
  }

  private void goToLine() {
    if (current() == null) return;
    EditText input = field("Line number", 0);
    input.setInputType(InputType.TYPE_CLASS_NUMBER);
    input.setText("1");
    input.selectAll();
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Go to line")
            .setView(padded(input))
            .setPositiveButton("Go", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        v -> {
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  button -> {
                    try {
                      int wanted = Integer.parseInt(input.getText().toString());
                      int line = 1, pos = 0;
                      String text = current().text;
                      while (line < wanted && pos < text.length()) {
                        if (text.charAt(pos++) == '\n') line++;
                      }
                      if (wanted < 1 || line != wanted) {
                        input.setError("Enter an existing line number");
                        return;
                      }
                      editor.requestFocus();
                      editor.setSelection(pos);
                      dialog.dismiss();
                    } catch (NumberFormatException e) {
                      input.setError("Enter a valid line number");
                    }
                  });
        });
    dialog.show();
  }

  private void convertCase() {
    if (current() == null) return;
    new AlertDialog.Builder(this)
        .setTitle("Convert selected text")
        .setItems(
            new String[] {"UPPERCASE", "lowercase"},
            (d, w) -> {
              int a = Math.min(editor.getSelectionStart(), editor.getSelectionEnd()),
                  b = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
              if (a == b) {
                toast("Select text first.");
                return;
              }
              String part = current().text.substring(a, b);
              part = w == 0 ? part.toUpperCase(Locale.ROOT) : part.toLowerCase(Locale.ROOT);
              applyText(
                  current().text.substring(0, a) + part + current().text.substring(b),
                  a,
                  a + part.length());
            })
        .show();
  }

  private void formatDialog() {
    Document d = current();
    if (d == null) return;
    String[] choices = {
      "Encoding: " + d.encoding,
      "Line endings: " + d.lineEnding,
      "Byte order mark: " + (d.bom ? "on" : "off"),
      "Reopen with encoding…"
    };
    new AlertDialog.Builder(this)
        .setTitle("Document format")
        .setItems(
            choices,
            (dialog, which) -> {
              if (which == 0) {
                chooseEncoding(
                    "Save encoding",
                    false,
                    encoding -> {
                      d.encoding = encoding;
                      if (!supportsBom(encoding)) d.bom = false;
                      formatChanged();
                    });
              } else if (which == 1) {
                String[] endings = {"LF", "CRLF", "CR"};
                new AlertDialog.Builder(this)
                    .setTitle("Save line endings")
                    .setItems(
                        new String[] {"LF · Unix / Android", "CRLF · Windows", "CR · Classic Mac"},
                        (v, i) -> {
                          d.lineEnding = endings[i];
                          formatChanged();
                        })
                    .show();
              } else if (which == 2) {
                if (!supportsBom(d.encoding)) {
                  toast(d.encoding + " does not support a byte order mark.");
                  return;
                }
                d.bom = !d.bom;
                formatChanged();
              } else {
                confirmReopen(d);
              }
            })
        .setNegativeButton("Done", null)
        .show();
  }

  private static boolean supportsBom(String encoding) {
    return encoding.equals("UTF-8") || encoding.equals("UTF-16LE") || encoding.equals("UTF-16BE");
  }

  private void chooseEncoding(
      String title, boolean includeAuto, java.util.function.Consumer<String> callback) {
    List<String> encodings = new ArrayList<>(TextCodec.availableEncodings());
    if (includeAuto) encodings.add(0, "Auto-detect");
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(
            encodings.toArray(new String[0]),
            (dialog, which) ->
                callback.accept(includeAuto && which == 0 ? null : encodings.get(which)))
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void confirmReopen(Document d) {
    if (d.uri.isEmpty()) {
      toast("Save this document before reopening it with another encoding.");
      return;
    }
    chooseEncoding(
        "Reopen with encoding",
        true,
        encoding -> {
          capture();
          if (d.dirty()) {
            new AlertDialog.Builder(this)
                .setTitle("Discard unsaved changes?")
                .setMessage(
                    "Reopening reads the saved file again. Unsaved changes in this tab will be"
                        + " lost.")
                .setPositiveButton(
                    "Discard and reopen", (dialog, which) -> reopenDocument(d, encoding))
                .setNegativeButton("Cancel", null)
                .show();
          } else {
            reopenDocument(d, encoding);
          }
        });
  }

  private void reopenDocument(Document d, String encoding) {
    if (d.uri.isEmpty() || !documents.contains(d)) return;
    String uri = d.uri;
    if (saving.contains(d.id) || openingUris.contains(uri)) {
      toast("Wait for the current file operation to finish.");
      return;
    }
    capture();
    String originalText = d.text;
    String originalFormat = d.format();
    openingUris.add(uri);
    IO.execute(
        () -> {
          try {
            byte[] bytes = readUri(Uri.parse(uri));
            TextCodec.Decoded decoded =
                encoding == null ? TextCodec.decode(bytes) : TextCodec.decode(bytes, encoding);
            String diskHash = hash(bytes);
            runOnUiThread(
                () -> {
                  openingUris.remove(uri);
                  if (isDestroyed() || !documents.contains(d)) return;
                  capture();
                  if (!uri.equals(d.uri)
                      || !originalText.equals(d.text)
                      || !originalFormat.equals(d.format())
                      || saving.contains(d.id)) {
                    showError(
                        "File was not reopened",
                        "The document changed while it was loading. Your edits have been kept. Try"
                            + " reopening again.");
                    return;
                  }
                  d.text = decoded.text;
                  d.encoding = decoded.encoding;
                  d.lineEnding = decoded.lineEnding;
                  d.bom = decoded.bom;
                  d.diskHash = diskHash;
                  d.start = d.end = d.scrollY = 0;
                  d.markSaved(d.text, d.format());
                  d.history.reset(d.text, 0, 0);
                  if (current() == d) displayDocument();
                  else updateTabLabels();
                  schedulePersist();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  openingUris.remove(uri);
                  if (!isDestroyed())
                    showError(
                        "Cannot reopen file",
                        readableError(e) + "\n\nYour current document has been kept.");
                });
          }
        });
  }

  private void formatChanged() {
    updateTabLabels();
    updateStatus();
    schedulePersist();
  }

  private void languageDialog() {
    Document d = current();
    if (d == null) return;
    String[] names = {
      "Auto",
      "txt",
      "markdown",
      "java",
      "kotlin",
      "javascript",
      "typescript",
      "python",
      "json",
      "html",
      "xml",
      "css",
      "sql",
      "sh",
      "yaml",
      "cpp",
      "c"
    };
    new AlertDialog.Builder(this)
        .setTitle("Syntax language")
        .setItems(
            names,
            (dialog, which) -> {
              d.language = names[which];
              editor.setLanguage(d.syntax());
              updateStatus();
              schedulePersist();
            })
        .show();
  }

  private void settings() {
    String[] items = {
      "Dark theme", "Word wrap", "Line numbers", "Auto indent", "Use tabs (otherwise 4 spaces)"
    };
    boolean[] values = {dark, wrap, numbers, autoIndent, prefs.getBoolean("tabs", false)};
    new AlertDialog.Builder(this)
        .setTitle("Editor settings")
        .setMultiChoiceItems(items, values, (dialog, which, checked) -> values[which] = checked)
        .setNeutralButton("Font size", (dialog, which) -> fontDialog())
        .setPositiveButton(
            "Apply",
            (dialog, which) -> {
              capture();
              dark = values[0];
              wrap = values[1];
              numbers = values[2];
              autoIndent = values[3];
              prefs
                  .edit()
                  .putBoolean("dark", dark)
                  .putBoolean("wrap", wrap)
                  .putBoolean("numbers", numbers)
                  .putBoolean("indent", autoIndent)
                  .putBoolean("tabs", values[4])
                  .apply();
              rebuildUi();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void fontDialog() {
    String[] sizes = {"12", "14", "16", "18", "20", "24", "28"};
    new AlertDialog.Builder(this)
        .setTitle("Editor font size")
        .setItems(
            sizes,
            (dialog, which) -> {
              fontSize = Integer.parseInt(sizes[which]);
              prefs.edit().putInt("fontSize", fontSize).apply();
              editor.setEditorFontSize(fontSize);
            })
        .show();
  }

  private void rebuildUi() {
    String find = findInput.getText().toString(), replacement = replaceInput.getText().toString();
    boolean cs = caseBox.isChecked(), ww = wordBox.isChecked(), re = regexBox.isChecked();
    buildUi();
    suppressSearch = true;
    findInput.setText(find);
    replaceInput.setText(replacement);
    caseBox.setChecked(cs);
    wordBox.setChecked(ww);
    regexBox.setChecked(re);
    suppressSearch = false;
    displayDocument();
  }

  private void share() {
    if (current() == null) return;
    if (current().text.length() > 100000) {
      toast("For large documents, save the file and share it from your file manager.");
      return;
    }
    Intent intent =
        new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, current().name)
            .putExtra(Intent.EXTRA_TEXT, current().text);
    startActivity(Intent.createChooser(intent, "Share text"));
  }

  private void documentInfo() {
    Document d = current();
    if (d == null) return;
    int lines = 1;
    for (int i = 0; i < d.text.length(); i++) if (d.text.charAt(i) == '\n') lines++;
    int words = d.text.trim().isEmpty() ? 0 : d.text.trim().split("\\s+").length;
    new AlertDialog.Builder(this)
        .setTitle(d.name)
        .setMessage(
            lines
                + " lines · "
                + words
                + " words · "
                + d.text.codePointCount(0, d.text.length())
                + " characters\n\n"
                + d.encoding
                + (d.bom ? " with BOM" : "")
                + " · "
                + d.lineEnding
                + "\n"
                + (d.uri.isEmpty() ? "Local draft. Use Save to choose a file." : d.uri)
                + "\n\n"
                + (d.dirty()
                    ? "Changes have not been saved to the source file."
                    : "No unsaved changes."))
        .setPositiveButton("Done", null)
        .show();
  }

  private void shortcuts() {
    new AlertDialog.Builder(this)
        .setTitle("Keyboard shortcuts")
        .setMessage(
            "Ctrl+N   New tab\n"
                + "Ctrl+O   Open files\n"
                + "Ctrl+S   Save\n"
                + "Ctrl+Shift+S   Save as\n"
                + "Ctrl+W   Close tab\n"
                + "Ctrl+Tab   Next tab\n"
                + "Ctrl+Shift+Tab   Previous tab\n\n"
                + "Ctrl+F   Find\n"
                + "Ctrl+H   Replace\n"
                + "F3 / Shift+F3   Next / previous match\n"
                + "Ctrl+G   Go to line\n"
                + "Escape   Close search\n\n"
                + "Ctrl+Z   Undo\n"
                + "Ctrl+Y / Ctrl+Shift+Z   Redo\n"
                + "Ctrl+A / C / X / V   Select / copy / cut / paste\n"
                + "Ctrl+D   Duplicate line / selection\n"
                + "Ctrl+/   Toggle line comment\n"
                + "Tab / Shift+Tab   Indent / unindent\n"
                + "Ctrl+Plus / Minus   Font size\n\n"
                + "Regex replacement supports $1 capture groups. The empty replacement deletes"
                + " matches.")
        .setPositiveButton("Got it", null)
        .show();
  }

  private void about() {
    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.app_name) + " 1.0.5")
        .setMessage(
            "A focused text and code editor for Android.\n\n"
                + "Your drafts stay on this device. No account, ads, analytics, or internet"
                + " permission. Files are opened through Android’s file picker.\n\n"
                + "Supports up to 12 tabs and 2 MiB per file. Syntax highlighting is lightweight;"
                + " language servers, plugins, and desktop Notepad++ extensions are not"
                + " included.\n\n"
                + "Independent software, inspired by desktop text editors. Not affiliated with"
                + " Notepad++.")
        .setPositiveButton("Done", null)
        .show();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN || current() == null)
      return super.dispatchKeyEvent(event);
    int key = event.getKeyCode();
    boolean ctrl = event.isCtrlPressed(), shift = event.isShiftPressed();
    if (ctrl) {
      switch (key) {
        case KeyEvent.KEYCODE_N:
          newDocument();
          return true;
        case KeyEvent.KEYCODE_O:
          openPicker();
          return true;
        case KeyEvent.KEYCODE_S:
          saveCurrent(shift, null);
          return true;
        case KeyEvent.KEYCODE_F:
          showSearch(false);
          return true;
        case KeyEvent.KEYCODE_H:
          showSearch(true);
          return true;
        case KeyEvent.KEYCODE_G:
          goToLine();
          return true;
        case KeyEvent.KEYCODE_W:
          closeDocument(current());
          return true;
        case KeyEvent.KEYCODE_TAB:
          selectDocument((selected + (shift ? -1 : 1) + documents.size()) % documents.size());
          return true;
        case KeyEvent.KEYCODE_Z:
          if (editor.hasFocus()) {
            undo(shift);
            return true;
          }
          break;
        case KeyEvent.KEYCODE_Y:
          if (editor.hasFocus()) {
            undo(true);
            return true;
          }
          break;
        case KeyEvent.KEYCODE_D:
          if (editor.hasFocus()) {
            duplicateLine();
            return true;
          }
          break;
        case KeyEvent.KEYCODE_SLASH:
          if (editor.hasFocus()) {
            toggleComment();
            return true;
          }
          break;
        case KeyEvent.KEYCODE_EQUALS:
        case KeyEvent.KEYCODE_PLUS:
          changeFont(1);
          return true;
        case KeyEvent.KEYCODE_MINUS:
          changeFont(-1);
          return true;
      }
    }
    if (key == KeyEvent.KEYCODE_F3) {
      findNext(shift);
      return true;
    }
    if (key == KeyEvent.KEYCODE_ESCAPE && searchVisible) {
      closeSearch();
      return true;
    }
    if (key == KeyEvent.KEYCODE_TAB && editor.hasFocus() && !ctrl && !event.isAltPressed()) {
      indent(shift);
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  private void changeFont(int delta) {
    fontSize = Math.max(10, Math.min(32, fontSize + delta));
    editor.setEditorFontSize(fontSize);
    prefs.edit().putInt("fontSize", fontSize).apply();
  }

  @Override
  public void onBackPressed() {
    if (searchVisible) {
      closeSearch();
      return;
    }
    persistSession();
    super.onBackPressed();
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout.LayoutParams lp(int width, int height) {
    return new LinearLayout.LayoutParams(
        width < 0 ? width : dp(width), height < 0 ? height : dp(height));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private TextView label(String text, int size, int color) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTextSize(size);
    v.setTextColor(color);
    return v;
  }

  private GradientDrawable round(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private View icon(String glyph, String description, Runnable action) {
    FrameLayout v = new FrameLayout(this);
    v.setLayoutParams(lp(46, 46));
    v.setContentDescription(description);
    v.setTooltipText(description);
    v.setFocusable(true);
    v.setBackground(selectable());
    v.addView(new IconView(this, glyph, fg), new FrameLayout.LayoutParams(-1, -1));
    v.setOnClickListener(w -> action.run());
    return v;
  }

  private android.graphics.drawable.Drawable selectable() {
    android.util.TypedValue value = new android.util.TypedValue();
    getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
    return getDrawable(value.resourceId);
  }

  private Button button(String text, Runnable action) {
    Button v = new Button(this);
    v.setText(text);
    v.setTextSize(12);
    v.setAllCaps(false);
    v.setTextColor(accent);
    v.setMinWidth(0);
    v.setMinimumWidth(0);
    v.setPadding(dp(9), 0, dp(9), 0);
    v.setBackground(selectable());
    v.setOnClickListener(w -> action.run());
    return v;
  }

  private Button key(String text, Runnable action) {
    Button v = button(text, action);
    v.setTextColor(muted);
    v.setTypeface(Typeface.MONOSPACE);
    if (text.equals("⇤")) v.setContentDescription("Unindent");
    if (text.equals("←")) v.setContentDescription("Move cursor left");
    if (text.equals("→")) v.setContentDescription("Move cursor right");
    return v;
  }

  private EditText field(String hint, int id) {
    EditText v = new EditText(this);
    if (id != 0) v.setId(id);
    v.setSingleLine(true);
    v.setFilters(new InputFilter[] {new InputFilter.LengthFilter(10000)});
    v.setTextSize(14);
    v.setTextColor(fg);
    v.setHintTextColor(muted);
    v.setHint(hint);
    v.setContentDescription(hint);
    v.setPadding(dp(10), dp(4), dp(10), dp(4));
    v.setBackground(round(elevated, 8));
    v.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    return v;
  }

  private CheckBox check(String text, String description) {
    CheckBox box = new CheckBox(this);
    box.setText(text);
    box.setTextColor(muted);
    box.setTextSize(12);
    box.setContentDescription(description);
    box.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
    box.setMinHeight(dp(42));
    return box;
  }

  private void divider(LinearLayout parent) {
    View line = new View(this);
    line.setBackgroundColor(border);
    parent.addView(line, lp(-1, 1));
  }

  private View padded(View child) {
    LinearLayout layout = column();
    layout.setPadding(dp(20), dp(12), dp(20), dp(4));
    layout.addView(child);
    return layout;
  }

  private TextWatcher watcher(Runnable changed) {
    return new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

      public void onTextChanged(CharSequence s, int st, int before, int count) {}

      public void afterTextChanged(Editable e) {
        changed.run();
      }
    };
  }

  private void keyboard(View view) {
    view.postDelayed(
        () ->
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT),
        100);
  }

  private String readableError(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private void showError(String title, String message) {
    if (isFinishing() || isDestroyed()) return;
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message == null ? "Please try again." : message)
        .setPositiveButton("OK", null)
        .show();
  }

  private void toast(String text) {
    if (!isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
  }
}
