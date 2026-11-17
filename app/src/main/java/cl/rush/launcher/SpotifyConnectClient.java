package cl.rush.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Spotify Connect control via Spotify's official Web API / OAuth PKCE.
 *
 * No privileged permission services, passwords,
 * client secrets or private media APIs. Tokens never enter WebView JS.
 * Refresh tokens and pending OAuth verifiers are encrypted at rest using
 * Android Keystore AES-GCM. Requires a Spotify developer Client ID and
 * user authorization (Premium for playback controls).
 */
final class SpotifyConnectClient {
    interface Callback {
        void event(String javascript);
        void openBrowser(Uri uri);
    }

    static final String REDIRECT_URI = "rushlauncherobd49://spotify/callback";
    private static final String AUTH = "https://accounts.spotify.com/authorize";
    private static final String TOKEN = "https://accounts.spotify.com/api/token";
    private static final String PLAYER = "https://api.spotify.com/v1/me/player";
    private static final String PREFS = "rush_spotify_connect";
    private static final String KEY_ALIAS = "rush_spotify_tokens_v1";
    private static final String SCOPES = "user-read-playback-state user-read-currently-playing user-modify-playback-state";

    private final Activity activity;
    private final Callback callback;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();

    private volatile boolean active = false;
    private volatile boolean destroyed = false;
    private volatile boolean lastPlaying = false;
    private volatile String accessToken = "";
    private volatile long tokenExpiry = 0L;
    private volatile long waitUntil = 0L;
    private volatile long lastPollAt = 0L;
    private volatile String statusMessage = "Vincula tu cuenta Spotify en Música";
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!active || destroyed) return;
            refreshPlayback(false);
            main.postDelayed(this, 16000L);
        }
    };

    SpotifyConnectClient(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    private void emit(String event, JSONObject value) {
        if (destroyed) return;
        callback.event("window." + event + "(" + value.toString() + ");");
    }

    private void status(String message) {
        statusMessage = message;
        JSONObject json = new JSONObject();
        try {
            json.put("configured", !prefs.getString("client_id", "").isEmpty());
            json.put("connected", !prefs.getString("refresh_encrypted", "").isEmpty());
            json.put("redirect", REDIRECT_URI);
            json.put("message", statusMessage);
        } catch (Exception ignored) { }
        emit("rushSpotifyConnectState", json);
    }

    void uiReady() {
        status(statusMessage);
    }

    void resume() {
        active = true;
        main.removeCallbacks(poller);
        main.postDelayed(poller, 450L);
    }

    void pause() {
        active = false;
        main.removeCallbacks(poller);
    }

    void destroy() {
        active = false;
        destroyed = true;
        main.removeCallbacks(poller);
        worker.shutdownNow();
    }

    void configure(String candidate) {
        String id = candidate == null ? "" : candidate.trim();
        if (!id.matches("[A-Za-z0-9]{32}")) {
            status("Client ID inválido: debe contener 32 caracteres alfanuméricos");
            return;
        }
        String previous = prefs.getString("client_id", "");
        if (!id.equals(previous)) {
            accessToken = "";
            tokenExpiry = 0;
            prefs.edit().remove("refresh_encrypted").putString("client_id", id).apply();
        }
        status("Client ID guardado. Pulsa Conectar Spotify");
    }

    private String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private String protect(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        byte[] iv = cipher.getIV();
        byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(data, 0, combined, iv.length, data.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private String unprotect(String value) throws Exception {
        if (value == null || value.isEmpty()) return "";
        byte[] combined = Base64.decode(value, Base64.DEFAULT);
        if (combined.length < 29) throw new SecurityException("Invalid encrypted token");
        byte[] iv = new byte[12];
        System.arraycopy(combined, 0, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
    }

    void connect() {
        String clientId = prefs.getString("client_id", "");
        if (!clientId.matches("[A-Za-z0-9]{32}")) {
            status("Primero guarda tu Client ID de Spotify for Developers");
            return;
        }
        try {
            byte[] verifierBytes = new byte[48];
            byte[] stateBytes = new byte[24];
            random.nextBytes(verifierBytes);
            random.nextBytes(stateBytes);
            String verifier = base64Url(verifierBytes);
            String state = base64Url(stateBytes);
            String challenge = base64Url(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            prefs.edit()
                    .putString("pkce_verifier", protect(verifier))
                    .putString("pkce_state", protect(state))
                    .putLong("pkce_time", System.currentTimeMillis())
                    .apply();
            Uri url = Uri.parse(AUTH).buildUpon()
                    .appendQueryParameter("client_id", clientId)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", REDIRECT_URI)
                    .appendQueryParameter("scope", SCOPES)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", challenge)
                    .build();
            callback.openBrowser(url);
            status("Autoriza Spotify en el navegador; regresarás a Rush Launcher");
        } catch (Exception ex) {
            status("No se pudo iniciar Spotify. Comprueba el navegador y seguridad Android");
        }
    }

    boolean handles(Uri uri) {
        return uri != null && "rushlauncherobd49".equals(uri.getScheme())
                && "spotify".equals(uri.getHost()) && "/callback".equals(uri.getPath());
    }

    void handleRedirect(Intent intent) {
        Uri uri = intent == null ? null : intent.getData();
        if (!handles(uri)) return;
        String result = uri.getQueryParameter("error");
        if (result != null) {
            clearPending();
            status("Autorización de Spotify cancelada: " + result);
            return;
        }
        final String code = uri.getQueryParameter("code");
        final String returnedState = uri.getQueryParameter("state");
        try {
            final String expected = unprotect(prefs.getString("pkce_state", ""));
            final String verifier = unprotect(prefs.getString("pkce_verifier", ""));
            long age = System.currentTimeMillis() - prefs.getLong("pkce_time", 0L);
            if (code == null || code.isEmpty() || expected.isEmpty() || verifier.isEmpty()
                    || returnedState == null || !expected.equals(returnedState)
                    || age < 0 || age > 10 * 60 * 1000L) {
                clearPending();
                status("Spotify rechazó la sesión: reintenta la vinculación");
                return;
            }
            clearPending();
            worker.execute(() -> {
                try {
                    String body = "grant_type=authorization_code"
                            + "&client_id=" + enc(prefs.getString("client_id", ""))
                            + "&redirect_uri=" + enc(REDIRECT_URI)
                            + "&code=" + enc(code)
                            + "&code_verifier=" + enc(verifier);
                    ApiResponse response = request("POST", TOKEN, null, body);
                    if (response.code < 200 || response.code >= 300) {
                        status("Spotify rechazó la autorización (HTTP " + response.code + "). Verifica Client ID y Redirect URI");
                        return;
                    }
                    JSONObject token = new JSONObject(response.body);
                    saveTokens(token, false);
                    status("Spotify conectado. Abre Spotify y comienza a reproducir");
                    refreshPlayback(true);
                } catch (Exception ex) {
                    status("No fue posible completar Spotify. Revisa internet y credenciales");
                }
            });
        } catch (Exception ex) {
            clearPending();
            status("No se pudo recuperar la autorización. Reintenta la vinculación");
        }
    }

    private void clearPending() {
        prefs.edit().remove("pkce_verifier").remove("pkce_state").remove("pkce_time").apply();
    }

    private String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private void saveTokens(JSONObject response, boolean refresh) throws Exception {
        String newToken = response.optString("access_token", "");
        if (newToken.isEmpty()) throw new SecurityException("Missing access token");
        accessToken = newToken;
        tokenExpiry = System.currentTimeMillis() +
                Math.max(60, response.optInt("expires_in", 3600)) * 1000L;
        String newRefresh = response.optString("refresh_token", "");
        if (!newRefresh.isEmpty()) {
            prefs.edit().putString("refresh_encrypted", protect(newRefresh)).apply();
        } else if (!refresh && prefs.getString("refresh_encrypted", "").isEmpty()) {
            throw new SecurityException("Missing refresh token");
        }
    }

    private String token() throws Exception {
        if (!accessToken.isEmpty() && System.currentTimeMillis() + 30000 < tokenExpiry)
            return accessToken;
        String encrypted = prefs.getString("refresh_encrypted", "");
        if (encrypted.isEmpty()) return "";
        String refresh = unprotect(encrypted);
        String body = "grant_type=refresh_token"
                + "&client_id=" + enc(prefs.getString("client_id", ""))
                + "&refresh_token=" + enc(refresh);
        ApiResponse response = request("POST", TOKEN, null, body);
        if (response.code != 200) {
            if (response.code == 400 || response.code == 401) {
                accessToken = "";
                tokenExpiry = 0;
                prefs.edit().remove("refresh_encrypted").apply();
                status("Sesión caducada. Pulsa Conectar Spotify nuevamente");
                return "";
            }
            throw new java.io.IOException("HTTP " + response.code);
        }
        saveTokens(new JSONObject(response.body), true);
        return accessToken;
    }

    private static final class ApiResponse {
        final int code;
        final String body;
        final int retryAfter;
        ApiResponse(int code, String body, int retryAfter) {
            this.code = code;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    private ApiResponse request(String method, String address, String bearer, String form) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            if (form != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            String retry = conn.getHeaderField("Retry-After");
            int retryAfter = 30;
            try { if (retry != null) retryAfter = Integer.parseInt(retry); }
            catch (NumberFormatException ignored) { }
            String response = "";
            InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (stream != null) {
                try (InputStream in = stream;
                     ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buff = new byte[2048];
                    int count;
                    while ((count = in.read(buff)) != -1) {
                        bytes.write(buff, 0, count);
                        if (bytes.size() > 128000) throw new java.io.IOException("Respuesta grande");
                    }
                    response = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return new ApiResponse(status, response, retryAfter);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse spotifyRequest(String verb, String url) throws Exception {
        String bearer = token();
        if (bearer.isEmpty()) return new ApiResponse(401, "", 0);
        ApiResponse result = request(verb, url, bearer, null);
        if (result.code == 401) {
            accessToken = "";
            bearer = token();
            if (!bearer.isEmpty()) result = request(verb, url, bearer, null);
        }
        if (result.code == 429) {
            waitUntil = System.currentTimeMillis() + Math.max(30, result.retryAfter) * 1000L;
        }
        return result;
    }

    void refreshPlayback(boolean userRequested) {
        if (prefs.getString("refresh_encrypted", "").isEmpty()) {
            if (userRequested) status("Conecta Spotify en Ajustes de música");
            return;
        }
        if (System.currentTimeMillis() < waitUntil) {
            if (userRequested) status("Límite temporal de Spotify. Espera antes de actualizar");
            return;
        }
        if (!userRequested && System.currentTimeMillis() - lastPollAt < 14000L) return;
        lastPollAt = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                ApiResponse response = spotifyRequest("GET", PLAYER);
                JSONObject state = new JSONObject();
                state.put("connected", true);
                if (response.code == 204 || response.code == 404) {
                    state.put("hasData", false);
                    state.put("message", "Inicia la reproducción en Spotify");
                    emit("rushSpotifyApiPlayback", state);
                    return;
                }
                if (response.code == 403) {
                    status("Spotify requiere Premium y permisos para leer la reproducción");
                    return;
                }
                if (response.code == 429) {
                    status("Spotify limitó las consultas. Se reintentará más tarde");
                    return;
                }
                if (response.code != 200) {
                    status("No se pudo consultar Spotify (HTTP " + response.code + ")");
                    return;
                }
                JSONObject data = new JSONObject(response.body);
                JSONObject item = data.optJSONObject("item");
                if (item == null) {
                    state.put("hasData", false);
                } else {
                    state.put("hasData", true);
                    state.put("track", item.optString("name", "Sin título"));
                    JSONArray artists = item.optJSONArray("artists");
                    String artist = "";
                    if (artists != null) {
                        for (int i = 0; i < artists.length(); i++) {
                            JSONObject a = artists.optJSONObject(i);
                            if (a != null) artist += (artist.isEmpty() ? "" : ", ") + a.optString("name", "");
                        }
                    }
                    if (artist.isEmpty()) artist = item.optString("publisher", "");
                    state.put("artist", artist);
                    state.put("duration", item.optLong("duration_ms", 0));
                    state.put("position", data.optLong("progress_ms", 0));
                    state.put("album", item.optJSONObject("album") == null
                            ? "" : item.optJSONObject("album").optString("name", ""));
                    JSONObject album = item.optJSONObject("album");
                    if (album != null) {
                        JSONArray images = album.optJSONArray("images");
                        JSONObject image = images == null ? null : images.optJSONObject(0);
                        if (image != null) {
                            String cover = image.optString("url", "");
                            if (cover.startsWith("https://i.scdn.co/")
                                    || cover.startsWith("https://mosaic.scdn.co/")) state.put("cover", cover);
                        }
                    }
                }
                lastPlaying = data.optBoolean("is_playing", false);
                state.put("playing", lastPlaying);
                JSONObject device = data.optJSONObject("device");
                state.put("device", device == null ? "" : device.optString("name", ""));
                state.put("source", "api");
                state.put("app", "Spotify");
                state.put("package", "com.spotify.music");
                emit("rushSpotifyApiPlayback", state);
            } catch (Exception ex) {
                status("Error de conexión Spotify. Comprueba internet");
            }
        });
    }

    void command(String command) {
        if (prefs.getString("refresh_encrypted", "").isEmpty()) {
            status("Primero vincula Spotify para activar los controles");
            return;
        }
        if (System.currentTimeMillis() < waitUntil) {
            status("Spotify limitó las solicitudes. Prueba más tarde");
            return;
        }
        String verb;
        String target;
        switch (command) {
            case "toggle":
                verb = "PUT";
                target = lastPlaying ? "/pause" : "/play";
                break;
            case "next":
                verb = "POST";
                target = "/next";
                break;
            case "previous":
                verb = "POST";
                target = "/previous";
                break;
            default: return;
        }
        worker.execute(() -> {
            try {
                ApiResponse r = spotifyRequest(verb, PLAYER + target);
                if (r.code == 204 || r.code == 202) {
                    lastPollAt = 0;
                    main.postDelayed(() -> refreshPlayback(true), 750);
                } else if (r.code == 403) {
                    status("Spotify Premium es necesario para controlar reproducción");
                } else if (r.code == 404) {
                    status("Abre Spotify en el TBox y activa un dispositivo de reproducción");
                } else {
                    status("Spotify no pudo ejecutar comando (HTTP " + r.code + ")");
                }
            } catch (Exception ex) { status("No se pudo comunicar con Spotify"); }
        });
    }

    void seek(long ms) {
        if (ms < 0 || ms > 86400000L) return;
        if (prefs.getString("refresh_encrypted", "").isEmpty()) return;
        worker.execute(() -> {
            try {
                ApiResponse r = spotifyRequest("PUT", PLAYER + "/seek?position_ms=" + ms);
                if (r.code == 204) {
                    lastPollAt = 0;
                    main.postDelayed(() -> refreshPlayback(true), 700);
                } else status("No se pudo cambiar la posición (HTTP " + r.code + ")");
            } catch (Exception ex) { status("No se pudo avanzar la canción"); }
        });
    }

    void disconnect() {
        accessToken = "";
        tokenExpiry = 0L;
        lastPlaying = false;
        prefs.edit().remove("refresh_encrypted").remove("pkce_verifier")
                .remove("pkce_state").remove("pkce_time").apply();
        JSONObject empty = new JSONObject();
        try { empty.put("connected", false); empty.put("hasData", false); }
        catch (Exception ignored) { }
        emit("rushSpotifyApiPlayback", empty);
        status("Spotify desconectado; controles multimedia Android siguen disponibles");
    }
}
