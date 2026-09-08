package com.shirochi.notepad.core;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ScrollBarMathTest {
  @Test
  public void nativeThumbProportionsIncludeMinimumAndFitSmallTracks() {
    assertEquals(100, ScrollBarMath.thumbLength(400, 8, 500, 2000));
    assertEquals(16, ScrollBarMath.thumbLength(400, 8, 500, 100000));
    assertEquals(10, ScrollBarMath.thumbLength(10, 8, 50, 100));
  }

  @Test
  public void contentThatFitsAndEmptyViewportsDoNotProduceThumbs() {
    assertEquals(0, ScrollBarMath.thumbLength(400, 8, 500, 500));
    assertEquals(0, ScrollBarMath.thumbLength(400, 8, 500, 0));
    assertEquals(0, ScrollBarMath.thumbLength(0, 8, 500, 2000));
    assertEquals(0, ScrollBarMath.thumbLength(400, 8, 0, 2000));
  }

  @Test
  public void thumbOffsetMatchesNativeBeginningMiddleAndEnd() {
    assertEquals(0, ScrollBarMath.thumbOffset(400, 100, 500, 2000, 0));
    assertEquals(150, ScrollBarMath.thumbOffset(400, 100, 500, 2000, 750));
    assertEquals(300, ScrollBarMath.thumbOffset(400, 100, 500, 2000, 1500));
    assertEquals(0, ScrollBarMath.thumbOffset(400, 100, 500, 2000, -50));
    assertEquals(300, ScrollBarMath.thumbOffset(400, 100, 500, 2000, 100000));
  }

  @Test
  public void trackTapsMapToContentAndClampBeyondTrackEnds() {
    assertEquals(0, ScrollBarMath.trackOffset(400, 100, 500, 2000, -200));
    assertEquals(750, ScrollBarMath.trackOffset(400, 100, 500, 2000, 150));
    assertEquals(1500, ScrollBarMath.trackOffset(400, 100, 500, 2000, 800));
  }

  @Test
  public void thumbGrabDoesNotJumpForOffsetsLostToNativePixelRounding() {
    // Many content offsets occupy the same single scrollbar pixel in a long document.
    assertEquals(0, ScrollBarMath.thumbOffset(400, 16, 500, 1000000, 321));
    assertEquals(321, ScrollBarMath.dragOffset(400, 16, 500, 1000000, 321, 0));
    assertEquals(383, ScrollBarMath.dragOffset(400, 16, 500, 1000000, 383, 0));
  }

  @Test
  public void draggingUsesRelativeMovementAndClampsBothEndpoints() {
    assertEquals(500, ScrollBarMath.dragOffset(400, 100, 500, 2000, 250, 50));
    assertEquals(0, ScrollBarMath.dragOffset(400, 100, 500, 2000, 250, -100));
    assertEquals(1500, ScrollBarMath.dragOffset(400, 100, 500, 2000, 250, 900));
  }

  @Test
  public void degenerateTracksDoNotDivideByZero() {
    assertEquals(0, ScrollBarMath.thumbOffset(10, 10, 10, 100, 5));
    assertEquals(0, ScrollBarMath.trackOffset(10, 10, 10, 100, 5));
    assertEquals(0, ScrollBarMath.dragOffset(10, 10, 10, 100, 5, 3));
    assertEquals(0, ScrollBarMath.trackOffset(100, 10, 100, 100, 50));
  }

  @Test
  public void largeDocumentMappingDoesNotOverflowIntegerArithmetic() {
    assertEquals(16, ScrollBarMath.thumbLength(10000, 8, 1000, Integer.MAX_VALUE));
    assertEquals(
        Integer.MAX_VALUE - 1000,
        ScrollBarMath.trackOffset(10000, 16, 1000, Integer.MAX_VALUE, 9984));
    assertEquals(
        Integer.MAX_VALUE - 1000,
        ScrollBarMath.dragOffset(10000, 16, 1000, Integer.MAX_VALUE, 1000000000, 10000));
  }
}
