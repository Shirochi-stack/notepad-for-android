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

  @Test(expected = IllegalArgumentException.class)
  public void invalidSelectionIsRejected() {
    TextTransforms.duplicateLine("abc", 0, 4);
  }
}
