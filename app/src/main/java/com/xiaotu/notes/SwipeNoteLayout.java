package com.xiaotu.notes;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A visual card shell. Only its foreground moves, leaving list-entry motion independent. */
final class SwipeNoteLayout extends FrameLayout {
    interface Listener { void opening(SwipeNoteLayout row); }

    private final View foreground;
    private final LinearLayout deleteButton;
    private final Listener listener;
    private final int revealWidth, touchSlop, minFling;
    private final boolean swipeEnabled;
    private VelocityTracker velocity;
    private float downX, downY, startOffset;
    private int pointerId = -1;
    private boolean opened, startedOpen, dragging, vertical, tapToClose, multiple;

    SwipeNoteLayout(Context context, View foreground, boolean enabled, Runnable remove, Listener listener) {
        super(context);
        this.foreground = foreground;
        this.listener = listener;
        swipeEnabled = enabled;
        revealWidth = Ui.dp(context, 72);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minFling = Math.max(configuration.getScaledMinimumFlingVelocity(), Ui.dp(context, 500));
        setBackground(Ui.shape(context, Ui.SURFACE, 12));
        setClipToOutline(true);
        Ui.cardShadow(this);

        deleteButton = Ui.column(context);
        deleteButton.setGravity(Gravity.CENTER);
        Ui.ripple(deleteButton, Ui.DESTRUCTIVE, 0);
        deleteButton.addView(new IconView(context, "trash", Ui.WHITE), Ui.lp(Ui.dp(context, 20), Ui.dp(context, 20)));
        Ui.gap(deleteButton, 4);
        TextView label = Ui.text(context, "删除", 13, Ui.WHITE, false);
        deleteButton.addView(label);
        deleteButton.setContentDescription("删除笔记，可在最近删除恢复");
        deleteButton.setOnClickListener(view -> { close(false); remove.run(); });
        addView(deleteButton, new FrameLayout.LayoutParams(revealWidth, LayoutParams.MATCH_PARENT, Gravity.RIGHT));
        foreground.setElevation(0);
        addView(foreground, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        updateDeleteAccess(false);
    }

    private void updateDeleteAccess(boolean exposed) {
        deleteButton.setVisibility(opened || foreground.getTranslationX() < -.5f ? VISIBLE : INVISIBLE);
        deleteButton.setEnabled(exposed);
        deleteButton.setFocusable(exposed);
        deleteButton.setImportantForAccessibility(exposed ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!swipeEnabled) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            recycleVelocity();
            velocity = VelocityTracker.obtain();
            pointerId = event.getPointerId(0);
            downX = event.getX(); downY = event.getY();
            foreground.animate().cancel();
            startOffset = foreground.getTranslationX();
            startedOpen = opened || startOffset < -revealWidth * .5f;
            dragging = false; vertical = false; multiple = false;
            tapToClose = startOffset < -.5f && downX < getWidth() + startOffset;
            // Release this short direction lock as soon as vertical intent is known.
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiple = true;
            cancelContentTouch();
            close(true);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            recycleVelocity(); pointerId = -1;
        }
        return handled;
    }

    private void direction(MotionEvent event) {
        if (dragging || vertical || multiple) return;
        int index = event.findPointerIndex(pointerId);
        if (index < 0) { multiple = true; close(true); return; }
        float dx = event.getX(index) - downX, dy = event.getY(index) - downY;
        if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
            vertical = true;
            cancelContentTouch();
            if (startedOpen || startOffset < 0) close(true);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        } else if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
            dragging = true;
            cancelContentTouch();
            if (listener != null) listener.opening(this);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void cancelContentTouch() {
        foreground.cancelLongPress(); foreground.setPressed(false);
        deleteButton.cancelLongPress(); deleteButton.setPressed(false);
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        foreground.dispatchTouchEvent(cancel);
        deleteButton.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!swipeEnabled) return false;
        if (multiple) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) direction(event);
        return dragging || tapToClose;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!swipeEnabled) return false;
        int action = event.getActionMasked();
        if (multiple) return true;
        if (action == MotionEvent.ACTION_MOVE) {
            direction(event);
            if (dragging) {
                int index = event.findPointerIndex(pointerId);
                if (index < 0) { close(true); return true; }
                float offset = startOffset + event.getX(index) - downX;
                foreground.setTranslationX(Math.max(-revealWidth, Math.min(0, offset)));
                updateDeleteAccess(false);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (dragging) {
                float speed = 0;
                if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = velocity.getXVelocity(pointerId); }
                boolean reveal = Math.abs(speed) >= minFling ? speed < 0 : foreground.getTranslationX() < -revealWidth * .45f;
                settle(reveal, true);
            } else if (tapToClose) close(true);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            settle(dragging && startedOpen, true);
        }
        return true;
    }

    private void settle(boolean reveal, boolean animate) {
        foreground.animate().cancel();
        opened = reveal && swipeEnabled;
        if (opened && listener != null) listener.opening(this);
        updateDeleteAccess(opened);
        float target = opened ? -revealWidth : 0;
        if (!animate || !Motion.allowed(getContext())) {
            foreground.setTranslationX(target);
            updateDeleteAccess(opened);
        }
        else foreground.animate().translationX(target).setDuration(200)
            .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f))
            .withEndAction(() -> updateDeleteAccess(opened)).start();
    }

    void close(boolean animate) { settle(false, animate); }

    private void recycleVelocity() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    @Override protected void onDetachedFromWindow() {
        close(false);
        recycleVelocity();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        super.onDetachedFromWindow();
    }
}
