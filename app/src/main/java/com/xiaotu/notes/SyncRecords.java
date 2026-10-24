package com.xiaotu.notes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Wire-only validation and deterministic merge shared with the desktop protocol. */
final class SyncRecords {
    static final long MAX_INTEGER = 9007199254740991L;
    static final int MAX_RECORDS = 20000;
    static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern CONFLICT = Pattern.compile("conflict-[0-9a-f]{64}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    static boolean validId(String value) { return value != null && (UUID.matcher(value).matches() || CONFLICT.matcher(value).matches()); }
    static boolean validDevice(String value) { return value != null && UUID.matcher(value).matches(); }
    static boolean validHash(String value) { return value != null && HASH.matcher(value).matches(); }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static JSONArray validate(JSONArray records) throws JSONException {
        require(records != null && records.length() <= MAX_RECORDS, "同步记录数量超过限制");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < records.length(); i++) {
            Object item = records.get(i);
            require(item instanceof JSONObject, "同步记录格式无效");
            JSONObject record = (JSONObject) item;
            exact(record, "kind", "id", "clock", "deleted", "data");
            String kind = string(record, "kind");
            require(kind.equals("folder") || kind.equals("book") || kind.equals("note"), "同步记录类型无效");
            String id = string(record, "id");
            require(validId(id) && ids.add(id), "同步记录编号无效或重复");
            require(record.get("deleted") instanceof Boolean, "同步删除状态无效");
            require(record.get("clock") instanceof JSONObject, "同步版本格式无效");
            JSONObject clock = record.getJSONObject("clock");
            require(clock.length() <= 256, "同步版本设备数量超过限制");
            for (String device : keys(clock)) {
                require(validDevice(device), "同步设备编号无效");
                integer(clock.get(device), "同步版本");
            }
            require(record.get("data") instanceof JSONObject, "同步内容格式无效");
            JSONObject data = record.getJSONObject("data");
            if (kind.equals("folder")) {
                exact(data, "name", "color");
                string(data, "name"); string(data, "color");
            } else if (kind.equals("book")) {
                exact(data, "name", "folderId");
                string(data, "name");
                require(validId(string(data, "folderId")), "同步文件夹编号无效");
            } else {
                exact(data, "body", "notebookId", "createdAt", "updatedAt", "trashedAt", "favorite", "pinned", "images", "color", "sticker", "conflictOf");
                string(data, "body"); string(data, "color"); string(data, "sticker");
                Object parent = data.get("notebookId");
                require(parent == JSONObject.NULL || (parent instanceof String && validId((String) parent)), "同步笔记本编号无效");
                integer(data.get("createdAt"), "创建时间");
                integer(data.get("updatedAt"), "修改时间");
                integer(data.get("trashedAt"), "回收站时间");
                require(data.get("favorite") instanceof Boolean && data.get("pinned") instanceof Boolean, "同步笔记状态无效");
                require(data.get("images") instanceof JSONArray, "同步图片列表无效");
                JSONArray images = data.getJSONArray("images");
                require(images.length() <= 10000, "单篇笔记图片数量超过限制");
                for (int j = 0; j < images.length(); j++) {
                    Object hash = images.get(j);
                    require(hash instanceof String && validHash((String) hash), "同步图片编号无效");
                }
                String conflict = string(data, "conflictOf");
                require(conflict.isEmpty() || validId(conflict), "冲突原稿编号无效");
            }
        }
        String text = canonical(records);
        require(text.getBytes(StandardCharsets.UTF_8).length <= MAX_MANIFEST_BYTES, "同步内容超过 32 MB，请减少单次同步内容");
        return new JSONArray(text);
    }

    private static void exact(JSONObject object, String... expected) {
        require(object.length() == expected.length, "同步字段数量无效");
        for (String key : expected) require(object.has(key), "同步字段缺失：" + key);
    }

    private static String string(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        require(value instanceof String, "同步文字字段无效：" + key);
        return (String) value;
    }

    static long integer(Object value, String label) {
        require(value instanceof Number, label + "不是有效整数");
        double number = ((Number) value).doubleValue();
        require(!Double.isNaN(number) && !Double.isInfinite(number) && number >= 0 && number <= MAX_INTEGER && number == Math.floor(number), label + "超出有效整数范围");
        return ((Number) value).longValue();
    }

