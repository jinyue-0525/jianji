package com.xiaotu.notes;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** One trusted computer, encrypted with a non-exportable installation key. */
final class EncryptedPeerPrefs {
    private static final String ALIAS = "jianji.lan.peer.v1";
    private final SharedPreferences preferences;

    static final class Peer {
        String host, fingerprint, accessToken, peerId, deviceId, name, lastStatus = "已配对，尚未同步";
        int port;
        long lastSyncAt, lastGeneration;

        JSONObject json() throws Exception {
            return new JSONObject().put("host", host).put("port", port).put("fingerprint", fingerprint)
                .put("accessToken", accessToken).put("peerId", peerId).put("deviceId", deviceId).put("name", name)
                .put("lastStatus", lastStatus).put("lastSyncAt", lastSyncAt).put("lastGeneration", lastGeneration);
        }
        static Peer from(JSONObject value) throws Exception {
            Peer peer = new Peer();
            peer.host = value.getString("host"); peer.port = value.getInt("port");
            peer.fingerprint = value.getString("fingerprint"); peer.accessToken = value.getString("accessToken");
            peer.peerId = value.getString("peerId"); peer.deviceId = value.getString("deviceId"); peer.name = value.getString("name");
            peer.lastStatus = value.optString("lastStatus", "已配对，尚未同步");
            peer.lastSyncAt = value.optLong("lastSyncAt", 0); peer.lastGeneration = value.optLong("lastGeneration", 0);
            new SyncClient.Endpoint(peer.host, peer.port, peer.fingerprint);
            SyncClient.validateToken(peer.accessToken);
            return peer;
        }
    }

    EncryptedPeerPrefs(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("lan_peer_secure", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    synchronized Peer read() {
        String stored = preferences.getString("peer", null);
        if (stored == null) return null;
        try {
            if (stored.length() > 24000) throw new IllegalStateException();
            JSONObject container = new JSONObject(stored);
            byte[] iv = Base64.decode(container.getString("iv"), Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(container.getString("data"), Base64.NO_WRAP);
            if (iv.length != 12) throw new IllegalStateException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return Peer.from(new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("本机配对信息无法读取，请解除本机配对后重新扫码。");
        }
    }

    synchronized void save(Peer peer) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(peer.json().toString().getBytes(StandardCharsets.UTF_8));
            JSONObject container = new JSONObject().put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
            if (!preferences.edit().putString("peer", container.toString()).commit()) throw new IllegalStateException();
        } catch (Exception error) {
            throw new IllegalStateException("无法安全保存配对信息，请检查本机存储空间后重试。");
        }
    }

    synchronized void clear() {
        if (!preferences.edit().remove("peer").commit()) throw new IllegalStateException("无法解除本机配对，请重试。");
    }
}
