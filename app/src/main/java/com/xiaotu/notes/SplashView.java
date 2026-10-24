package com.xiaotu.notes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** One-shot launch mark. The activity owns its lifetime and the transition to content. */
final class SplashView extends View {
    private static final long DRAW_DURATION = 740L;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sloganPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path paper = new Path();
    private final Path fold = new Path();
    private final Path visibleOutline = new Path();
    private final PathMeasure outline = new PathMeasure();
    private final float density;
    private ValueAnimator animator;
    private Runnable onFinished;
    private boolean requested, drawn, scheduled, completed;
    private float progress;
    private final Runnable begin = this::beginAnimation;

    SplashView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Ui.BG);
        setClickable(true);
        // This short decorative transition should not steal accessibility focus from home.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paper.moveTo(38, 28); paper.lineTo(58, 28); paper.lineTo(76, 46);
        paper.lineTo(76, 74); paper.quadTo(76, 80, 70, 80);
        paper.lineTo(38, 80); paper.quadTo(32, 80, 32, 74);
        paper.lineTo(32, 34); paper.quadTo(32, 28, 38, 28); paper.close();
        fold.moveTo(58, 28); fold.lineTo(58, 41); fold.quadTo(58, 46, 63, 46);
        fold.lineTo(76, 46); fold.close();
        outline.setPath(paper, false);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        sloganPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        sloganPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Starts after the first frame is drawn, so setup work cannot consume the animation. */
    void start(Runnable finished) {
        if (requested || completed) return;
        onFinished = finished;
        requested = true;
        if (!animationsEnabled()) { finishImmediately(); return; }
        scheduleStart();
        invalidate();
    }

    private boolean animationsEnabled() {
        if (!ValueAnimator.areAnimatorsEnabled()) return false;
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f;
    }

    private void scheduleStart() {
        if (!requested || !drawn || !isAttachedToWindow() || scheduled || animator != null || completed) return;
        scheduled = true;
        postOnAnimation(begin);
    }

    private void beginAnimation() {
        scheduled = false;
        if (completed || !requested || animator != null || !isAttachedToWindow()) return;
        if (!animationsEnabled()) { finishImmediately(); return; }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DRAW_DURATION);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                animator = null;
                complete();
            }
        });
        animator.start();
    }

    /** Used for backgrounding, back navigation, or disabled system animation scales. */
    void finishImmediately() {
        if (completed) return;
        stopAnimator();
        progress = 1f;
        invalidate();
        complete();
    }

    /** Cancels pending callbacks without asking a destroyed activity to change its UI. */
    void cancel() {
        completed = true;
        onFinished = null;
        stopAnimator();
    }

    private void stopAnimator() {
        removeCallbacks(begin);
        scheduled = false;
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
            animator = null;
        }
    }

    private void complete() {
        if (completed) return;
        completed = true;
        Runnable finished = onFinished;
        onFinished = null;
        if (finished != null) finished.run();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleStart();
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawn = true;
        final float availableWidth = Math.max(1f, getWidth() - dp(32));
        final float layoutScale = Math.min(1f, Math.min(availableWidth / dp(248),
                Math.max(1f, getHeight() - dp(32)) / dp(288)));
        final float centerX = getWidth() / 2f;
        final float centerY = getHeight() * .42f;
        final float tile = dp(96) * layoutScale;
        float settle = slice(progress, 0f, .38f);
        float iconScale = .88f + .12f * settle(settle);
        float iconAlpha = easeOut(slice(progress, 0f, .16f));
        canvas.save();
        canvas.translate(centerX, centerY + dp(8) * layoutScale * (1f - easeOut(settle)));
        canvas.scale(iconScale, iconScale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.ACCENT);
        paint.setAlpha(alpha(iconAlpha));
        canvas.drawRoundRect(-tile / 2f, -tile / 2f, tile / 2f, tile / 2f,
                dp(25) * layoutScale, dp(25) * layoutScale, paint);
        canvas.translate(-tile / 2f, -tile / 2f);
        canvas.scale(tile / 108f, tile / 108f);

        // The folded sheet uses the same geometry as the launcher artwork.
        float sheetAlpha = easeOut(slice(progress, .1f, .4f)) * iconAlpha;
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha(sheetAlpha));
        canvas.drawPath(paper, paint);
        paint.setColor(0xFFDDE3FF);
        paint.setAlpha(alpha(sheetAlpha));
        canvas.drawPath(fold, paint);

        visibleOutline.reset();
        outline.getSegment(0f, outline.getLength() * easeOut(slice(progress, .02f, .42f)),
                visibleOutline, true);
        // A round outline and three written strokes keep the mark readable at small sizes.
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha(iconAlpha));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(visibleOutline, paint);
        paint.setColor(Ui.ACCENT);
        paint.setAlpha(alpha(sheetAlpha));
        paint.setStrokeWidth(3.5f);
        writeLine(canvas, 42f, 51f, 61f, slice(progress, .3f, .48f));
        writeLine(canvas, 42f, 60f, 65f, slice(progress, .44f, .64f));
        writeLine(canvas, 42f, 69f, 55f, slice(progress, .6f, .78f));
        canvas.restore();

        float textScale = Math.min(getResources().getDisplayMetrics().scaledDensity,
                density * 1.35f) * layoutScale;
        titlePaint.setTextSize(30f * textScale);
        titlePaint.setColor(Ui.INK);
        float titleProgress = easeOut(slice(progress, .24f, .66f));
        titlePaint.setAlpha(alpha(titleProgress));
        canvas.drawText("简记", centerX, centerY + dp(98) * layoutScale
                + dp(8) * layoutScale * (1f - titleProgress), titlePaint);
        sloganPaint.setTextSize(14f * textScale);
        sloganPaint.setColor(Ui.MUTED);
        float sloganProgress = easeOut(slice(progress, .44f, .86f));
        sloganPaint.setAlpha(alpha(sloganProgress));
        canvas.drawText("把想法，轻轻记下", centerX, centerY + dp(126) * layoutScale
                + dp(6) * layoutScale * (1f - sloganProgress), sloganPaint);
        scheduleStart();
    }

    private void writeLine(Canvas canvas, float x, float y, float end, float value) {
        if (value > 0f) canvas.drawLine(x, y, x + (end - x) * easeOut(value), y, paint);
    }

    private float dp(float value) { return value * density; }
    private static int alpha(float value) { return Math.round(255f * Math.max(0f, Math.min(1f, value))); }
    private static float slice(float value, float start, float end) {
        return Math.max(0f, Math.min(1f, (value - start) / (end - start)));
    }
    private static float easeOut(float value) {
        float remaining = 1f - value;
        return 1f - remaining * remaining * remaining;
    }
    private static float settle(float value) {
        float remaining = value - 1f;
        return 1f + 2.2f * remaining * remaining * remaining + 1.2f * remaining * remaining;
    }
}
