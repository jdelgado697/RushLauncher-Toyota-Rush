package cl.rush.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.LruCache;
import android.media.AudioManager;
import android.net.Uri;
import android.bluetooth.BluetoothAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.speech.tts.TextToSpeech;

import com.google.android.gms.security.ProviderInstaller;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.List;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;

/**
 * Rush Launcher: interfaz local sobre Android WebView en TBox Lite S.
 * No modifica firmware Toyota ni controla la camara o la radio FM original.
 */
public class MainActivity extends Activity implements LocationListener {

    private static final int LOCATION_REQUEST_CODE = 1101;
    private static final int BLUETOOTH_REQUEST_CODE = 1102;
    private ObdReader obdReader;
    private SpotifyConnectClient spotifyConnect;
    private SpotifyArtworkFetcher spotifyArtwork;
    private TextToSpeech navSpeech;
    private boolean navSpeechReady = false;
    private String pendingNavSpeech = null;
    private WebView webView;
    private LocationManager locationManager;
    private boolean gpsRequested = false; // Solicitud manual del usuario.
    private boolean mapGpsActive = false; // GPS temporal del MAPA 2D.
    private boolean weatherGpsActive = false; // Ubicación opt-in del clima.
    // Filtros de antigüedad y prioridad GPS para no alternar con red imprecisa.
    private long lastFixElapsedNs = 0L;
    private long lastAccurateGpsNs = 0L;
    private boolean pageLoaded = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService weatherExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService navigationExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean tlsProviderChecked = false;
    private volatile String tlsProviderStatus = "Sin comprobar";
    private final ExecutorService suggestionsExecutor = Executors.newSingleThreadExecutor();
    private volatile int latestSuggestionRequest = 0;
    private volatile long lastSuggestionFetchAt = 0L;
    private volatile long lastRouteRequest = 0L;
    private volatile long lastSearchRequest = 0L;
    // Iconos del PackageManager, sin descargas ni archivos externos.
    private final ExecutorService iconExecutor = Executors.newSingleThreadExecutor();
    private final LruCache<String, String> appIconCache = new LruCache<>(128);
    private volatile long lastWeatherRequest = 0L;
    private volatile double lastWeatherLat = 999.0;
    private volatile double lastWeatherLon = 999.0;
    private volatile String lastWeatherPayload = "";
    // Spotify ofrece emisiones opt-in sin acceso a notificaciones ni datos privados.
    private static final String SPOTIFY_METADATA = "com.spotify.music.metadatachanged";
    private static final String SPOTIFY_PLAYBACK = "com.spotify.music.playbackstatechanged";
    private String spotifyTrack = "";
    private String spotifyArtist = "";
    private String spotifyAlbum = "";
    private String spotifyTrackUri = "";
    private String spotifyCover = "";
    private long spotifyDurationMs = 0;
    private long spotifyPositionMs = 0;
    private long spotifyPositionReceivedAt = 0;
    private Boolean spotifyPlaying = null;
    private boolean spotifyReceiverRegistered = false;

