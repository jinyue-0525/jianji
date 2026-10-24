package com.xiaotu.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** One consistent family of 24 dp line icons; the container owns its touch target. */
public class IconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final String name;
    private int color;

    public IconView(Context context, String name, int color) {
        super(context);
        this.name = name == null ? "notes" : name;
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = Math.round(24 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(size + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(size + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        float desired = 24 * getResources().getDisplayMetrics().density;
        float size = Math.min(desired, Math.min(width, height));
        if (size <= 0) return;
        int save = c.save();
        c.translate(getPaddingLeft() + (width - size) / 2, getPaddingTop() + (height - size) / 2);
        c.scale(size / 24f, size / 24f);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        switch (name) {
            case "menu":
                line(c, 4, 6, 20, 6); line(c, 4, 12, 20, 12); line(c, 4, 18, 20, 18); break;
            case "chevronDown":
                line(c, 5, 9, 12, 16); line(c, 12, 16, 19, 9); break;
            case "chevronUp":
            case "collapse":
                line(c, 5, 15, 12, 8); line(c, 12, 8, 19, 15); break;
            case "notebook":
                // The caller's color identifies the notebook through its 3 dp spine.
                path.moveTo(8, 3); path.lineTo(8, 21); path.lineTo(7, 21);
                path.quadTo(5, 21, 5, 19); path.lineTo(5, 5);
                path.quadTo(5, 3, 7, 3); path.close();
                paint.setStyle(Paint.Style.FILL); drawPath(c);
                paint.setStyle(Paint.Style.STROKE); paint.setColor(0xFF737680);
                rect(c, 5, 3, 20, 21, 2); line(c, 8, 3, 8, 21);
                line(c, 8, 18, 20, 18); line(c, 12, 7, 16, 7);
                paint.setColor(color); break;
            case "document":
                path.moveTo(6, 3); path.lineTo(14, 3); path.lineTo(20, 9);
                path.lineTo(20, 19); path.quadTo(20, 21, 18, 21);
                path.lineTo(6, 21); path.quadTo(4, 21, 4, 19);
                path.lineTo(4, 5); path.quadTo(4, 3, 6, 3); path.close(); drawPath(c);
                path.reset(); path.moveTo(14, 3); path.lineTo(14, 7);
                path.quadTo(14, 9, 16, 9); path.lineTo(20, 9); drawPath(c);
                line(c, 8, 13, 16, 13); line(c, 8, 17, 13, 17); break;
            case "undo":
                path.moveTo(4, 10); path.cubicTo(9, 5, 20, 7, 20, 15);
                path.lineTo(20, 18); drawPath(c);
                line(c, 4, 10, 4, 4); line(c, 4, 10, 10, 10); break;
            case "redo":
                path.moveTo(20, 10); path.cubicTo(15, 5, 4, 7, 4, 15);
                path.lineTo(4, 18); drawPath(c);
                line(c, 20, 10, 20, 4); line(c, 20, 10, 14, 10); break;
            case "star":
                path.moveTo(12, 3); path.lineTo(14.8f, 8.7f); path.lineTo(21, 9.6f);
                path.lineTo(16.5f, 14); path.lineTo(17.6f, 20.3f); path.lineTo(12, 17.3f);
                path.lineTo(6.4f, 20.3f); path.lineTo(7.5f, 14); path.lineTo(3, 9.6f);
                path.lineTo(9.2f, 8.7f); path.close(); drawPath(c); break;
            case "circleCheck":
                circle(c, 12, 12, 9); line(c, 7.5f, 12, 10.5f, 15); line(c, 10.5f, 15, 16.5f, 9); break;
            case "folder":
                path.moveTo(3, 7); path.lineTo(3, 5.5f); path.quadTo(3, 4, 4.5f, 4);
                path.lineTo(9, 4); path.lineTo(11, 7); path.lineTo(19.5f, 7);
                path.quadTo(21, 7, 21, 8.5f); path.lineTo(21, 18.5f);
                path.quadTo(21, 20, 19.5f, 20); path.lineTo(4.5f, 20);
                path.quadTo(3, 20, 3, 18.5f); path.close(); drawPath(c); break;
            case "heart":
                path.moveTo(12, 20); path.cubicTo(8, 17, 3, 13, 3, 8.5f);
                path.cubicTo(3, 3, 9, 2, 12, 7); path.cubicTo(15, 2, 21, 3, 21, 8.5f);
                path.cubicTo(21, 13, 16, 17, 12, 20); path.close(); drawPath(c); break;
            case "search": circle(c, 10.5f, 10.5f, 6.5f); line(c, 16, 16, 21, 21); break;
            case "plus": line(c, 12, 5, 12, 19); line(c, 5, 12, 19, 12); break;
            case "back": line(c, 20, 12, 4, 12); line(c, 4, 12, 10, 6); line(c, 4, 12, 10, 18); break;
            case "more": dot(c, 5, 12); dot(c, 12, 12); dot(c, 19, 12); break;
            case "check": line(c, 4, 12, 9, 17); line(c, 9, 17, 20, 6); break;
            case "close": line(c, 6, 6, 18, 18); line(c, 18, 6, 6, 18); break;
            case "trash":
                line(c, 3, 6, 21, 6); line(c, 9, 3, 15, 3);
                path.moveTo(5, 6); path.lineTo(6, 19); path.quadTo(6, 21, 8, 21);
                path.lineTo(16, 21); path.quadTo(18, 21, 18, 19); path.lineTo(19, 6); drawPath(c);
                line(c, 10, 10, 10, 17); line(c, 14, 10, 14, 17); break;
            case "restore":
                path.moveTo(4, 10); path.cubicTo(5, 3, 14, 1, 19, 7);
                path.cubicTo(25, 15, 15, 25, 7, 19); drawPath(c);
                line(c, 4, 4, 4, 10); line(c, 4, 10, 10, 10);
                line(c, 12, 7, 12, 12); line(c, 12, 12, 16, 14); break;
            case "image":
                rect(c, 3, 3, 21, 21, 3); circle(c, 8, 8, 1.5f);
                path.moveTo(3, 17); path.lineTo(9, 12); path.lineTo(13, 16);
                path.lineTo(17, 12); path.lineTo(21, 16); drawPath(c); break;
            case "checklist":
                line(c, 3, 6, 5, 8); line(c, 5, 8, 8, 4); line(c, 12, 6, 21, 6);
                line(c, 3, 13, 5, 15); line(c, 5, 15, 8, 11); line(c, 12, 13, 21, 13);
                line(c, 12, 20, 21, 20); break;
            case "sticker":
                path.moveTo(20, 14); path.lineTo(20, 6); path.quadTo(20, 3, 17, 3);
                path.lineTo(6, 3); path.quadTo(3, 3, 3, 6); path.lineTo(3, 17);
                path.quadTo(3, 20, 6, 20); path.lineTo(14, 20); path.close(); drawPath(c);
                path.reset(); path.moveTo(14, 20); path.lineTo(14, 17);
                path.quadTo(14, 14, 17, 14); path.lineTo(20, 14); drawPath(c);
                dot(c, 8, 9); dot(c, 15, 9);
                path.reset(); path.moveTo(8, 13); path.quadTo(11, 16, 14, 13); drawPath(c); break;
            case "palette":
                path.moveTo(12, 3); path.cubicTo(6, 3, 2, 7, 3, 13);
                path.cubicTo(3, 19, 10, 23, 14, 20); path.cubicTo(16, 18, 12, 15, 16, 14);
                path.cubicTo(25, 15, 23, 3, 12, 3); path.close(); drawPath(c);
                dot(c, 8, 8); dot(c, 14, 7); dot(c, 18, 10); dot(c, 7, 14); break;
            case "share":
                circle(c, 18, 5, 3); circle(c, 5, 12, 3); circle(c, 18, 19, 3);
                line(c, 7.6f, 10.6f, 15.4f, 6.4f); line(c, 7.6f, 13.4f, 15.4f, 17.6f); break;
            case "settings":
                circle(c, 12, 12, 7); circle(c, 12, 12, 2.5f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    line(c, 12 + (float)Math.cos(a) * 7, 12 + (float)Math.sin(a) * 7,
                            12 + (float)Math.cos(a) * 9.5f, 12 + (float)Math.sin(a) * 9.5f);
                } break;
            case "chevron": line(c, 9, 5, 16, 12); line(c, 16, 12, 9, 19); break;
            case "export":
                path.moveTo(4, 14); path.lineTo(4, 18); path.quadTo(4, 21, 7, 21);
                path.lineTo(17, 21); path.quadTo(20, 21, 20, 18); path.lineTo(20, 14); drawPath(c);
                line(c, 12, 3, 12, 15); line(c, 8, 7, 12, 3); line(c, 16, 7, 12, 3); break;
            case "info": circle(c, 12, 12, 9); dot(c, 12, 7); line(c, 12, 11, 12, 17); break;
            case "grid":
                rect(c, 3, 3, 10, 10, 2); rect(c, 14, 3, 21, 10, 2);
                rect(c, 3, 14, 10, 21, 2); rect(c, 14, 14, 21, 21, 2); break;
            case "list":
                for (int y = 5; y <= 19; y += 7) { dot(c, 4, y); line(c, 9, y, 21, y); } break;
            case "edit":
                path.moveTo(4, 20); path.lineTo(5, 14); path.lineTo(16, 3);
                path.quadTo(17, 2, 19, 4); path.quadTo(21, 6, 20, 7);
                path.lineTo(9, 18); path.close(); drawPath(c); line(c, 14, 5, 18, 9); break;
            case "pin":
                path.moveTo(8, 3); path.lineTo(16, 3); path.lineTo(16, 9);
                path.lineTo(20, 13); path.lineTo(20, 15); path.lineTo(4, 15);
                path.lineTo(4, 13); path.lineTo(8, 9); path.close(); drawPath(c);
                line(c, 12, 15, 12, 22); break;
            case "leaf":
                path.moveTo(4, 17); path.cubicTo(0, 7, 11, 3, 21, 3);
                path.cubicTo(21, 14, 15, 23, 6, 18); drawPath(c);
                path.reset(); path.moveTo(3, 22); path.quadTo(6, 14, 16, 8); drawPath(c); break;
            case "notes":
            default:
                rect(c, 5, 3, 21, 21, 3); line(c, 3, 7, 7, 7); line(c, 3, 12, 7, 12);
                line(c, 3, 17, 7, 17); line(c, 11, 8, 17, 8); line(c, 11, 12, 17, 12);
                line(c, 11, 16, 14, 16); break;
        }
        c.restoreToCount(save);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) { c.drawLine(x1, y1, x2, y2, paint); }
    private void circle(Canvas c, float x, float y, float radius) { c.drawCircle(x, y, radius, paint); }
    private void rect(Canvas c, float l, float t, float r, float b, float radius) { c.drawRoundRect(l, t, r, b, radius, radius, paint); }
    private void drawPath(Canvas c) { c.drawPath(path, paint); }
    private void dot(Canvas c, float x, float y) {
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, 1.2f, paint);
        paint.setStyle(Paint.Style.STROKE);
    }
}