    static String canonical(Object value) throws JSONException {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) out.append("null");
        else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Boolean) out.append(value.toString());
        else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            require(!Double.isNaN(number) && !Double.isInfinite(number) && Math.abs(number) <= MAX_INTEGER && number == Math.floor(number), "同步数值不是安全整数");
            out.append(((Number) value).longValue());
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            out.append('[');
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                append(out, array.get(i));
            }
            out.append(']');
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            out.append('{');
            boolean first = true;
            for (String key : keys(object)) {
                if (!first) out.append(',');
                first = false;
                quote(out, key); out.append(':'); append(out, object.get(key));
            }
            out.append('}');
        } else throw new IllegalArgumentException("同步包含不支持的数据类型");
    }

    private static void quote(StringBuilder out, String value) {
        final char[] hex = "0123456789abcdef".toCharArray();
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') { out.append('\\'); out.append(c); }
            else if (c < 0x20 || c > 0x7e) {
                out.append("\\u").append(hex[(c >>> 12) & 15]).append(hex[(c >>> 8) & 15]).append(hex[(c >>> 4) & 15]).append(hex[c & 15]);
            } else out.append(c);
        }
        out.append('"');
    }

    static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        Collections.sort(result);
        return result;
    }

    static String sha256(String text) { return hex(digest().digest(text.getBytes(StandardCharsets.UTF_8))); }
    static String fingerprint(JSONObject record) throws JSONException { return sha256(canonical(record)); }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("当前系统不支持 SHA-256", impossible); }
    }
    static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { chars[i * 2] = alphabet[(bytes[i] >>> 4) & 15]; chars[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(chars);
    }
    static JSONObject copy(JSONObject value) throws JSONException { return new JSONObject(canonical(value)); }

    static final class Merged {
        final JSONArray records;
        final int conflicts;
        Merged(JSONArray records, int conflicts) { this.records = records; this.conflicts = conflicts; }
    }

    static Merged merge(JSONArray local, JSONArray incoming) throws JSONException {
        Map<String, JSONObject> result = new TreeMap<>();
        for (int i = 0; i < local.length(); i++) {
            JSONObject record = local.getJSONObject(i);
            result.put(record.getString("id"), copy(record));
        }
        Map<String, JSONObject> ordered = new TreeMap<>();
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject record = incoming.getJSONObject(i);
            ordered.put(record.getString("id"), copy(record));
        }
        ArrayDeque<JSONObject> pending = new ArrayDeque<>(ordered.values());
        int conflicts = 0;
        int processed = 0;
        while (!pending.isEmpty()) {
            require(++processed <= MAX_RECORDS * 4, "同步冲突数量超过限制");
            JSONObject other = pending.removeFirst();
            String id = other.getString("id");
            JSONObject current = result.get(id);
            if (current == null) { result.put(id, other); continue; }
            require(current.getString("kind").equals(other.getString("kind")), "同一同步编号出现不同记录类型");
            int comparison = compareClocks(current.getJSONObject("clock"), other.getJSONObject("clock"));
            if (comparison == 1) continue;
            if (comparison == -1) { result.put(id, other); continue; }
            String currentFingerprint = fingerprint(current);
            String otherFingerprint = fingerprint(other);
            if (currentFingerprint.equals(otherFingerprint)) continue;
            JSONObject joined = join(current.getJSONObject("clock"), other.getJSONObject("clock"));
            JSONObject winner;
            if (current.getBoolean("deleted") != other.getBoolean("deleted")) winner = current.getBoolean("deleted") ? current : other;
            else if (current.getString("kind").equals("note") &&
                    (current.getJSONObject("data").getLong("trashedAt") > 0) != (other.getJSONObject("data").getLong("trashedAt") > 0)) {
                winner = current.getJSONObject("data").getLong("trashedAt") > 0 ? current : other;
            } else winner = currentFingerprint.compareTo(otherFingerprint) >= 0 ? current : other;
            JSONObject loser = winner == current ? other : current;
            String loserFingerprint = winner == current ? otherFingerprint : currentFingerprint;
            JSONObject primary = copy(winner);
            primary.put("clock", joined);
            result.put(id, primary);
            if (current.getString("kind").equals("note") && !loser.getBoolean("deleted") &&
                    !canonical(current.getJSONObject("data")).equals(canonical(other.getJSONObject("data")))) {
                String copyId = "conflict-" + sha256(id + "\n" + loserFingerprint);
                JSONObject retained = copy(loser);
                retained.put("id", copyId);
                retained.put("clock", copy(joined));
                retained.put("deleted", false);
                retained.getJSONObject("data").put("conflictOf", id).put("trashedAt", 0);
                if (!result.containsKey(copyId) && !ordered.containsKey(copyId)) conflicts++;
                pending.addLast(retained);
            }
            require(result.size() <= MAX_RECORDS, "合并后的同步记录数量超过限制");
        }
        JSONArray records = new JSONArray();
        for (JSONObject record : result.values()) records.put(record);
        return new Merged(validate(records), conflicts);
    }

    private static int compareClocks(JSONObject a, JSONObject b) throws JSONException {
        Set<String> devices = new HashSet<>(keys(a)); devices.addAll(keys(b));
        boolean larger = false, smaller = false;
        for (String device : devices) {
            long left = a.has(device) ? integer(a.get(device), "同步版本") : 0;
            long right = b.has(device) ? integer(b.get(device), "同步版本") : 0;
            larger |= left > right; smaller |= left < right;
        }
        return larger && !smaller ? 1 : smaller && !larger ? -1 : larger ? 2 : 0;
    }

    private static JSONObject join(JSONObject a, JSONObject b) throws JSONException {
        JSONObject result = new JSONObject();
        Set<String> devices = new HashSet<>(keys(a)); devices.addAll(keys(b));
        for (String device : devices) result.put(device, Math.max(a.optLong(device, 0), b.optLong(device, 0)));
        return result;
    }
}