    private final BroadcastReceiver spotifyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            long eventTime = intent.getLongExtra("timeSent", 0);
            if (eventTime != 0 && Math.abs(System.currentTimeMillis() - eventTime) > 120000L) {
                // Evitar mostrar notificaciones pegajosas antiguas como reproducción actual.
                return;
            }
            String action = intent.getAction();
            if (SPOTIFY_METADATA.equals(action)) {
                spotifyTrack = intent.getStringExtra("track") == null ? "" : intent.getStringExtra("track");
                spotifyArtist = intent.getStringExtra("artist") == null ? "" : intent.getStringExtra("artist");
                spotifyAlbum = intent.getStringExtra("album") == null ? "" : intent.getStringExtra("album");
                String incomingUri = intent.getStringExtra("id");
                if (incomingUri == null) incomingUri = "";
                if (!incomingUri.equals(spotifyTrackUri)) {
                    spotifyTrackUri = incomingUri;
                    spotifyCover = "";
                    if (spotifyArtwork != null) spotifyArtwork.lookup(spotifyTrackUri);
                }
                // Spotify documenta length en segundos (Integer).
                spotifyDurationMs = Math.max(0, intent.getIntExtra("length", 0)) * 1000L;
                spotifyPositionMs = 0L;
                spotifyPositionReceivedAt = System.currentTimeMillis();
            } else if (SPOTIFY_PLAYBACK.equals(action)) {
                spotifyPlaying = intent.getBooleanExtra("playing", false);
                spotifyPositionMs = Math.max(0L, intent.getIntExtra("playbackPosition", 0));
                spotifyPositionReceivedAt = System.currentTimeMillis();
            } else return;
            sendSpotifyState();
        }
    };

    private void registerSpotifyReceiver() {
        if (spotifyReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(SPOTIFY_METADATA);
            filter.addAction(SPOTIFY_PLAYBACK);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(spotifyReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(spotifyReceiver, filter);
            }
            spotifyReceiverRegistered = true;
        } catch (RuntimeException ignored) {
            spotifyReceiverRegistered = false;
        }
    }

    private void sendSpotifyState() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("track", spotifyTrack);
            payload.put("artist", spotifyArtist);
            payload.put("album", spotifyAlbum);
            payload.put("trackUri", spotifyTrackUri);
            payload.put("cover", spotifyCover);
            payload.put("playing", spotifyPlaying == null ? JSONObject.NULL : spotifyPlaying.booleanValue());
            payload.put("hasData", !spotifyTrack.isEmpty() || spotifyPlaying != null);
            payload.put("app", "Spotify");
            payload.put("source", "broadcast");
            payload.put("package", "com.spotify.music");
            payload.put("duration", spotifyDurationMs);
            payload.put("position", spotifyPositionMs);
            payload.put("updatedAt", spotifyPositionReceivedAt);
        } catch (Exception ignored) { }
        javascript("window.rushSpotifyState(" + payload.toString() + ");");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        spotifyArtwork = new SpotifyArtworkFetcher((uri, coverUrl) -> {
            // Evita que una respuesta de otra canción sustituya la imagen actual.
            if (uri != null && uri.equals(spotifyTrackUri)) {
                spotifyCover = coverUrl;
                sendSpotifyState();
            }
        });
        registerSpotifyReceiver();
        spotifyConnect = new SpotifyConnectClient(this, new SpotifyConnectClient.Callback() {
            @Override public void event(String expression) {
                javascript(expression);
            }
            @Override public void openBrowser(Uri uri) {
                try {
                    Intent browser = new Intent(Intent.ACTION_VIEW, uri);
                    browser.addCategory(Intent.CATEGORY_BROWSABLE);
                    startActivity(browser);
                } catch (Exception error) {
                    message("No hay navegador compatible para autorizar Spotify.");
                }
            }
        });
        if (spotifyConnect.handles(getIntent().getData())) {
            spotifyConnect.handleRedirect(getIntent());
        }
        obdReader = new ObdReader(new ObdReader.Listener() {
            @Override public void onStatus(String status) {
                javascript("window.rushObdStatus(" + JSONObject.quote(status) + ");");
            }
            @Override public void onData(JSONObject snapshot) {
                javascript("window.rushObdData(" + snapshot.toString() + ");");
            }
        });
        webView = new WebView(this);
        webView.setBackgroundColor(0xFFE9EDF4);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        // Identificación de la app y caché estándar para los mosaicos OSM.
        settings.setUserAgentString(settings.getUserAgentString()
                + " RushLauncher/4.13.0 (+https://github.com/jdelgado697/RushLauncher-Toyota-Rush)");
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.addJavascriptInterface(new RushBridge(), "Rush");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                // Modo multimedia: sin solicitud de GPS al iniciar ni mapa activo.
                // El GPS se inicia solo al pulsar Activar clima o Solicitar GPS.
                sendSpotifyState();
                sendHomeState();
                
                dispatchExternalNavigation();
                if (spotifyConnect != null) spotifyConnect.uiReady();
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void runUi(Runnable task) {
        mainHandler.post(task);
    }

    private void javascript(String expression) {
        runUi(() -> {
            if (webView != null && pageLoaded) {
                webView.evaluateJavascript(expression, null);
            }
        });
    }

    private void sendGpsStatus(String value) {
        javascript("window.rushGpsStatus(" + JSONObject.quote(value) + ");");
    }

    private void message(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private void launchPackage(String packageName, String displayName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                return;
            } catch (Exception ignored) {
                // El fabricante puede restringir actividades de aplicaciones externas.
            }
        }
        message(displayName + " no esta disponible en este Android Box");
    }

    private void launchAutoKit() {
        String[] candidates = {
                "cn.manstep.phonemirrorBox",
                "cn.manstep.phonemirrorBox2",
                "com.carlinkit.autokit",
                "com.zjinnova.zlink",
                "com.suding.speedplay"
        };
        for (String pkg : candidates) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                try {
                    startActivity(intent);
                    return;
                } catch (Exception ignored) { }
            }
        }
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> apps =
                getPackageManager().queryIntentActivities(query, 0);
        for (android.content.pm.ResolveInfo app : apps) {
            CharSequence label = app.loadLabel(getPackageManager());
            String name = label == null ? "" : label.toString().toLowerCase();
            if (name.contains("autokit") || name.contains("zlink")) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setClassName(app.activityInfo.packageName, app.activityInfo.name);
                try {
                    startActivity(intent);
                    return;
                } catch (Exception ignored) { }
            }
        }
        message("No se encontro AutoKit. Regresa al inicio del TBox para abrirlo.");
    }

    /**
     * Lista únicamente apps con pantalla de inicio (MAIN/LAUNCHER).
     * Sin QUERY_ALL_PACKAGES ni acceso a datos sensibles.
     */
    private List<android.content.pm.ResolveInfo> launcherApps() {
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        return getPackageManager().queryIntentActivities(query, 0);
    }

    private static final String[] AUTOKIT_PACKAGES = {
            "cn.manstep.phonemirrorBox",
            "cn.manstep.phonemirrorBox2",
            "com.carlinkit.autokit",
            "com.zjinnova.zlink",
            "com.suding.speedplay"
    };
    private static final String[] MAP_PACKAGES = {
            "com.google.android.apps.maps",
            "com.google.android.apps.mapslite",
            "app.organicmaps",
            "net.osmand",
            "com.here.app.maps"
    };

    /** Devuelve un ícono real del launcher, no un logo inventado. */
    private String pngIcon(android.content.pm.ResolveInfo info) {
        if (info == null || info.activityInfo == null) return "";
        String key = info.activityInfo.packageName + "/" + info.activityInfo.name;
        String cached = appIconCache.get(key);
        if (cached != null) return cached;
        String data = "";
        try {
            Drawable drawable = info.loadIcon(getPackageManager());
            if (drawable != null) {
                // 64×64 evita enviar bitmaps gigantes al WebView 800×480.
                Bitmap bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                drawable.setBounds(0, 0, 64, 64);
                drawable.draw(canvas);
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                if (bmp.compress(Bitmap.CompressFormat.PNG, 90, encoded)) {
                    data = "data:image/png;base64," +
                            Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP);
                }
                bmp.recycle();
            }
        } catch (Exception ignored) { }
        appIconCache.put(key, data);
        return data;
    }

    private android.content.pm.ResolveInfo findLauncherApp(
            List<android.content.pm.ResolveInfo> apps, String name) {
        for (android.content.pm.ResolveInfo info : apps) {
            if (info.activityInfo != null && name.equals(info.activityInfo.packageName)) {
                return info;
            }
        }
        return null;
    }

    private android.content.pm.ResolveInfo firstLauncherApp(
            List<android.content.pm.ResolveInfo> apps, String[] packages) {
        for (String pkg : packages) {
            android.content.pm.ResolveInfo candidate = findLauncherApp(apps, pkg);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private android.content.pm.ResolveInfo findAutoKit(
            List<android.content.pm.ResolveInfo> apps) {
        android.content.pm.ResolveInfo preferred = firstLauncherApp(apps, AUTOKIT_PACKAGES);
        if (preferred != null) return preferred;
        for (android.content.pm.ResolveInfo app : apps) {
            if (app.activityInfo == null) continue;
            CharSequence title = app.loadLabel(getPackageManager());
            String label = title == null ? "" :
                    title.toString().toLowerCase(java.util.Locale.ROOT);
            if (label.contains("autokit") || label.contains("zlink")) return app;
        }
        return null;
    }

    private JSONObject describeApp(android.content.pm.ResolveInfo info) throws Exception {
        JSONObject result = new JSONObject();
        String name = String.valueOf(info.loadLabel(getPackageManager()));
        result.put("name", name.trim().isEmpty() ? info.activityInfo.packageName : name);
        result.put("package", info.activityInfo.packageName);
        String image = pngIcon(info);
        if (!image.isEmpty()) result.put("icon", image);
        return result;
    }

    /**
     * Actualiza los íconos del dock desde el dispositivo; si la app no existe
     * no se suministra un logo, por lo que queda el símbolo neutral del botón.
     */
    private void sendDockIcons() {
        iconExecutor.execute(() -> {
            JSONArray dock = new JSONArray();
            try {
                List<android.content.pm.ResolveInfo> apps = launcherApps();
                android.content.pm.ResolveInfo[] choices = {
                        firstLauncherApp(apps, MAP_PACKAGES),
                        findLauncherApp(apps, "com.spotify.music"),
                        findAutoKit(apps)
                };
                String[] actions = {"route", "spotify", "autokit"};
                for (int n = 0; n < choices.length; n++) {
                    if (choices[n] == null) continue;
                    JSONObject item = describeApp(choices[n]);
                    item.put("action", actions[n]);
                    dock.put(item);
                }
            } catch (Exception ignored) { }
            javascript("window.rushDockApps(" + dock.toString() + ");");
        });
    }

    private void sendInstalledApps() {
        iconExecutor.execute(() -> {
            JSONArray result = new JSONArray();
            try {
                List<android.content.pm.ResolveInfo> apps = launcherApps();
                java.util.Collections.sort(apps, (a, b) -> {
                    String first = String.valueOf(a.loadLabel(getPackageManager()));
                    String second = String.valueOf(b.loadLabel(getPackageManager()));
                    return first.compareToIgnoreCase(second);
                });
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (android.content.pm.ResolveInfo info : apps) {
                    if (info.activityInfo == null) continue;
                    String pkg = info.activityInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                    result.put(describeApp(info));
                }
            } catch (Exception ignored) { }
            javascript("window.rushInstalledApps(" + result.toString() + ");");
        });
    }

    private void openInstalledApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        // Comprobar que el paquete aparece entre los lanzadores accesibles.
        for (android.content.pm.ResolveInfo app : launcherApps()) {
            if (app.activityInfo == null || !pkg.equals(app.activityInfo.packageName)) continue;
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setClassName(app.activityInfo.packageName, app.activityInfo.name);
            try {
                startActivity(intent);
                return;
            } catch (Exception e) {
                break;
            }
        }
        message("No se pudo abrir la aplicación seleccionada");
    }

    /**
     * Navegación sin depender de Waze.
     * Google Maps instalado -> navegador instalado -> sitio Maps mediante ACTION_VIEW.
     * Los servicios web de rutas requieren internet.
     */
    private static final String SPOTIFY_PLAYLIST_ID = "6jaGMO7U1463pu2nSX2V6v";

    /** Abrir la playlist del usuario en Spotify oficial, o en navegador si no está. */
    private void openSpotifyPlaylist() {
        try {
            Intent nativeSpotify = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("spotify:playlist:" + SPOTIFY_PLAYLIST_ID));
            nativeSpotify.setPackage("com.spotify.music");
            startActivity(nativeSpotify);
            return;
        } catch (Exception ignored) {
            // Algunos Android Box no exponen este intent.
        }
        try {
            Intent webSpotify = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://open.spotify.com/playlist/" + SPOTIFY_PLAYLIST_ID));
            webSpotify.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(webSpotify);
        } catch (Exception error) {
            message("No se encontró Spotify ni navegador compatible en el TBox");
        }
    }

    private void openSpotifyWeb() {
        try {
            Intent site = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://open.spotify.com/"));
            site.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(site);
            message("Spotify no está instalado. Abriendo versión web; el control externo puede no funcionar.");
        } catch (Exception ex) {
            message("Spotify no está disponible. Comprueba las apps instaladas.");
        }
    }

    private void openMaps(String destination) {
        String dest = destination == null ? "" : destination.trim();
        if (dest.length() > 180) dest = dest.substring(0, 180);
        if (dest.isEmpty()) {
            String[] candidates = {
                    "com.google.android.apps.maps",
                    "com.google.android.apps.mapslite",
                    "app.organicmaps",
                    "net.osmand",
                    "com.here.app.maps"
            };
            for (String pkg : candidates) {
                Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent == null) continue;
                try { startActivity(intent); return; }
                catch (Exception ignored) { }
            }
        }
        try {
            String url = dest.isEmpty() ? "https://www.google.com/maps" :
                    "https://www.google.com/maps/dir/?api=1&travelmode=driving&destination="
                    + java.net.URLEncoder.encode(dest, "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ex) {
            message("No hay navegador o Maps compatible. Abre Apps para buscar otro.");
        }
    }

    private void openRoute(String action) {
        switch (action) {
            case "spotifyPlaylist":
                openSpotifyPlaylist();
                break;
            case "spotify":
                Intent installedSpotify = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
                if (installedSpotify != null) {
                    try { startActivity(installedSpotify); }
                    catch (Exception ex) { openSpotifyWeb(); }
                } else openSpotifyWeb();
                break;
            case "maps":
            case "waze":
                openMaps("");
                break;
            case "youtube":
                launchPackage("com.google.android.youtube", "YouTube");
                break;
            case "netflix":
                launchPackage("com.netflix.mediaclient", "Netflix");
                break;
            case "phone":
            case "autokit":
                launchAutoKit();
                break;
            case "bluetoothSettings":
                openBluetoothSettings();
                break;
            case "settings":
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception ex) {
                    message("No se pueden abrir los ajustes de Android");
                }
                break;
            case "radio":
                message("La radio FM se utiliza desde la pantalla original Toyota");
                break;
            case "enableHome":
                enableHomeOption();
                break;
            case "exit":
                exitToOriginalHome();
                break;
            default:
                message("Accion no disponible: " + action);
        }
    }

    private void setHomeOption(boolean enabled) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, "cl.rush.launcher.obd413.HomeAlias"),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo resolved =
                    getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved != null && resolved.activityInfo != null
                    && getPackageName().equals(resolved.activityInfo.packageName);
        } catch (Exception ignored) { return false; }
    }

    private void sendHomeState() {
        boolean available = false;
        try {
            int enabled = getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, "cl.rush.launcher.obd413.HomeAlias"));
            available = enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception ignored) { }
        javascript("window.rushHomeState({available:" + available
                + ",selected:" + isDefaultHome() + "});");
    }

    private void enableHomeOption() {
        try {
            setHomeOption(true);
            sendHomeState();
            // Android muestra la elección si el firmware soporta apps HOME.
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (Exception ignored) {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
            }
        } catch (Exception ex) {
            message("El firmware del TBox no ofrece la selección de inicio. No se modificó el sistema Toyota.");
        }
    }


    private void dispatchExternalNavigation() {
        Intent incoming = getIntent();
        if (incoming == null || !incoming.hasExtra("rush_open_apps") || !pageLoaded) return;
        boolean openApps = incoming.getBooleanExtra("rush_open_apps", false);
        incoming.removeExtra("rush_open_apps");
        javascript("if(window.rushExternalNav)window.rushExternalNav("
                + (openApps ? "'apps'" : "'home'") + ");");
    }

    private void exitToOriginalHome() {
        // Deshabilitamos el alias HOME para que no se vuelva a abrir Rush al salir.
        try { setHomeOption(false); } catch (Exception ignored) { }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Exception ignored) { }
        finish();
    }

    @SuppressWarnings("MissingPermission")
    private boolean bluetoothAvailable() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"},
                    BLUETOOTH_REQUEST_CODE);
            javascript("window.rushObdStatus('Autoriza dispositivos cercanos para OBD-II');");
            return false;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            javascript("window.rushObdStatus('Activa Bluetooth y vincula un ELM327 desde Ajustes');");
            return false;
        }
        return true;
    }

    private void showPairedObd() {
        if (!bluetoothAvailable()) return;
        try {
            javascript("window.rushObdDevices("
                    + obdReader.pairedDevices().toString() + ");");
        } catch (SecurityException ignored) {
            javascript("window.rushObdStatus('Bluetooth sin permisos');");
        }
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception ex) {
            message("Abre Bluetooth desde Ajustes Android");
        }
    }

    private void mediaKey(String command) {
        int code;
        switch (command) {
            case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "toggle":
                code = spotifyPlaying == null ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        : (spotifyPlaying ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            default: return;
        }
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            message("El sistema Android no permite controlar la música");
            return;
        }
        try {
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (RuntimeException ex) {
            message("Abre Spotify e inicia la reproducción desde allí");
        }
    }

    /**
     * Clima únicamente a petición del usuario. Se comparte la ubicación
     * aproximada con Open-Meteo (HTTPS), sin registrarla en el dispositivo.
     * Se reutiliza el resultado durante 15 minutos cerca de la misma posición.
     */
    private void fetchWeather(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            javascript("window.rushWeather({ok:false,message:'Ubicación inválida'});");
            return;
        }
        if (!lastWeatherPayload.isEmpty()
                && System.currentTimeMillis() - lastWeatherRequest < 900000L
                && Math.abs(lat - lastWeatherLat) < .07
                && Math.abs(lon - lastWeatherLon) < .07) {
            javascript("window.rushWeather(" + lastWeatherPayload + ");");
            return;
        }
        weatherExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String endpoint = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&forecast_days=1&timezone=auto";
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(6500);
                connection.setReadTimeout(6500);
                connection.setUseCaches(true);
                if (connection.getResponseCode() != 200) {
                    throw new java.io.IOException("HTTP " + connection.getResponseCode());
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[2048];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                        if (output.size() > 65536) throw new java.io.IOException("Respuesta demasiado grande");
                    }
                }
                JSONObject data = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("current", data.getJSONObject("current"));
                result.put("daily", data.getJSONObject("daily"));
                String payload = result.toString();
                lastWeatherPayload = payload;
                lastWeatherRequest = System.currentTimeMillis();
                lastWeatherLat = lat;
                lastWeatherLon = lon;
                javascript("window.rushWeather(" + payload + ");");
            } catch (Exception e) {
                javascript("window.rushWeather({ok:false,message:'Clima no disponible. Comprueba internet y GPS'});");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }


    // Photon admite sugerencias mientras se escribe; Nominatim solo búsqueda
    // explícita, tal como exige la política del servidor público de Nominatim.
    // Los dos servicios pueden limitar solicitudes; no ofrecen SLA.
    private static final String RUSH_NAV_UA =
            "RushLauncher/4.9 (+https://github.com/jdelgado697/RushLauncher-Toyota-Rush)";
    private static boolean validCoord(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }
    private static String getNavText(String url, int maxBytes) throws Exception {
        return getNavText(url,maxBytes,8500,10000);
    }
    // Los servidores públicos de demostración pueden tener latencias distintas.
    private static String getNavText(String url,int maxBytes,int connectMs,int readMs)
            throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", RUSH_NAV_UA);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Language", "es");
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(true);
            if(c.getResponseCode()!=200)
                throw new java.io.IOException("Servidor HTTP " + c.getResponseCode());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=in.read(buffer))!=-1){
                    output.write(buffer,0,count);
                    if(output.size()>maxBytes)
                        throw new java.io.IOException("Respuesta de navegación demasiado grande");
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }finally{if(c!=null)c.disconnect();}
    }
    private void navReply(String method, JSONObject value) {
        javascript("if(window."+method+")window."+method+"("+value.toString()+");");
    }
    private static JSONObject navError(String message) {
        JSONObject result=new JSONObject();
        try{result.put("ok",false);result.put("message",message);}catch(Exception ignored){}
        return result;
    }
    // Photon está diseñado para autocompletado. Prioriza Chile por COUNTRYCODE
    // y luego un área local en torno al GPS del TBox. Si no encuentra nada
    // cerca, amplía una sola vez al país, sin sugerir otros países.
    private static JSONArray photonFeatures(String q, boolean local,
                                             double lat, double lon) throws Exception {
        String url = "https://photon.komoot.io/api/?q="
                + URLEncoder.encode(q,"UTF-8")
                + "&limit=10&lang=es&countrycode=CL";
        if(local){
            // Caja aprox. 100 km a lo largo de la latitud y 100–150 km
            // de longitud; se usa solo para priorizar resultados cercanos.
            double west=Math.max(-180,lon-1.2),east=Math.min(180,lon+1.2);
            double south=Math.max(-90,lat-.85),north=Math.min(90,lat+.85);
            url += String.format(Locale.US,"&bbox=%.5f,%.5f,%.5f,%.5f",
                    west,south,east,north);
            url += String.format(Locale.US,
                    "&lat=%.6f&lon=%.6f&zoom=12&location_bias_scale=0.8",
                    lat,lon);
        } else if(validCoord(lat,lon) && !(lat==0&&lon==0)){
            url += String.format(Locale.US,
                    "&lat=%.6f&lon=%.6f&zoom=11&location_bias_scale=0.7",
                    lat,lon);
        }
        JSONObject root=new JSONObject(getNavText(url,160000));
        JSONArray features=root.optJSONArray("features");
        return features==null?new JSONArray():features;
    }
    private static JSONArray suggestionsFromFeatures(JSONArray features,int limit)
            throws Exception {
        JSONArray rows=new JSONArray();
        for(int i=0;i<features.length() && rows.length()<limit;i++){
            JSONObject item=features.optJSONObject(i);
            if(item==null)continue;
            JSONObject props=item.optJSONObject("properties");
            JSONObject geometry=item.optJSONObject("geometry");
            if(props==null||geometry==null)continue;
            String cc=props.optString("countrycode","").toLowerCase(Locale.ROOT);
            // Se descartan países extranjeros incluso si el proveedor
            // llegara a omitir/aplicar incorrectamente el filtro.
            if(cc.length()>0 && !"cl".equals(cc))continue;
            JSONArray coords=geometry.optJSONArray("coordinates");
            if(coords==null||coords.length()<2)continue;
            double lon=coords.optDouble(0,Double.NaN);
            double lat=coords.optDouble(1,Double.NaN);
            if(!validCoord(lat,lon))continue;
            String name=props.optString("name","").trim();
            if(name.length()==0)name=props.optString("street","").trim();
            if(name.length()==0)name=props.optString("city","").trim();
            if(name.length()==0)continue;
            StringBuilder address=new StringBuilder();
            for(String key:new String[]{"housenumber","street","district","city","county","state"}){
                String piece=props.optString(key,"").trim();
                if(piece.length()==0||piece.equalsIgnoreCase(name))continue;
                if(address.length()+piece.length()>110)continue;
                if(address.length()>0)address.append(", ");
                address.append(piece);
            }
            JSONObject row=new JSONObject();
            row.put("lat",lat);row.put("lon",lon);
            row.put("name",name);row.put("detail",address.toString()+", Chile");
            rows.put(row);
        }
        return rows;
    }
    // Como los servidores públicos pueden ser lentos, se muestran
    // inmediatamente los destinos recientes y un indicador de búsqueda.
    // Se limita el envío en segundo plano a 1 búsqueda cada 1.2 segundos.
    private void suggestNavigationDestination(String query, double biasLat,
                                              double biasLon, int requestId) {
        if(query==null||query.trim().length()<3||query.length()>100||requestId<=0)return;
        latestSuggestionRequest=requestId;
        final String q=query.trim();
        final boolean near=validCoord(biasLat,biasLon)&&!(biasLat==0&&biasLon==0)
                && biasLat >= -56.5 && biasLat <= -17 && biasLon >= -76 && biasLon <= -66;
        suggestionsExecutor.execute(()->{
            if(requestId!=latestSuggestionRequest)return;
            try{
                long delay=1200L-(System.currentTimeMillis()-lastSuggestionFetchAt);
                if(delay>0)Thread.sleep(delay);
                if(requestId!=latestSuggestionRequest)return;
                lastSuggestionFetchAt=System.currentTimeMillis();
                JSONArray nearby=photonFeatures(q,near,biasLat,biasLon);
                JSONArray rows=suggestionsFromFeatures(nearby,5);
                if(rows.length()==0 && near && requestId==latestSuggestionRequest) {
                    // Búsquedas de ciudades de otra región siguen funcionando.
                    rows=suggestionsFromFeatures(
                            photonFeatures(q,false,biasLat,biasLon),5);
                }
                if(requestId!=latestSuggestionRequest)return;
                JSONObject answer=new JSONObject();
                answer.put("ok",true);answer.put("requestId",requestId);
                answer.put("query",q);answer.put("items",rows);
                navReply("rushNavSuggest",answer);
            }catch(InterruptedException error){
                Thread.currentThread().interrupt();
            }catch(Exception error){
                if(requestId!=latestSuggestionRequest)return;
                JSONObject failure=navError(
                        "La búsqueda de Chile no respondió. Intenta Buscar.");
                try{failure.put("requestId",requestId);failure.put("query",q);}
                catch(Exception ignored){}
                navReply("rushNavSuggest",failure);
            }
        });
    }
    private void searchNavigationDestination(String query) {
        searchNavigationDestination(query,0,0);
    }
    private void searchNavigationDestination(String query,double focusLat,double focusLon) {
        if(query==null || query.trim().length()<3 || query.length()>140){
            navReply("rushNavSearch",navError("Escribe una dirección o lugar."));
            return;
        }
        long now=System.currentTimeMillis();
        if(now-lastSearchRequest<1800L){
            navReply("rushNavSearch",navError("Espera un momento antes de buscar de nuevo."));
            return;
        }
        lastSearchRequest=now;
        final String q=query.trim();
        final boolean near=validCoord(focusLat,focusLon)
                && focusLat>=-56.5 && focusLat<=-17
                && focusLon>=-76 && focusLon<=-66;
        navigationExecutor.execute(()->{
            try{
                String url="https://nominatim.openstreetmap.org/search"
                        +"?format=jsonv2&limit=6&addressdetails=0&countrycodes=cl&q="
                        +URLEncoder.encode(q,"UTF-8");
                if(near)url+=String.format(Locale.US,
                        "&viewbox=%.5f,%.5f,%.5f,%.5f",
                        focusLon-.75,focusLat+.6,focusLon+.75,focusLat-.6);
                JSONArray response=new JSONArray(getNavText(url,160000));
                JSONArray items=new JSONArray();
                for(int i=0;i<Math.min(6,response.length());i++){
                    JSONObject hit=response.getJSONObject(i);
                    double lat=Double.parseDouble(hit.getString("lat"));
                    double lon=Double.parseDouble(hit.getString("lon"));
                    if(!validCoord(lat,lon))continue;
                    JSONObject row=new JSONObject();
                    row.put("lat",lat);row.put("lon",lon);
                    row.put("name",hit.optString("display_name","Destino"));
                    items.put(row);
                }
                JSONObject result=new JSONObject();
                result.put("ok",true);result.put("items",items);
                navReply("rushNavSearch",result);
            }catch(Exception ignored){
                navReply("rushNavSearch",navError(
                    "No se pudo buscar en Chile. Verifica internet o selecciona un punto."));
            }
        });
    }
    // Los servidores de OSRM y Valhalla son demostrativos, sin SLA.
    // Ambas APIs devuelven JSON OSRM, Valhalla con format=osrm.
    // Servidor FOSSGIS público para uso ligero a petición del usuario.
    private static JSONObject retrieveFossgisRoute(double aLat,double aLon,double bLat,
                                                    double bLon) throws Exception {
        String coords=String.format(Locale.US,"%.6f,%.6f;%.6f,%.6f",
                aLon,aLat,bLon,bLat);
        String url="https://routing.openstreetmap.de/routed-car/route/v1/driving/"
                +coords+"?overview=simplified&geometries=geojson&steps=true";
        return new JSONObject(getNavText(url,550000,4800,7000));
    }
    private static JSONObject retrieveOsrmRoute(double aLat,double aLon,double bLat,
                                                 double bLon) throws Exception {
        String coords=String.format(Locale.US,"%.6f,%.6f;%.6f,%.6f",
                aLon,aLat,bLon,bLat);
        String url="https://router.project-osrm.org/route/v1/driving/"
                +coords+"?overview=simplified&geometries=geojson&steps=true";
        return new JSONObject(getNavText(url,550000,4500,7000));
    }
    private static JSONObject retrieveValhallaRoute(double aLat,double aLon,double bLat,
                                                    double bLon) throws Exception {
        JSONArray locations=new JSONArray();
        JSONObject start=new JSONObject();start.put("lat",aLat);start.put("lon",aLon);
        JSONObject dest=new JSONObject();dest.put("lat",bLat);dest.put("lon",bLon);
        locations.put(start);locations.put(dest);
        JSONObject request=new JSONObject();
        request.put("locations",locations);
        request.put("costing","auto");
        request.put("format","osrm");
        request.put("shape_format","geojson");
        request.put("directions_type","instructions");
        request.put("language","es-ES");
        request.put("units","kilometers");
        String url="https://valhalla1.openstreetmap.de/route?json="
                +URLEncoder.encode(request.toString(),"UTF-8");
        return new JSONObject(getNavText(url,700000,6500,10000));
    }
    private static JSONObject normalizedRoute(JSONObject root,String provider) throws Exception {
        String code=root.optString("code","Ok");
        if(!"Ok".equalsIgnoreCase(code)) {
            throw new java.io.IOException("SIN_RUTA:"+code);
        }
        JSONArray routes=root.optJSONArray("routes");
        if(routes==null||routes.length()==0)throw new java.io.IOException("SIN_RUTA:Sin opciones");
        JSONObject route=routes.getJSONObject(0);
        JSONObject geometry=route.optJSONObject("geometry");
        if(geometry==null)throw new java.io.IOException("FORMATO_RUTA:Sin geometría GeoJSON");
        JSONArray raw=geometry.optJSONArray("coordinates");
        if(raw==null||raw.length()<2)
            throw new java.io.IOException("FORMATO_RUTA:Geometría insuficiente");
        double meters=route.optDouble("distance",0),seconds=route.optDouble("duration",0);
        if(!Double.isFinite(meters)||!Double.isFinite(seconds)||meters<=0||seconds<=0)
            throw new java.io.IOException("FORMATO_RUTA:Distancia o tiempo inválido");
        JSONArray coordsSafe=new JSONArray();
        int jump=Math.max(1,(int)Math.ceil(raw.length()/750.0));
        for(int i=0;i<raw.length();i+=jump){
            JSONArray pt=raw.optJSONArray(i);
            if(pt!=null&&pt.length()>=2&&validCoord(pt.optDouble(1,Double.NaN),
                    pt.optDouble(0,Double.NaN)))coordsSafe.put(pt);
        }
        if((raw.length()-1)%jump!=0) {
            JSONArray last=raw.optJSONArray(raw.length()-1);
            if(last!=null&&last.length()>=2)coordsSafe.put(last);
        }
        JSONObject result=new JSONObject();
        result.put("ok",true);result.put("provider",provider);
        result.put("meters",meters);result.put("seconds",seconds);
        result.put("geometry",coordsSafe);
        JSONArray out=new JSONArray();
        JSONArray legs=route.optJSONArray("legs");
        if(legs!=null)for(int j=0;j<legs.length();j++){
            JSONArray steps=legs.getJSONObject(j).optJSONArray("steps");
            if(steps==null)continue;
            for(int k=0;k<steps.length()&&out.length()<140;k++){
                JSONObject step=steps.getJSONObject(k),turn=step.optJSONObject("maneuver");
                if(turn==null)continue;
                JSONArray at=turn.optJSONArray("location");
                if(at==null||at.length()<2)continue;
                JSONObject item=new JSONObject();
                item.put("lat",at.optDouble(1));item.put("lon",at.optDouble(0));
                item.put("type",turn.optString("type","turn"));
                item.put("modifier",turn.optString("modifier","straight"));
                item.put("road",step.optString("name",""));
                item.put("meters",step.optDouble("distance",0));
                out.put(item);
            }
        }
        result.put("steps",out);
        return result;
    }
    private static String navigationFailure(Exception e) {
        Throwable cause=e;
        boolean ssl=false;
        String diagnostic="";
        while(cause!=null){
            if(cause instanceof java.security.cert.CertificateExpiredException)
                return "certificado caducado o reloj Android incorrecto";
            if(cause instanceof java.security.cert.CertificateNotYetValidException)
                return "certificado aún no válido; revisa fecha y hora de Android";
            if(cause instanceof java.security.cert.CertPathValidatorException)
                return "cadena de confianza de certificado no válida ("+
                        briefCause(cause)+")";
            if(cause instanceof javax.net.ssl.SSLPeerUnverifiedException)
                return "certificado del servidor no verificable ("+briefCause(cause)+")";
            if(cause instanceof javax.net.ssl.SSLHandshakeException ||
               cause instanceof javax.net.ssl.SSLProtocolException ||
               cause instanceof javax.net.ssl.SSLException){
                ssl=true; diagnostic=briefCause(cause);
            }
            if(cause instanceof java.net.SocketTimeoutException)
                return "tiempo de espera agotado";
            if(cause instanceof java.net.UnknownHostException)
                return "falló DNS o no se encontró el servidor";
            if(cause instanceof java.net.ConnectException)
                return "conexión rechazada";
            cause=cause.getCause();
        }
        if(ssl)return "HTTPS/TLS: "+diagnostic;
        String msg=e.getMessage();
        if(msg!=null){
            if(msg.startsWith("SIN_RUTA:"))return "sin ruta vial entre los puntos";
            if(msg.startsWith("FORMATO_RUTA:"))return "respuesta de ruta no válida";
            if(msg.startsWith("Servidor HTTP ")){
                String status=msg.substring("Servidor HTTP ".length()).trim();
                return "servidor HTTP "+status.replaceAll("[^0-9]","");
            }
        }
        return "no se recibió respuesta utilizable ("+e.getClass().getSimpleName()+")";
    }
    private static String briefCause(Throwable cause) {
        if(cause==null)return "desconocido";
        String m=cause.getMessage();
        if(m==null||m.trim().isEmpty())return cause.getClass().getSimpleName();
        m=m.replaceAll("[\\r\\n\\t]"," ").trim();
        return m.length()>120?m.substring(0,120):m;
    }
    /**
     * Actualiza el proveedor TLS con Google Play Services (si está presente).
     * Corre únicamente sobre el hilo del executor de navegación.
     * No elimina verificación de certificados ni instala CA personalizada.
     */
    private void ensureModernTlsProvider() {
        if(tlsProviderChecked)return;
        tlsProviderChecked=true;
        try{
            ProviderInstaller.installIfNeeded(getApplicationContext());
            tlsProviderStatus="Proveedor TLS de Google verificado";
        }catch(com.google.android.gms.common.GooglePlayServicesRepairableException repairable){
            tlsProviderStatus="Google Play Services necesita actualización (código "+
                    repairable.getConnectionStatusCode()+")";
        }catch(com.google.android.gms.common.GooglePlayServicesNotAvailableException unavailable){
            tlsProviderStatus="Proveedor TLS de Google no disponible (código "+
                    unavailable.errorCode+")";
        }catch(Exception e){
            tlsProviderStatus="No se pudo actualizar TLS ("+e.getClass().getSimpleName()+")";
        }
    }
    private void routeProgress(String msg){
        JSONObject progress=new JSONObject();
        try{progress.put("message",msg);}catch(Exception ignored){}
        navReply("rushNavRouteProgress",progress);
    }
    private void fetchNavigationRoute(double aLat,double aLon,double bLat,double bLon) {
        if(!validCoord(aLat,aLon)||!validCoord(bLat,bLon)){
            navReply("rushNavRoute",navError("Ubicación o destino no válidos."));return;
        }
        long now=System.currentTimeMillis();
        if(now-lastRouteRequest<3000L){
            navReply("rushNavRoute",navError("Espera unos segundos antes de calcular otra ruta."));return;
        }
        lastRouteRequest=now;
        navigationExecutor.execute(()->{
            routeProgress("Comprobando proveedor de seguridad HTTPS…");
            ensureModernTlsProvider();
            routeProgress(tlsProviderStatus+" · calculando ruta con FOSSGIS…");
            String fossgisError="";
            try{
                JSONObject answer=normalizedRoute(
                        retrieveFossgisRoute(aLat,aLon,bLat,bLon),"OSRM FOSSGIS");
                navReply("rushNavRoute",answer);return;
            }catch(Exception e){fossgisError=navigationFailure(e);}
            routeProgress("FOSSGIS no respondió ("+fossgisError+"). Probando OSRM…");
            String osrmError="";
            try{
                JSONObject answer=normalizedRoute(
                        retrieveOsrmRoute(aLat,aLon,bLat,bLon),"OSRM alternativo");
                navReply("rushNavRoute",answer);return;
            }catch(Exception e){osrmError=navigationFailure(e);}
            routeProgress("OSRM no respondió ("+osrmError+"). Probando Valhalla…");
            String valhallaError="";
            try{
                JSONObject answer=normalizedRoute(
                        retrieveValhallaRoute(aLat,aLon,bLat,bLon),"Valhalla");
                navReply("rushNavRoute",answer);return;
            }catch(Exception e){valhallaError=navigationFailure(e);}
            boolean https=fossgisError.contains("HTTPS")&&
                    osrmError.contains("HTTPS")&&valhallaError.contains("HTTPS");
            String message;
            if(https){
                message="Conexión HTTPS fallida en los tres motores. "
                    +tlsProviderStatus+". Detalle: FOSSGIS ("+fossgisError
                    +"), OSRM ("+osrmError+"), Valhalla ("+valhallaError
                    +"). Verifica fecha y hora automáticas y Google Play Services. "
                    +"No desactives la seguridad SSL.";
            }else if(fossgisError.contains("sin ruta vial")&&
                     osrmError.contains("sin ruta vial")&&
                     valhallaError.contains("sin ruta vial")){
                message="Ningún motor encontró un recorrido en automóvil. Comprueba "
                    +"si ambos puntos están sobre calles accesibles.";
            }else{
                message="Sin ruta: FOSSGIS ("+fossgisError+"), OSRM ("
                        +osrmError+"), Valhalla ("+valhallaError+"). "
                        +tlsProviderStatus+". Comprueba internet, certificados y GPS.";
            }
            navReply("rushNavRoute",navError(message));
        });
    }

    // Guía hablada bajo demanda; sin permisos especiales ni grabación de audio.
    private void speakNavigationInternal(String message) {
        if(message==null||message.isEmpty())return;
        final String phrase=message.length()>220?message.substring(0,220):message;
        if(navSpeech==null) {
            pendingNavSpeech=phrase;
            navSpeech=new TextToSpeech(getApplicationContext(),status -> {
                if(status!=TextToSpeech.SUCCESS||navSpeech==null)return;
                int lang=navSpeech.setLanguage(new Locale("es","CL"));
                if(lang==TextToSpeech.LANG_MISSING_DATA||lang==TextToSpeech.LANG_NOT_SUPPORTED)
                    lang=navSpeech.setLanguage(new Locale("es","ES"));
                navSpeechReady=lang!=TextToSpeech.LANG_MISSING_DATA
                        &&lang!=TextToSpeech.LANG_NOT_SUPPORTED;
                if(navSpeechReady&&pendingNavSpeech!=null){
                    navSpeech.speak(pendingNavSpeech,TextToSpeech.QUEUE_FLUSH,null,"rush-first-turn");
                }
                pendingNavSpeech=null;
            });
            return;
        }
        if(navSpeechReady){
            navSpeech.speak(phrase,TextToSpeech.QUEUE_FLUSH,null,
                    "rush-turn-"+System.currentTimeMillis());
        }
    }
    private void stopNavigationSpeechInternal() {
        pendingNavSpeech=null;
        if(navSpeech!=null)navSpeech.stop();
    }

    private void startGps() {
        boolean fine = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, LOCATION_REQUEST_CODE);
            }
            sendGpsStatus("Permiso GPS requerido");
            return;
        }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            sendGpsStatus("GPS no disponible");
            return;
        }
        boolean started = false;
        try {
            // Evita registros de ubicación duplicados al volver de Ajustes/Waze.
            locationManager.removeUpdates(this);
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this);
                started = true;
            }
            if ((fine || coarse) && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, this);
                started = true;
            }
            // Una ubicación reciente permite mostrar el mapa al abrir el TBox.
            // Nunca usar una ubicación antigua como si fuera la posición actual.
            Location recent = null;
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                recent = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                Location fromNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (fromNetwork != null && (recent == null
                        || fromNetwork.getTime() > recent.getTime())) recent = fromNetwork;
            }
            if (recent != null && System.currentTimeMillis() - recent.getTime() < 15000L
                    && recent.getTime() <= System.currentTimeMillis() + 5000L
                    && (!recent.hasAccuracy() || recent.getAccuracy() < 500f)) {
                onLocationChanged(recent);
            }
        } catch (SecurityException ignored) { }
        sendGpsStatus(started ? "Buscando señal GPS…" : "Sin proveedor GPS activo");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == BLUETOOTH_REQUEST_CODE) {
            showPairedObd();
            return;
        }
        if (requestCode == LOCATION_REQUEST_CODE) {
            boolean granted = false;
            for (int result : results) {
                if (result == PackageManager.PERMISSION_GRANTED) granted = true;
            }
            if (granted && (gpsRequested || mapGpsActive || weatherGpsActive)) startGps();
            else sendGpsStatus("GPS sin permiso");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        // No mostrar una localización almacenada/antigua como si fuese en vivo.
        long elapsedNs = location.getElapsedRealtimeNanos();
        long nowNs = android.os.SystemClock.elapsedRealtimeNanos();
        if (elapsedNs <= 0L || elapsedNs > nowNs + 1000000000L
                || nowNs - elapsedNs > 12000000000L
                || elapsedNs <= lastFixElapsedNs) return;
        // Cuando hay GPS reciente y preciso, evitar saltos de red/Wi-Fi.
        if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())
                && lastAccurateGpsNs > 0L
                && nowNs - lastAccurateGpsNs < 5000000000L) return;
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())
                && location.hasAccuracy() && location.getAccuracy() <= 65f) {
            lastAccurateGpsNs = elapsedNs;
        }
        lastFixElapsedNs = elapsedNs;
        String speed = location.hasSpeed()
                ? String.valueOf(Math.max(0, location.getSpeed() * 3.6f)) : "null";
        String heading = location.hasBearing()
                ? String.valueOf(location.getBearing()) : "null";
        float acc = location.hasAccuracy() ? location.getAccuracy() : -1f;
        // Un solo envío al WebView por punto: menos salto en CPU del TBox.
        javascript("window.rushGpsData(" + speed + "," + heading + ");"
                + "window.rushPosition(" + location.getLatitude() + ","
                + location.getLongitude() + "," + acc + ");");
    }

    @Override public void onProviderEnabled(String provider) { sendGpsStatus("Buscando señal GPS…"); }
    @Override public void onProviderDisabled(String provider) { sendGpsStatus("GPS desactivado"); }
    @Override @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Android anteriores a 10 requieren este metodo.
    }

    @Override
    protected void onPause() {
        if (spotifyConnect != null) spotifyConnect.pause();
        super.onPause();
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (spotifyConnect != null) spotifyConnect.resume();
        if (pageLoaded) {
            if (gpsRequested || mapGpsActive || weatherGpsActive) startGps();
            sendSpotifyState(); sendDockIcons(); sendHomeState(); 
            dispatchExternalNavigation();
        }
    }

    @Override
    protected void onDestroy() {
        stopNavigationSpeechInternal();
        if(navSpeech!=null){navSpeech.shutdown();navSpeech=null;}
        if (spotifyArtwork != null) spotifyArtwork.shutdown();
        if (spotifyConnect != null) spotifyConnect.destroy();
        if (spotifyReceiverRegistered) {
            try { unregisterReceiver(spotifyReceiver); } catch (RuntimeException ignored) { }
            spotifyReceiverRegistered = false;
        }
        weatherExecutor.shutdownNow();
        navigationExecutor.shutdownNow();
        suggestionsExecutor.shutdownNow();
        iconExecutor.shutdownNow();
        if (obdReader != null) obdReader.disconnect();
        if (webView != null) {
            webView.removeJavascriptInterface("Rush");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (spotifyConnect != null) spotifyConnect.handleRedirect(intent);
        dispatchExternalNavigation();
    }

    @Override
    public void onBackPressed() {
        if (pageLoaded && webView != null) {
            webView.evaluateJavascript(
                    "window.rushHandleBack?window.rushHandleBack():false",
                    result -> {
                        if (!"true".equals(result) && !isDefaultHome()) moveTaskToBack(true);
                    });
        } else if (!isDefaultHome()) {
            moveTaskToBack(true);
        }
    }

    private final class RushBridge {
        @JavascriptInterface public void ready() {
            runUi(() -> {
                sendSpotifyState();
                sendDockIcons();
                if (spotifyConnect != null) spotifyConnect.uiReady();
            });
        }
        @JavascriptInterface public void spotifyClientId(String clientId) {
            runUi(() -> spotifyConnect.configure(clientId));
        }
        @JavascriptInterface public void spotifyConnect() {
            runUi(() -> spotifyConnect.connect());
        }
        @JavascriptInterface public void spotifyDisconnect() {
            runUi(() -> spotifyConnect.disconnect());
        }
        @JavascriptInterface public void spotifyRefresh() {
            runUi(() -> spotifyConnect.refreshPlayback(true));
        }
        @JavascriptInterface public void spotifyControl(String command) {
            runUi(() -> spotifyConnect.command(command));
        }
        @JavascriptInterface public void spotifySeek(long milliseconds) {
            runUi(() -> spotifyConnect.seek(milliseconds));
        }
        @JavascriptInterface public void spotifyDevelopers() {
            runUi(() -> {
                try {
                    Intent browser = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://developer.spotify.com/dashboard"));
                    startActivity(browser);
                } catch (Exception ex) {
                    message("No hay navegador para abrir Spotify for Developers");
                }
            });
        }
        @JavascriptInterface public void listApps() {
            runUi(() -> sendInstalledApps());
        }
        @JavascriptInterface public void launchApp(String packageName) {
            runUi(() -> openInstalledApp(packageName));
        }
        @JavascriptInterface public void open(String action) {
            runUi(() -> openRoute(action));
        }
        @JavascriptInterface public void media(String action) {
            runUi(() -> mediaKey(action));
        }
        @JavascriptInterface public void navigate(String destination) {
            runUi(() -> openMaps(destination));
        }
        @JavascriptInterface public void askGps() {
            runUi(() -> { gpsRequested = true; startGps(); });
        }
        @JavascriptInterface public void startWeatherGps() {
            runUi(() -> { weatherGpsActive = true; startGps(); });
        }
        @JavascriptInterface public void stopWeatherGps() {
            runUi(() -> {
                weatherGpsActive = false;
                if (!gpsRequested && !mapGpsActive && locationManager != null) {
                    try { locationManager.removeUpdates(MainActivity.this); }
                    catch (SecurityException ignored) { }
                }
            });
        }
        @JavascriptInterface public void startMapGps() {
            runUi(() -> { mapGpsActive = true; startGps(); });
        }
        @JavascriptInterface public void stopMapGps() {
            runUi(() -> {
                mapGpsActive = false;
                if (!gpsRequested && !weatherGpsActive && locationManager != null) {
                    try { locationManager.removeUpdates(MainActivity.this); }
                    catch (SecurityException ignored) { }
                }
            });
        }
        @JavascriptInterface public void searchNav(String text) {
            searchNavigationDestination(text);
        }
        @JavascriptInterface public void searchNavNear(String text,double lat,double lon) {
            searchNavigationDestination(text,lat,lon);
        }
        @JavascriptInterface public void suggestNav(String text, double biasLat,
                                                     double biasLon, int requestId) {
            suggestNavigationDestination(text,biasLat,biasLon,requestId);
        }
        @JavascriptInterface public void speakNavigation(String message) {
            runUi(() -> speakNavigationInternal(message));
        }
        @JavascriptInterface public void stopNavigationSpeech() {
            runUi(() -> stopNavigationSpeechInternal());
        }
        @JavascriptInterface public void routeNav(double lat1,double lon1,double lat2,double lon2) {
            fetchNavigationRoute(lat1,lon1,lat2,lon2);
        }
        @JavascriptInterface public void getWeather(double latitude, double longitude) {
            runUi(() -> fetchWeather(latitude, longitude));
        }
        @JavascriptInterface public void requestObdDevices() {
            runUi(() -> showPairedObd());
        }
        @JavascriptInterface public void connectObd(String mac) {
            runUi(() -> { if (bluetoothAvailable()) obdReader.connect(mac); });
        }
        @JavascriptInterface public void disconnectObd() {
            runUi(() -> obdReader.disconnect());
        }
        @JavascriptInterface public void bluetoothSettings() {
            runUi(() -> openBluetoothSettings());
        }
    }
}
