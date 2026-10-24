package com.xiaotu.notes;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local camera decoding only; no account, Google service, or remote image upload. */
@SuppressWarnings("deprecation")
public final class QrScanActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_QR = "jianji_pair_qr";
    private static final int CAMERA_PERMISSION = 8412;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private SurfaceView preview;
    private TextView hint;
    private Camera camera;
    private boolean resumed, surfaceReady, delivered;
    private int cameraGeneration;
    private final Runnable focusAgain = new Runnable() {
        @Override public void run() {
            if (camera == null || !resumed) return;
            try {
                if (Camera.Parameters.FOCUS_MODE_AUTO.equals(camera.getParameters().getFocusMode())) camera.autoFocus((success, source) -> {});
            } catch (RuntimeException ignored) { }
            main.postDelayed(this, 1800);
        }
    };

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base); MainActivity.applyFontPreference(this, base);
    }
    @Override public void onCreate(Bundle state) {
        setTheme(R.style.AppTheme); super.onCreate(state);
        getWindow().setStatusBarColor(Ui.WHITE); getWindow().setNavigationBarColor(Ui.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout screen = Ui.column(this); screen.setBackgroundColor(Ui.WHITE); Ui.systemInsets(this, screen);
        LinearLayout header = Ui.row(this); Ui.pad(header, 8, 0, 2);
        FrameLayout close = new FrameLayout(this); Ui.ripple(close, Color.TRANSPARENT, 12);
        close.setContentDescription("返回电脑同步"); close.setFocusable(true); close.setOnClickListener(view -> finish());
        close.addView(new IconView(this, "back", Ui.INK), new FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20), Gravity.CENTER));
        header.addView(close, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 48))); header.addView(Ui.text(this, "扫描电脑配对码", 16, Ui.INK, true)); screen.addView(header);
        preview = new SurfaceView(this); preview.getHolder().addCallback(this);
        screen.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        hint = Ui.text(this, "将电脑简记中的二维码放入画面。\n二维码仅在本机识别，连接前还会请你确认电脑。", 13, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER); hint.setLineSpacing(Ui.dp(this, 4), 1f); Ui.pad(hint, 20, 20, 20); screen.addView(hint);
        TextView cancel = Ui.button(this, "返回，改用粘贴配对码", Color.TRANSPARENT, Ui.ACCENT_TEXT); cancel.setOnClickListener(view -> finish()); screen.addView(cancel, Ui.lp(-1, Ui.dp(this, 48)));
        setContentView(screen);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; openCamera(); }
    @Override protected void onPause() { resumed = false; releaseCamera(); super.onPause(); }
    @Override protected void onDestroy() { releaseCamera(); main.removeCallbacksAndMessages(null); decoder.shutdownNow(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else hint.setText("相机权限未开启。可以返回粘贴电脑上的配对码，或在系统设置中允许相机后再试。");
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; openCamera(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; releaseCamera(); }

    private void openCamera() {
        if (!resumed || !surfaceReady || camera != null || delivered || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int selected = 0; Camera.CameraInfo info = new Camera.CameraInfo();
            int count = Camera.getNumberOfCameras(); if (count < 1) throw new IllegalStateException();
            for (int i = 0; i < count; i++) { Camera.getCameraInfo(i, info); if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { selected = i; break; } }
            Camera.getCameraInfo(selected, info); camera = Camera.open(selected);
            Camera.Parameters parameters = camera.getParameters(); parameters.setPreviewFormat(ImageFormat.NV21);
            Camera.Size chosen = null;
            for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
                if (size.width <= 1280 && size.height <= 960 && (chosen == null || size.width * size.height > chosen.width * chosen.height)) chosen = size;
            }
            if (chosen != null) parameters.setPreviewSize(chosen.width, chosen.height);
            List<String> focus = parameters.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(parameters);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
            int orientation = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? (360 - (info.orientation + degrees) % 360) % 360 : (info.orientation - degrees + 360) % 360;
            camera.setDisplayOrientation(orientation); camera.setPreviewDisplay(preview.getHolder()); camera.startPreview();
            cameraGeneration++; requestFrame(cameraGeneration); main.post(focusAgain);
        } catch (Exception error) {
            releaseCamera(); hint.setText("暂时无法打开相机，请关闭其他占用相机的应用。也可以返回粘贴配对码。");
        }
    }
    private void requestFrame(int generation) {
        if (!resumed || camera == null || delivered || generation != cameraGeneration) return;
        try {
            camera.setOneShotPreviewCallback((data, source) -> {
                if (generation != cameraGeneration || !resumed || data == null) return;
                Camera.Size size;
                try { size = source.getParameters().getPreviewSize(); } catch (RuntimeException ignored) { return; }
                decoder.execute(() -> {
                    String result = null;
                    MultiFormatReader reader = new MultiFormatReader();
                    try {
                        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
                        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
                        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE); hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
                        PlanarYUVLuminanceSource pixels = new PlanarYUVLuminanceSource(data, size.width, size.height, 0, 0, size.width, size.height, false);
                        result = reader.decode(new BinaryBitmap(new HybridBinarizer(pixels)), hints).getText();
                    } catch (Exception ignored) { }
                    finally { reader.reset(); }
                    final String decoded = result;
                    main.post(() -> {
                        if (!resumed || isFinishing() || generation != cameraGeneration || delivered) return;
                        if (decoded != null && decoded.length() <= 16384) {
                            delivered = true; setResult(RESULT_OK, new Intent().putExtra(EXTRA_QR, decoded)); finish();
                        } else main.postDelayed(() -> requestFrame(generation), 160);
                    });
                });
            });
        } catch (RuntimeException ignored) { if (resumed) main.postDelayed(() -> requestFrame(generation), 300); }
    }
    private void releaseCamera() {
        cameraGeneration++; main.removeCallbacks(focusAgain);
        if (camera != null) {
            try { camera.setPreviewCallback(null); camera.stopPreview(); } catch (RuntimeException ignored) { }
            try { camera.release(); } catch (RuntimeException ignored) { }
            camera = null;
        }
    }
}
