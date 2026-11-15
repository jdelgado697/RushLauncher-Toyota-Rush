package cl.rush.launcher;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Obtiene la imagen oficial asociada a la URI de la pista recibida por
 * com.spotify.music.metadatachanged a través del oEmbed público de Spotify.
 * No requiere Spotify Developers, token OAuth ni permisos de notificaciones.
 *
 * Nunca busca carátulas mediante coincidencias de texto: una portada incorrecta
 * resulta peor que mostrar el icono de Spotify como alternativa.
 */
final class SpotifyArtworkFetcher {
    interface Listener {
        void onResult(String trackUri, String thumbnail);
    }

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, String> cache = new LruCache<>(96);
    private final Listener listener;
    private volatile boolean closed;

    SpotifyArtworkFetcher(Listener listener) {
        this.listener = listener;
    }

    static String spotifyWebAddress(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        if (uri.matches("^spotify:(track|episode):[a-zA-Z0-9]{22}$")) {
            String[] pieces = uri.split(":");
            return "https://open.spotify.com/" + pieces[1] + "/" + pieces[2];
        }
        if (uri.matches("^https://open\\.spotify\\.com/(track|episode)/[a-zA-Z0-9]{22}(\\?.*)?$")) {
            String address = uri.split("\\?", 2)[0];
            return address;
        }
        return "";
    }

    void lookup(String trackUri) {
        if (closed) return;
        String webAddress = spotifyWebAddress(trackUri);
        if (webAddress.isEmpty()) {
            main.post(() -> { if (!closed) listener.onResult(trackUri, ""); });
            return;
        }
        network.execute(() -> {
            String cover = cache.get(webAddress);
            if (cover == null) {
                cover = fetch(webAddress);
                // También cachea un fallo para no consultar de nuevo cada segundo.
                cache.put(webAddress, cover);
            }
            final String ready = cover;
            main.post(() -> { if (!closed) listener.onResult(trackUri, ready); });
        });
    }

    private String fetch(String webAddress) {
        HttpURLConnection connection = null;
        try {
            String endpoint = "https://open.spotify.com/oembed?url="
                    + URLEncoder.encode(webAddress, "UTF-8");
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "RushLauncher/3.6 (Android album artwork)");
            if (connection.getResponseCode() != 200) return "";
            byte[] content;
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[2048];
                int count;
                while ((count = in.read(bytes)) != -1) {
                    out.write(bytes, 0, count);
                    if (out.size() > 32000) return "";
                }
                content = out.toByteArray();
            }
            JSONObject result = new JSONObject(new String(content, StandardCharsets.UTF_8));
            String image = result.optString("thumbnail_url", "");
            Uri uri = Uri.parse(image);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return "";
            String host = uri.getHost();
            if (host == null) return "";
            boolean spotifyCdn = host.equals("i.scdn.co")
                    || host.equals("mosaic.scdn.co")
                    || (host.startsWith("image-cdn-") && host.endsWith(".spotifycdn.com"));
            return spotifyCdn ? image : "";
        } catch (Exception ignored) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    void shutdown() {
        closed = true;
        network.shutdownNow();
        cache.evictAll();
    }
}
