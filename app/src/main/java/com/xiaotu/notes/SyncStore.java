package com.xiaotu.notes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Local, transactional projection of the LAN sync protocol. No network or UI code. */
public final class SyncStore {
    private static final long MAX_IMAGE_BYTES = 30L * 1024L * 1024L;
    private final NoteStore notes;
    private final File blobsDirectory;
    private final File backupsDirectory;
    private final File imagesDirectory;

    public static final class Snapshot {
        public final JSONObject manifest;
        public final String signature;
        public final Map<String, File> blobs;

        Snapshot(JSONObject manifest, String signature, Map<String, File> blobs) {
            this.manifest = manifest;
            this.signature = signature;
            this.blobs = Collections.unmodifiableMap(new LinkedHashMap<>(blobs));
        }
    }

    private static final class Entry {
        final String id;
        final String kind;
        Long localId;
        JSONObject wire;
        JSONObject observed;

        Entry(String id, String kind, Long localId, JSONObject wire, JSONObject observed) {
            this.id = id; this.kind = kind; this.localId = localId; this.wire = wire; this.observed = observed;
        }
    }

    private static final class Captured {
        final Map<String, Entry> entries;
        final Snapshot snapshot;
        Captured(Map<String, Entry> entries, Snapshot snapshot) { this.entries = entries; this.snapshot = snapshot; }
    }

    public SyncStore(Context context, NoteStore notes) {
        if (context == null || notes == null) throw new IllegalArgumentException("同步存储参数不能为空");
        this.notes = notes;
        File files = context.getApplicationContext().getFilesDir();
        File sync = new File(files, "sync");
        blobsDirectory = new File(sync, "blobs");
        backupsDirectory = new File(sync, "backups");
        imagesDirectory = new File(files, "images");
        ensureDirectory(blobsDirectory);
        ensureDirectory(backupsDirectory);
        ensureDirectory(imagesDirectory);
    }

    public String deviceId() {
        synchronized (notes) {
            SQLiteDatabase db = notes.getWritableDatabase();
            db.beginTransaction();
            try {
                String id = readDeviceId(db);
                db.setTransactionSuccessful();
                return id;
            } finally { db.endTransaction(); }
        }
    }

    private String readDeviceId(SQLiteDatabase db) {
        try (Cursor cursor = db.query("sync_settings", new String[]{"value"}, "key = ?",
                new String[]{"device_id"}, null, null, null)) {
            if (cursor.moveToFirst()) {
                String id = cursor.getString(0);
                SyncRecords.require(SyncRecords.validDevice(id), "本机同步设备编号损坏，请保留数据并联系开发者");
                return id;
            }
        }
        String id = UUID.randomUUID().toString();
        ContentValues values = new ContentValues(); values.put("key", "device_id"); values.put("value", id);
        db.insertOrThrow("sync_settings", null, values);
        return id;
    }

    public Snapshot capture() {
        synchronized (notes) {
            SQLiteDatabase db = notes.getWritableDatabase();
            db.beginTransaction();
            try {
                Snapshot snapshot = captureInside(db).snapshot;
                checkCancelled();
                db.setTransactionSuccessful();
                return snapshot;
            } catch (IOException | JSONException error) {
                throw failure("无法准备同步内容", error);
            } finally { db.endTransaction(); }
        }
    }

    public File blobFile(String hash) {
        checkHash(hash);
        File file = new File(blobsDirectory, hash);
        if (!file.isFile()) return null;
        try { return hash.equals(hashFile(file)) ? file : null; }
        catch (IOException error) { return null; }
    }

    public boolean hasBlob(String hash) { return blobFile(hash) != null; }

