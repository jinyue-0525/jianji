package com.xiaotu.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Foreground-only LAN pairing and bidirectional synchronization. */
@SuppressWarnings("deprecation")
public final class SyncActivity extends Activity {
    public interface SyncHost {
        boolean prepareSync();
        void syncFinished();
    }
    private interface Work { String run(Operation operation) throws Exception; }
    private static final int SCAN_CODE = 8411;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile long appliedGeneration;
    private static Active active;
    private static long activeSerial;
    private static Operation operation;
    private static SyncClient.Cancellation polling;
    private static String lastPollError = "";
    private static final Map<String,Long> handledRequests = new HashMap<>();

    private static final class Active {
        final WeakReference<Activity> activity;
        final NoteStore notes;
        final SyncHost host;
        final long serial;
        Active(Activity activity, NoteStore notes, SyncHost host, long serial) {
            this.activity = new WeakReference<>(activity); this.notes = notes; this.host = host; this.serial = serial;
        }
    }
    private static final class Operation {
        final SyncClient.Cancellation cancellation = new SyncClient.Cancellation();
        final Active owner;
        final boolean sync;
        ProgressDialog progress;
        WeakReference<Activity> dialogOwner = new WeakReference<>(null);
        String stage;
        Operation(Active owner, boolean sync, String stage) { this.owner = owner; this.sync = sync; this.stage = stage; }
        void stage(String value) {
            MAIN.post(() -> {
                if (operation != this || cancellation.isCancelled()) return;
                stage = value;
                if (progress != null) progress.setMessage(value);
            });
        }
    }

    public static long syncGeneration() { return appliedGeneration; }
    public static boolean syncInProgress() { return operation != null && operation.sync; }

