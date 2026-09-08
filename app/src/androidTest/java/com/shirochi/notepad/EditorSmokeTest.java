package com.shirochi.notepad;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.shirochi.notepad.editor.CodeEditor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises native Android editing, shortcuts and asynchronous draft recovery. */
@RunWith(AndroidJUnit4.class)
public final class EditorSmokeTest {
  private ActivityScenario<MainActivity> scenario;

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
    if (scenario != null) scenario.close();
    drainIo();
  }

  @Test
  public void ctrlFindOpensSearchAndF3NavigatesMatches() {
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText("alpha beta alpha");
          editor.setSelection(0);
          editor.requestFocus();
          shortcut(activity, KeyEvent.KEYCODE_F, false);
          assertEquals(View.VISIBLE, activity.findViewById(R.id.find_input).getVisibility());
          assertTrue(activity.findViewById(R.id.find_input).isShown());
          assertFalse(activity.findViewById(R.id.replace_input).isShown());
          ((EditText) activity.findViewById(R.id.find_input)).setText("alpha");
          key(activity, KeyEvent.KEYCODE_F3, 0);
          assertEquals(0, editor.getSelectionStart());
          assertEquals(5, editor.getSelectionEnd());
          key(activity, KeyEvent.KEYCODE_F3, 0);
          assertEquals(11, editor.getSelectionStart());
          assertEquals(16, editor.getSelectionEnd());
          key(activity, KeyEvent.KEYCODE_F3, KeyEvent.META_SHIFT_ON);
          assertEquals(0, editor.getSelectionStart());
          key(activity, KeyEvent.KEYCODE_ESCAPE, 0);
          assertFalse(activity.findViewById(R.id.find_input).isShown());
          assertEquals("alpha beta alpha", editor.getText().toString());
        });
  }

  @Test
  public void ctrlReplaceAllIsOneUndoableEditAndRedoRestoresIt() {
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText("cat cat\ncat");
          editor.setSelection(0);
          editor.requestFocus();
          shortcut(activity, KeyEvent.KEYCODE_H, false);
          assertTrue(activity.findViewById(R.id.replace_input).isShown());
          ((EditText) activity.findViewById(R.id.find_input)).setText("cat");
          ((EditText) activity.findViewById(R.id.replace_input)).setText("dog");
          View all = findText(activity.getWindow().getDecorView(), "All");
          assertNotNull("Replace All action must be present", all);
          assertTrue(all.performClick());
          assertEquals("dog dog\ndog", editor.getText().toString());
          key(activity, KeyEvent.KEYCODE_ESCAPE, 0);
          shortcut(activity, KeyEvent.KEYCODE_Z, false);
          assertEquals("cat cat\ncat", editor.getText().toString());
          shortcut(activity, KeyEvent.KEYCODE_Y, false);
          assertEquals("dog dog\ndog", editor.getText().toString());
        });
  }

  @Test
  public void paragraphMenuFormatsWholeDocumentOnceAndSupportsUndoRedo() {
    String source = "<p>one</p><P>two</P>\n<p>three</p>";
    String formatted = "<p>one</p>\n<P>two</P>\n<p>three</p>\n";
    scenario.onActivity(
        activity -> {
          editor(activity).setText(source);
          // A caret in the middle still applies the command to the entire document.
          editor(activity).setSelection(source.indexOf("two"));
        });
    paragraphMenuCommand();
    scenario.onActivity(activity -> assertEquals(formatted, editor(activity).getText().toString()));
    paragraphMenuCommand();
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          assertEquals(
              "Repeating the command must not add blank lines",
              formatted,
              editor.getText().toString());
          editor.requestFocus();
          shortcut(activity, KeyEvent.KEYCODE_Z, false);
          assertEquals(
              "The complete formatting operation needs only one Undo",
              source,
              editor.getText().toString());
          shortcut(activity, KeyEvent.KEYCODE_Y, false);
          assertEquals(formatted, editor.getText().toString());
        });
  }

  @Test
  public void paragraphMenuLimitsChangesToReversedSelection() {
    String before = "<p>leave before</p>";
    String selected = "<p>selected one</p><p>selected two</p>";
    String after = "<p>leave after</p>";
    String source = before + selected + after;
    int start = before.length();
    int end = start + selected.length();
    scenario.onActivity(
        activity -> {
          editor(activity).setText(source);
          editor(activity).setSelection(end, start);
        });
    paragraphMenuCommand();
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          assertEquals(
              before + "<p>selected one</p>\n<p>selected two</p>\n" + after,
              editor.getText().toString());
          editor.requestFocus();
          shortcut(activity, KeyEvent.KEYCODE_Z, false);
          assertEquals(source, editor.getText().toString());
          assertEquals(end, editor.getSelectionStart());
          assertEquals(start, editor.getSelectionEnd());
        });
  }

  @Test
  public void paragraphMenuWithoutMatchingSelectedTagPreservesSelectionAndRedo() {
    String source = "<p>outside selection</p>plain text";
    String subsequentEdit = source + " later edit";
    int start = source.indexOf("plain");
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText(source);
          editor.getText().append(" later edit");
          editor.requestFocus();
          shortcut(activity, KeyEvent.KEYCODE_Z, false);
          assertEquals(source, editor.getText().toString());
          editor.setSelection(start, start + 5);
        });
    paragraphMenuCommand();
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          assertEquals(
              "No matching selected tag must leave the whole document unchanged",
              source,
              editor.getText().toString());
          assertEquals(start, editor.getSelectionStart());
          assertEquals(start + 5, editor.getSelectionEnd());
          editor.requestFocus();
          shortcut(activity, KeyEvent.KEYCODE_Y, false);
          assertEquals(
              "A no-op command must retain an existing Redo",
              subsequentEdit,
              editor.getText().toString());
        });
  }

  @Test
  public void tabsPreserveIndependentTextSelectionAndHistory() {
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText("first document");
          editor.setSelection(4);
          shortcut(activity, KeyEvent.KEYCODE_N, false);
          assertEquals("", editor.getText().toString());
          editor.setText("second document");
          editor.setSelection(8);
          shortcut(activity, KeyEvent.KEYCODE_TAB, false);
          assertEquals("first document", editor.getText().toString());
          assertEquals(4, editor.getSelectionStart());
          shortcut(activity, KeyEvent.KEYCODE_TAB, true);
          assertEquals("second document", editor.getText().toString());
          assertEquals(8, editor.getSelectionStart());
          editor.requestFocus();
          shortcut(activity, KeyEvent.KEYCODE_Z, false);
          assertEquals("", editor.getText().toString());
          shortcut(activity, KeyEvent.KEYCODE_TAB, false);
          assertEquals("first document", editor.getText().toString());
        });
  }

  @Test
  public void activityRecreationRecoversDraftTabsAndSelection() throws Exception {
    scenario.onActivity(
        activity -> {
          assertFalse(
              "Large documents belong in atomic draft storage, not saved view state",
              editor(activity).isSaveEnabled());
          editor(activity).setText("Recovered first draft\nمرحبا 🌿");
          editor(activity).setSelection(10);
          shortcut(activity, KeyEvent.KEYCODE_N, false);
          editor(activity).setText("Recovered second draft");
          editor(activity).setSelection(3, 9);
        });
    // onPause/onSaveInstanceState enqueue the write before the new activity's read.
    scenario.recreate();
    awaitReady();
    drainIo();
    scenario.onActivity(
        activity -> {
          assertEquals("Recovered second draft", editor(activity).getText().toString());
          assertEquals(3, editor(activity).getSelectionStart());
          assertEquals(9, editor(activity).getSelectionEnd());
          shortcut(activity, KeyEvent.KEYCODE_TAB, false);
          assertEquals("Recovered first draft\nمرحبا 🌿", editor(activity).getText().toString());
          assertEquals(10, editor(activity).getSelectionStart());
        });
  }

  @Test
  public void incomingSharedTextAtTabLimitPreservesCurrentDocument() {
    scenario.onActivity(
        activity -> {
          editor(activity).setText("Keep the first document");
          for (int i = 1; i < 12; i++) shortcut(activity, KeyEvent.KEYCODE_N, false);
          editor(activity).setText("Keep the twelfth document");
          Intent launchIntent = activity.getIntent();
          activity.onNewIntent(
              new Intent(activity, MainActivity.class)
                  .setAction(Intent.ACTION_SEND)
                  .setType("text/plain")
                  .putExtra(Intent.EXTRA_TEXT, "Incoming shared text"));
          // ActivityScenario identifies the activity by its launch intent during close().
          activity.setIntent(launchIntent);
          assertEquals("Keep the twelfth document", editor(activity).getText().toString());
          shortcut(activity, KeyEvent.KEYCODE_TAB, false);
          assertEquals("Keep the first document", editor(activity).getText().toString());
        });
  }

  @Test
  public void reversedSelectionSupportsPairsAndCaseConversion() {
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText("abcdef");
          editor.setSelection(5, 2);
          View pair = findText(activity.getWindow().getDecorView(), "( )");
          assertNotNull(pair);
          assertTrue(pair.performClick());
          assertEquals("ab(cde)f", editor.getText().toString());
        });
    // Finish the keyboard resize before a dialog item receives a coordinate-based Espresso tap.
    closeSoftKeyboard();
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText("abcdef");
          editor.setSelection(5, 2);
          invoke(activity, "convertCase");
        });
    onView(withText("UPPERCASE")).inRoot(isDialog()).perform(click());
    scenario.onActivity(activity -> assertEquals("abCDEf", editor(activity).getText().toString()));
  }

  @Test
  public void zeroWidthRegexReplacementAdvancesToLaterMatches() {
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText("one two");
          editor.setSelection(0);
          shortcut(activity, KeyEvent.KEYCODE_H, false);
          ((EditText) activity.findViewById(R.id.find_input)).setText("\\b");
          ((EditText) activity.findViewById(R.id.replace_input)).setText("-");
          View regex = findDescription(activity.getWindow().getDecorView(), "Regular expression");
          assertNotNull(regex);
          ((CheckBox) regex).setChecked(true);
          View replace =
              findText((View) activity.findViewById(R.id.replace_input).getParent(), "Replace");
          assertNotNull(replace);
          assertTrue(replace.performClick());
          assertEquals("-one two", editor.getText().toString());
          assertEquals(4, editor.getSelectionStart());
          assertTrue(replace.performClick());
          assertEquals("-one- two", editor.getText().toString());
          assertEquals(6, editor.getSelectionStart());
        });
  }

  @Test
  public void wrappingUsesLogicalLineNumbersAndUpdatesAfterEdits() {
    String longLine = "A long line of readable text that should wrap on a phone. ".repeat(8);
    String source = longLine + "\nsecond\n";
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText(source);
          editor.setSelection(0);
          editor.setWordWrap(true);
          assertEquals(3, editor.getLogicalLineCount());
          assertEquals(1, editor.getLogicalLineForOffset(longLine.length()));
          assertEquals(2, editor.getLogicalLineForOffset(longLine.length() + 1));
          assertEquals(longLine.length() + 1, editor.getOffsetForLogicalLine(2));
        });
    awaitActivity(
        activity ->
            editor(activity).getLayout() != null && editor(activity).getLayout().getLineCount() > 3,
        "wrapped visual lines");
    scenario.onActivity(activity -> editor(activity).setWordWrap(false));
    awaitActivity(
        activity ->
            editor(activity).getLayout() != null
                && editor(activity).getLayout().getLineCount() == 3,
        "unwrapped logical lines");
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          assertEquals(3, editor.getLogicalLineCount());
          int withGutter = editor.getPaddingLeft();
          editor.setShowLineNumbers(false);
          assertFalse(editor.isShowingLineNumbers());
          assertTrue(editor.getPaddingLeft() < withGutter);
          editor.setShowLineNumbers(true);
          assertEquals(withGutter, editor.getPaddingLeft());
          editor.getText().replace(longLine.length(), longLine.length() + 1, " / ");
          assertEquals(2, editor.getLogicalLineCount());
          editor.getText().insert(0, "top\nnext\n");
          assertEquals(4, editor.getLogicalLineCount());
          assertEquals(4, editor.getOffsetForLogicalLine(2));
          assertEquals(9, editor.getOffsetForLogicalLine(3));
        });
  }

  @Test
  public void highlightingAndThemeChangesNeverEmitTextEdits() {
    AtomicInteger callbacks = new AtomicInteger();
    String source = "public class Example {\n    String label = \"Hello\"; // note\n}";
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          editor.setText(source);
          editor.setSelection(7, 12);
          editor.addTextChangedListener(
              new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                  callbacks.incrementAndGet();
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                  callbacks.incrementAndGet();
                }

                @Override
                public void afterTextChanged(Editable s) {
                  callbacks.incrementAndGet();
                }
              });
          editor.setLanguage("java");
          editor.setSearchMatches(Collections.singletonList(new int[] {7, 12}), 0);
          editor.setEditorTheme(false);
        });
    awaitActivity(
        activity ->
            editor(activity)
                    .getText()
                    .getSpans(0, source.length(), ForegroundColorSpan.class)
                    .length
                > 0,
        "syntax spans to be applied");
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          assertTrue(
              editor.getText().getSpans(0, source.length(), BackgroundColorSpan.class).length > 0);
          assertEquals(source, editor.getText().toString());
          assertEquals(7, editor.getSelectionStart());
          assertEquals(12, editor.getSelectionEnd());
          assertEquals(
              "Decoration must never dirty the document or its undo history", 0, callbacks.get());
        });
  }

  private void awaitReady() {
    awaitActivity(
        activity -> !readBoolean(activity, "loading") && editor(activity) != null,
        "initial document recovery");
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

  private static CodeEditor editor(MainActivity activity) {
    return activity.findViewById(R.id.editor);
  }

  private static void paragraphMenuCommand() {
    onView(withContentDescription("More options")).perform(click());
    onView(withText("Line break after </p>")).perform(click());
  }

  private static void shortcut(MainActivity activity, int keyCode, boolean shift) {
    key(activity, keyCode, KeyEvent.META_CTRL_ON | (shift ? KeyEvent.META_SHIFT_ON : 0));
  }

  private static void key(MainActivity activity, int keyCode, int metaState) {
    long time = SystemClock.uptimeMillis();
    assertTrue(
        "Keyboard command should be handled",
        activity.dispatchKeyEvent(
            new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)));
    activity.dispatchKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, metaState));
  }

  private static View findText(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static boolean readBoolean(MainActivity activity, String name) {
    try {
      Field field = MainActivity.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.getBoolean(activity);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findDescription(group.getChildAt(i), description);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void invoke(MainActivity activity, String name) {
    try {
      Method method = MainActivity.class.getDeclaredMethod(name);
      method.setAccessible(true);
      method.invoke(activity);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void drainIo() throws Exception {
    Field field = MainActivity.class.getDeclaredField("IO");
    field.setAccessible(true);
    ((ExecutorService) field.get(null)).submit(() -> {}).get(10, TimeUnit.SECONDS);
  }
}
