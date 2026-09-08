package com.shirochi.notepad.core;

/** Native scrollbar proportions and bounded mapping between thumb movement and content pixels. */
public final class ScrollBarMath {
  private ScrollBarMath() {}

  public static int thumbLength(int trackLength, int thickness, int extent, int range) {
    if (trackLength <= 0 || extent <= 0 || range <= extent) return 0;
    // Matches Android's ScrollBarUtils, with an additional clamp for very small viewports.
    return Math.min(
        trackLength, Math.max(thickness * 2, Math.round((float) trackLength * extent / range)));
  }

  public static int thumbOffset(
      int trackLength, int thumbLength, int extent, int range, int offset) {
    int travel = trackLength - thumbLength;
    int maximum = Math.max(0, range - extent);
    if (travel <= 0 || maximum == 0) return 0;
    return Math.round((float) travel * clamp(offset, maximum) / maximum);
  }

  public static int trackOffset(
      int trackLength, int thumbLength, int extent, int range, float thumbPosition) {
    int travel = trackLength - thumbLength;
    int maximum = Math.max(0, range - extent);
    if (travel <= 0 || maximum == 0) return 0;
    return clamp(Math.round((double) thumbPosition * maximum / travel), maximum);
  }

  public static int dragOffset(
      int trackLength, int thumbLength, int extent, int range, int initialOffset, float delta) {
    int travel = trackLength - thumbLength;
    int maximum = Math.max(0, range - extent);
    if (travel <= 0 || maximum == 0) return 0;
    // Relative movement preserves the exact initial content offset despite native thumb rounding.
    return clamp(initialOffset + Math.round((double) delta * maximum / travel), maximum);
  }

  private static int clamp(long offset, int maximum) {
    return (int) Math.max(0, Math.min(maximum, offset));
  }
}
