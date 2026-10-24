package com.xiaotu.notes;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int BG = 0xFFFFFFFF, WHITE = 0xFFFFFFFF;
    static final int INK = 0xFF1E2433, MUTED = 0xFF697386;
    static final int ACCENT = 0xFF4D6BFE, ACCENT_SOFT = 0xFFEDF0FF;
    static final int ACCENT_TEXT = 0xFF3B52C7;
    static final int SURFACE = 0xFFF5F6F8, LINE = 0xFFEEF0F3;
    static final int DESTRUCTIVE = 0xFFC43D42;
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static void systemInsets(android.app.Activity activity, View root) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return insets;
            });
        }
    }
    static GradientDrawable shape(Context c, int color, float radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c,radius)); return g;
    }
    static GradientDrawable outline(Context c, int color, float radius, int stroke) {
        GradientDrawable g = shape(c,color,radius); g.setStroke(dp(c,1),stroke); return g;
    }
    static void ripple(View v,int color,float radius) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x184D6BFE),shape(v.getContext(),color,radius),shape(v.getContext(),WHITE,radius)));
        Motion.tap(v);
    }
    static void cardShadow(View view) {
        view.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        view.setElevation(dp(view.getContext(),2));
        if(android.os.Build.VERSION.SDK_INT>=28){
            view.setOutlineAmbientShadowColor(INK);
            view.setOutlineSpotShadowColor(INK);
        }
    }
    static TextView text(Context c,String value,int sp,int color,boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); t.setIncludeFontPadding(false);
        if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return t;
    }
    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(android.view.Gravity.CENTER_VERTICAL); return l; }
    static LinearLayout.LayoutParams lp(int width,int height) { return new LinearLayout.LayoutParams(width,height); }
    static void pad(View v,int h,int top,int bottom) { Context c=v.getContext(); v.setPadding(dp(c,h),dp(c,top),dp(c,h),dp(c,bottom)); }
    static void gap(LinearLayout l,int height) { l.addView(new View(l.getContext()),lp(1,dp(l.getContext(),height))); }
    static TextView button(Context c,String label,int background,int foreground) {
        TextView t=text(c,label,14,foreground,true);t.setGravity(android.view.Gravity.CENTER);t.setMinHeight(dp(c,48));t.setMinWidth(dp(c,48));
        pad(t,16,10,10);ripple(t,background,10);t.setClickable(true);t.setFocusable(true);
        t.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
        return t;
    }
}
