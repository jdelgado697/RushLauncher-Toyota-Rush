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

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.List;
import java.net.URL;
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
    private WebView webView;
    private LocationManager locationManager;
    private boolean pageLoaded = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService weatherExecutor = Executors.newSingleThreadExecutor();
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
            } else if (SPOTIFY_PLAYBACK.equals(action)) {
                spotifyPlaying = intent.getBooleanExtra("playing", false);
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
            payload.put("playing", spotifyPlaying == null ? JSONObject.NULL : spotifyPlaying.booleanValue());
            payload.put("hasData", !spotifyTrack.isEmpty() || spotifyPlaying != null);
            payload.put("app", "Spotify");
            payload.put("source", "broadcast");
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
            @Override public void onData(int speed, int rpm, int coolant) {
                javascript("window.rushObdData(" + speed + "," + rpm + "," + coolant + ");");
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
                + " RushLauncher/3.1.0 (+https://github.com/jdelgado697/RushLauncher-Toyota-Rush)");
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.addJavascriptInterface(new RushBridge(), "Rush");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                // Solicita permiso de ubicación al iniciar la app y empieza a
                // leer el GPS automáticamente, si el TBox dispone de proveedor.
                startGps();
                sendSpotifyState();
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
                new ComponentName(this, "cl.rush.launcher.HomeAlias"),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void enableHomeOption() {
        try {
            setHomeOption(true);
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception ex) {
            message("Configura la aplicacion de inicio desde Ajustes Android");
        }
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
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, this);
                started = true;
            }
            if ((fine || coarse) && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, this);
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
            if (recent != null && System.currentTimeMillis() - recent.getTime() < 120000L
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
            if (granted) startGps();
            else sendGpsStatus("GPS sin permiso");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        String speed = location.hasSpeed()
                ? String.valueOf(Math.max(0, location.getSpeed() * 3.6f)) : "null";
        String heading = location.hasBearing()
                ? String.valueOf(location.getBearing()) : "null";
        javascript("window.rushGpsData(" + speed + "," + heading + ");");
        javascript("window.rushPosition(" + location.getLatitude() + ","
                + location.getLongitude() + "," + location.getAccuracy() + ");");
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
        if (pageLoaded) { startGps(); sendSpotifyState(); sendDockIcons(); }
    }

    @Override
    protected void onDestroy() {
        if (spotifyConnect != null) spotifyConnect.destroy();
        if (spotifyReceiverRegistered) {
            try { unregisterReceiver(spotifyReceiver); } catch (RuntimeException ignored) { }
            spotifyReceiverRegistered = false;
        }
        weatherExecutor.shutdownNow();
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
    }

    @Override
    public void onBackPressed() {
        exitToOriginalHome();
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
            runUi(() -> startGps());
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
