package com.shirochi.notepad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Crisp icons at every density, without a font or network dependency. */
final class IconView extends View {
  private final String icon;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  public IconView(Context context) {
    this(context, "note", android.graphics.Color.GRAY);
  }

  IconView(Context context, String icon, int color) {
    super(context);
    this.icon = icon;
    paint.setColor(color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas original) {
    super.onDraw(original);
    Canvas c = original;
    c.save();
    float size = Math.min(getWidth(), getHeight()) * .48f;
    c.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
    c.scale(size / 24, size / 24);
    switch (icon) {
      case "new":
        line(c, 12, 4, 12, 20);
        line(c, 4, 12, 20, 12);
        break;
      case "close":
        line(c, 7, 7, 17, 17);
        line(c, 17, 7, 7, 17);
        break;
      case "find":
        c.drawCircle(10, 10, 6, paint);
        line(c, 14.5f, 14.5f, 21, 21);
        break;
      case "open":
        path(c, 3, 20, 3, 5, 10, 5, 12, 8, 21, 8, 21, 11);
        path(c, 3, 20, 7, 11, 23, 11, 19, 20, 3, 20);
        break;
      case "save":
        path(c, 4, 3, 17, 3, 21, 7, 21, 21, 3, 21, 3, 3, 4, 3);
        path(c, 7, 3, 7, 10, 17, 10, 17, 3);
        path(c, 7, 21, 7, 14, 17, 14, 17, 21);
        break;
      case "undo":
        path(c, 8, 4, 3, 9, 8, 14);
        path(c, 3, 9, 15, 9, 20, 12, 20, 17, 17, 20);
        break;
      case "redo":
        path(c, 16, 4, 21, 9, 16, 14);
        path(c, 21, 9, 9, 9, 4, 12, 4, 17, 7, 20);
        break;
      case "menu":
        c.drawCircle(12, 5, 1, paint);
        c.drawCircle(12, 12, 1, paint);
        c.drawCircle(12, 19, 1, paint);
        break;
      case "up":
        path(c, 5, 15, 12, 8, 19, 15);
        break;
      case "down":
        path(c, 5, 9, 12, 16, 19, 9);
        break;
      case "note":
        c.drawRoundRect(5, 3, 21, 21, 3, 3, paint);
        line(c, 9, 3, 9, 21);
        line(c, 3, 8, 6, 8);
        line(c, 3, 16, 6, 16);
        line(c, 12, 9, 17, 9);
        line(c, 12, 13, 17, 13);
        break;
    }
    c.restore();
  }

  private void line(Canvas c, float a, float b, float x, float y) {
    c.drawLine(a, b, x, y, paint);
  }

  private void path(Canvas c, float... points) {
    Path p = new Path();
    p.moveTo(points[0], points[1]);
    for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
    c.drawPath(p, paint);
  }
}
