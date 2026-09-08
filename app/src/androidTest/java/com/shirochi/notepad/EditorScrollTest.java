package com.shirochi.notepad;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeLeft;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.CoordinatesProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.shirochi.notepad.editor.CodeEditor;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises real viewport gestures and captures the rendered native scrollbars. */
@RunWith(AndroidJUnit4.class)
public final class EditorScrollTest {
  private ActivityScenario<MainActivity> scenario;

  @Before
  public void launchCleanWorkspace() throws Exception {
    drainIo();
    Context context = context();
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
  public void verticalSwipeMovesRenderedThumbAndItRemainsVisibleInBothThemes() throws Exception {
    String source = numberedLines(180);
    scenario.onActivity(
        activity -> {
          editor(activity).setText(source);
          editor(activity).setSelection(0);
        });
    awaitActivity(activity -> editor(activity).canScrollVertically(1), "vertical overflow");
    awaitStableScroll();
    ScrollbarFrame top = screenshot("vertical-dark-top");
    assertVerticalThumbContrast(top);
    onView(withId(R.id.editor)).perform(swipeUp());
    awaitActivity(activity -> editor(activity).getScrollY() > 0, "swipe to scroll the document");
    awaitStableScroll();
    // Wait past the platform's ordinary fade interval; the position indicator must remain visible.
    SystemClock.sleep(
        ViewConfiguration.getScrollDefaultDelay()
            + ViewConfiguration.getScrollBarFadeDuration()
            + 100L);
    ScrollbarFrame scrolled = screenshot("vertical-dark-scrolled");
    assertTrue(scrolled.scrollY > top.scrollY);
    if (Build.VERSION.SDK_INT >= 29) {
      assertTrue(
          "The rendered thumb must move down with the viewport",
          scrolled.vertical.top > top.vertical.top);
    }
    assertVerticalThumbContrast(scrolled);
    scenario.onActivity(activity -> assertEquals(source, editor(activity).getText().toString()));

    toggleSetting("Dark theme");
    awaitActivity(
        activity -> !editor(activity).isDarkTheme() && editor(activity).canScrollVertically(-1),
        "light theme with restored viewport");
    awaitStableScroll();
    SystemClock.sleep(
        ViewConfiguration.getScrollDefaultDelay()
            + ViewConfiguration.getScrollBarFadeDuration()
            + 100L);
    ScrollbarFrame light = screenshot("vertical-light-scrolled");
    assertVerticalThumbContrast(light);
    scenario.onActivity(activity -> assertEquals(source, editor(activity).getText().toString()));
  }

  @Test
  public void horizontalSwipeRevealsLongLineAndWordWrapReturnsToLeftEdge() throws Exception {
    toggleSetting("Word wrap");
    String source = "BEGIN " + "column_0123456789 ".repeat(60) + " END";
    scenario.onActivity(
        activity -> {
          editor(activity).setText(source);
          editor(activity).setSelection(0);
        });
    awaitActivity(
        activity -> editor(activity).canScrollHorizontally(1), "unwrapped horizontal overflow");
    awaitStableScroll();
    ScrollbarFrame left = screenshot("horizontal-dark-left");
    onView(withId(R.id.editor)).perform(swipeLeft());
    awaitActivity(activity -> editor(activity).getScrollX() > 0, "horizontal swipe");
    awaitStableScroll();
    ScrollbarFrame right = screenshot("horizontal-dark-scrolled");
    assertTrue(right.scrollX > left.scrollX);
    if (Build.VERSION.SDK_INT >= 29) {
      assertFalse(
          "An overflowing long line must render a horizontal thumb", left.horizontal.isEmpty());
      assertTrue(
          "Horizontal thumb must follow the viewport",
          right.horizontal.left > left.horizontal.left);
    }
    scenario.onActivity(activity -> assertEquals(source, editor(activity).getText().toString()));
    toggleSetting("Word wrap");
    awaitActivity(
        activity ->
            editor(activity).isWordWrap()
                && editor(activity).getScrollX() == 0
                && !editor(activity).canScrollHorizontally(1)
                && editor(activity).getLayout() != null
                && editor(activity).getLayout().getLineCount() > 1,
        "wrapped layout at the left edge");
    screenshot("horizontal-dark-wrap-enabled");
    scenario.onActivity(activity -> assertEquals(source, editor(activity).getText().toString()));
  }

  @Test
  public void chosenFontSurvivesRecreationWithLongDraftAndSelection() {
    String source = numberedLines(80);
    AtomicInteger originalLineHeight = new AtomicInteger();
    scenario.onActivity(
        activity -> {
          editor(activity).setText(source);
          editor(activity).setSelection(source.indexOf("Line 040"));
          originalLineHeight.set(editor(activity).getLineHeight());
          invoke(activity, "fontDialog");
        });
    onView(withText("20")).inRoot(isDialog()).perform(click());
    awaitActivity(
        activity -> editor(activity).getLineHeight() > originalLineHeight.get(),
        "chosen font to reflow the draft");
    AtomicReference<Float> chosenTextSize = new AtomicReference<>();
    AtomicInteger selected = new AtomicInteger();
    AtomicInteger chosenLineHeight = new AtomicInteger();
    scenario.onActivity(
        activity -> {
          chosenTextSize.set(editor(activity).getTextSize());
          chosenLineHeight.set(editor(activity).getLineHeight());
          selected.set(editor(activity).getSelectionStart());
          assertEquals(source, editor(activity).getText().toString());
        });
    scenario.recreate();
    awaitReady();
    scenario.onActivity(
        activity -> {
          assertEquals(chosenTextSize.get(), editor(activity).getTextSize(), 0.01f);
          assertEquals(chosenLineHeight.get(), editor(activity).getLineHeight());
          assertEquals(source, editor(activity).getText().toString());
          assertEquals(selected.get(), editor(activity).getSelectionStart());
        });
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void fingerDragsAndTrackTapsNavigateVerticalBarWithoutEditingOrShowingKeyboard()
      throws Exception {
    String source = numberedLines(240);
    prepareGestureDocument(source);
    onView(withId(R.id.editor)).perform(scrollbarGesture(true, true, 0.85f));
    awaitActivity(
        activity -> editor(activity).getScrollY() > editor(activity).getLayout().getHeight() / 2,
        "finger drag far down the document");
    awaitStableScroll();
    screenshot("vertical-touch-drag");
    assertGesturePreservedDocument(source);
    onView(withId(R.id.editor)).perform(scrollbarGesture(true, false, 0.25f));
    awaitActivity(
        activity ->
            editor(activity).getScrollY() > 0
                && editor(activity).getScrollY() < editor(activity).getLayout().getHeight() / 2,
        "track tap to jump toward the document start");
    awaitStableScroll();
    onView(withId(R.id.editor)).perform(scrollbarGesture(true, true, 1.1f));
    awaitActivity(
        activity -> editor(activity).getScrollY() > 0 && !editor(activity).canScrollVertically(1),
        "drag clamped at the document end");
    awaitStableScroll();
    onView(withId(R.id.editor)).perform(scrollbarGesture(true, true, -0.1f));
    awaitActivity(
        activity -> editor(activity).getScrollY() == 0 && !editor(activity).canScrollVertically(-1),
        "drag clamped at the document start");
    assertGesturePreservedDocument(source);
    assertGesturePreservedRedo(source);

    scenario.onActivity(activity -> editor(activity).setSelection(3, 8));
    CoordinatesProvider text =
        view -> {
          CodeEditor editor = (CodeEditor) view;
          int[] location = new int[2];
          editor.getLocationOnScreen(location);
          return new float[] {
            location[0] + editor.getTotalPaddingLeft() + editor.getPaint().measureText("Line "),
            location[1] + editor.getTotalPaddingTop() + editor.getLineHeight() / 2f
          };
        };
    onView(withId(R.id.editor)).perform(touchscreenGesture(text, text, false));
    awaitActivity(
        activity ->
            editor(activity).getSelectionStart() == editor(activity).getSelectionEnd()
                && (Build.VERSION.SDK_INT < 30 || keyboardVisible(editor(activity))),
        "ordinary text tap to place the caret and show the keyboard");
    scenario.onActivity(activity -> assertEquals(source, editor(activity).getText().toString()));
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void fingerDragsAndTrackTapsNavigateHorizontalBarAndPreserveRedo() throws Exception {
    toggleSetting("Word wrap");
    String source = "BEGIN " + "column_0123456789 ".repeat(60) + " END";
    prepareGestureDocument(source);
    onView(withId(R.id.editor)).perform(scrollbarGesture(false, true, 0.85f));
    awaitActivity(
        activity ->
            editor(activity).getScrollX() > editor(activity).getLayout().getLineWidth(0) / 2,
        "finger drag far across the long line");
    awaitStableScroll();
    screenshot("horizontal-touch-drag");
    assertGesturePreservedDocument(source);
    onView(withId(R.id.editor)).perform(scrollbarGesture(false, false, 0.25f));
    awaitActivity(
        activity ->
            editor(activity).getScrollX() > 0
                && editor(activity).getScrollX() < editor(activity).getLayout().getLineWidth(0) / 2,
        "horizontal track tap to jump left");
    awaitStableScroll();
    onView(withId(R.id.editor)).perform(scrollbarGesture(false, true, 1.1f));
    awaitActivity(
        activity -> editor(activity).getScrollX() > 0 && !editor(activity).canScrollHorizontally(1),
        "drag clamped at the long line end");
    awaitStableScroll();
    onView(withId(R.id.editor)).perform(scrollbarGesture(false, true, -0.1f));
    awaitActivity(
        activity ->
            editor(activity).getScrollX() == 0 && !editor(activity).canScrollHorizontally(-1),
        "drag clamped at the long line start");
    assertGesturePreservedDocument(source);
    assertGesturePreservedRedo(source);
  }

  private void prepareGestureDocument(String source) {
    scenario.onActivity(
        activity -> {
          editor(activity).setText(source);
          editor(activity).getText().append(" later edit");
        });
    onView(withContentDescription("Undo · Ctrl+Z")).perform(click());
    scenario.onActivity(
        activity -> {
          assertEquals(source, editor(activity).getText().toString());
          editor(activity).setSelection(3, 8);
        });
    closeSoftKeyboard();
    awaitActivity(
        activity -> !keyboardVisible(editor(activity)),
        "keyboard hidden before scrollbar gestures");
    awaitStableScroll();
  }

  private void assertGesturePreservedDocument(String source) {
    scenario.onActivity(
        activity -> {
          assertEquals(
              "Scrollbar gestures must not edit the document",
              source,
              editor(activity).getText().toString());
          assertEquals(3, editor(activity).getSelectionStart());
          assertEquals(8, editor(activity).getSelectionEnd());
          assertFalse(
              "Scrollbar touch must not summon the keyboard", keyboardVisible(editor(activity)));
        });
  }

  private void assertGesturePreservedRedo(String source) {
    onView(withContentDescription("Redo · Ctrl+Y")).perform(click());
    scenario.onActivity(
        activity -> assertEquals(source + " later edit", editor(activity).getText().toString()));
    onView(withContentDescription("Undo · Ctrl+Z")).perform(click());
    scenario.onActivity(activity -> assertEquals(source, editor(activity).getText().toString()));
  }

  private static boolean keyboardVisible(CodeEditor editor) {
    if (Build.VERSION.SDK_INT < 30) return false;
    WindowInsets insets = editor.getRootWindowInsets();
    return insets != null && insets.isVisible(WindowInsets.Type.ime());
  }

  private static ViewAction scrollbarGesture(boolean vertical, boolean drag, float targetFraction) {
    CoordinatesProvider destination =
        view -> scrollbarPoint((CodeEditor) view, vertical, false, targetFraction);
    CoordinatesProvider start =
        drag ? view -> scrollbarPoint((CodeEditor) view, vertical, true, 0) : destination;
    return touchscreenGesture(start, destination, drag);
  }

  private static float[] scrollbarPoint(
      CodeEditor editor, boolean vertical, boolean thumb, float fraction) {
    if (Build.VERSION.SDK_INT < 29)
      throw new AssertionError("Scrollbar drawable inspection needs Android 10+");
    Drawable drawable =
        vertical
            ? (thumb
                ? editor.getVerticalScrollbarThumbDrawable()
                : editor.getVerticalScrollbarTrackDrawable())
            : (thumb
                ? editor.getHorizontalScrollbarThumbDrawable()
                : editor.getHorizontalScrollbarTrackDrawable());
    assertNotNull("The rendered scrollbar must have a drawable", drawable);
    Rect bounds = viewportBounds(drawable, editor);
    assertFalse("The scrollbar must be rendered before touching it", bounds.isEmpty());
    float x = bounds.centerX(), y = bounds.centerY();
    if (!thumb) {
      if (vertical) y = bounds.top + bounds.height() * fraction;
      else x = bounds.left + bounds.width() * fraction;
    }
    // End beyond the track to exercise clamping, while keeping the touch on the display.
    x = Math.max(2, Math.min(editor.getWidth() - 2, x));
    y = Math.max(2, Math.min(editor.getHeight() - 2, y));
    int[] location = new int[2];
    editor.getLocationOnScreen(location);
    return new float[] {location[0] + x, location[1] + y};
  }

  private static ViewAction touchscreenGesture(
      CoordinatesProvider start, CoordinatesProvider end, boolean drag) {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints() {
        return isDisplayed();
      }

      @Override
      public String getDescription() {
        return drag ? "drag with a touchscreen finger" : "tap with a touchscreen finger";
      }

      @Override
      public void perform(UiController controller, View view) {
        float[] from = start.calculateCoordinates(view), to = end.calculateCoordinates(view);
        long downTime = SystemClock.uptimeMillis();
        injectFinger(controller, downTime, MotionEvent.ACTION_DOWN, from[0], from[1]);
        if (drag) {
          for (int i = 1; i <= 18; i++) {
            controller.loopMainThreadForAtLeast(16);
            float fraction = i / 18f;
            injectFinger(
                controller,
                downTime,
                MotionEvent.ACTION_MOVE,
                from[0] + (to[0] - from[0]) * fraction,
                from[1] + (to[1] - from[1]) * fraction);
          }
        }
        controller.loopMainThreadForAtLeast(32);
        injectFinger(controller, downTime, MotionEvent.ACTION_UP, to[0], to[1]);
        controller.loopMainThreadUntilIdle();
      }
    };
  }

  private static void injectFinger(
      UiController controller, long downTime, int action, float x, float y) {
    MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
    pointer.id = 0;
    pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
    coordinates.x = x;
    coordinates.y = y;
    coordinates.pressure = 1;
    coordinates.size = 1;
    MotionEvent event =
        MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            1,
            new MotionEvent.PointerProperties[] {pointer},
            new MotionEvent.PointerCoords[] {coordinates},
            0,
            0,
            1,
            1,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0);
    try {
      assertTrue(
          "Android must accept the touchscreen gesture event", controller.injectMotionEvent(event));
    } catch (Exception error) {
      throw new AssertionError(error);
    } finally {
      event.recycle();
    }
  }

  private void toggleSetting(String label) {
    scenario.onActivity(activity -> invoke(activity, "settings"));
    onView(withText(label)).inRoot(isDialog()).perform(click());
    onView(withText("Apply")).inRoot(isDialog()).perform(click());
    awaitReady();
  }

  private ScrollbarFrame screenshot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    ScrollbarFrame frame = new ScrollbarFrame();
    scenario.onActivity(
        activity -> {
          CodeEditor editor = editor(activity);
          frame.scrollX = editor.getScrollX();
          frame.scrollY = editor.getScrollY();
          frame.height = editor.getHeight();
          frame.paddingBottom = editor.getPaddingBottom();
          editor.getLocationOnScreen(frame.location);
          if (Build.VERSION.SDK_INT >= 29) {
            frame.vertical = viewportBounds(editor.getVerticalScrollbarThumbDrawable(), editor);
            frame.horizontal = viewportBounds(editor.getHorizontalScrollbarThumbDrawable(), editor);
          }
        });
    Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull("The displayed editor must be available for visual verification", bitmap);
    File directory = context().getExternalFilesDir("scrollbar-test-screenshots");
    assertNotNull(directory);
    assertTrue(directory.isDirectory() || directory.mkdirs());
    try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
    }
    if (Build.VERSION.SDK_INT >= 29 && !frame.vertical.isEmpty()) {
      int x = frame.location[0] + frame.vertical.centerX();
      int thumbY = frame.location[1] + frame.vertical.centerY();
      int trackY = frame.location[1] + frame.height - frame.paddingBottom - 8;
      assertTrue(x >= 0 && x < bitmap.getWidth());
      assertTrue(thumbY >= 0 && thumbY < bitmap.getHeight());
      assertTrue(trackY >= 0 && trackY < bitmap.getHeight());
      frame.thumbPixel = bitmap.getPixel(x, thumbY);
      frame.trackPixel = bitmap.getPixel(x, trackY);
    }
    bitmap.recycle();
    return frame;
  }

  private static Rect viewportBounds(Drawable drawable, CodeEditor editor) {
    if (drawable == null) return new Rect();
    Rect result = new Rect(drawable.getBounds());
    result.offset(-editor.getScrollX(), -editor.getScrollY());
    return result;
  }

  private static void assertVerticalThumbContrast(ScrollbarFrame frame) {
    if (Build.VERSION.SDK_INT < 29) return;
    assertFalse("Overflow must produce a rendered vertical thumb", frame.vertical.isEmpty());
    int difference =
        Math.abs(Color.red(frame.thumbPixel) - Color.red(frame.trackPixel))
            + Math.abs(Color.green(frame.thumbPixel) - Color.green(frame.trackPixel))
            + Math.abs(Color.blue(frame.thumbPixel) - Color.blue(frame.trackPixel));
    assertTrue(
        "The visible thumb must contrast with its track in the captured screen", difference > 40);
  }

  private void awaitReady() {
    awaitActivity(
        activity ->
            !readBoolean(activity, "loading")
                && editor(activity) != null
                && editor(activity).getLayout() != null,
        "editor layout");
  }

  private void awaitStableScroll() {
    int[] previous = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    AtomicInteger stable = new AtomicInteger();
    awaitActivity(
        activity -> {
          int x = editor(activity).getScrollX(), y = editor(activity).getScrollY();
          int width = editor(activity).getWidth(), height = editor(activity).getHeight();
          if (x == previous[0] && y == previous[1] && width == previous[2] && height == previous[3])
            stable.incrementAndGet();
          else stable.set(0);
          previous[0] = x;
          previous[1] = y;
          previous[2] = width;
          previous[3] = height;
          return stable.get() >= 6;
        },
        "scroll gesture to settle");
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

  private static String numberedLines(int count) {
    StringBuilder source = new StringBuilder();
    for (int i = 1; i <= count; i++) {
      source.append(
          String.format(Locale.ROOT, "Line %03d  Android text editor scrolling sample.%n", i));
    }
    return source.toString();
  }

  private static Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private static CodeEditor editor(MainActivity activity) {
    return activity.findViewById(R.id.editor);
  }

  private static boolean readBoolean(MainActivity activity, String name) {
    try {
      Field field = MainActivity.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.getBoolean(activity);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void invoke(MainActivity activity, String name) {
    try {
      Method method = MainActivity.class.getDeclaredMethod(name);
      method.setAccessible(true);
      method.invoke(activity);
    } catch (InvocationTargetException error) {
      throw new AssertionError(error.getCause());
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void drainIo() throws Exception {
    Field field = MainActivity.class.getDeclaredField("IO");
    field.setAccessible(true);
    ((ExecutorService) field.get(null)).submit(() -> {}).get(10, TimeUnit.SECONDS);
  }

  private static final class ScrollbarFrame {
    int scrollX, scrollY, height, paddingBottom, thumbPixel, trackPixel;
    final int[] location = new int[2];
    Rect vertical = new Rect();
    Rect horizontal = new Rect();
  }
}
