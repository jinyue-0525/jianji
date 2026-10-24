package com.xiaotu.notes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local note storage. A stored folder ID of zero means the note is unfiled. */
public final class NoteStore extends SQLiteOpenHelper {
    private static final String[] NOTE_COLUMNS = {
            "id", "folder_id", "created_at", "updated_at", "deleted_at",
            "title", "body", "color", "sticker", "type", "attachments", "checklist",
            "favorite", "pinned", "notebook_id", "conflict_of"
    };
    private final File imageDirectory;

    public static final class Note {
        public long id;
        public long folderId;
        public long notebookId;
        public long createdAt;
        public long updatedAt;
        public long deletedAt;
        public String title = "";
        public String body = "";
        public String color = "cream";
        public String sticker = "";
        public String type = "text";
        public String attachments = "[]";
        public String checklist = "[]";
        public String conflictOf = "";
        public boolean favorite;
        public boolean pinned;
    }

    public static final class Folder {
        public long id;
        public String name;
        public String color;
        public int count;
    }

    public static final class Notebook {
        public long id;
        public long folderId;
        public String name;
        public int count;
    }

    public NoteStore(Context context) {
        super(context.getApplicationContext(), "xiaotu-notes.db", null, 3);
        imageDirectory = new File(context.getApplicationContext().getFilesDir(), "images");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE folders ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL COLLATE NOCASE UNIQUE,"
                + "color TEXT NOT NULL DEFAULT 'cream')");
        createNotebookTable(db);
        db.execSQL("CREATE TABLE notes ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "folder_id INTEGER NOT NULL DEFAULT 0,"
                + "notebook_id INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,"
                + "deleted_at INTEGER NOT NULL DEFAULT 0,"
                + "title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',"
                + "color TEXT NOT NULL DEFAULT 'cream', sticker TEXT NOT NULL DEFAULT '',"
                + "type TEXT NOT NULL DEFAULT 'text',"
                + "attachments TEXT NOT NULL DEFAULT '[]', checklist TEXT NOT NULL DEFAULT '[]',"
                + "checklist_text TEXT NOT NULL DEFAULT '',"
                + "favorite INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0,"
                + "conflict_of TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX notes_order ON notes(deleted_at, pinned DESC, updated_at DESC)");
        db.execSQL("CREATE INDEX notes_folder ON notes(folder_id, deleted_at)");
        createNotebookIndex(db);
        createSyncTables(db);
        insertNotebook(db, insertFolder(db, "生活", "pink"), "默认笔记本");
        insertNotebook(db, insertFolder(db, "学习", "mint"), "默认笔记本");
        insertNotebook(db, insertFolder(db, "工作", "sky"), "默认笔记本");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion > 2 || newVersion != 3) {
            throw new IllegalStateException("不支持的数据库版本升级：" + oldVersion + " → " + newVersion);
        }
        // SQLiteOpenHelper wraps this callback and the version change in one transaction.
        if (oldVersion == 1) {
            createNotebookTable(db);
            db.execSQL("ALTER TABLE notes ADD COLUMN notebook_id INTEGER NOT NULL DEFAULT 0");
            createNotebookIndex(db);
            try (Cursor cursor = db.query("folders", new String[]{"id"},
                null, null, null, null, "id")) {
                while (cursor.moveToNext()) {
                    long folderId = cursor.getLong(0);
                    long notebookId = insertNotebook(db, folderId, "默认笔记本");
                    ContentValues values = new ContentValues();
                    values.put("notebook_id", notebookId);
                    db.update("notes", values, "folder_id = ?", new String[]{Long.toString(folderId)});
                }
            }
        }
        db.execSQL("ALTER TABLE notes ADD COLUMN conflict_of TEXT NOT NULL DEFAULT ''");
        createSyncTables(db);
    }

    private static void createSyncTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sync_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sync_records (id TEXT PRIMARY KEY, kind TEXT NOT NULL,"
                + "local_id INTEGER, wire TEXT NOT NULL, observed TEXT, UNIQUE(kind, local_id))");
    }

    private static void createNotebookTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notebooks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "folder_id INTEGER NOT NULL CHECK(folder_id > 0),"
                + "name TEXT NOT NULL COLLATE NOCASE, UNIQUE(folder_id, name))");
    }

    private static void createNotebookIndex(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX notes_notebook ON notes("
                + "notebook_id, deleted_at, pinned DESC, updated_at DESC)");
    }

    public List<Note> loadNotes(String query, long folderId, String filter) {
        return queryNotes(query, folderId, 0, filter, null);
    }

    public List<Note> loadNotes(String query, long folderId, String filter, int limit, int offset) {
        return loadNotes(query, folderId, 0, filter, limit, offset);
    }

    public List<Note> loadNotes(String query, long folderId, long notebookId,
                                String filter, int limit, int offset) {
        if (limit <= 0 || offset < 0) {
            throw new IllegalArgumentException("笔记分页参数无效");
        }
        return queryNotes(query, folderId, notebookId, filter, limit + " OFFSET " + offset);
    }

    public int countNotes(String query, long folderId, String filter) {
        return countNotes(query, folderId, 0, filter);
    }

    public int countNotes(String query, long folderId, long notebookId, String filter) {
        NoteSelection selection = noteSelection(query, folderId, notebookId, filter);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM notes WHERE " + selection.where, selection.arguments)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    public List<Long> noteIds(String query, long folderId, long notebookId, String filter) {
        NoteSelection selection = noteSelection(query, folderId, notebookId, filter);
        List<Long> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("notes", new String[]{"id"},
                selection.where, selection.arguments, null, null,
                "pinned DESC, updated_at DESC, id DESC")) {
            while (cursor.moveToNext()) {
                result.add(cursor.getLong(0));
            }
        }
        return result;
    }

    private List<Note> queryNotes(String query, long folderId, long notebookId, String filter, String limit) {
        NoteSelection selection = noteSelection(query, folderId, notebookId, filter);
        List<Note> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("notes", NOTE_COLUMNS,
                selection.where, selection.arguments, null, null,
                "pinned DESC, updated_at DESC, id DESC", limit)) {
            while (cursor.moveToNext()) {
                result.add(readNote(cursor));
            }
        }
        return result;
    }

    private static final class NoteSelection {
        final String where;
        final String[] arguments;

        NoteSelection(String where, String[] arguments) {
            this.where = where;
            this.arguments = arguments;
        }
    }

    private static NoteSelection noteSelection(String query, long folderId, long notebookId, String filter) {
        if (folderId < -1) {
            throw new IllegalArgumentException("文件夹编号无效");
        }
        if (notebookId < 0) {
            throw new IllegalArgumentException("笔记本编号无效");
        }
        String selectedFilter = filter == null || filter.length() == 0 ? "all" : filter;
        StringBuilder selection = new StringBuilder();
        switch (selectedFilter) {
            case "all":
                selection.append("deleted_at = 0");
                break;
            case "favorite":
                selection.append("deleted_at = 0 AND favorite = 1");
                break;
            case "trash":
                selection.append("deleted_at > 0");
                break;
            case "checklist":
                selection.append("deleted_at = 0 AND type = 'checklist'");
                break;
            default:
                throw new IllegalArgumentException("未知的笔记筛选类型");
        }
        List<String> arguments = new ArrayList<>();
        if (folderId != 0) {
            selection.append(" AND folder_id = ?");
            arguments.add(Long.toString(folderId == -1 ? 0 : folderId));
        }
        if (notebookId > 0) {
            selection.append(" AND notebook_id = ?");
            arguments.add(Long.toString(notebookId));
        }
        String search = query == null ? "" : query.trim();
        if (!search.isEmpty()) {
            selection.append(" AND (title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\'"
                    + " OR checklist_text LIKE ? ESCAPE '\\')");
            String match = "%" + search.replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_") + "%";
            arguments.add(match);
            arguments.add(match);
            arguments.add(match);
        }
        return new NoteSelection(selection.toString(), arguments.toArray(new String[0]));
    }

    public Note getNote(long id) {
        return getNote(getReadableDatabase(), id);
    }

    public Note createNote(String type, long folderId) {
        SQLiteDatabase db = getWritableDatabase();
        Note note = new Note();
        note.type = validType(type);
        db.beginTransaction();
        try {
            note.folderId = checkedFolderId(db, folderId);
            note.notebookId = note.folderId == 0 ? 0 : defaultNotebookId(db, note.folderId);
            insertNote(db, note);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return note;
    }

    public Note createNoteInNotebook(long notebookId) {
        SQLiteDatabase db = getWritableDatabase();
        Note note = new Note();
        db.beginTransaction();
        try {
            note.folderId = folderForNotebook(db, notebookId);
            note.notebookId = notebookId;
            insertNote(db, note);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return note;
    }

    private static void insertNote(SQLiteDatabase db, Note note) {
        note.createdAt = System.currentTimeMillis();
        note.updatedAt = note.createdAt;
        ContentValues values = noteValues(note);
        values.put("created_at", note.createdAt);
        values.put("updated_at", note.updatedAt);
        values.put("deleted_at", 0);
        note.id = db.insertOrThrow("notes", null, values);
    }

    public void saveNote(Note note) {
        if (note == null) {
            throw new IllegalArgumentException("笔记不能为空");
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = noteValues(note);
        long updatedAt = System.currentTimeMillis();
        Note stored;
        long folderId;
        db.beginTransaction();
        try {
            stored = requireNote(db, note.id);
            folderId = folderForNotebook(db, note.notebookId);
            values.put("folder_id", folderId);
            values.put("updated_at", updatedAt);
            updateNote(db, note.id, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        note.folderId = folderId;
        note.createdAt = stored.createdAt;
        note.updatedAt = updatedAt;
        note.deletedAt = stored.deletedAt;
    }

    public void trashNote(long id) {
        SQLiteDatabase db = getWritableDatabase();
        Note note = requireNote(db, id);
        if (note.deletedAt == 0) {
            ContentValues values = new ContentValues();
            values.put("deleted_at", System.currentTimeMillis());
            updateNote(db, id, values);
        }
    }

    public int trashNotes(List<Long> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("笔记编号列表不能为空");
        }
        Set<Long> uniqueIds = new HashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("笔记编号无效");
            }
            uniqueIds.add(id);
        }
        if (uniqueIds.isEmpty()) {
            return 0;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("deleted_at", System.currentTimeMillis());
        int updated = 0;
        db.beginTransaction();
        try {
            for (Long id : uniqueIds) {
                updated += db.update("notes", values, "id = ? AND deleted_at = 0",
                        new String[]{Long.toString(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return updated;
    }

    public void restoreNote(long id) {
        SQLiteDatabase db = getWritableDatabase();
        requireNote(db, id);
        ContentValues values = new ContentValues();
        values.put("deleted_at", 0);
        updateNote(db, id, values);
    }

    public void deleteForever(long id) {
        SQLiteDatabase db = getWritableDatabase();
        Set<String> removedPaths;
        db.beginTransaction();
        try {
            Note note = requireNote(db, id);
            removedPaths = attachmentPaths(note.attachments);
            if (db.delete("notes", "id = ?", new String[]{Long.toString(id)}) != 1) {
                throw new IllegalArgumentException("笔记不存在");
            }
            removeReferencedPaths(db, removedPaths);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        // Shared attachments stay intact, and only files inside the private image directory are removed.
        for (String path : removedPaths) {
            deletePrivateImage(path, "笔记已删除");
        }
    }

    public void cleanupUnreferencedImages(List<String> paths) {
        if (paths == null) {
            throw new IllegalArgumentException("图片路径列表不能为空");
        }
        Set<String> candidates = new HashSet<>();
        try {
            String prefix = imageDirectory.getCanonicalPath() + File.separator;
            for (String path : paths) {
                if (path == null || path.isEmpty()) {
                    throw new IllegalArgumentException("图片路径无效");
                }
                String canonical = new File(path).getCanonicalPath();
                if (canonical.startsWith(prefix)) {
                    candidates.add(canonical);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("笔记已保存，但图片路径读取失败", exception);
        }
        if (candidates.isEmpty()) {
            return;
        }
        removeReferencedPaths(getReadableDatabase(), candidates);
        for (String path : candidates) {
            deletePrivateImage(path, "笔记已保存");
        }
    }

    private static void removeReferencedPaths(SQLiteDatabase db, Set<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        try (Cursor cursor = db.query("notes", new String[]{"attachments"},
                "attachments <> '[]'", null, null, null, null)) {
            while (!paths.isEmpty() && cursor.moveToNext()) {
                paths.removeAll(attachmentPaths(cursor.getString(0)));
            }
        }
    }

    /** Returns legacy content as text without changing the stored fields or trimming user text. */
    public static String plainText(Note note) {
        if (note == null) {
            throw new IllegalArgumentException("笔记不能为空");
        }
        if (note.checklist == null || note.checklist.trim().isEmpty()) {
            throw new IllegalArgumentException("旧清单内容格式无效");
        }
        JSONArray checklist = jsonArray(validChecklist(note.checklist), "旧清单内容");
        StringBuilder text = new StringBuilder();
        appendTextPart(text, note.title);
        appendTextPart(text, note.body);
        for (int index = 0; index < checklist.length(); index++) {
            JSONObject item = checklist.optJSONObject(index);
            appendTextPart(text, (item.optBoolean("done") ? "[x] " : "[ ] ") + item.optString("text"));
        }
        return text.toString();
    }

    private static void appendTextPart(StringBuilder target, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(part);
    }

    public void toggleFavorite(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Note note = requireNote(db, id);
            ContentValues values = new ContentValues();
            values.put("favorite", note.favorite ? 0 : 1);
            updateNote(db, id, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void togglePinned(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Note note = requireNote(db, id);
            ContentValues values = new ContentValues();
            values.put("pinned", note.pinned ? 0 : 1);
            updateNote(db, id, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void moveNote(long id, long folderId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireNote(db, id);
            ContentValues values = new ContentValues();
            long resolvedFolderId = checkedFolderId(db, folderId);
            values.put("folder_id", resolvedFolderId);
            values.put("notebook_id", resolvedFolderId == 0 ? 0 : defaultNotebookId(db, resolvedFolderId));
            updateNote(db, id, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void moveNoteToNotebook(long id, long notebookId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireNote(db, id);
            ContentValues values = new ContentValues();
            values.put("folder_id", folderForNotebook(db, notebookId));
            values.put("notebook_id", notebookId);
            updateNote(db, id, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Notebook> notebooks(long folderId) {
        if (folderId < 0) {
            throw new IllegalArgumentException("文件夹编号无效");
        }
        String selection = folderId == 0 ? "" : " WHERE b.folder_id = ?";
        String[] arguments = folderId == 0 ? null : new String[]{Long.toString(folderId)};
        String sql = notebookQuery() + selection + " GROUP BY b.id, b.folder_id, b.name ORDER BY b.id";
        List<Notebook> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, arguments)) {
            while (cursor.moveToNext()) {
                result.add(readNotebook(cursor));
            }
        }
        return result;
    }

    public Notebook getNotebook(long id) {
        return getNotebook(getReadableDatabase(), id);
    }

    public long createNotebook(long folderId, String name) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long checkedFolder = checkedFolderId(db, folderId);
            if (checkedFolder == 0) {
                throw new IllegalArgumentException("请先选择文件夹");
            }
            long id = insertNotebook(db, checkedFolder, name);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public void renameNotebook(long id, String name) {
        if (id <= 0) {
            throw new IllegalArgumentException("笔记本编号无效");
        }
        ContentValues values = new ContentValues();
        values.put("name", validNotebookName(name));
        if (getWritableDatabase().update("notebooks", values, "id = ?",
                new String[]{Long.toString(id)}) != 1) {
            throw new IllegalArgumentException("笔记本不存在");
        }
    }

    public void deleteNotebook(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireNotebook(db, id);
            ContentValues values = new ContentValues();
            values.put("folder_id", 0);
            values.put("notebook_id", 0);
            db.update("notes", values, "notebook_id = ?", new String[]{Long.toString(id)});
            db.delete("notebooks", "id = ?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static String notebookQuery() {
        return "SELECT b.id, b.folder_id, b.name, COUNT(n.id) AS note_count "
                + "FROM notebooks b LEFT JOIN notes n ON n.notebook_id = b.id AND n.deleted_at = 0";
    }

    private static Notebook getNotebook(SQLiteDatabase db, long id) {
        String sql = notebookQuery() + " WHERE b.id = ? GROUP BY b.id, b.folder_id, b.name";
        try (Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(id)})) {
            return cursor.moveToFirst() ? readNotebook(cursor) : null;
        }
    }

    private static Notebook readNotebook(Cursor cursor) {
        Notebook notebook = new Notebook();
        notebook.id = cursor.getLong(0);
        notebook.folderId = cursor.getLong(1);
        notebook.name = cursor.getString(2);
        notebook.count = cursor.getInt(3);
        return notebook;
    }

    private static Notebook requireNotebook(SQLiteDatabase db, long id) {
        Notebook notebook = getNotebook(db, id);
        if (notebook == null) {
            throw new IllegalArgumentException("笔记本不存在");
        }
        return notebook;
    }

    private static long folderForNotebook(SQLiteDatabase db, long notebookId) {
        if (notebookId < 0) {
            throw new IllegalArgumentException("笔记本编号无效");
        }
        if (notebookId == 0) {
            return 0;
        }
        Notebook notebook = requireNotebook(db, notebookId);
        return checkedFolderId(db, notebook.folderId);
    }

    private static long defaultNotebookId(SQLiteDatabase db, long folderId) {
        try (Cursor cursor = db.query("notebooks", new String[]{"id"}, "folder_id = ?",
                new String[]{Long.toString(folderId)}, null, null, "id", "1")) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return insertNotebook(db, folderId, "默认笔记本");
    }

    private static long insertNotebook(SQLiteDatabase db, long folderId, String name) {
        ContentValues values = new ContentValues();
        values.put("folder_id", folderId);
        values.put("name", validNotebookName(name));
        return db.insertOrThrow("notebooks", null, values);
    }

    private static String validNotebookName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("请输入笔记本名称");
        }
        return trimmed;
    }

    public List<Folder> folders() {
        List<Folder> result = new ArrayList<>();
        String sql = "SELECT f.id, f.name, f.color, COUNT(n.id) AS note_count "
                + "FROM folders f LEFT JOIN notes n ON n.folder_id = f.id AND n.deleted_at = 0 "
                + "GROUP BY f.id, f.name, f.color ORDER BY f.id";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                Folder folder = new Folder();
                folder.id = cursor.getLong(0);
                folder.name = cursor.getString(1);
                folder.color = cursor.getString(2);
                folder.count = cursor.getInt(3);
                result.add(folder);
            }
        }
        return result;
    }

    public long createFolder(String name, String color) {
        return insertFolder(getWritableDatabase(), name, color);
    }

    public void renameFolder(long id, String name) {
        if (id <= 0) {
            throw new IllegalArgumentException("文件夹编号无效");
        }
        ContentValues values = new ContentValues();
        values.put("name", validFolderName(name));
        if (getWritableDatabase().update("folders", values, "id = ?",
                new String[]{Long.toString(id)}) != 1) {
            throw new IllegalArgumentException("文件夹不存在");
        }
    }

    public void deleteFolder(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("文件夹编号无效");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            checkedFolderId(db, id);
            ContentValues values = new ContentValues();
            values.put("folder_id", 0);
            values.put("notebook_id", 0);
            db.update("notes", values, "folder_id = ? OR notebook_id IN "
                    + "(SELECT id FROM notebooks WHERE folder_id = ?)",
                    new String[]{Long.toString(id), Long.toString(id)});
            db.delete("notebooks", "folder_id = ?", new String[]{Long.toString(id)});
            db.delete("folders", "id = ?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public int activeCount() {
        return count("deleted_at = 0");
    }

    public int favoriteCount() {
        return count("deleted_at = 0 AND favorite = 1");
    }

    public int trashCount() {
        return count("deleted_at > 0");
    }

    public int checklistCount() {
        return count("deleted_at = 0 AND type = 'checklist'");
    }

    private int count(String predicate) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM notes WHERE " + predicate, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private static Note getNote(SQLiteDatabase db, long id) {
        try (Cursor cursor = db.query("notes", NOTE_COLUMNS, "id = ?",
                new String[]{Long.toString(id)}, null, null, null)) {
            return cursor.moveToFirst() ? readNote(cursor) : null;
        }
    }

    private static Note requireNote(SQLiteDatabase db, long id) {
        Note note = getNote(db, id);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在");
        }
        return note;
    }

    private static void updateNote(SQLiteDatabase db, long id, ContentValues values) {
        if (db.update("notes", values, "id = ?", new String[]{Long.toString(id)}) != 1) {
            throw new IllegalArgumentException("笔记不存在");
        }
    }

    private static ContentValues noteValues(Note note) {
        ContentValues values = new ContentValues();
        values.put("folder_id", note.folderId);
        values.put("notebook_id", note.notebookId);
        values.put("title", note.title == null ? "" : note.title);
        values.put("body", note.body == null ? "" : note.body);
        values.put("color", validColor(note.color));
        values.put("sticker", validSticker(note.sticker));
        values.put("type", validType(note.type));
        values.put("attachments", validAttachments(note.attachments));
        String checklist = validChecklist(note.checklist);
        values.put("checklist", checklist);
        values.put("checklist_text", checklistText(checklist));
        values.put("favorite", note.favorite ? 1 : 0);
        values.put("pinned", note.pinned ? 1 : 0);
        values.put("conflict_of", note.conflictOf == null ? "" : note.conflictOf);
        return values;
    }

    private static Note readNote(Cursor cursor) {
        Note note = new Note();
        note.id = cursor.getLong(0);
        note.folderId = cursor.getLong(1);
        note.createdAt = cursor.getLong(2);
        note.updatedAt = cursor.getLong(3);
        note.deletedAt = cursor.getLong(4);
        note.title = cursor.getString(5);
        note.body = cursor.getString(6);
        note.color = cursor.getString(7);
        note.sticker = cursor.getString(8);
        note.type = cursor.getString(9);
        note.attachments = cursor.getString(10);
        note.checklist = cursor.getString(11);
        note.favorite = cursor.getInt(12) == 1;
        note.pinned = cursor.getInt(13) == 1;
        note.notebookId = cursor.getLong(14);
        note.conflictOf = cursor.getString(15);
        return note;
    }

    private static long checkedFolderId(SQLiteDatabase db, long folderId) {
        if (folderId == 0 || folderId == -1) {
            return 0;
        }
        if (folderId < 0) {
            throw new IllegalArgumentException("文件夹编号无效");
        }
        try (Cursor cursor = db.query("folders", new String[]{"id"}, "id = ?",
                new String[]{Long.toString(folderId)}, null, null, null)) {
            if (!cursor.moveToFirst()) {
                throw new IllegalArgumentException("文件夹不存在");
            }
        }
        return folderId;
    }

    private static long insertFolder(SQLiteDatabase db, String name, String color) {
        ContentValues values = new ContentValues();
        values.put("name", validFolderName(name));
        values.put("color", validColor(color));
        return db.insertOrThrow("folders", null, values);
    }

    private static String validFolderName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("请输入文件夹名称");
        }
        return trimmed;
    }

    private static String validType(String type) {
        if (!"text".equals(type) && !"checklist".equals(type)) {
            throw new IllegalArgumentException("笔记类型无效");
        }
        return type;
    }

    private static String validColor(String color) {
        if (color == null || color.isEmpty()) {
            return "cream";
        }
        if (!"cream".equals(color) && !"pink".equals(color) && !"mint".equals(color)
                && !"lavender".equals(color) && !"sky".equals(color)) {
            throw new IllegalArgumentException("笔记颜色无效");
        }
        return color;
    }

    private static String validSticker(String sticker) {
        if (sticker == null || sticker.isEmpty()) {
            return "";
        }
        if (!"hello".equals(sticker) && !"read".equals(sticker) && !"sleep".equals(sticker)
                && !"carrot".equals(sticker) && !"love".equals(sticker)) {
            throw new IllegalArgumentException("装饰数据无效");
        }
        return sticker;
    }

    private static String validAttachments(String json) {
        JSONArray array = jsonArray(json, "图片附件");
        for (int index = 0; index < array.length(); index++) {
            Object path = array.opt(index);
            if (!(path instanceof String) || ((String) path).isEmpty()) {
                throw new IllegalArgumentException("图片附件格式无效");
            }
        }
        return array.toString();
    }

    private static String validChecklist(String json) {
        JSONArray array = jsonArray(json, "待办事项");
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null || !(item.opt("text") instanceof String)
                    || !(item.opt("done") instanceof Boolean)) {
                throw new IllegalArgumentException("待办事项格式无效");
            }
        }
        return array.toString();
    }

    private static String checklistText(String validatedJson) {
        JSONArray array = jsonArray(validatedJson, "待办事项");
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < array.length(); index++) {
            if (index > 0) {
                text.append('\n');
            }
            text.append(array.optJSONObject(index).optString("text"));
        }
        return text.toString();
    }

    private static JSONArray jsonArray(String json, String label) {
        try {
            JSONTokener parser = new JSONTokener(json == null || json.trim().isEmpty() ? "[]" : json);
            Object value = parser.nextValue();
            if (!(value instanceof JSONArray) || parser.nextClean() != 0) {
                throw new IllegalArgumentException(label + "格式无效");
            }
            return (JSONArray) value;
        } catch (JSONException exception) {
            throw new IllegalArgumentException(label + "格式无效", exception);
        }
    }

    private static Set<String> attachmentPaths(String json) {
        JSONArray array = jsonArray(validAttachments(json), "图片附件");
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            try {
                paths.add(new File((String) array.opt(index)).getCanonicalPath());
            } catch (IOException exception) {
                throw new IllegalStateException("图片路径读取失败", exception);
            }
        }
        return paths;
    }

    private void deletePrivateImage(String path, String completedOperation) {
        try {
            File file = new File(path).getCanonicalFile();
            String prefix = imageDirectory.getCanonicalPath() + File.separator;
            if (file.getPath().startsWith(prefix) && file.isFile() && !file.delete()) {
                throw new IllegalStateException(completedOperation + "，但图片清理失败");
            }
        } catch (IOException exception) {
            throw new IllegalStateException(completedOperation + "，但图片路径读取失败", exception);
        }
    }
}
