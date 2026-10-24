package com.xiaotu.notes;

import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** HTTPS only, restricted to literal LAN IPv4 addresses and an exact scanned certificate. */
final class SyncClient {
    static final int MAX_JSON = 32 * 1024 * 1024, MAX_BLOB = 30 * 1024 * 1024, MAX_RECORDS = 20000, MAX_HASHES = 20000;
    interface Progress { void stage(String value); }
    interface Applied { void committed(); }
    static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpsURLConnection connection;
        void cancel() { cancelled.set(true); HttpsURLConnection current = connection; if (current != null) current.disconnect(); }
        void check() throws IOException { if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Cancelled(); }
        void attach(HttpsURLConnection value) throws IOException {
            connection = value;
            if (cancelled.get()) { value.disconnect(); connection = null; throw new Cancelled(); }
            check();
        }
        void detach(HttpsURLConnection value) { if (connection == value) connection = null; }
        boolean isCancelled() { return cancelled.get(); }
    }
    static final class Cancelled extends IOException { Cancelled() { super("已取消同步，未完成的操作可重新发起。"); } }
    static final class Failure extends IOException { Failure(String message) { super(message); } }
    static class Endpoint {
        final String host, fingerprint;
        final int port;
        Endpoint(String host, int port, String fingerprint) throws Failure {
            if (!privateAddress(host) || port < 1 || port > 65535 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new Failure("配对码中的局域网地址、端口或证书指纹无效。");
            this.host = host; this.port = port; this.fingerprint = fingerprint;
        }
    }
    static final class PairCode extends Endpoint {
        final String token, deviceId, name;
        PairCode(JSONObject value) throws Exception {
            super(value.getString("host"), exactInt(value, "port"), value.getString("fingerprint"));
            if (exactInt(value, "v") != 1) throw new Failure("此配对码版本暂不支持，请更新两端简记。");
            token = value.getString("token"); validateToken(token);
            deviceId = identity(value.getString("deviceId")); name = displayName(value.getString("name"));
        }
    }

    private final Endpoint endpoint;
    private final Cancellation cancellation;
    private final SSLSocketFactory tls;

    SyncClient(Endpoint endpoint, Cancellation cancellation) throws Exception {
        this.endpoint = endpoint; this.cancellation = cancellation;
        byte[] expected = hex(endpoint.fingerprint);
        X509TrustManager pin = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("不接受客户端证书"); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0 || !MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))
                        throw new CertificateException("电脑证书已变化，请重新扫码确认。");
                    chain[0].checkValidity();
                } catch (CertificateException error) { throw error; }
                catch (Exception error) { throw new CertificateException("无法验证电脑证书。"); }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{pin}, null); tls = context.getSocketFactory();
    }

    static PairCode parsePairCode(String raw) throws Failure {
        try {
            if (raw == null || raw.length() > 16384) throw new Failure("配对码无效或过长。");
            String json = raw.trim();
            if (!json.startsWith("{")) {
                Uri uri = Uri.parse(json);
                if (!"jianji".equals(uri.getScheme()) || !"pair".equals(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || (uri.getPath() != null && !uri.getPath().isEmpty()) || uri.getFragment() != null)
                    throw new Failure("请扫描电脑简记显示的配对二维码。");
                String data = uri.getQueryParameter("data");
                if (data == null || !data.matches("[A-Za-z0-9_-]+={0,2}")) throw new Failure("配对码内容无效。");
                json = new String(Base64.decode(data, Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
            }
            return new PairCode(new JSONObject(json));
        } catch (Failure error) { throw error; }
        catch (Exception error) { throw new Failure("无法读取配对码，请在电脑上重新生成后再试。"); }
    }
    private static int exactInt(JSONObject value, String key) throws Exception {
        Object number = value.get(key);
        if (!(number instanceof Number) || ((Number) number).doubleValue() != ((Number) number).intValue()) throw new Failure("配对码中的数值无效。");
        return ((Number) number).intValue();
    }
    static void validateToken(String token) throws Failure {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43,256}")) throw new Failure("配对凭据无效，请重新扫码。");
        try { if (Base64.decode(token, Base64.URL_SAFE | Base64.NO_WRAP).length < 32) throw new Failure("配对凭据无效。"); }
        catch (IllegalArgumentException error) { throw new Failure("配对凭据无效，请重新扫码。"); }
    }
    private static String identity(String value) throws Failure {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,127}")) throw new Failure("电脑设备标识无效。");
        return value;
    }
    private static String displayName(String value) throws Failure {
        if (value == null || value.trim().isEmpty() || value.length() > 100 || value.matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw new Failure("电脑名称无效。");
        return value;
    }
    private static boolean privateAddress(String host) {
        if (host == null || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = host.split("\\."); int[] values = new int[4];
        for (int i = 0; i < 4; i++) { values[i] = Integer.parseInt(parts[i]); if (values[i] > 255) return false; }
        return values[0] == 10 || (values[0] == 172 && values[1] >= 16 && values[1] <= 31)
            || (values[0] == 192 && values[1] == 168) || (values[0] == 169 && values[1] == 254);
    }
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    private HttpsURLConnection connection(String method, String path, String auth) throws Exception {
        cancellation.check();
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https", endpoint.host, endpoint.port, path).openConnection();
        connection.setSSLSocketFactory(tls);
        connection.setHostnameVerifier((host, session) -> {
            try {
                return endpoint.host.equals(host) && MessageDigest.isEqual(hex(endpoint.fingerprint), MessageDigest.getInstance("SHA-256").digest(session.getPeerCertificates()[0].getEncoded()));
            } catch (Exception error) { return false; }
        });
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setConnectTimeout(8000); connection.setReadTimeout(path.equals("/v1/sync") ? 60000 : 15000);
        connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
        if (auth != null) connection.setRequestProperty("Authorization", auth);
        cancellation.attach(connection); return connection;
    }
    private void statusCode(HttpsURLConnection connection) throws Exception {
        int code = connection.getResponseCode(); cancellation.check();
        if (code >= 200 && code < 300) return;
        if (code >= 300 && code < 400) throw new Failure("电脑返回了重定向，已停止连接。请重新扫码确认地址。");
        if (code == 401) throw new Failure("配对凭据已失效或被撤销，请重新扫码连接。");
        if (code == 403) throw new Failure("电脑拒绝了此请求，请检查电脑端的配对状态。");
        if (code == 409) throw new Failure("电脑仍有未保存内容或正在同步，请稍后重试。");
        if (code == 410) throw new Failure("配对码或请求已过期，请在电脑上重新生成。");
        if (code == 413) throw new Failure("同步数据超过限制，请减少单次内容后重试。");
        throw new Failure("电脑暂时无法完成请求（" + code + "），请查看电脑端状态。");
    }
    private byte[] readBounded(InputStream input, int maximum) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) {
            cancellation.check(); if (bytes.size() + count > maximum) throw new Failure("电脑返回的数据超过安全大小限制。");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    JSONObject json(String method, String path, String auth, JSONObject body) throws Exception {
        HttpsURLConnection connection = connection(method, path, auth);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_JSON) throw new Failure("笔记数据超过单次同步的 32 MB 限制。");
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    for (int offset = 0; offset < bytes.length; offset += 16384) { cancellation.check(); out.write(bytes, offset, Math.min(16384, bytes.length - offset)); }
                }
            }
            statusCode(connection);
            if (connection.getContentLengthLong() > MAX_JSON) throw new Failure("电脑返回的数据超过安全大小限制。");
            try (InputStream in = connection.getInputStream()) {
                byte[] response = readBounded(in, MAX_JSON);
                if (response.length == 0) return new JSONObject();
                try { return new JSONObject(new String(response, StandardCharsets.UTF_8)); }
                catch (Exception error) { throw new Failure("电脑返回的数据格式异常，未覆盖本机笔记。"); }
            }
        } finally { cancellation.detach(connection); connection.disconnect(); }
    }
    EncryptedPeerPrefs.Peer pair(PairCode code, SyncStore store, Progress progress) throws Exception {
        String phoneName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        if (phoneName.length() > 100) phoneName = phoneName.substring(0, 100);
        JSONObject requested = json("POST", "/v1/pair/request", null, new JSONObject().put("token", code.token).put("deviceId", store.deviceId()).put("name", phoneName));
        String requestId = identity(requested.getString("requestId"));
        progress.stage("请在电脑上批准这部手机…");
        long deadline = SystemClock.elapsedRealtime() + 5 * 60 * 1000;
        while (SystemClock.elapsedRealtime() < deadline) {
            cancellation.check();
            JSONObject reply = json("GET", "/v1/pair/status?requestId=" + URLEncoder.encode(requestId, "UTF-8"), "Pair " + code.token, null);
            String status = reply.optString("status");
            if ("rejected".equals(status)) throw new Failure("电脑拒绝了配对。需要时可重新扫码申请。");
            if ("approved".equals(status)) {
                if (!code.deviceId.equals(reply.getString("deviceId"))) throw new Failure("电脑设备标识与配对码不一致，已停止连接。");
                EncryptedPeerPrefs.Peer peer = new EncryptedPeerPrefs.Peer();
                peer.host = code.host; peer.port = code.port; peer.fingerprint = code.fingerprint;
                peer.deviceId = code.deviceId; peer.name = displayName(reply.getString("name"));
                peer.peerId = identity(reply.getString("peerId")); peer.accessToken = reply.getString("accessToken"); validateToken(peer.accessToken);
                progress.stage("正在保存配对…");
                json("POST", "/v1/pair/finish", "Bearer " + peer.accessToken, new JSONObject().put("requestId", requestId));
                return peer;
            }
            if (!"pending".equals(status)) throw new Failure("配对请求已失效，请重新扫码。");
            Thread.sleep(1000);
        }
        throw new Failure("等待电脑批准超时，请重新生成配对码。");
    }
    JSONObject status(EncryptedPeerPrefs.Peer peer) throws Exception {
        JSONObject value = json("GET", "/v1/status", "Bearer " + peer.accessToken, null);
        if (exactInt(value, "protocol") != 1 || !peer.deviceId.equals(value.getString("deviceId"))) throw new Failure("电脑身份或同步协议已变化，请重新扫码。");
        Object generation = value.get("syncRequested");
        if (!(generation instanceof Number) || ((Number) generation).doubleValue() != ((Number) generation).longValue() || ((Number) generation).longValue() < 0 || ((Number) generation).longValue() > 9007199254740991L)
            throw new Failure("电脑返回了无效的同步请求。");
        return value;
    }
    private Set<String> hashes(JSONObject envelope) throws Exception {
        JSONArray records = envelope.getJSONArray("records");
        if (records.length() > MAX_RECORDS) throw new Failure("记录数量超过单次同步限制。");
        Set<String> hashes = new LinkedHashSet<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (!record.getBoolean("deleted") && "note".equals(record.getString("kind"))) {
                JSONArray assets = record.getJSONObject("data").getJSONArray("images");
                if (assets.length() > 10000) throw new Failure("单篇笔记图片引用数量超过限制。");
                for (int j = 0; j < assets.length(); j++) { String hash = assets.getString(j); validateHash(hash); hashes.add(hash); }
                if (hashes.size() > MAX_HASHES) throw new Failure("图片引用数量超过限制。");
            }
        }
        return hashes;
    }
    private static void validateHash(String hash) throws Failure { if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new Failure("同步数据含有无效的图片标识。"); }
    private void upload(String hash, File file, String auth) throws Exception {
        validateHash(hash);
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_BLOB) throw new Failure("本机图片缺失或超过 30 MB，未覆盖笔记。");
        HttpsURLConnection connection = connection("PUT", "/v1/blobs/" + hash, auth);
        try {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/octet-stream"); connection.setFixedLengthStreamingMode(file.length());
            try (InputStream in = new FileInputStream(file); OutputStream out = connection.getOutputStream()) {
                byte[] buffer = new byte[16384]; int count;
                while ((count = in.read(buffer)) != -1) { cancellation.check(); out.write(buffer, 0, count); }
            }
            statusCode(connection);
        } finally { cancellation.detach(connection); connection.disconnect(); }
    }
    private void download(String hash, String auth, SyncStore store) throws Exception {
        validateHash(hash); HttpsURLConnection connection = connection("GET", "/v1/blobs/" + hash, auth);
        try {
            statusCode(connection);
            if (connection.getContentLengthLong() > MAX_BLOB) throw new Failure("电脑图片超过 30 MB 限制。");
            try (InputStream raw = connection.getInputStream(); InputStream checked = new java.io.FilterInputStream(raw) {
                private long bytes;
                @Override public int read(byte[] target, int offset, int length) throws IOException {
                    cancellation.check(); int result = super.read(target, offset, length);
                    if (result > 0 && (bytes += result) > MAX_BLOB) throw new Failure("电脑图片超过 30 MB 限制。");
                    return result;
                }
                @Override public int read() throws IOException {
                    cancellation.check(); int result = super.read(); if (result >= 0 && ++bytes > MAX_BLOB) throw new Failure("电脑图片超过 30 MB 限制。"); return result;
                }
            }) { store.receiveBlob(hash, checked); }
        } finally { cancellation.detach(connection); connection.disconnect(); }
    }
    JSONObject synchronize(EncryptedPeerPrefs.Peer peer, SyncStore store, long requestGeneration, Progress progress, Applied applied) throws Exception {
        boolean committed = false;
        try {
            cancellation.check(); progress.stage("正在整理本机笔记和图片…");
            SyncStore.Snapshot snapshot = store.capture(); cancellation.check();
            Set<String> outgoing = hashes(snapshot.manifest);
            String auth = "Bearer " + peer.accessToken;
            JSONArray requested = new JSONArray(); for (String hash : outgoing) requested.put(hash);
            progress.stage("正在核对电脑图片…");
            JSONArray missing = json("POST", "/v1/blobs/check", auth, new JSONObject().put("hashes", requested)).getJSONArray("missing");
            if (missing.length() > outgoing.size()) throw new Failure("电脑返回的图片请求数量异常。");
            Set<String> uploaded = new LinkedHashSet<>();
            for (int i = 0; i < missing.length(); i++) {
                String hash = missing.getString(i); validateHash(hash);
                if (!outgoing.contains(hash)) throw new Failure("电脑请求了本次同步之外的图片，已停止。");
                if (uploaded.add(hash)) { progress.stage("正在上传图片 " + uploaded.size() + "/" + missing.length() + "…"); upload(hash, snapshot.blobs.get(hash), auth); }
            }
            progress.stage("正在合并两端笔记…");
            JSONObject merged = json("POST", "/v1/sync", auth, snapshot.manifest);
            if (exactInt(merged, "protocol") != 1 || !peer.deviceId.equals(merged.getString("deviceId"))) throw new Failure("电脑返回的同步身份不匹配。");
            String syncId = identity(merged.getString("syncId"));
            JSONArray validated = SyncRecords.validate(merged.getJSONArray("records"));
            merged.put("records", validated);
            int desktopConflicts = exactInt(merged.getJSONObject("summary"), "conflicts");
            if (desktopConflicts < 0 || desktopConflicts > MAX_RECORDS) throw new Failure("电脑返回的冲突统计无效。");
            Set<String> incoming = hashes(merged); int downloaded = 0;
            for (String hash : incoming) {
                cancellation.check();
                if (!store.hasBlob(hash)) { progress.stage("正在接收图片 " + (++downloaded) + "…"); download(hash, auth, store); }
            }
            cancellation.check(); progress.stage("正在保存本机合并结果…");
            JSONObject summary = store.apply(validated, snapshot.signature);
            committed = true; applied.committed();
            summary.put("conflicts", summary.optInt("conflicts", 0) + desktopConflicts);
            cancellation.check(); progress.stage("正在确认同步完成…");
            json("POST", "/v1/sync/ack", auth, new JSONObject().put("syncId", syncId).put("syncRequested", requestGeneration));
            return summary;
        } catch (Exception error) {
            if (committed) throw new Failure("本机数据已合并，但电脑尚未确认完成。请再次同步以完成确认。");
            throw error;
        }
    }
    static String readable(Exception error, Cancellation cancellation) {
        if (error instanceof Failure) return error.getMessage();
        if (cancellation != null && cancellation.isCancelled() || error instanceof Cancelled || error instanceof InterruptedException) return "操作已取消。未完成的同步可重新发起。";
        if (error instanceof SSLException || error instanceof CertificateException) return "无法验证电脑证书，可能已更换或过期。请在电脑上重新生成配对码并确认。";
        if (error instanceof java.net.SocketTimeoutException) return "连接电脑超时，请确认两端在同一 Wi-Fi、电脑简记已打开。";
        if (error instanceof IOException) return "无法连接电脑，请确认同一 Wi-Fi、电脑地址未变化且防火墙允许连接。";
        if (error instanceof IllegalStateException && error.getMessage() != null) return error.getMessage();
        return "同步数据未通过检查，未覆盖本机笔记。请查看电脑端状态后重试。";
    }
}
