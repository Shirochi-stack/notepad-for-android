package com.shirochi.notepad.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class TextTransformsTest {
  @Test
  public void indentSingleLinePreservesCursorColumnRelativeToText() {
    TextTransforms.Result result = TextTransforms.indent("abc\ndef", 1, 1, "    ");
    assertEquals("    abc\ndef", result.text);
    assertEquals(5, result.start);
    assertEquals(5, result.end);
  }

  @Test
  public void selectionEndingAtNextLineStartDoesNotIndentThatLine() {
    TextTransforms.Result result = TextTransforms.indent("a\nb\nc", 0, 4, "\t");
    assertEquals("\ta\n\tb\nc", result.text);
    assertEquals(1, result.start);
    assertEquals(6, result.end);
  }

  @Test
  public void unindentHandlesMixedTabsAndPartialSpaces() {
    TextTransforms.Result result = TextTransforms.unindent("\ta\n  b\n      c", 0, 14, "    ");
    assertEquals("a\nb\n  c", result.text);
    assertEquals(0, result.start);
    assertEquals(7, result.end);
  }

  @Test
  public void unindentClampsCursorWithinRemovedIndentation() {
    TextTransforms.Result result = TextTransforms.unindent("    abc", 2, 2, "    ");
    assertEquals("abc", result.text);
    assertEquals(0, result.start);
  }

  @Test
  public void duplicateTerminatedLineKeepsFollowingLineAndColumn() {
    TextTransforms.Result result = TextTransforms.duplicateLine("abc\ndef", 2, 2);
    assertEquals("abc\nabc\ndef", result.text);
    assertEquals(6, result.start);
  }

  @Test
  public void duplicateLastLineAddsSeparator() {
    TextTransforms.Result result = TextTransforms.duplicateLine("abc\ndef", 5, 5);
    assertEquals("abc\ndef\ndef", result.text);
    assertEquals(9, result.start);
  }

  @Test
  public void duplicateSelectedLinesExcludesExclusiveEndLine() {
    TextTransforms.Result result = TextTransforms.duplicateLine("a\nb\nc", 0, 4);
    assertEquals("a\nb\na\nb\nc", result.text);
    assertEquals(4, result.start);
    assertEquals(8, result.end);
  }

  @Test
  public void duplicateEmptyDocumentCreatesEmptyLine() {
    TextTransforms.Result result = TextTransforms.duplicateLine("", 0, 0);
    assertEquals("\n", result.text);
    assertEquals(1, result.start);
  }

  @Test
  public void commentTogglePreservesIndentationAndBlankLines() {
    String original = "  one\n\n\ttwo";
    TextTransforms.Result commented =
        TextTransforms.toggleLineComment(original, 0, original.length(), "//");
    assertEquals("  // one\n\n\t// two", commented.text);
    TextTransforms.Result restored =
        TextTransforms.toggleLineComment(commented.text, 0, commented.text.length(), "//");
    assertEquals(original, restored.text);
  }

  @Test
  public void commentToggleSupportsHashCommentsWithoutFollowingSpace() {
    TextTransforms.Result result = TextTransforms.toggleLineComment("#one\n  # two", 0, 12, "#");
    assertEquals("one\n  two", result.text);
  }

  @Test
  public void trailingEmptyLineCanBeIndented() {
    TextTransforms.Result result = TextTransforms.indent("abc\n", 4, 4, "\t");
    assertEquals("abc\n\t", result.text);
    assertEquals(5, result.start);
  }

  @Test
  public void paragraphBreaksSeparateConsecutiveParagraphsAndTerminateFinalTag() {
    String original = "<p>First paragraph</p><p>Second paragraph</p>";
    TextTransforms.Result result = TextTransforms.breakAfterParagraphTags(original, 0, 0);
    assertEquals("<p>First paragraph</p>\n<p>Second paragraph</p>\n", result.text);
    assertEquals(0, result.start);
    assertEquals(0, result.end);
  }

  @Test
  public void paragraphBreaksMatchCaseAndHorizontalWhitespaceWithoutChangingOtherTags() {
    String original = "<P>A</P><p>B</p \t><div>C</div></pre></p-extra><p>";
    TextTransforms.Result result = TextTransforms.breakAfterParagraphTags(original, 0, 0);
    assertEquals("<P>A</P>\n<p>B</p \t>\n<div>C</div></pre></p-extra><p>", result.text);
  }

  @Test
  public void paragraphBreaksPreserveExistingBreaksAndWhitespaceAndAreIdempotent() {
    String original = "<p>A</p>\n<p>B</p> \t\n<p>C</p>\n\n<p>D</p>  ";
    TextTransforms.Result first = TextTransforms.breakAfterParagraphTags(original, 0, 0);
    assertEquals("<p>A</p>\n<p>B</p> \t\n<p>C</p>\n\n<p>D</p>\n  ", first.text);
    TextTransforms.Result second = TextTransforms.breakAfterParagraphTags(first.text, 0, 0);
    assertEquals(first.text, second.text);
    assertEquals(first.start, second.start);
    assertEquals(first.end, second.end);
  }

  @Test
  public void paragraphBreaksOnlyProcessCompleteTagsWithinSelection() {
    String original = "<p>One</p><p>Two</p><p>Three</p><p>Four</p>";
    int start = original.indexOf("</p>") + 1; // Excludes '<' of the first closing tag.
    int end = original.indexOf("</p>", original.indexOf("Three")) + 3; // Excludes third '>'.
    TextTransforms.Result result = TextTransforms.breakAfterParagraphTags(original, start, end);
    assertEquals("<p>One</p><p>Two</p>\n<p>Three</p><p>Four</p>", result.text);
    assertEquals(start, result.start);
    assertEquals(end + 1, result.end);
  }

  @Test
  public void paragraphBreaksPreserveReversedSelectionAndIncludeInsertedBoundaryBreak() {
    String original = "before</p><p>Middle</p>after";
    int low = original.indexOf("</p>");
    int high = original.indexOf("after");
    TextTransforms.Result result = TextTransforms.breakAfterParagraphTags(original, high, low);
    assertEquals("before</p>\n<p>Middle</p>\nafter", result.text);
    assertEquals(high + 2, result.start);
    assertEquals(low, result.end);
    assertEquals("</p>\n<p>Middle</p>\n", result.text.substring(result.end, result.start));
  }

  @Test
  public void paragraphBreaksRecognizeExistingNewlineOutsideSelection() {
    String original = "<p>A</p> \t\n<p>B</p>";
    int end = original.indexOf("</p>") + 4;
    TextTransforms.Result result = TextTransforms.breakAfterParagraphTags(original, 0, end);
    assertEquals(original, result.text);
    assertEquals(0, result.start);
    assertEquals(end, result.end);
  }

  @Test
  public void paragraphBreaksMapCaretBeforeInsideAfterAndAtInsertionBoundary() {
    String original = "x</p>y</p>z";
    for (int caret = 0; caret <= original.length(); caret++) {
      TextTransforms.Result result = TextTransforms.breakAfterParagraphTags(original, caret, caret);
      assertEquals("x</p>\ny</p>\nz", result.text);
      int expected = caret + (caret >= 5 ? 1 : 0) + (caret >= 10 ? 1 : 0);
      assertEquals("Caret " + caret, expected, result.start);
      assertEquals(result.start, result.end);
      assertTrue(result.start >= 0 && result.start <= result.text.length());
    }
  }

  @Test
  public void paragraphBreaksMoveEndOfFileCaretPastNewFinalBreak() {
    String original = "<p>🌿</p>";
    TextTransforms.Result result =
        TextTransforms.breakAfterParagraphTags(original, original.length(), original.length());
    assertEquals(original + "\n", result.text);
    assertEquals(result.text.length(), result.start);
    assertEquals(result.start, result.end);
  }

  @Test
  public void paragraphBreaksHandleEmptyTextAndIncompleteTagsWithoutChanges() {
    for (String original :
        new String[] {"", "plain text", "<p>opening", "</p", "</p \t", "</p\n>"}) {
      TextTransforms.Result result =
          TextTransforms.breakAfterParagraphTags(original, original.length(), original.length());
      assertEquals(original, result.text);
      assertEquals(original.length(), result.start);
      assertEquals(result.start, result.end);
    }
  }

  @Test
  public void paragraphBreaksRejectOutOfBoundsSelections() {
    for (int[] selection : new int[][] {{-1, 0}, {0, -1}, {5, 0}, {0, 5}}) {
      try {
        TextTransforms.breakAfterParagraphTags("</p>", selection[0], selection[1]);
        fail("Out-of-bounds selection should be rejected");
      } catch (IllegalArgumentException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void invalidSelectionIsRejected() {
    TextTransforms.duplicateLine("abc", 0, 4);
  }
}
