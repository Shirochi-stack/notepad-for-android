package com.shirochi.notepad.core;

import static org.junit.Assert.*;

import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.Test;

public class SearchEngineTest {
  @Test
  public void literalSearchDoesNotInterpretRegex() {
    List<SearchEngine.Match> result =
        SearchEngine.findAll("a.b axb a.b", "a.b", true, false, false);
    assertEquals(2, result.size());
    assertEquals(0, result.get(0).start);
    assertEquals(3, result.get(0).end);
    assertEquals(8, result.get(1).start);
  }

  @Test
  public void caseInsensitiveUsesUnicodeCaseFolding() {
    assertEquals(2, SearchEngine.findAll("École école", "école", false, false, false).size());
    assertEquals(1, SearchEngine.findAll("École école", "école", true, false, false).size());
  }

  @Test
  public void wholeWordsRespectUnicodeLettersMarksAndConnectors() {
    assertEquals(
        1,
        SearchEngine.findAll("cat caté cat_ cat\u0301 \uD801\uDC00cat", "cat", true, true, false)
            .size());
  }

  @Test
  public void matchOffsetsAreUtf16ForAndroidSelections() {
    SearchEngine.Match match =
        SearchEngine.findAll("\uD83D\uDE00 cat", "cat", true, true, false).get(0);
    assertEquals(3, match.start);
    assertEquals(6, match.end);
  }

  @Test
  public void emptyQueryIsNoOp() {
    assertTrue(SearchEngine.findAll("text", "", true, false, true).isEmpty());
    assertEquals("text", SearchEngine.replaceAll("text", "", "x", true, false, true));
  }

  @Test
  public void zeroWidthMatchesAdvanceAndReplace() {
    List<SearchEngine.Match> matches = SearchEngine.findAll("aa", "(?=a)", true, false, true);
    assertEquals(2, matches.size());
    assertEquals(0, matches.get(0).start);
    assertEquals(0, matches.get(0).end);
    assertEquals(1, matches.get(1).start);
    assertEquals("xaxa", SearchEngine.replaceAll("aa", "(?=a)", "x", true, false, true));
  }

  @Test
  public void literalReplacementPreservesDollarAndBackslash() {
    assertEquals(
        "$1\\name $1\\name", SearchEngine.replaceAll("a a", "a", "$1\\name", true, false, false));
  }

  @Test
  public void regexReplacementExpandsNumberedAndNamedCaptures() {
    assertEquals(
        "12:cat 4:dog",
        SearchEngine.replaceAll(
            "cat12 dog4", "(?<word>[a-z]+)([0-9]+)", "$2:${word}", true, false, true));
  }

  @Test
  public void wholeWordReplacementPreservesRejectedMatches() {
    assertEquals(
        "bobcat dog cat_ dog",
        SearchEngine.replaceAll("bobcat cat cat_ cat", "cat", "dog", true, true, false));
  }

  @Test
  public void replaceOneUsesSurroundingLookbehindAndCaptures() {
    assertEquals(
        "x:1 x:22",
        SearchEngine.replaceOne("x:1 x:2", "(?<=x:)([0-9])", "$1$1", true, false, true, 6, 7));
  }

  @Test
  public void replaceOneReplacesOnlyRequestedOccurrence() {
    assertEquals(
        "one TWO one",
        SearchEngine.replaceOne("one one one", "one", "TWO", true, true, false, 4, 7));
  }

  @Test(expected = IllegalArgumentException.class)
  public void replaceOneRejectsStaleRange() {
    SearchEngine.replaceOne("one", "one", "two", true, false, false, 0, 2);
  }

  @Test(expected = PatternSyntaxException.class)
  public void malformedRegexIsReported() {
    SearchEngine.findAll("x", "[", true, false, true);
  }

  @Test(expected = IllegalArgumentException.class)
  public void invalidCaptureDoesNotSilentlyCorruptText() {
    SearchEngine.replaceAll("cat", "(cat)", "$2", true, false, true);
  }

  @Test(expected = IllegalArgumentException.class)
  public void malformedReplacementIsReported() {
    SearchEngine.replaceAll("cat", "cat", "$", true, false, true);
  }

  @Test(expected = IllegalArgumentException.class)
  public void excessiveSearchResultsAreBounded() {
    SearchEngine.findAll(repeat('a', SearchEngine.MAX_MATCHES + 1), "a", true, false, false);
  }

  @Test
  public void replacementIsNotLimitedByHighlightMatchCap() {
    String source = repeat('a', SearchEngine.MAX_MATCHES + 1);
    assertEquals(
        repeat('b', source.length()),
        SearchEngine.replaceAll(source, "a", "b", true, false, false));
  }

  @Test(timeout = 5000, expected = IllegalArgumentException.class)
  public void catastrophicRegexIsInterrupted() {
    SearchEngine.findAll(repeat('a', 50_000) + "!", "(a+)+$", true, false, true);
  }

  @Test
  public void replacementExpansionMatchesJavaCaptureAndEscapeRules() {
    String text = "cat12 dog4";
    String pattern = "(?<word>[a-z]+)([0-9]+)";
    for (String replacement :
        new String[] {"$0", "$00", "$01", "$12", "$21", "${word}", "\\$1", "\\\\$2", "$2:$1"}) {
      String expected =
          java.util.regex.Pattern.compile(pattern).matcher(text).replaceAll(replacement);
      assertEquals(
          replacement,
          expected,
          SearchEngine.replaceAll(text, pattern, replacement, true, false, true));
    }
    assertEquals("b", SearchEngine.replaceAll("b", "(a)?b", "$1b", true, false, true));
  }

  @Test
  public void oversizedLiteralReplacementFailsWithoutChangingOriginal() {
    String original = repeat('a', 1000);
    try {
      SearchEngine.replaceAll(original, "a", repeat('b', 10_000), true, false, false);
      fail("Output larger than the editor limit must be rejected before it is accumulated.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("limit"));
    }
    assertEquals(repeat('a', 1000), original);
  }

  @Test
  public void hugeSingleRegexCaptureExpansionIsBoundedBeforeAppending() {
    String original = repeat('a', 1_100_000);
    try {
      SearchEngine.replaceOne(
          original, "(?s)(.*)", "$1$1$1$1", true, false, true, 0, original.length());
      fail("Repeated captures must respect the output bound within one match.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("limit"));
    }
    assertEquals(1_100_000, original.length());
  }

  @Test
  public void replacementTailCannotExceedTheLimit() {
    String original = "x" + repeat('a', SearchEngine.MAX_OUTPUT_CHARACTERS - 1);
    try {
      SearchEngine.replaceOne(original, "x", "xx", true, false, false, 0, 1);
      fail("The unchanged suffix also counts toward the output limit.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("limit"));
    }
    assertEquals(original, SearchEngine.replaceOne(original, "x", "x", true, false, false, 0, 1));
  }

  private static String repeat(char character, int count) {
    char[] buffer = new char[count];
    java.util.Arrays.fill(buffer, character);
    return new String(buffer);
  }
}
