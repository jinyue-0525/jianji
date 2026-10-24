package com.xiaotu.notes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Small native transitions. Required UI state never waits for an animation to finish. */
final class Motion {
    static final long PRESS = 70, RELEASE = 240, ENTER = 240, EXIT = 180, PAGE = 300;
    private static final Interpolator EASE = new PathInterpolator(.2f, 0f, 0f, 1f);
    private static final Interpolator SETTLE = new OvershootInterpolator(.8f);
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();
    private static final WeakHashMap<Dialog, Boolean> DIALOGS = new WeakHashMap<>();

    private Motion() {}

    static boolean allowed() { return ValueAnimator.areAnimatorsEnabled(); }

    static boolean allowed(Context context) {
        return allowed() && Settings.Global.getFloat(context.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f;
    }

    private static final class State {
        ValueAnimator main, press, dim, focus;
        float baseX, baseY, scale = 1f, pressed = 1f, opacity = 1f, enabledOpacity = 1f, enabledTarget = 1f, focusOpacity = 1f;
        Integer foldHeight;
        boolean expanded;
        boolean focusBound;
        State(View view) {
            baseX = view.getScaleX(); baseY = view.getScaleY(); opacity = view.getAlpha();
        }
    }

    private static State state(View view) {
        State state = STATES.get(view);
        if (state == null) {
            state = new State(view); STATES.put(view, state);
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) { cancel(v); }
            });
        }
        return state;
    }

    private static void scale(View view, State state) {
        view.setScaleX(state.baseX * state.scale * state.pressed);
        view.setScaleY(state.baseY * state.scale * state.pressed);
    }

    private static void opacity(View view, State state) {
        view.setAlpha(state.opacity * state.enabledOpacity * state.focusOpacity);
    }

    private static void stopMain(State state) {
        ValueAnimator old = state.main; state.main = null;
        if (old != null) old.cancel();
    }

    private interface Frame { void apply(float fraction); }

    private static void animate(View view, State state, long duration, long delay, Frame frame, Runnable finish) {
        stopMain(state);
        if (!allowed(view.getContext())) { frame.apply(1f); if (finish != null) finish.run(); return; }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        state.main = animator; animator.setDuration(duration); animator.setStartDelay(delay); animator.setInterpolator(EASE);
        animator.addUpdateListener(a -> {
            if (state.main != a) return;
            // A system animation-setting change also completes transition cleanup.
            if (finish != null && !allowed(view.getContext()) && a.getAnimatedFraction() < 1f) { a.end(); return; }
            frame.apply((float) a.getAnimatedValue());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (state.main != a) return;
                state.main = null; if (finish != null) finish.run();
            }
        });
        animator.start();
    }

    private static float mix(float from, float to, float fraction) { return from + (to - from) * fraction; }

    /** Observe native pressed state, without replacing or consuming touch/long-click listeners. */
    static void tap(View view) {
        if (view.getBackground() instanceof PressDrawable) return;
        state(view);
        Drawable background = view.getBackground();
        int left = view.getPaddingLeft(), top = view.getPaddingTop(), right = view.getPaddingRight(), bottom = view.getPaddingBottom();
        view.setBackground(new PressDrawable(view, background == null ? new ColorDrawable(Color.TRANSPARENT) : background));
        view.setPadding(left, top, right, bottom);
    }

    private static final class PressDrawable extends LayerDrawable {
        final WeakReference<View> view;
        boolean pressed;
        PressDrawable(View view, Drawable background) { super(new Drawable[]{background}); this.view = new WeakReference<>(view); }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] states) {
            boolean changed = super.onStateChange(states);
            // LayerDrawable may dispatch state during its constructor.
            if (view == null) return changed;
            boolean next = false;
            for (int value : states) if (value == android.R.attr.state_pressed) { next = true; break; }
            View target = view.get();
            if (target != null && next != pressed) { pressed = next; press(target, next && target.isEnabled()); }
            return changed;
        }
    }

    private static void press(View view, boolean pressed) {
        State state = state(view);
        ValueAnimator old = state.press; state.press = null; if (old != null) old.cancel();
        boolean large = view.getWidth() >= Ui.dp(view.getContext(), 160);
        float target = pressed ? (large ? .985f : .96f) : 1f;
        if (!allowed(view.getContext())) { state.pressed = 1f; scale(view, state); return; }
        ValueAnimator animator = ValueAnimator.ofFloat(state.pressed, target); state.press = animator;
        animator.setDuration(pressed ? PRESS : RELEASE); animator.setInterpolator(pressed ? EASE : SETTLE);
        animator.addUpdateListener(a -> { state.pressed = (float) a.getAnimatedValue(); scale(view, state); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { if (state.press == a) state.press = null; }
        }); animator.start();
    }

    static void enter(View view, int index) {
        State state = state(view); stopMain(state);
        view.setTranslationX(0f);
        view.setVisibility(View.VISIBLE); state.scale = 1f; scale(view, state);
        if (!allowed(view.getContext())) { state.opacity = 1f; opacity(view, state); view.setTranslationY(0f); return; }
        float distance = Ui.dp(view.getContext(), 8);
        state.opacity = 0f; opacity(view, state); view.setTranslationY(distance);
        animate(view, state, ENTER, Math.min(Math.max(index, 0), 5) * 18L,
            f -> { state.opacity = f; opacity(view, state); view.setTranslationY(distance * (1f - f)); }, null);
    }

    static void page(View view, boolean forward) { page(view, null, forward, null); }

    /** The outgoing view belongs to the host overlay and never receives input. */
    static void page(View view, View outgoing, boolean forward, Runnable finish) {
        State state = state(view); stopMain(state); state.scale = 1f; scale(view, state);
        view.setTranslationY(0f);
        if (!allowed(view.getContext())) {
            state.opacity = 1f; opacity(view, state); view.setTranslationX(0f);
            if (finish != null) finish.run();
            return;
        }
        float distance = Ui.dp(view.getContext(), forward ? 24 : -24);
        float outgoingX = outgoing == null ? 0f : outgoing.getTranslationX();
        float outgoingAlpha = outgoing == null ? 1f : outgoing.getAlpha();
        float exitDistance = Ui.dp(view.getContext(), forward ? -10 : 10);
        float incomingAlpha = outgoing == null ? 0f : 1f;
        state.opacity = incomingAlpha; opacity(view, state); view.setTranslationX(distance);
        animate(view, state, PAGE, 0, f -> {
            state.opacity = mix(incomingAlpha, 1f, f); opacity(view, state); view.setTranslationX(distance * (1f - f));
            if (outgoing != null) {
                outgoing.setAlpha(outgoingAlpha * (1f - f));
                outgoing.setTranslationX(mix(outgoingX, exitDistance, f));
            }
        }, finish);
    }

    static void finishPage(View view) { if (view != null) cancel(view); }

    static void pulse(View view) {
        State state = state(view); stopMain(state);
        state.opacity = 1f; opacity(view, state); view.setTranslationX(0f); view.setTranslationY(0f);
        if (!allowed(view.getContext())) { state.scale = 1f; scale(view, state); return; }
        animate(view, state, ENTER, 0, f -> {
            state.scale = 1f + .03f * (float) Math.sin(Math.PI * f); scale(view, state);
        }, () -> { state.scale = 1f; scale(view, state); });
    }

    static void visible(View view, boolean show) {
        State state = state(view); stopMain(state);
        view.setTranslationX(0f); view.setTranslationY(0f);
        if (show && view.getVisibility() != View.VISIBLE) { state.opacity = 0f; state.scale = .96f; }
        if (!show && view.getVisibility() != View.VISIBLE) return;
        view.setVisibility(View.VISIBLE);
        float alpha = state.opacity, startScale = state.scale;
        animate(view, state, show ? ENTER : EXIT, 0, f -> {
            state.opacity = mix(alpha, show ? 1f : 0f, f); state.scale = mix(startScale, show ? 1f : .96f, f);
            opacity(view, state); scale(view, state);
        }, () -> {
            if (!show) view.setVisibility(View.GONE);
            state.opacity = 1f; state.scale = 1f; opacity(view, state); scale(view, state);
        });
    }

    /** The caller's expanded boolean is authoritative, including during rapid reversal. */
    static void expand(View view, View arrow, boolean expanded) {
        State state = state(view); stopMain(state);
        state.scale = 1f; scale(view, state); view.setTranslationX(0f); view.setTranslationY(0f);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) { view.setVisibility(expanded ? View.VISIBLE : View.GONE); arrow.setRotation(expanded ? 180f : 0f); return; }
        if (state.foldHeight == null) state.foldHeight = params.height;
        state.expanded = expanded;
        if (!allowed(view.getContext())) {
            params.height = state.foldHeight; view.setLayoutParams(params); view.setVisibility(expanded ? View.VISIBLE : View.GONE);
            state.opacity = 1f; opacity(view, state); arrow.setRotation(expanded ? 180f : 0f); return;
        }
        int from = view.getVisibility() == View.VISIBLE ? Math.max(0, params.height >= 0 ? params.height : view.getHeight()) : 0;
        int width = view.getWidth();
        if (width <= 0 && view.getParent() instanceof View) {
            View parent = (View) view.getParent(); width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        }
        if (width <= 0) width = view.getResources().getDisplayMetrics().widthPixels;
        params.height = state.foldHeight;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            state.foldHeight >= 0 ? View.MeasureSpec.makeMeasureSpec(state.foldHeight, View.MeasureSpec.EXACTLY) : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int to = expanded ? view.getMeasuredHeight() : 0;
        float alpha = from == 0 ? 0f : state.opacity, rotation = arrow.getRotation();
        params.height = from; view.setLayoutParams(params); view.setVisibility(View.VISIBLE);
        animate(view, state, expanded ? ENTER : EXIT, 0, f -> {
            params.height = Math.round(mix(from, to, f)); view.setLayoutParams(params);
            state.opacity = mix(alpha, expanded ? 1f : 0f, f); opacity(view, state);
            arrow.setRotation(mix(rotation, expanded ? 180f : 0f, f));
        }, () -> {
            params.height = state.foldHeight; view.setLayoutParams(params); view.setVisibility(expanded ? View.VISIBLE : View.GONE);
            state.opacity = 1f; opacity(view, state); arrow.setRotation(expanded ? 180f : 0f);
        });
    }

    static void text(TextView view, String value) {
        if (value.contentEquals(view.getText())) return;
        view.setText(value);
        State state = state(view); stopMain(state);
        // Counters and autosave status change frequently; keep their baseline stable.
        state.scale = 1f; state.opacity = 1f; scale(view, state); opacity(view, state);
        view.setTranslationX(0f); view.setTranslationY(0f);
    }

    static void enabled(View view, boolean enabled) {
        view.setEnabled(enabled); State state = state(view);
        float target = enabled ? 1f : .42f;
        boolean animate = allowed(view.getContext());
        if (animate && ((state.dim != null && state.enabledTarget == target) || (state.dim == null && state.enabledOpacity == target))) return;
        ValueAnimator old = state.dim; state.dim = null; if (old != null) old.cancel();
        state.enabledTarget = target;
        if (!animate) { state.enabledOpacity = target; opacity(view, state); return; }
        ValueAnimator animator = ValueAnimator.ofFloat(state.enabledOpacity, target); state.dim = animator;
        animator.setDuration(RELEASE); animator.setInterpolator(EASE);
        animator.addUpdateListener(a -> { state.enabledOpacity = (float) a.getAnimatedValue(); opacity(view, state); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { if (state.dim == a) state.dim = null; }
        }); animator.start();
    }

    static void focus(EditText field, View surface) {
        State state = state(surface);
        if (state.focusBound) return;
        state.focusBound = true;
        // Keep the writing surface open: no foreground, stroke, or changing geometry.
        state.focusOpacity = field.hasFocus() ? 1f : .94f;
        opacity(surface, state);
        View.OnFocusChangeListener previousListener = field.getOnFocusChangeListener();
        field.setOnFocusChangeListener((view, focused) -> {
            if (previousListener != null) previousListener.onFocusChange(view, focused);
            ValueAnimator old = state.focus; state.focus = null; if (old != null) old.cancel();
            float target = focused ? 1f : .94f;
            if (!allowed(surface.getContext())) { state.focusOpacity = target; opacity(surface, state); return; }
            ValueAnimator animator = ValueAnimator.ofFloat(state.focusOpacity, target); state.focus = animator;
            animator.setDuration(RELEASE); animator.setInterpolator(EASE);
            animator.addUpdateListener(a -> {
                state.focusOpacity = (float) a.getAnimatedValue(); opacity(surface, state);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { if (state.focus == a) state.focus = null; }
            }); animator.start();
        });
    }

    /** Bounded fades/resizing for small containers; large note lists use enter() instead. */
    static void layout(ViewGroup parent) {
        if (!allowed(parent.getContext()) || parent.getChildCount() > 24) { parent.setLayoutTransition(null); return; }
        if (parent.getLayoutTransition() != null) return;
        LayoutTransition transition = new BoundedTransition();
        transition.setDuration(ENTER); transition.setDuration(LayoutTransition.DISAPPEARING, EXIT);
        transition.setAnimateParentHierarchy(false);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.setAnimator(LayoutTransition.APPEARING, ObjectAnimator.ofFloat(null, "alpha", 0f, 1f));
        transition.setAnimator(LayoutTransition.DISAPPEARING, ObjectAnimator.ofFloat(null, "alpha", 1f, 0f));
        for (int type = 0; type <= LayoutTransition.CHANGING; type++) {
            transition.setStartDelay(type, 0); transition.setStagger(type, 0); transition.setInterpolator(type, EASE);
        }
        parent.setLayoutTransition(transition);
    }

    private static final class BoundedTransition extends LayoutTransition {
        private boolean ready(ViewGroup parent) {
            boolean enabled = allowed(parent.getContext()) && parent.getChildCount() <= 24;
            if (enabled) enableTransitionType(CHANGING); else parent.setLayoutTransition(null);
            return enabled;
        }
        @Override public void addChild(ViewGroup parent, View child) { if (ready(parent)) super.addChild(parent, child); }
        @Override public void removeChild(ViewGroup parent, View child) { if (ready(parent)) super.removeChild(parent, child); }
        @Override public void showChild(ViewGroup parent, View child, int oldVisibility) { if (ready(parent)) super.showChild(parent, child, oldVisibility); }
        @Override public void hideChild(ViewGroup parent, View child, int newVisibility) { if (ready(parent)) super.hideChild(parent, child, newVisibility); }
    }

    /** Configure before show(): preserves any existing OnShowListener and returns the same dialog. */
    static <T extends Dialog> T dialog(T dialog) { return window(dialog, false); }
    static <T extends Dialog> T sheet(T dialog) { return window(dialog, true); }

    static final class DialogBuilder extends AlertDialog.Builder {
        DialogBuilder(Context context) { super(context); }
        @Override public AlertDialog show() {
            AlertDialog dialog = create(); Motion.dialog(dialog).show(); return dialog;
        }
    }

    private static <T extends Dialog> T window(T dialog, boolean sheet) {
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setWindowAnimations(allowed(dialog.getContext()) ? (sheet ? R.style.JiannoteSheetMotion : R.style.JiannoteDialogMotion) : 0);
        if (DIALOGS.put(dialog, Boolean.TRUE) != null) return dialog;
        View decor = window.getDecorView();
        ViewTreeObserver.OnPreDrawListener feedback = () -> { dialogTapTree(decor); return true; };
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                dialogTapTree(view); view.getViewTreeObserver().addOnPreDrawListener(feedback);
            }
            @Override public void onViewDetachedFromWindow(View view) {
                if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnPreDrawListener(feedback);
                cancelTree(view);
            }
        });
        if (decor.isAttachedToWindow()) { dialogTapTree(decor); decor.getViewTreeObserver().addOnPreDrawListener(feedback); }
        return dialog;
    }

    private static void dialogTapTree(View view) {
        if ((view.isClickable() && !(view instanceof EditText) && !(view instanceof ListView)) || view.getId() == android.R.id.button1 ||
                view.getId() == android.R.id.button2 || view.getId() == android.R.id.button3) tap(view);
        if (view instanceof ListView) {
            ListView list = (ListView) view;
            for (int i = 0; i < list.getChildCount(); i++) tap(list.getChildAt(i));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) dialogTapTree(group.getChildAt(i));
        }
    }

    static void cancelTree(View view) {
        cancel(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (group.getLayoutTransition() != null) group.setLayoutTransition(null);
            for (int i = 0; i < group.getChildCount(); i++) cancelTree(group.getChildAt(i));
        }
    }

    private static void cancel(View view) {
        view.animate().cancel(); view.clearAnimation();
        State state = STATES.get(view); if (state == null) return;
        stopMain(state);
        if (state.press != null) { ValueAnimator old = state.press; state.press = null; old.cancel(); }
        if (state.dim != null) { ValueAnimator old = state.dim; state.dim = null; old.cancel(); }
        if (state.focus != null) { ValueAnimator old = state.focus; state.focus = null; old.cancel(); }
        state.scale = 1f; state.pressed = 1f; state.opacity = 1f; state.focusOpacity = 1f;
        state.enabledOpacity = view.isEnabled() ? 1f : .42f;
        state.enabledTarget = state.enabledOpacity;
        scale(view, state); opacity(view, state); view.setTranslationX(0f); view.setTranslationY(0f);
        if (state.foldHeight != null && view.getLayoutParams() != null) {
            view.getLayoutParams().height = state.foldHeight; view.setVisibility(state.expanded ? View.VISIBLE : View.GONE);
        }
    }
}