    /** The caller owns stream; this method only closes its own staging file. */
    public void receiveBlob(String hash, InputStream stream) {
        checkHash(hash);
        if (stream == null) throw new IllegalArgumentException("没有读取到同步图片");
        File temporary = new File(blobsDirectory, ".incoming-" + UUID.randomUUID());
        try {
            MessageDigest digest = SyncRecords.digest();
            long total = 0;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("同步已取消");
                    if (read == 0) continue;
                    total += read;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("同步图片超过 30 MB");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                if (total == 0) throw new IOException("同步图片内容为空");
                if (!hash.equals(SyncRecords.hex(digest.digest()))) throw new IOException("同步图片校验失败，请重试");
                output.getFD().sync();
            }
            File target = new File(blobsDirectory, hash);
            if (blobFile(hash) == null) atomicMove(temporary, target);
        } catch (IOException error) { throw failure("图片没有接收成功", error); }
        finally { if (temporary.exists()) temporary.delete(); }
    }

    public JSONObject apply(JSONArray records, String expectedSignature) {
        final JSONArray incoming;
        try { incoming = SyncRecords.validate(records); }
        catch (JSONException error) { throw failure("收到的同步内容格式无效", error); }
        SyncRecords.require(SyncRecords.validHash(expectedSignature), "同步快照编号无效，请重新同步");
        // Validate every received image before touching local rows. Immutable staging
        // survives cancellation, but the existing manuscripts remain unchanged.
        requireBlobs(incoming);
        synchronized (notes) {
            SQLiteDatabase db = notes.getWritableDatabase();
            db.beginTransaction();
            try {
                Captured before = captureInside(db);
                if (!expectedSignature.equals(before.snapshot.signature)) {
                    throw new IllegalStateException("同步期间本机笔记发生了变化，已保留本机内容，请重新同步");
                }
                SyncRecords.Merged merged = SyncRecords.merge(before.snapshot.manifest.getJSONArray("records"), incoming);
                requireBlobs(merged.records);
                saveBackup("before", before.snapshot.manifest);
                saveBackup("received", incoming);
                materialize(db, before.entries, merged.records);
                JSONObject summary = new JSONObject().put("received", incoming.length()).put("conflicts", merged.conflicts);
                checkCancelled();
                db.setTransactionSuccessful();
                return summary;
            } catch (IOException | JSONException error) {
                throw failure("同步没有导入成功，原笔记已保留", error);
            } finally { db.endTransaction(); }
        }
    }

    private Captured captureInside(SQLiteDatabase db) throws JSONException, IOException {
        String device = readDeviceId(db);
        Map<String, Entry> entries = readEntries(db);
        Map<String, Entry> local = new HashMap<>();
        for (Entry entry : entries.values()) if (entry.localId != null) local.put(localKey(entry.kind, entry.localId), entry);
        Map<Long, String> folderIds = new HashMap<>();
        Map<Long, String> bookIds = new HashMap<>();
        List<Long> noteIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Cursor cursor = db.query("folders", new String[]{"id"}, null, null, null, null, "id")) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                Entry entry = bind(entries, local, "folder", id);
                folderIds.put(id, entry.id); seen.add(entry.id);
            }
        }
        try (Cursor cursor = db.query("notebooks", new String[]{"id"}, null, null, null, null, "id")) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                Entry entry = bind(entries, local, "book", id);
                bookIds.put(id, entry.id); seen.add(entry.id);
            }
        }
        try (Cursor cursor = db.query("notes", new String[]{"id"}, null, null, null, null, "id")) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                Entry entry = bind(entries, local, "note", id);
                noteIds.add(id); seen.add(entry.id);
            }
        }
        SyncRecords.require(entries.size() <= SyncRecords.MAX_RECORDS, "笔记和分类总数超过同步限制");
        try (Cursor cursor = db.query("folders", new String[]{"id", "name", "color"}, null, null, null, null, "id")) {
            while (cursor.moveToNext()) {
                JSONObject projection = new JSONObject().put("name", cursor.getString(1)).put("color", cursor.getString(2));
                observe(local.get(localKey("folder", cursor.getLong(0))), projection, device);
            }
        }
        try (Cursor cursor = db.query("notebooks", new String[]{"id", "folder_id", "name"}, null, null, null, null, "id")) {
            while (cursor.moveToNext()) {
                String parent = folderIds.get(cursor.getLong(1));
                SyncRecords.require(parent != null, "本机笔记本所属文件夹缺失，已停止同步以保护内容");
                JSONObject projection = new JSONObject().put("name", cursor.getString(2)).put("folderId", parent);
                observe(local.get(localKey("book", cursor.getLong(0))), projection, device);
            }
        }
        for (Long id : noteIds) {
            checkCancelled();
            NoteStore.Note note = notes.getNote(id);
            if (note == null) throw new IllegalStateException("准备同步时笔记发生变化，请重试");
            JSONArray images = new JSONArray();
            JSONArray paths = new JSONArray(note.attachments);
            for (int i = 0; i < paths.length(); i++) images.put(cacheLocalImage(paths.getString(i)));
            String parent = bookIds.get(note.notebookId);
            JSONObject projection = new JSONObject()
                    .put("body", NoteStore.plainText(note)).put("notebookId", parent == null ? JSONObject.NULL : parent)
                    .put("createdAt", note.createdAt).put("updatedAt", note.updatedAt).put("trashedAt", note.deletedAt)
                    .put("favorite", note.favorite).put("pinned", note.pinned).put("images", images)
                    .put("color", note.color).put("sticker", note.sticker).put("conflictOf", note.conflictOf == null ? "" : note.conflictOf);
            observe(local.get(localKey("note", id)), projection, device);
        }
        for (Entry entry : entries.values()) {
            if (entry.localId != null && !seen.contains(entry.id)) {
                entry.wire = SyncRecords.copy(entry.wire);
                entry.wire.put("deleted", true);
                increment(entry.wire, device);
                entry.localId = null;
                entry.observed = null;
            }
        }
        JSONArray records = new JSONArray();
        for (Entry entry : entries.values()) records.put(entry.wire);
        records = SyncRecords.validate(records);
        JSONObject manifest = new JSONObject().put("protocol", 1).put("deviceId", device).put("records", records);
        String serialized = SyncRecords.canonical(manifest);
        SyncRecords.require(serialized.getBytes(StandardCharsets.UTF_8).length <= SyncRecords.MAX_MANIFEST_BYTES, "同步内容超过 32 MB");
        Map<String, File> files = referencedBlobs(records);
        for (Entry entry : entries.values()) saveEntry(db, entry);
        return new Captured(entries, new Snapshot(manifest, SyncRecords.sha256(serialized), files));
    }

    private Map<String, Entry> readEntries(SQLiteDatabase db) throws JSONException {
        Map<String, Entry> result = new TreeMap<>();
        JSONArray existing = new JSONArray();
        try (Cursor cursor = db.query("sync_records", new String[]{"id", "kind", "local_id", "wire", "observed"}, null, null, null, null, "id")) {
            while (cursor.moveToNext()) {
                JSONObject wire = new JSONObject(cursor.getString(3));
                String id = cursor.getString(0), kind = cursor.getString(1);
                SyncRecords.require(id.equals(wire.getString("id")) && kind.equals(wire.getString("kind")), "本机同步索引损坏，已停止同步");
                Entry entry = new Entry(id, kind, cursor.isNull(2) ? null : cursor.getLong(2), wire,
                        cursor.isNull(4) ? null : new JSONObject(cursor.getString(4)));
                result.put(id, entry); existing.put(wire);
            }
        }
        SyncRecords.validate(existing);
        return result;
    }

    private Entry bind(Map<String, Entry> entries, Map<String, Entry> local, String kind, long id) {
        String key = localKey(kind, id);
        Entry found = local.get(key);
        if (found != null) return found;
        String global;
        do { global = UUID.randomUUID().toString(); } while (entries.containsKey(global));
        Entry entry = new Entry(global, kind, id, null, null);
        entries.put(global, entry); local.put(key, entry);
        return entry;
    }

    private void observe(Entry entry, JSONObject projection, String device) throws JSONException {
        boolean changed = entry.observed == null || !SyncRecords.canonical(entry.observed).equals(SyncRecords.canonical(projection));
        if (changed) {
            JSONObject data;
            if (entry.wire == null) data = SyncRecords.copy(projection);
            else {
                data = SyncRecords.copy(entry.wire.getJSONObject("data"));
                for (String key : SyncRecords.keys(projection)) {
                    if (entry.observed == null || !entry.observed.has(key) ||
                            !SyncRecords.canonical(projection.get(key)).equals(SyncRecords.canonical(entry.observed.get(key)))) {
                        data.put(key, projection.get(key));
                    }
                }
            }
            JSONObject clock = entry.wire == null ? new JSONObject() : SyncRecords.copy(entry.wire.getJSONObject("clock"));
            entry.wire = new JSONObject().put("kind", entry.kind).put("id", entry.id).put("clock", clock)
                    .put("deleted", false).put("data", data);
            increment(entry.wire, device);
        }
        entry.observed = SyncRecords.copy(projection);
    }

    private static void increment(JSONObject record, String device) throws JSONException {
        JSONObject clock = record.getJSONObject("clock");
        long current = clock.optLong(device, 0);
        SyncRecords.require(current < SyncRecords.MAX_INTEGER, "同步版本已超过上限，无法继续同步");
        clock.put(device, current + 1);
    }

    private void materialize(SQLiteDatabase db, Map<String, Entry> entries, JSONArray records) throws JSONException, IOException {
        Set<Long> oldFolders = localIds(entries, "folder"), oldBooks = localIds(entries, "book"), oldNotes = localIds(entries, "note");
        for (int i = 0; i < records.length(); i++) {
            JSONObject wire = records.getJSONObject(i);
            String id = wire.getString("id");
            Entry entry = entries.get(id);
            if (entry == null) { entry = new Entry(id, wire.getString("kind"), null, wire, null); entries.put(id, entry); }
            else entry.wire = wire;
        }
        // Move existing names out of the way before a deterministic re-projection;
        // a simultaneous name swap must not hit the local UNIQUE constraints.
        for (Long id : oldFolders) temporaryName(db, "folders", id);
        for (Long id : oldBooks) temporaryName(db, "notebooks", id);
        Map<String, Long> folders = new HashMap<>(), books = new HashMap<>();
        Map<String, Long> bookFolders = new HashMap<>();
        Set<String> usedFolders = new HashSet<>();
        Map<String, Set<String>> usedBooks = new HashMap<>();
        Set<Long> wantedFolders = new HashSet<>(), wantedBooks = new HashSet<>(), wantedNotes = new HashSet<>();

        for (Entry entry : entries.values()) {
            if (!entry.kind.equals("folder") || entry.wire.getBoolean("deleted")) continue;
            checkCancelled();
            JSONObject data = entry.wire.getJSONObject("data");
            String name = projectedName(data.getString("name"), entry.id, usedFolders);
            ContentValues values = new ContentValues(); values.put("name", name); values.put("color", data.getString("color"));
            entry.localId = upsert(db, "folders", entry.localId, values);
            entry.observed = new JSONObject().put("name", name).put("color", data.getString("color"));
            folders.put(entry.id, entry.localId); wantedFolders.add(entry.localId);
        }
        for (Entry entry : entries.values()) {
            if (!entry.kind.equals("book") || entry.wire.getBoolean("deleted")) continue;
            checkCancelled();
            JSONObject data = entry.wire.getJSONObject("data");
            String parent = data.getString("folderId");
            Long folder = folders.get(parent);
            if (folder == null) { entry.localId = null; entry.observed = null; continue; }
            Set<String> used = usedBooks.get(parent);
            if (used == null) { used = new HashSet<>(); usedBooks.put(parent, used); }
            String name = projectedName(data.getString("name"), entry.id, used);
            ContentValues values = new ContentValues(); values.put("name", name); values.put("folder_id", folder);
            entry.localId = upsert(db, "notebooks", entry.localId, values);
            entry.observed = new JSONObject().put("name", name).put("folderId", parent);
            books.put(entry.id, entry.localId); bookFolders.put(entry.id, folder); wantedBooks.add(entry.localId);
        }
        for (Entry entry : entries.values()) {
            if (!entry.kind.equals("note") || entry.wire.getBoolean("deleted")) continue;
            checkCancelled();
            JSONObject data = entry.wire.getJSONObject("data");
            String parent = data.isNull("notebookId") ? null : data.getString("notebookId");
            Long book = books.get(parent), folder = bookFolders.get(parent);
            JSONArray images = data.getJSONArray("images"), paths = new JSONArray();
            for (int i = 0; i < images.length(); i++) paths.put(localImage(images.getString(i)).getAbsolutePath());
            ContentValues values = new ContentValues();
            values.put("folder_id", folder == null ? 0 : folder); values.put("notebook_id", book == null ? 0 : book);
            values.put("created_at", data.getLong("createdAt")); values.put("updated_at", data.getLong("updatedAt")); values.put("deleted_at", data.getLong("trashedAt"));
            values.put("title", ""); values.put("body", data.getString("body")); values.put("type", "text");
            values.put("color", data.getString("color")); values.put("sticker", data.getString("sticker"));
            values.put("attachments", paths.toString()); values.put("checklist", "[]"); values.put("checklist_text", "");
            values.put("favorite", data.getBoolean("favorite") ? 1 : 0); values.put("pinned", data.getBoolean("pinned") ? 1 : 0);
            values.put("conflict_of", data.getString("conflictOf"));
            entry.localId = upsert(db, "notes", entry.localId, values);
            entry.observed = SyncRecords.copy(data);
            entry.observed.put("notebookId", book == null ? JSONObject.NULL : parent);
            wantedNotes.add(entry.localId);
        }
        removeUnwanted(db, "notes", oldNotes, wantedNotes);
        removeUnwanted(db, "notebooks", oldBooks, wantedBooks);
        removeUnwanted(db, "folders", oldFolders, wantedFolders);
        for (Entry entry : entries.values()) {
            if (entry.wire.getBoolean("deleted")) { entry.localId = null; entry.observed = null; }
            saveEntry(db, entry);
        }
    }

    private static String projectedName(String original, String id, Set<String> used) {
        String name = original;
        String fingerprint = SyncRecords.sha256(id);
        int length = 8;
        while (!used.add(sqliteFold(name))) {
            name = original + " (" + fingerprint.substring(0, Math.min(length, 64)) + (length > 64 ? "-" + length : "") + ")";
            length += 4;
        }
        return name;
    }

    private static String sqliteFold(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            folded.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return folded.toString();
    }

    private static Set<Long> localIds(Map<String, Entry> entries, String kind) {
        Set<Long> result = new HashSet<>();
        for (Entry entry : entries.values()) if (entry.kind.equals(kind) && entry.localId != null) result.add(entry.localId);
        return result;
    }

    private static void temporaryName(SQLiteDatabase db, String table, long id) {
        ContentValues value = new ContentValues(); value.put("name", "\u0001sync-" + UUID.randomUUID());
        if (db.update(table, value, "id = ?", new String[]{Long.toString(id)}) != 1) throw new IllegalStateException("同步分类发生变化，请重试");
    }

    private static long upsert(SQLiteDatabase db, String table, Long id, ContentValues values) {
        if (id == null) return db.insertOrThrow(table, null, values);
        if (db.update(table, values, "id = ?", new String[]{Long.toString(id)}) != 1) throw new IllegalStateException("同步记录发生变化，请重试");
        return id;
    }

    private static void removeUnwanted(SQLiteDatabase db, String table, Set<Long> old, Set<Long> wanted) {
        for (Long id : old) if (!wanted.contains(id)) db.delete(table, "id = ?", new String[]{Long.toString(id)});
    }

    private static void saveEntry(SQLiteDatabase db, Entry entry) throws JSONException {
        ContentValues values = new ContentValues();
        values.put("id", entry.id); values.put("kind", entry.kind);
        if (entry.localId == null) values.putNull("local_id"); else values.put("local_id", entry.localId);
        values.put("wire", SyncRecords.canonical(entry.wire));
        if (entry.observed == null) values.putNull("observed"); else values.put("observed", SyncRecords.canonical(entry.observed));
        if (db.insertWithOnConflict("sync_records", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) throw new IllegalStateException("同步索引没有保存成功");
    }

    private String cacheLocalImage(String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        String root = imagesDirectory.getCanonicalPath() + File.separator;
        if (!file.getPath().startsWith(root)) throw new IOException("笔记包含不属于应用的图片路径，已停止同步");
        String hash = hashFile(file);
        if (!hasBlob(hash)) {
            try (InputStream input = new FileInputStream(file)) { receiveBlob(hash, input); }
        }
        return hash;
    }

    private File localImage(String hash) throws IOException {
        File blob = blobFile(hash);
        if (blob == null) throw new IOException("同步图片尚未接收完整，请重试");
        File target = new File(imagesDirectory, "sync-" + hash + ".jpg");
        if (target.isFile()) {
            try { if (hash.equals(hashFile(target))) return target; }
            catch (IOException ignored) { /* Repair an incomplete UI copy from the immutable cache. */ }
        }
        File temporary = new File(imagesDirectory, ".sync-" + UUID.randomUUID());
        try {
            try (InputStream input = new FileInputStream(blob); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            atomicMove(temporary, target);
            return target;
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private Map<String, File> referencedBlobs(JSONArray records) throws JSONException {
        Map<String, File> result = new TreeMap<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (!record.getString("kind").equals("note") || record.getBoolean("deleted")) continue;
            JSONArray images = record.getJSONObject("data").getJSONArray("images");
            for (int j = 0; j < images.length(); j++) {
                String hash = images.getString(j);
                if (result.containsKey(hash)) continue;
                File file = blobFile(hash);
                if (file == null) throw new IllegalStateException("同步图片尚未接收完整或已损坏，请重新同步");
                result.put(hash, file);
            }
        }
        return result;
    }

    private void requireBlobs(JSONArray records) {
        try { referencedBlobs(records); }
        catch (JSONException error) { throw failure("同步图片列表无效", error); }
    }

    private void saveBackup(String label, Object manifest) throws JSONException, IOException {
        File temporary = new File(backupsDirectory, ".incoming-" + UUID.randomUUID());
        File target = new File(backupsDirectory, label + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(SyncRecords.canonical(manifest).getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            atomicMove(temporary, target);
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private static String hashFile(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0) throw new IOException("笔记图片不存在或为空，请先恢复图片");
        if (file.length() > MAX_IMAGE_BYTES) throw new IOException("笔记图片超过 30 MB，无法同步");
        MessageDigest digest = SyncRecords.digest();
        long total = 0;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("同步已取消");
                total += count;
                if (total > MAX_IMAGE_BYTES) throw new IOException("笔记图片超过 30 MB");
                digest.update(buffer, 0, count);
            }
        }
        if (total == 0) throw new IOException("笔记图片为空");
        return SyncRecords.hex(digest.digest());
    }

    private static void atomicMove(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建本机同步存储目录");
    }
    private static void checkHash(String hash) { SyncRecords.require(SyncRecords.validHash(hash), "同步图片编号无效"); }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已取消，原笔记已保留");
    }
    private static String localKey(String kind, long id) { return kind + ":" + id; }
    private static IllegalStateException failure(String prefix, Exception error) {
        String detail = error.getMessage();
        return new IllegalStateException(prefix + (detail == null || detail.isEmpty() ? "" : "：" + detail), error);
    }
}
