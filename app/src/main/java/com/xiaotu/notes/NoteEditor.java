package com.xiaotu.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NoteEditor extends LinearLayout {
    public interface Listener { void onClose(); }
    private static final int REQUEST_IMAGE = 4821;
    private static final long MAX_IMAGE_BYTES = 30L * 1024L * 1024L;
    private final Activity activity;
    private final NoteStore store;
    private final Listener listener;
    private final boolean newDraft;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final List<String> attachments = new ArrayList<>();
    private final List<AttachmentCard> attachmentCards = new ArrayList<>();
    private final LinkedHashSet<String> cleanupCandidates = new LinkedHashSet<>();
    private final NoteStore.Note note;
    private final EditText bodyInput;
    private final TextView status;
    private final FrameLayout importProgress;
    private final ProgressBar importSpinner;
    private final View importStaticIndicator;
    private final TextView folderLabel;
    private final TextView dateLabel;
    private final TextView wordCount;
    private final LinearLayout document;
    private final LinearLayout imageList;
    private final ScrollView scroll;
    private final FrameLayout undoButton;
    private final FrameLayout redoButton;
    private final LinearLayout imageButton;
    private EditorHistory history;
    private long lastTypingAt;
    private int editKind;
    private int lastEditKind;
    private boolean loading = true;
    private boolean dirty;
    private boolean importing;
    private volatile boolean disposed;
    private final Runnable pendingSave = () -> persist(false);
    private final Runnable pendingHistory = this::captureTyping;

    public NoteEditor(Activity activity, NoteStore store, long noteId, Listener listener) {
        this(activity, store, noteId, false, listener);
    }

    public NoteEditor(Activity activity, NoteStore store, long noteId, boolean isNewDraft, Listener listener) {
        super(activity);
        this.activity = activity;
        this.store = store;
        this.listener = listener;
        this.newDraft = isNewDraft;
        this.note = store.getNote(noteId);
        if (note == null) throw new IllegalArgumentException("这篇笔记不存在或已被删除");
        readContent();
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.WHITE);

        LinearLayout header = Ui.row(activity);
        Ui.pad(header, 8, 0, 2);
        header.addView(iconButton("back", "返回笔记", v -> onBack()));
        LinearLayout headingGroup = Ui.column(activity);
        headingGroup.setGravity(Gravity.CENTER_VERTICAL);
        headingGroup.setPadding(Ui.dp(activity, 4), 0, Ui.dp(activity, 4), 0);
        headingGroup.setMinimumHeight(Ui.dp(activity, 48));
        TextView heading = Ui.text(activity, "笔记", 15, Ui.INK, true);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        headingGroup.addView(heading, Ui.lp(-1, -2));
        Ui.gap(headingGroup, 2);
        LinearLayout saveRow = Ui.row(activity);
        importProgress = new FrameLayout(activity);
        importProgress.setVisibility(GONE);
        importProgress.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        importSpinner = new ProgressBar(activity, null, android.R.attr.progressBarStyleSmall);
        importSpinner.setIndeterminate(true);
        importSpinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        importProgress.addView(importSpinner, new FrameLayout.LayoutParams(-1, -1));
        importStaticIndicator = new View(activity);
        GradientDrawable importRing = Ui.outline(activity, Color.TRANSPARENT, 0, Ui.ACCENT);
        importRing.setShape(GradientDrawable.OVAL);
        importStaticIndicator.setBackground(importRing);
        importStaticIndicator.setVisibility(GONE);
        importProgress.addView(importStaticIndicator, new FrameLayout.LayoutParams(-1, -1));
        LayoutParams progressParams = Ui.lp(Ui.dp(activity, 12), Ui.dp(activity, 12));
        progressParams.rightMargin = Ui.dp(activity, 3);
        saveRow.addView(importProgress, progressParams);
        status = Ui.text(activity, "已保存", 12, Ui.MUTED, false);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        keepFullTextAccessible(status, status, "", "");
        saveRow.addView(status, new LayoutParams(0, -2, 1));
        headingGroup.addView(saveRow, Ui.lp(-1, -2));
        header.addView(headingGroup, new LayoutParams(0, -2, 1));
        undoButton = iconButton("undo", "撤销", v -> undo());
        redoButton = iconButton("redo", "重做", v -> redo());
        header.addView(undoButton);
        header.addView(redoButton);
        header.addView(iconButton("check", "保存笔记", v -> saveFromToolbar()));
        header.addView(iconButton("more", "更多笔记操作", this::showMenu));
        addView(header, Ui.lp(-1, -2));

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 2), Ui.dp(activity, 20), Ui.dp(activity, 24));
        scroll.setVerticalScrollBarEnabled(false);
        document = Ui.column(activity);

        LinearLayout metadata = Ui.row(activity);
        Ui.pad(metadata, 20, 0, 0);
        LinearLayout dateRow = Ui.row(activity);
        dateLabel = Ui.text(activity, formatDate(), 12, Ui.MUTED, false);
        dateLabel.setSingleLine(true);
        dateLabel.setEllipsize(TextUtils.TruncateAt.END);
        dateLabel.setGravity(Gravity.CENTER_VERTICAL);
        keepFullTextAccessible(dateLabel, dateLabel, "", "");
        dateRow.addView(dateLabel, new LayoutParams(0, -2, 1));
        wordCount = Ui.text(activity, "0 字", 12, Ui.ACCENT_TEXT, true);
        wordCount.setFontFeatureSettings("tnum");
        wordCount.setSingleLine(true);
        wordCount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        wordCount.setPadding(Ui.dp(activity, 8), 0, 0, 0);
        dateRow.addView(wordCount, Ui.lp(-2, -2));
        metadata.addView(dateRow, new LayoutParams(0, -2, 1));
        FrameLayout folderChip = new FrameLayout(activity);
        folderChip.setMinimumHeight(Ui.dp(activity, 48));
        folderChip.setMinimumWidth(Ui.dp(activity, 48));
        folderChip.setFocusable(true);
        Ui.ripple(folderChip, Color.TRANSPARENT, 8);
        folderChip.setContentDescription("当前位置：" + locationName() + "，点按移动笔记");
        folderChip.setOnClickListener(v -> chooseFolder());
        folderLabel = Ui.text(activity, locationName(), 12, Ui.ACCENT_TEXT, false);
        folderLabel.setSingleLine(true);
        folderLabel.setEllipsize(TextUtils.TruncateAt.END);
        folderLabel.setMaxWidth(Ui.dp(activity, 120));
        folderLabel.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(folderLabel, 8, 0, 0);
        folderLabel.setBackground(Ui.shape(activity, Ui.ACCENT_SOFT, 8));
        folderLabel.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        folderLabel.setMinHeight(Ui.dp(activity, 26));
        keepFullTextAccessible(folderLabel, folderChip, "当前位置：", "，点按移动笔记");
        folderChip.addView(folderLabel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        LayoutParams folderParams = Ui.lp(-2, Ui.dp(activity, 48));
        folderParams.leftMargin = Ui.dp(activity, 8);
        metadata.addView(folderChip, folderParams);
        metadata.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) fitFolderWidth(right - left);
        });
        addView(metadata, Ui.lp(-1, Ui.dp(activity, 48)));
        bodyInput = edit("写下此刻的想法…", 16, false);
        bodyInput.setContentDescription("笔记正文");
        bodyInput.setMinLines(1);
        bodyInput.setGravity(Gravity.TOP | Gravity.START);
        bodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bodyInput.setText(NoteStore.plainText(note));
        updateWordCount();
        document.addView(bodyInput, Ui.lp(-1, -2));
        document.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        document.setOnClickListener(view -> {
            bodyInput.requestFocus();
            InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(bodyInput, InputMethodManager.SHOW_IMPLICIT);
        });
        imageList = Ui.column(activity);
        Motion.layout(imageList);
        document.addView(imageList, Ui.lp(-1, -2));
        scroll.addView(document, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1));

        LinearLayout toolRow = Ui.row(activity);
        Ui.pad(toolRow, 12, 0, 2);
        imageButton = toolButton("image", "图片", this::pickImage);
        toolRow.addView(imageButton, Ui.lp(-2, Ui.dp(activity, 48)));
        addView(toolRow, Ui.lp(-1, -2));
        renderImages();
        history = new EditorHistory(snapshot());
        bodyInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                if (loading) return;
                if (history.current().body.contentEquals(text))
                    history.rememberSelection(bodyInput.getSelectionStart(), bodyInput.getSelectionEnd());
                editKind = count == 0 && after > 0 ? 1 : after == 0 && count > 0 ? -1 : 0;
            }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                updateWordCount();
                if (loading) return;
                handler.removeCallbacks(pendingHistory);
                handler.postDelayed(pendingHistory, 350);
                changed();
                updateHistoryButtons();
            }
        });
        loading = false;
        updateHistoryButtons();
        setFocusableInTouchMode(true);
        requestFocus();
    }

    private void keepFullTextAccessible(TextView label, View target, String prefix, String suffix) {
        Runnable update = () -> {
            String description = prefix + label.getText().toString() + suffix;
            target.setContentDescription(description);
            target.setTooltipText(description);
        };
        update.run();
        label.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { update.run(); }
        });
    }

    private void fitFolderWidth(int width) {
        if (width <= 0) width = Ui.dp(activity, getResources().getConfiguration().screenWidthDp);
        int countWidth = (int) Math.ceil(wordCount.getPaint().measureText(wordCount.getText().toString()))
            + wordCount.getPaddingLeft() + wordCount.getPaddingRight();
        // Keep the complete count ahead of a long category, with a little room for the date.
        int available = width - Ui.dp(activity, 40 + 8 + 24) - countWidth;
        folderLabel.setMaxWidth(Math.min(Ui.dp(activity, 120), Math.max(Ui.dp(activity, 48), available)));
    }

    private void updateWordCount() {
        CharSequence text = bodyInput.getText();
        int count = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = Character.codePointAt(text, offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) count++;
            offset += Character.charCount(codePoint);
        }
        Motion.text(wordCount, count + " 字");
        wordCount.setContentDescription("正文 " + count + " 字，不含空格和换行");
        fitFolderWidth(getWidth());
    }

    private void readContent() {
        try {
            JSONArray images = new JSONArray(emptyJson(note.attachments));
            for (int i = 0; i < images.length(); i++) {
                String path = images.optString(i, "");
                if (!path.isEmpty()) attachments.add(path);
            }
        } catch (JSONException e) {
            throw new IllegalStateException("笔记内容格式损坏，暂时无法编辑", e);
        }
    }

    private EditText edit(String hint, int sp, boolean bold) {
        EditText input = new EditText(activity);
        input.setTextSize(sp);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.MUTED);
        input.setHint(hint);
        input.setBackground(null);
        input.setPadding(0, Ui.dp(activity, 5), 0, Ui.dp(activity, 5));
        input.setMinHeight(Ui.dp(activity, 48));
        input.setLineSpacing(Ui.dp(activity, 4), 1.1f);
        input.setSelectAllOnFocus(false);
        if (bold) input.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        return input;
    }

    private FrameLayout iconButton(String icon, String label, View.OnClickListener click) {
        FrameLayout button = new FrameLayout(activity);
        button.setLayoutParams(Ui.lp(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        boolean save = "check".equals(icon);
        Ui.ripple(button, Color.TRANSPARENT, 12);
        button.setContentDescription(label);
        button.setFocusable(true);
        IconView graphic = new IconView(activity, icon, save ? Ui.ACCENT_TEXT : Ui.INK);
        graphic.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(graphic, new FrameLayout.LayoutParams(Ui.dp(activity, 20), Ui.dp(activity, 20), Gravity.CENTER));
        button.setOnClickListener(click);
        return button;
    }

    private LinearLayout toolButton(String icon, String label, View.OnClickListener click) {
        LinearLayout button = Ui.row(activity);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(Ui.dp(activity, 48));
        button.setMinimumWidth(Ui.dp(activity, 84));
        Ui.pad(button, 8, 0, 0);
        Ui.ripple(button, Color.TRANSPARENT, 16);
        button.setContentDescription(label);
        button.setFocusable(true);
        IconView graphic = new IconView(activity, icon, Ui.ACCENT);
        graphic.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(graphic, Ui.lp(Ui.dp(activity, 18), Ui.dp(activity, 18)));
        TextView text = Ui.text(activity, label, 13, Ui.INK, false);
        text.setPadding(Ui.dp(activity, 7), 0, 0, 0);
        button.addView(text);
        button.setOnClickListener(click);
        return button;
    }

    private void changed() {
        if (loading || disposed) return;
        dirty = true;
        Motion.text(status, importing ? "正在导入图片…" : "保存中…");
        handler.removeCallbacks(pendingSave);
        handler.postDelayed(pendingSave, 700);
    }

    private EditorHistory.Snapshot snapshot() {
        return new EditorHistory.Snapshot(bodyInput.getText().toString(), attachments,
            bodyInput.getSelectionStart(), bodyInput.getSelectionEnd());
    }

    private void recordTyping() {
        long now = android.os.SystemClock.uptimeMillis();
        boolean merge = now - lastTypingAt < 1200 && editKind == lastEditKind && editKind != 0;
        history.record(snapshot(), merge);
        lastTypingAt = now;
        lastEditKind = editKind;
    }

    private void captureTyping() {
        if (disposed || loading || BaseInputConnection.getComposingSpanStart(bodyInput.getText()) >= 0) return;
        recordTyping();
        updateHistoryButtons();
    }

    private void finishHistoryGroup() {
        handler.removeCallbacks(pendingHistory);
        history.record(snapshot(), false);
        lastTypingAt = 0;
        lastEditKind = 0;
    }

    private void updateHistoryButtons() {
        if (history == null) return;
        boolean pendingText = !history.current().sameContent(snapshot());
        boolean canUndo = history.canUndo() || pendingText;
        boolean canRedo = history.canRedo() && !pendingText;
        Motion.enabled(undoButton, canUndo);
        Motion.enabled(redoButton, canRedo);
    }

    private void undo() {
        bodyInput.clearComposingText();
        finishHistoryGroup();
        if (history.canUndo()) {
            restoreSnapshot(history.undo());
            Motion.pulse(undoButton);
        }
    }

    private void redo() {
        bodyInput.clearComposingText();
        finishHistoryGroup();
        if (history.canRedo()) {
            restoreSnapshot(history.redo());
            Motion.pulse(redoButton);
        }
    }

    private void restoreSnapshot(EditorHistory.Snapshot snapshot) {
        loading = true;
        boolean imagesChanged = !attachments.equals(snapshot.images);
        cleanupCandidates.addAll(attachments);
        attachments.clear();
        attachments.addAll(snapshot.images);
        bodyInput.setText(snapshot.body);
        bodyInput.setSelection(snapshot.selectionStart, snapshot.selectionEnd);
        if (imagesChanged) renderImages();
        loading = false;
        lastTypingAt = 0;
        changed();
        updateHistoryButtons();
    }

    private void cleanupAfterClose() {
        try {
            store.cleanupUnreferencedImages(new ArrayList<>(cleanupCandidates));
            cleanupCandidates.clear();
        } catch (Exception error) {
            toast("笔记已保存，部分图片副本暂未清理");
        }
    }

    public boolean save() {
        handler.removeCallbacks(pendingSave);
        return persist(true);
    }

    public boolean isImporting() { return importing; }

    private void saveFromToolbar() {
        hideKeyboard();
        if (save()) {
            Motion.pulse(status);
            toast(importing ? "当前内容已保存，图片仍在导入" : "已保存");
        }
    }

    private boolean persist(boolean tellUser) {
        if (disposed) return true;
        if (!dirty) return true;
        note.title = "";
        note.body = bodyInput.getText().toString();
        note.attachments = new JSONArray(attachments).toString();
        note.checklist = "[]";
        note.type = "text";
        try {
            store.saveNote(note);
            dirty = false;
            Motion.text(status, importing ? "正在导入图片…" : "已保存");
            Motion.text(dateLabel, formatDate());
            return true;
        } catch (Exception e) {
            Motion.text(status, importing ? "保存失败 · 图片仍在导入" : "保存失败 · 点右上角重试");
            Motion.pulse(status);
            if (tellUser) showError("没有保存成功", "你的编辑内容还在，请重试。", e);
            return false;
        }
    }

    public void onBack() {
        if (importing) {
            Motion.pulse(status);
            toast("正在处理图片，请稍候");
            return;
        }
        if (newDraft && isEmpty()) {
            try {
                handler.removeCallbacks(pendingSave);
                store.deleteForever(note.id);
                cleanupAfterClose();
                hideKeyboard();
                listener.onClose();
            } catch (Exception e) {
                showError("暂时无法关闭", "空白草稿未能清理，请重试。", e);
            }
            return;
        }
        if (save()) {
            cleanupAfterClose();
            hideKeyboard();
            listener.onClose();
        }
    }

    private boolean isEmpty() {
        return bodyInput.getText().toString().trim().isEmpty() && attachments.isEmpty()
            && safe(note.sticker).isEmpty() && !note.favorite && !note.pinned;
    }

    private void showMenu(View anchor) {
        Motion.pulse(anchor);
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenu().add(0, 1, 0, note.favorite ? "取消收藏" : "收藏");
        popup.getMenu().add(0, 2, 1, note.pinned ? "取消置顶" : "置顶笔记");
        popup.getMenu().add(0, 3, 2, "移动到笔记本");
        popup.getMenu().add(0, 4, 3, "分享文字");
        popup.getMenu().add(0, 5, 4, "删除");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    toggleFavorite();
                    return true;
                case 2:
                    note.pinned = !note.pinned;
                    changed();
                    if (!save()) note.pinned = !note.pinned;
                    else {
                        Motion.pulse(status);
                        toast(note.pinned ? "已置顶笔记" : "已取消置顶");
                    }
                    return true;
                case 3: chooseFolder(); return true;
                case 4: share(); return true;
                case 5: trash(); return true;
                default: return false;
            }
        });
        popup.show();
    }

    private void toggleFavorite() {
        note.favorite = !note.favorite;
        changed();
        if (!save()) note.favorite = !note.favorite;
        else {
            Motion.pulse(status);
            toast(note.favorite ? "已收藏" : "已取消收藏");
        }
    }

    private String locationName() {
        if (note.notebookId <= 0) return "未分类";
        NoteStore.Notebook notebook = store.getNotebook(note.notebookId);
        if (notebook == null) return "未分类";
        for (NoteStore.Folder folder : store.folders())
            if (folder.id == notebook.folderId) return folder.name + " / " + notebook.name;
        return notebook.name;
    }

    private String formatDate() {
        long timestamp = note.updatedAt > 0 ? note.updatedAt : note.createdAt;
        return new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
            .format(new Date(timestamp > 0 ? timestamp : System.currentTimeMillis()));
    }

    private void chooseFolder() {
        hideKeyboard();
        try {
            List<NoteStore.Notebook> notebooks = store.notebooks(0);
            Map<Long, String> folders = new HashMap<>();
            for (NoteStore.Folder folder : store.folders()) folders.put(folder.id, folder.name);
            String[] labels = new String[notebooks.size() + 1];
            labels[0] = "未分类";
            int checked = 0;
            for (int i = 0; i < notebooks.size(); i++) {
                NoteStore.Notebook book = notebooks.get(i);
                String folder = folders.get(book.folderId);
                labels[i + 1] = (folder == null ? "" : folder + " / ") + book.name;
                if (book.id == note.notebookId) checked = i + 1;
            }
            Motion.dialog(new AlertDialog.Builder(activity).setTitle("移动到笔记本")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    long previousNotebook = note.notebookId;
                    note.notebookId = which == 0 ? 0 : notebooks.get(which - 1).id;
                    changed();
                    if (save()) {
                        Motion.text(folderLabel, labels[which]);
                        Motion.pulse((View) folderLabel.getParent());
                        ((View) folderLabel.getParent()).setContentDescription("当前位置：" + labels[which] + "，点按移动笔记");
                        dialog.dismiss();
                    } else note.notebookId = previousNotebook;
                }).setNegativeButton("取消", null).create()).show();
        } catch (Exception error) {
            showError("无法打开笔记本", "请稍后重试。", error);
        }
    }

    private void share() {
        if (!save()) return;
        String text = bodyInput.getText().toString();
        if (text.trim().isEmpty()) { toast("暂无可分享的文字"); return; }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        try { activity.startActivity(Intent.createChooser(intent, "分享笔记")); }
        catch (Exception e) { showError("暂时无法分享", "没有找到可以接收文字的应用。", e); }
    }

    private void trash() {
        if (importing) { toast("图片还在处理中，请稍后再操作"); return; }
        if (!save()) return;
        try {
            store.trashNote(note.id);
            cleanupAfterClose();
            hideKeyboard();
            toast("已移至最近删除，可恢复");
            listener.onClose();
        } catch (Exception e) { showError("删除失败", "笔记仍然保留，请重试。", e); }
    }

    private static final class AttachmentCard {
        final String path;
        final LinearLayout view;
        boolean active = true;

        AttachmentCard(String path, LinearLayout view) {
            this.path = path;
            this.view = view;
        }
    }

    private void renderImages() {
        List<AttachmentCard> remaining = new ArrayList<>(attachmentCards);
        List<AttachmentCard> updated = new ArrayList<>();
        for (String path : attachments) {
            AttachmentCard selected = null;
            for (int i = 0; i < remaining.size(); i++) {
                if (path.equals(remaining.get(i).path)) {
                    selected = remaining.remove(i);
                    break;
                }
            }
            updated.add(selected == null ? createAttachmentCard(path) : selected);
        }
        for (AttachmentCard removed : remaining) {
            removed.active = false;
            Motion.cancelTree(removed.view);
            imageList.removeView(removed.view);
        }
        attachmentCards.clear();
        attachmentCards.addAll(updated);
        for (int i = 0; i < updated.size(); i++) {
            LinearLayout card = updated.get(i).view;
            int previousIndex = imageList.indexOfChild(card);
            if (previousIndex == i) continue;
            if (previousIndex >= 0) imageList.removeView(card);
            imageList.addView(card, i, Ui.lp(-1, -2));
        }
    }

    private AttachmentCard createAttachmentCard(String path) {
        LinearLayout card = Ui.column(activity);
        Ui.pad(card, 0, 12, 0);
        ImageView image = new ImageView(activity);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMaxHeight(Ui.dp(activity, 420));
        image.setContentDescription("笔记中的图片，点按查看大图");
        Ui.ripple(image, Ui.SURFACE, 8);
        image.setClipToOutline(true);
        image.setFocusable(true);
        image.setOnClickListener(v -> previewImage(image));
        card.addView(image, Ui.lp(-1, -2));
        TextView imageStatus = Ui.text(activity, "正在加载图片…", 12, Ui.MUTED, false);
        imageStatus.setGravity(Gravity.CENTER);
        imageStatus.setMinHeight(Ui.dp(activity, 28));
        card.addView(imageStatus, Ui.lp(-1, -2));
        TextView remove = Ui.text(activity, "移除图片", 12, Ui.MUTED, false);
        remove.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        remove.setMinHeight(Ui.dp(activity, 48));
        Ui.ripple(remove, Color.TRANSPARENT, 8);
        remove.setFocusable(true);
        remove.setOnClickListener(v -> Motion.dialog(new AlertDialog.Builder(activity).setTitle("移除这张图片？")
            .setMessage("只会移除笔记里的副本，手机里的原图不会受影响。")
            .setNegativeButton("保留", null).setPositiveButton("移除", (dialog, which) -> removeImage(path)).create()).show());
        card.addView(remove, Ui.lp(-1, -2));
        AttachmentCard attachment = new AttachmentCard(path, card);
        loadImage(attachment, image, imageStatus);
        return attachment;
    }

    private void previewImage(ImageView source) {
        if (!(source.getDrawable() instanceof BitmapDrawable)) {
            toast("图片暂未加载，请稍后重试");
            Motion.pulse(source);
            return;
        }
        hideKeyboard();
        ImageView preview = new ImageView(activity);
        preview.setImageBitmap(((BitmapDrawable) source.getDrawable()).getBitmap());
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("笔记图片预览");
        Ui.pad(preview, 12, 12, 12);
        int previewHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * .65f);
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, previewHeight));
        Motion.dialog(new AlertDialog.Builder(activity).setTitle("图片预览").setView(frame)
            .setPositiveButton("关闭", null).create()).show();
    }

    private void loadImage(AttachmentCard attachment, ImageView image, TextView imageStatus) {
        image.setMinimumHeight(Ui.dp(activity, 100));
        imageExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                File file = imageFile(attachment.path);
                if (file != null && file.isFile()) bitmap = decodeSampled(file, 1200);
            } catch (Exception ignored) { /* The visible placeholder keeps the attachment recoverable. */ }
            catch (OutOfMemoryError ignored) { /* Keep the original file and allow the user to remove it. */ }
            final Bitmap result = bitmap;
            activity.runOnUiThread(() -> {
                if (disposed || !attachment.active || !(image.getParent() instanceof View)
                    || ((View) image.getParent()).getParent() != imageList) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result != null) {
                    image.setMinimumHeight(0);
                    image.setImageBitmap(result);
                    Motion.visible(imageStatus, false);
                    Motion.enter(image, 0);
                } else {
                    image.setContentDescription("图片暂时无法读取，原引用已保留");
                    Motion.text(imageStatus, "图片暂时无法读取，原文件引用已保留");
                }
            });
        });
    }

    private void removeImage(String path) {
        int index = attachments.indexOf(path);
        if (index < 0) return;
        finishHistoryGroup();
        attachments.remove(index);
        cleanupCandidates.add(path);
        history.record(snapshot(), false);
        changed();
        renderImages();
        updateHistoryButtons();
        save();
    }

    private void pickImage(View view) {
        if (importing) { toast("上一张图片还在处理中"); return; }
        hideKeyboard();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivityForResult(intent, REQUEST_IMAGE); }
        catch (Exception e) { showError("无法打开图片选择器", "请检查手机是否有可用的文件应用。", e); }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMAGE || resultCode != Activity.RESULT_OK || data == null || disposed) return;
        Uri uri = data.getData();
        if (uri == null) return;
        importing = true;
        Motion.enabled(imageButton, false);
        boolean animateProgress = Motion.allowed(activity);
        importSpinner.setVisibility(animateProgress ? VISIBLE : GONE);
        importStaticIndicator.setVisibility(animateProgress ? GONE : VISIBLE);
        Motion.visible(importProgress, true);
        Motion.text(status, "正在导入图片…");
        imageExecutor.execute(() -> {
            File image = null;
            Exception failure = null;
            try { image = importImage(uri); }
            catch (Exception e) { failure = e; }
            catch (OutOfMemoryError e) { failure = new IOException("图片尺寸太大，请换一张较小的图片", e); }
            final File result = image;
            final Exception error = failure;
            activity.runOnUiThread(() -> {
                importing = false;
                if (disposed) {
                    if (result != null) result.delete();
                    return;
                }
                Motion.visible(importProgress, false);
                Motion.enabled(imageButton, true);
                if (error != null || result == null) {
                    Motion.text(status, dirty ? "有内容尚未保存" : "已保存");
                    showError("图片没有添加成功", "请重新选择图片。支持不超过 30 MB 的常见图片。", error);
                    return;
                }
                finishHistoryGroup();
                attachments.add(result.getAbsolutePath());
                cleanupCandidates.add(result.getAbsolutePath());
                history.record(snapshot(), false);
                changed();
                renderImages();
                updateHistoryButtons();
                if (save()) toast("已插入适合阅读的图片副本");
            });
        });
    }

    private File importImage(Uri uri) throws IOException {
        File directory = new File(activity.getFilesDir(), "images");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建图片存储目录");
        File temporary = new File(directory, ".incoming-" + UUID.randomUUID());
        File output = new File(directory, UUID.randomUUID() + ".jpg");
        Bitmap bitmap = null;
        Bitmap corrected = null;
        Bitmap flattened = null;
        boolean complete = false;
        try {
            try (InputStream input = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream target = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("无法读取这张图片");
                byte[] buffer = new byte[16384];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("图片导入已取消");
                    total += count;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("图片超过 30 MB，请选择较小的图片");
                    target.write(buffer, 0, count);
                }
                if (total == 0) throw new IOException("图片内容为空");
            }
            bitmap = decodeSampled(temporary, 2048);
            if (bitmap == null) throw new IOException("无法解码图片，请使用 JPEG、PNG 或 WebP 图片");
            Matrix transform = new Matrix();
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                orientation = new ExifInterface(temporary.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException ignored) { /* Images without readable EXIF retain their decoded orientation. */ }
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(-90); transform.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(-90); break;
                default: break;
            }
            corrected = transform.isIdentity() ? bitmap : Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), transform, true);
            flattened = Bitmap.createBitmap(corrected.getWidth(), corrected.getHeight(), Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(flattened);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(corrected, 0, 0, null);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!flattened.compress(Bitmap.CompressFormat.JPEG, 88, stream)) throw new IOException("图片保存失败");
            }
            complete = true;
            return output;
        } finally {
            temporary.delete();
            if (!complete) output.delete();
            if (flattened != null) flattened.recycle();
            if (corrected != null && corrected != bitmap) corrected.recycle();
            if (bitmap != null) bitmap.recycle();
        }
    }

    private Bitmap decodeSampled(File file, int maxSide) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("图片格式不支持");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private File imageFile(String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        File images = new File(activity.getFilesDir(), "images").getCanonicalFile();
        return file.getPath().startsWith(images.getPath() + File.separator) ? file : null;
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        clearFocus();
    }

    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show(); }

    private void showError(String title, String message, Exception error) {
        String detail = error == null ? "" : error.getMessage();
        Motion.dialog(new AlertDialog.Builder(activity).setTitle(title)
            .setMessage(message + (detail == null || detail.trim().isEmpty() ? "" : "\n\n" + detail))
            .setPositiveButton("确定", null).create()).show();
    }

    private static String safe(String text) { return text == null ? "" : text; }
    private static String emptyJson(String text) { return text == null || text.trim().isEmpty() ? "[]" : text; }

    public void captureState(Bundle state) {
        if (disposed) return;
        if (importing) state.putLong("xiaotu.editor.importingNote", note.id);
        else state.remove("xiaotu.editor.importingNote");
        finishHistoryGroup();
        state.putLong("xiaotu.editor.historyNote", note.id);
        state.putBundle("xiaotu.editor.history", history.capture());
        state.putStringArrayList("xiaotu.editor.cleanup", new ArrayList<>(cleanupCandidates));
        if (!dirty) return;
        Bundle draft = new Bundle();
        draft.putInt("format", 2);
        draft.putLong("id", note.id);
        draft.putString("body", bodyInput.getText().toString());
        draft.putInt("start", bodyInput.getSelectionStart());
        draft.putInt("end", bodyInput.getSelectionEnd());
        draft.putLong("notebookId", note.notebookId);
        draft.putString("color", note.color);
        draft.putString("sticker", note.sticker);
        draft.putBoolean("favorite", note.favorite);
        draft.putBoolean("pinned", note.pinned);
        draft.putStringArrayList("attachments", new ArrayList<>(attachments));
        state.putBundle("xiaotu.editor.draft", draft);
    }

    public void restoreState(Bundle state) {
        if (state == null || disposed) return;
        boolean importInterrupted = state.getLong("xiaotu.editor.importingNote", -1) == note.id;
        Bundle draft = state.getBundle("xiaotu.editor.draft");
        boolean restoredDraft = draft != null && draft.getLong("id", -1) == note.id;
        if (restoredDraft) {
            loading = true;
            note.notebookId = draft.getLong("notebookId", note.notebookId);
            note.color = draft.getString("color", note.color);
            note.sticker = draft.getString("sticker", note.sticker);
            note.favorite = draft.getBoolean("favorite");
            note.pinned = draft.getBoolean("pinned");
            String text = draft.getInt("format", 1) >= 2 ? draft.getString("body", "") : legacyDraftBody(draft);
            bodyInput.setText(text);
            int start = Math.max(0, Math.min(text.length(), draft.getInt("start", text.length())));
            int end = Math.max(start, Math.min(text.length(), draft.getInt("end", start)));
            bodyInput.setSelection(start, end);
            attachments.clear();
            ArrayList<String> images = draft.getStringArrayList("attachments");
            if (images != null) attachments.addAll(images);
            Motion.text(folderLabel, locationName());
            ((View) folderLabel.getParent()).setContentDescription("当前位置：" + locationName() + "，点按移动笔记");
            renderImages();
            loading = false;
        }
        if (state.getLong("xiaotu.editor.historyNote", -1) == note.id) {
            history = EditorHistory.restore(state.getBundle("xiaotu.editor.history"), snapshot());
            ArrayList<String> candidates = state.getStringArrayList("xiaotu.editor.cleanup");
            if (candidates != null) cleanupCandidates.addAll(candidates);
            EditorHistory.Snapshot selected = history.current();
            bodyInput.setSelection(selected.selectionStart, selected.selectionEnd);
        } else history = new EditorHistory(snapshot());
        if (restoredDraft) {
            changed();
            Motion.text(status, "已恢复未保存内容");
        }
        if (importInterrupted) {
            Motion.text(status, "图片导入已中断");
            toast("图片导入已中断，请重新选择");
        }
        updateHistoryButtons();
    }

    private String legacyDraftBody(Bundle draft) {
        NoteStore.Note legacy = new NoteStore.Note();
        legacy.title = draft.getString("title", "");
        legacy.body = draft.getString("body", "");
        ArrayList<String> text = draft.getStringArrayList("todoText");
        boolean[] done = draft.getBooleanArray("todoDone");
        JSONArray items = new JSONArray();
        try {
            if (text != null) for (int i = 0; i < text.size(); i++) {
                JSONObject item = new JSONObject();
                item.put("text", safe(text.get(i)));
                item.put("done", done != null && i < done.length && done[i]);
                items.put(item);
            }
        } catch (JSONException error) {
            throw new IllegalStateException("无法恢复旧版草稿", error);
        }
        legacy.checklist = items.toString();
        return NoteStore.plainText(legacy);
    }

    public void dispose() {
        disposed = true;
        Motion.cancelTree(this);
        importProgress.setVisibility(GONE);
        importSpinner.setVisibility(GONE);
        handler.removeCallbacksAndMessages(null);
        imageExecutor.shutdownNow();
    }
}
