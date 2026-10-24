package com.xiaotu.notes;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Editor-only content history; file deletion is deferred until the editor closes. */
final class EditorHistory {
    static final class Snapshot {
        final String body;
        final List<String> images;
        final int selectionStart;
        final int selectionEnd;

        Snapshot(String body, List<String> images, int selectionStart, int selectionEnd) {
            this.body = body;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.selectionStart = Math.max(0, Math.min(body.length(), selectionStart));
            this.selectionEnd = Math.max(this.selectionStart, Math.min(body.length(), selectionEnd));
        }

        boolean sameContent(Snapshot other) {
            return body.equals(other.body) && images.equals(other.images);
        }

        Bundle bundle() {
            Bundle state = new Bundle();
            state.putString("body", body);
            state.putStringArrayList("images", new ArrayList<>(images));
            state.putInt("start", selectionStart);
            state.putInt("end", selectionEnd);
            return state;
        }
    }

    private final ArrayList<Snapshot> entries = new ArrayList<>();
    private int position;

    EditorHistory(Snapshot initial) { entries.add(initial); }
    Snapshot current() { return entries.get(position); }
    boolean canUndo() { return position > 0; }
    boolean canRedo() { return position + 1 < entries.size(); }

    void rememberSelection(int start, int end) {
        Snapshot current = current();
        entries.set(position, new Snapshot(current.body, current.images, start, end));
    }

    void record(Snapshot next, boolean mergeTyping) {
        if (current().sameContent(next)) {
            entries.set(position, next);
            return;
        }
        boolean hadRedo = canRedo();
        while (entries.size() > position + 1) entries.remove(entries.size() - 1);
        if (mergeTyping && position > 0 && !hadRedo) entries.set(position, next);
        else { entries.add(next); position++; }
        int characters = 0;
        for (Snapshot entry : entries) characters += entry.body.length();
        while (entries.size() > 2 && (entries.size() > 40 || characters > 500000)) {
            characters -= entries.remove(0).body.length();
            position--;
        }
    }

    Snapshot undo() { return canUndo() ? entries.get(--position) : current(); }
    Snapshot redo() { return canRedo() ? entries.get(++position) : current(); }

    Bundle capture() {
        int start = Math.max(0, position - 4);
        int end = Math.min(entries.size(), start + 8);
        int characters = 0;
        for (int i = start; i < end; i++) characters += entries.get(i).body.length();
        while (characters > 64000 && end - start > 1) {
            if (position - start >= end - position - 1 && start < position)
                characters -= entries.get(start++).body.length();
            else characters -= entries.get(--end).body.length();
        }
        if (characters > 64000) return null;
        Bundle state = new Bundle();
        state.putInt("position", position - start);
        state.putInt("count", end - start);
        for (int i = start; i < end; i++) state.putBundle("entry" + (i - start), entries.get(i).bundle());
        return state;
    }

    static EditorHistory restore(Bundle state, Snapshot fallback) {
        if (state == null) return new EditorHistory(fallback);
        int count = state.getInt("count", 0);
        int selected = state.getInt("position", -1);
        if (count < 1 || count > 8 || selected < 0 || selected >= count) return new EditorHistory(fallback);
        ArrayList<Snapshot> restored = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Bundle entry = state.getBundle("entry" + i);
            if (entry == null) return new EditorHistory(fallback);
            String body = entry.getString("body");
            ArrayList<String> images = entry.getStringArrayList("images");
            if (body == null || images == null) return new EditorHistory(fallback);
            restored.add(new Snapshot(body, images, entry.getInt("start"), entry.getInt("end")));
        }
        if (!restored.get(selected).sameContent(fallback)) return new EditorHistory(fallback);
        EditorHistory history = new EditorHistory(fallback);
        history.entries.clear();
        history.entries.addAll(restored);
        history.position = selected;
        return history;
    }
}