    public static void onForeground(Activity activity, NoteStore notes, SyncHost host) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> onForeground(activity, notes, host)); return; }
        if (polling != null) polling.cancel();
        MAIN.removeCallbacks(POLL);
        active = new Active(activity, notes, host, ++activeSerial);
        if (operation != null) showProgress(activity, operation);
        else MAIN.postDelayed(POLL, 1000);
    }

    public static void onBackground(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> onBackground(activity)); return; }
        if (active != null && active.activity.get() == activity) {
            active = null; activeSerial++;
            MAIN.removeCallbacks(POLL);
            if (polling != null) polling.cancel();
        }
        if (operation != null && operation.dialogOwner.get() == activity) {
            operation.cancellation.cancel(); operation.stage = "正在取消，请稍候…";
            dismissProgress(operation);
        }
    }

    private static final Runnable POLL = () -> {
        Active captured = active;
        if (captured == null || operation != null) return;
        Activity activity = captured.activity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        SyncClient.Cancellation cancellation = new SyncClient.Cancellation(); polling = cancellation;
        android.content.Context context = activity.getApplicationContext();
        WORKER.execute(() -> {
            EncryptedPeerPrefs.Peer peer = null;
            long requested = -1;
            String errorMessage = null;
            try {
                peer = new EncryptedPeerPrefs(context).read();
                if (peer != null) {
                    SyncClient client = client(peer, cancellation);
                    JSONObject state = client.status(peer);
                    long generation = state.getLong("syncRequested");
                    if (generation > peer.lastGeneration) requested = generation;
                }
            } catch (Exception error) {
                if (!cancellation.isCancelled()) {
                    errorMessage = SyncClient.readable(error, cancellation);
                    if (peer != null) saveStatus(context, peer, errorMessage);
                }
            }
            final EncryptedPeerPrefs.Peer resultPeer = peer;
            final long request = requested;
            final String failure = errorMessage;
            MAIN.post(() -> {
                if (polling == cancellation) polling = null;
                if (active != captured || cancellation.isCancelled()) return;
                if (failure != null) {
                    if (!failure.equals(lastPollError)) { lastPollError = failure; toast(captured.activity.get(), failure); refreshConnection(captured.activity.get()); }
                } else lastPollError = "";
                if (operation == null && request >= 0 && resultPeer != null) {
                    String key = resultPeer.peerId + ":" + resultPeer.fingerprint;
                    long handled = handledRequests.containsKey(key) ? handledRequests.get(key) : -1;
                    if (request > handled) { handledRequests.put(key, request); startSync(captured, resultPeer, request); }
                }
                if (operation == null && active == captured) MAIN.postDelayed(SyncActivity.POLL, 3000);
            });
        });
    };

    private static SyncClient client(EncryptedPeerPrefs.Peer peer, SyncClient.Cancellation cancellation) throws Exception {
        return new SyncClient(new SyncClient.Endpoint(peer.host, peer.port, peer.fingerprint), cancellation);
    }
    private static boolean samePeer(EncryptedPeerPrefs.Peer left, EncryptedPeerPrefs.Peer right) {
        return left != null && right != null && left.peerId.equals(right.peerId) && left.fingerprint.equals(right.fingerprint)
            && left.accessToken.equals(right.accessToken);
    }
    private static void saveStatus(android.content.Context context, EncryptedPeerPrefs.Peer expected, String status) {
        try {
            EncryptedPeerPrefs prefs = new EncryptedPeerPrefs(context); EncryptedPeerPrefs.Peer current = prefs.read();
            if (samePeer(current, expected) && !status.equals(current.lastStatus)) { current.lastStatus = status; prefs.save(current); }
        } catch (RuntimeException ignored) { }
    }
    private static void consumeRequest(android.content.Context context, EncryptedPeerPrefs.Peer expected, long generation) {
        if (expected == null || generation < 0) return;
        WORKER.execute(() -> {
            try {
                EncryptedPeerPrefs prefs = new EncryptedPeerPrefs(context); EncryptedPeerPrefs.Peer peer = prefs.read();
                if (samePeer(peer, expected)) { peer.lastGeneration = Math.max(peer.lastGeneration, generation); peer.lastStatus = "请等待当前图片处理或先保存笔记，再发起同步。"; prefs.save(peer); }
            } catch (RuntimeException ignored) { }
        });
    }

    private static void startSync(Active captured, EncryptedPeerPrefs.Peer expected, long requestedGeneration) {
        Activity activity = captured.activity.get();
        if (active != captured || operation != null || activity == null || activity.isFinishing()) return;
        try {
            if (!MainActivity.flushForSync() || !captured.host.prepareSync()) {
                consumeRequest(activity.getApplicationContext(), expected, requestedGeneration);
                toast(activity, "请等待当前图片处理或先保存笔记"); return;
            }
        } catch (RuntimeException error) {
            consumeRequest(activity.getApplicationContext(), expected, requestedGeneration);
            toast(activity, "当前笔记未能保存，已停止同步。请先保存后再试。"); return;
        }
        android.content.Context context = activity.getApplicationContext();
        begin(captured, true, "正在连接电脑…", task -> {
            EncryptedPeerPrefs prefs = new EncryptedPeerPrefs(context);
            EncryptedPeerPrefs.Peer peer = prefs.read();
            if (peer == null || expected != null && !samePeer(peer, expected)) throw new SyncClient.Failure("配对状态已变化，请重新发起同步。");
            SyncClient network = client(peer, task.cancellation);
            long request = requestedGeneration;
            if (request < 0) request = network.status(peer).getLong("syncRequested");
            peer.lastGeneration = Math.max(peer.lastGeneration, request); prefs.save(peer);
            try {
                SyncStore data = new SyncStore(context, captured.notes);
                network.synchronize(peer, data, request, task::stage, () -> appliedGeneration++);
                peer.lastSyncAt = System.currentTimeMillis(); peer.lastStatus = "同步完成"; prefs.save(peer);
                return "同步完成";
            } catch (Exception error) {
                saveStatus(context, peer, SyncClient.readable(error, task.cancellation));
                throw error;
            }
        });
    }

    private static void begin(Active captured, boolean sync, String stage, Work work) {
        Activity activity = captured.activity.get();
        if (operation != null || active != captured || activity == null) return;
        MAIN.removeCallbacks(POLL);
        if (polling != null) polling.cancel();
        Operation task = new Operation(captured, sync, stage); operation = task;
        showProgress(activity, task);
        WORKER.execute(() -> {
            String success = null, failure = null;
            try { task.cancellation.check(); success = work.run(task); }
            catch (Exception error) { failure = SyncClient.readable(error, task.cancellation); }
            final String completed = success, failed = failure;
            MAIN.post(() -> {
                if (operation != task) return;
                dismissProgress(task); operation = null;
                if (!task.sync && completed != null) handledRequests.clear();
                Active current = active;
                if (current != null) {
                    Activity shown = current.activity.get();
                    if (task.sync) {
                        try { current.host.syncFinished(); }
                        catch (RuntimeException ignored) { toast(shown, "同步已结束，请返回列表查看更新。"); }
                    }
                    refreshConnection(shown);
                    if (failed != null && !task.cancellation.isCancelled()) showError(shown, task.sync ? "同步未完成" : "操作未完成", failed);
                    else if (failed != null) toast(shown, failed);
                    else if (completed != null) toast(shown, completed);
                    MAIN.postDelayed(POLL, 3000);
                }
            });
        });
    }
    private static void showProgress(Activity activity, Operation task) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (task.progress != null && task.dialogOwner.get() == activity && task.progress.isShowing()) return;
        dismissProgress(task);
        ProgressDialog dialog = new ProgressDialog(activity); dialog.setTitle(task.sync ? "电脑同步" : "连接电脑");
        dialog.setMessage(task.stage); dialog.setIndeterminate(true); dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false);
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "取消", (ignored, which) -> {});
        task.progress = dialog; task.dialogOwner = new WeakReference<>(activity);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
            task.cancellation.cancel(); task.stage = "正在取消，请稍候…"; dialog.setMessage(task.stage);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }));
        Motion.dialog(dialog).show();
        if (task.cancellation.isCancelled()) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
    }
    private static void dismissProgress(Operation task) {
        ProgressDialog dialog = task.progress; task.progress = null; task.dialogOwner = new WeakReference<>(null);
        if (dialog != null) { try { dialog.dismiss(); } catch (RuntimeException ignored) { } }
    }
    private static void toast(Activity activity, String message) {
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
    private static void showError(Activity activity, String title, String message) {
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) new Motion.DialogBuilder(activity).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show();
    }
    private static void refreshConnection(Activity activity) { if (activity instanceof SyncActivity) ((SyncActivity) activity).renderConnection(); }

    private NoteStore localStore;
    private TextView computerName, connectionDetails, lastStatus, lastTime, syncButton, disconnectButton;
    private EncryptedPeerPrefs.Peer displayedPeer;
    private int renderVersion;
    private boolean pairingPrompt;
    private String pendingQr;
    private final SyncHost ownHost = new SyncHost() {
        @Override public boolean prepareSync() { return !pairingPrompt; }
        @Override public void syncFinished() { renderConnection(); }
    };

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base); MainActivity.applyFontPreference(this, base);
    }
    @Override public void onCreate(Bundle state) {
        setTheme(R.style.AppTheme); super.onCreate(state);
        getWindow().setStatusBarColor(Ui.WHITE); getWindow().setNavigationBarColor(Ui.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        localStore = new NoteStore(getApplicationContext());
        LinearLayout screen = Ui.column(this); screen.setBackgroundColor(Ui.WHITE); Ui.systemInsets(this, screen);
        LinearLayout header = Ui.row(this); Ui.pad(header, 8, 0, 2);
        FrameLayout back = new FrameLayout(this); Ui.ripple(back, Color.TRANSPARENT, 12); back.setContentDescription("返回笔记"); back.setFocusable(true); back.setOnClickListener(view -> finish());
        back.addView(new IconView(this, "back", Ui.INK), new FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20), Gravity.CENTER));
        header.addView(back, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 48))); header.addView(Ui.text(this, "电脑同步", 16, Ui.INK, true)); screen.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = Ui.column(this); Ui.pad(content, 20, 12, 24); scroll.addView(content); screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView introduction = Ui.text(this, "在同一 Wi-Fi 下，与 Mac 或 Windows 电脑双向同步笔记和图片。", 14, Ui.INK, false); introduction.setLineSpacing(Ui.dp(this, 5), 1f); content.addView(introduction); Ui.gap(content, 20);
        content.addView(Ui.text(this, "已配对电脑", 12, Ui.MUTED, false)); Ui.gap(content, 8);
        LinearLayout card = Ui.column(this); Ui.pad(card, 14, 14, 14); card.setBackground(Ui.shape(this, Ui.SURFACE, 12)); Ui.cardShadow(card);
        computerName = Ui.text(this, "尚未连接电脑", 15, Ui.INK, true); card.addView(computerName); Ui.gap(card, 7);
        connectionDetails = Ui.text(this, "在电脑简记中打开“设备同步”，再扫描配对码。", 12, Ui.MUTED, false); connectionDetails.setLineSpacing(Ui.dp(this, 4), 1f); card.addView(connectionDetails); Ui.gap(card, 12);
        lastStatus = Ui.text(this, "配对后不会自动传输全部笔记", 12, Ui.MUTED, false); lastStatus.setLineSpacing(Ui.dp(this, 4), 1f); card.addView(lastStatus); Ui.gap(card, 6);
        lastTime = Ui.text(this, "尚未同步", 12, Ui.MUTED, false); card.addView(lastTime); content.addView(card); Ui.gap(content, 18);
        TextView scan = Ui.button(this, "扫一扫连接电脑", Ui.INK, Ui.WHITE); scan.setOnClickListener(view -> startActivityForResult(new Intent(this, QrScanActivity.class), SCAN_CODE)); content.addView(scan, Ui.lp(-1, Ui.dp(this, 48))); Ui.gap(content, 8);
        TextView paste = Ui.button(this, "粘贴配对码", Color.TRANSPARENT, Ui.ACCENT_TEXT); paste.setOnClickListener(view -> pasteCode()); content.addView(paste, Ui.lp(-1, Ui.dp(this, 48))); Ui.gap(content, 12);
        syncButton = Ui.button(this, "立即同步", Ui.ACCENT_SOFT, Ui.ACCENT_TEXT); syncButton.setEnabled(false);
        syncButton.setOnClickListener(view -> { Active current = active; if (current != null && current.activity.get() == this) startSync(current, null, -1); }); content.addView(syncButton, Ui.lp(-1, Ui.dp(this, 48))); Ui.gap(content, 8);
        disconnectButton = Ui.button(this, "解除配对", Color.TRANSPARENT, Ui.DESTRUCTIVE); disconnectButton.setEnabled(false); disconnectButton.setOnClickListener(view -> disconnect()); content.addView(disconnectButton, Ui.lp(-1, Ui.dp(this, 48))); Ui.gap(content, 18);
        TextView help = Ui.text(this, "只有本应用在前台时，电脑发起的同步请求才会响应。\n同步期间会暂时锁定编辑；并发修改会保留冲突副本。\n电脑 IP 或证书变化后，需要重新扫码确认。", 12, Ui.MUTED, false); help.setLineSpacing(Ui.dp(this, 5), 1f); content.addView(help);
        setContentView(screen);
    }
    @Override protected void onResume() {
        super.onResume(); onForeground(this, localStore, ownHost); renderConnection();
        if (pendingQr != null) { String value = pendingQr; pendingQr = null; confirmCode(value); }
    }
    @Override protected void onPause() { onBackground(this); super.onPause(); }
    @Override protected void onDestroy() {
        onBackground(this); renderVersion++;
        NoteStore closing = localStore; WORKER.execute(closing::close);
        super.onDestroy();
    }
    @Override public void onBackPressed() { if (operation != null) { toast(this, "请先等待操作完成或取消"); return; } super.onBackPressed(); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCAN_CODE && resultCode == RESULT_OK && data != null) pendingQr = data.getStringExtra(QrScanActivity.EXTRA_QR);
    }
    private void renderConnection() {
        int version = ++renderVersion;
        android.content.Context context = getApplicationContext();
        WORKER.execute(() -> {
            EncryptedPeerPrefs.Peer peer = null; String failure = null;
            try { peer = new EncryptedPeerPrefs(context).read(); }
            catch (RuntimeException error) { failure = error.getMessage(); }
            final EncryptedPeerPrefs.Peer result = peer; final String problem = failure;
            MAIN.post(() -> {
                if (version != renderVersion || isFinishing() || isDestroyed()) return;
                displayedPeer = result;
                computerName.setText(result == null ? "尚未连接电脑" : result.name);
                connectionDetails.setText(result == null ? "在电脑简记中打开“设备同步”，再扫描配对码。" : result.host + ":" + result.port + " · 加密连接");
                lastStatus.setText(problem != null ? problem : result == null ? "配对后不会自动传输全部笔记" : result.lastStatus);
                lastTime.setText(result == null || result.lastSyncAt <= 0 ? "尚未同步" : "上次完成：" + new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(result.lastSyncAt)));
                Motion.enabled(syncButton, result != null); Motion.enabled(disconnectButton, result != null || problem != null);
            });
        });
    }
    private void pasteCode() {
        if (operation != null) return;
        EditText input = new EditText(this); input.setTextSize(14); input.setBackground(null); input.setHint("粘贴电脑显示的配对码"); input.setContentDescription("电脑配对码"); input.setMinLines(3); input.setMaxLines(6); Ui.pad(input, 24, 8, 8);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16384)});
        pairingPrompt = true;
        AlertDialog dialog = new Motion.DialogBuilder(this).setTitle("粘贴配对码").setView(input).setNegativeButton("取消", null).setPositiveButton("继续", null).create();
        dialog.setOnDismissListener(ignored -> pairingPrompt = false);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String raw = input.getText().toString();
            try { SyncClient.parsePairCode(raw); dialog.dismiss(); confirmCode(raw); }
            catch (Exception error) { input.setError(error.getMessage()); }
        }));
        Motion.dialog(dialog).show();
    }
    private void confirmCode(String raw) {
        if (operation != null) { toast(this, "请先完成当前操作，再重新扫码"); return; }
        final SyncClient.PairCode code;
        try { code = SyncClient.parsePairCode(raw); }
        catch (Exception error) { showError(this, "无法使用配对码", error.getMessage()); return; }
        pairingPrompt = true;
        AlertDialog dialog = new Motion.DialogBuilder(this).setTitle("连接这台电脑？")
            .setMessage(code.name + "\n" + code.host + ":" + code.port + "\n\n请确认这是你的电脑。接下来需要在电脑端批准；配对完成后不会立即同步笔记。" + (displayedPeer != null ? "\n新的配对成功后将替换本机当前连接。" : ""))
            .setNegativeButton("取消", null).setPositiveButton("确认连接", (ignored, which) -> {
                pairingPrompt = false;
                Active captured = active;
                if (captured == null || captured.activity.get() != this) return;
                android.content.Context context = getApplicationContext();
                begin(captured, false, "正在验证电脑证书…", task -> {
                    SyncStore data = new SyncStore(context, captured.notes);
                    EncryptedPeerPrefs.Peer peer = new SyncClient(code, task.cancellation).pair(code, data, task::stage);
                    new EncryptedPeerPrefs(context).save(peer);
                    return "电脑配对完成，可点“立即同步”";
                });
            }).create();
        dialog.setOnDismissListener(ignored -> pairingPrompt = false); Motion.dialog(dialog).show();
    }
    private void disconnect() {
        if (operation != null) return;
        new Motion.DialogBuilder(this).setTitle("解除电脑配对？").setMessage("本机和电脑已有的笔记都会保留。解除后需要重新扫码才能继续同步。")
            .setNegativeButton("取消", null).setPositiveButton("解除配对", (ignored, which) -> {
                Active captured = active; if (captured == null || captured.activity.get() != this) return;
                android.content.Context context = getApplicationContext();
                begin(captured, false, "正在解除配对…", task -> {
                    EncryptedPeerPrefs prefs = new EncryptedPeerPrefs(context); EncryptedPeerPrefs.Peer peer = null;
                    try { peer = prefs.read(); } catch (RuntimeException ignoredRead) { }
                    boolean notified = peer == null;
                    if (peer != null) {
                        try { client(peer, task.cancellation).json("DELETE", "/v1/peers/self", "Bearer " + peer.accessToken, null); notified = true; }
                        catch (Exception error) { task.cancellation.check(); }
                    }
                    task.cancellation.check(); prefs.clear();
                    return notified ? "已解除配对" : "已解除本机配对。电脑未响应，可在电脑端移除此手机。";
                });
            }).show();
    }
}
