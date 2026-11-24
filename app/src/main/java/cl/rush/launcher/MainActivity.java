package cl.rush.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.LruCache;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.media.AudioAttributes;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
    private ExternalNmeaGps externalNmea;
    private final BuildingData3D buildingData3D = new BuildingData3D();
    private OfflineChileTiles offlineChile;
    private static final String NMEA_PREFS = "rush_gps_iphone_direct";
    private static final String NMEA_HOST = "host";
    private static final String NMEA_PORT = "port";
    private static final String NMEA_ENABLED = "enabled";
    private volatile boolean nmeaFixRecentlyReceived = false;
    private volatile long lastExternalNmeaFixMs = 0L;
    private long externalNmeaFixCounter = 0L;

    private SharedPreferences nmeaSettings() {
        return getSharedPreferences(NMEA_PREFS,MODE_PRIVATE);
    }

    private void nmeaInfoToWeb(String status) {
        JSONObject payload=new JSONObject();
        try {
            payload.put("status",status);
            payload.put("host",nmeaSettings().getString(NMEA_HOST,"192.168.0.101"));
            payload.put("port",nmeaSettings().getInt(NMEA_PORT,11123));
            payload.put("enabled",nmeaSettings().getBoolean(NMEA_ENABLED,false));
            payload.put("fixes",externalNmeaFixCounter);
            payload.put("recent",nmeaSettings().getBoolean(NMEA_ENABLED,false)
                && lastExternalNmeaFixMs>0L &&
                android.os.SystemClock.elapsedRealtime()-lastExternalNmeaFixMs<10000L);
        }catch(Exception ignored){}
        javascript("if(window.rushExternalNmeaState)window.rushExternalNmeaState("+payload.toString()+");");
    }

    private void connectSavedNmea() {
        if(externalNmea==null || !nmeaSettings().getBoolean(NMEA_ENABLED,false))return;
        externalNmea.connect(nmeaSettings().getString(NMEA_HOST,"192.168.0.101"),
             nmeaSettings().getInt(NMEA_PORT,11123));
    }

    private void acceptExternalNmea(double lat,double lon,Double speed,Double heading) {
        if(!nmeaSettings().getBoolean(NMEA_ENABLED,false))return;
        long now=android.os.SystemClock.elapsedRealtime();
        lastExternalNmeaFixMs=now;
        nmeaFixRecentlyReceived=true;
        externalNmeaFixCounter++;
        // RMC no publica precisión: null, NUNCA ±0 m.
        String s=speed!=null&&Double.isFinite(speed)?String.valueOf(speed):"null";
        String h=heading!=null&&Double.isFinite(heading)?String.valueOf(heading):"null";
        String js="window.rushGpsData("+s+","+h+");"
             +"window.rushPosition("+lat+","+lon+",null);"
             +"if(window.rushGpsFixMeta)window.rushGpsFixMeta('iPhone NMEA',"
             +externalNmeaFixCounter+","+(heading!=null?"true":"false")+");";
        javascript(js);
        if(externalNmeaFixCounter%8==1)nmeaInfoToWeb("GPS iPhone activo · lecturas "+externalNmeaFixCounter);
    }

    private static final String OBD_PREFS = "rush_obd_autoconnect";
    private static final String OBD_MAC = "paired_elm327";
    private static final String OBD_AUTOMATIC = "enabled";
    private static final String LIGHT_CAN_ID="rush_light_can_id";
    private static final String LIGHT_CAN_BYTE="rush_light_can_byte";
    private static final String LIGHT_CAN_MASK="rush_light_can_mask";
    private static final String LIGHT_CAN_ON="rush_light_can_on";
    private boolean obdPermissionRequested = false;
    private boolean obdInForeground = false;
    private final Runnable obdAutoPoll = new Runnable() {
        @Override public void run() {
            if (!obdInForeground || isFinishing()) return;
            autoConnectObd();
            // Reintentos moderados, nunca un bucle Bluetooth continuo.
            if (obdInForeground && !isFinishing()) scheduleObdAuto(false);
        }
    };
    private SpotifyConnectClient spotifyConnect;
    private SpotifyArtworkFetcher spotifyArtwork;
    private TextToSpeech navSpeech;
    private boolean navSpeechReady = false;
    private final AudioManager.OnAudioFocusChangeListener navAudioFocusListener = change -> { };
    private boolean navFocusHeld = false;
    private String pendingNavSpeech = null;
    private WebView webView;
    private LocationManager locationManager;
    private boolean gpsRequested = false; // Solicitud manual del usuario.
    private boolean mapGpsActive = false; // GPS temporal del MAPA 2D.
    private boolean weatherGpsActive = false; // Ubicación opt-in del clima.
    // Filtros de antigüedad y prioridad GPS para no alternar con red imprecisa.
    private long lastFixElapsedNs = 0L;
    private long lastGpsFixNs = 0L;
    private long lastNetworkFixNs = 0L;
    private long lastAccurateGpsNs = 0L;
    private long lastFixReceivedMs = 0L;
    private long gpsRegisteredMs = 0L;
    private long lastGpsRestartMs = 0L;
    private int gpsFixCounter = 0;
    private int gpsRawCounter = 0;
    private long gpsRawReceivedMs = 0L;
    private String lastGpsProvider = "Ninguno";
    private String gpsRejectReason = "";
    // No suponer que el receptor avanza si Android devuelve siempre la misma posición.
    private boolean gpsTrackingEnabled() {
        return gpsRequested || mapGpsActive || weatherGpsActive;
    }
    private void sendGpsDiagnostics(long ageMs) {
        JSONObject info = new JSONObject();
        try {
            info.put("ageSeconds",Math.max(0,ageMs/1000L));
            info.put("rawCount",gpsRawCounter);
            info.put("acceptedCount",gpsFixCounter);
            info.put("provider",lastGpsProvider);
            info.put("rejectReason",gpsRejectReason);
            info.put("registered",gpsRegistered);
        }catch(Exception ignored){}
        javascript("if(window.rushGpsDiagnostics)window.rushGpsDiagnostics("+info.toString()+");");
    }
    private boolean gpsRegistered = false;
    private boolean gpsWatchdogRunning = false;
    private final Runnable gpsWatchdog = new Runnable() {
        @Override public void run() {
            if(!gpsTrackingEnabled() || isFinishing() || isDestroyed()) {
                gpsWatchdogRunning=false;return;
            }
            long now=android.os.SystemClock.elapsedRealtime();
            // Después de reactivar el receptor, conceder una ventana nueva
            // de adquisición de señal; no usar el timestamp viejo del viaje previo.
            long latest=Math.max(lastFixReceivedMs,gpsRegisteredMs);
            long age=latest>0L?Math.max(0L,now-latest):0L;
            sendGpsDiagnostics(age);
            if(age>=10000L) {
                sendGpsStatus("SIN GPS NUEVO · "+(age/1000L)+" s · "
                    +lastGpsProvider+" · "+gpsFixCounter+" lecturas");
                boolean granted=Build.VERSION.SDK_INT<Build.VERSION_CODES.M
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                      ==PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                      ==PackageManager.PERMISSION_GRANTED;
                if(granted && age>=17000L && now-lastGpsRestartMs>=30000L){
                    lastGpsRestartMs=now;
                    gpsRegistered=false;
                    // Reinscribir en GNSS+red sin alterar la última posición dibujada.
                    startGps();
                }
            }
            mainHandler.postDelayed(this,4000L);
        }
    };
    private void startGpsWatchdog() {
        if(!gpsTrackingEnabled() || gpsWatchdogRunning)return;
        gpsWatchdogRunning=true;
        mainHandler.postDelayed(gpsWatchdog,4000L);
    }
    private void stopGpsWatchdog(){
        gpsWatchdogRunning=false;
        mainHandler.removeCallbacks(gpsWatchdog);
    }
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
        externalNmea = new ExternalNmeaGps(new ExternalNmeaGps.Listener() {
            @Override public void onStatus(String status) {
                nmeaInfoToWeb(status);
            }
            @Override public void onFix(double lat,double lon,Double kmh,Double bearing) {
                acceptExternalNmea(lat,lon,kmh,bearing);
            }
        });
        obdReader = new ObdReader(new ObdReader.Listener() {
            @Override public void onStatus(String status) {
                javascript("window.rushObdStatus(" + JSONObject.quote(status) + ");");
            }
            @Override public void onData(JSONObject snapshot) {
                javascript("window.rushObdData(" + snapshot.toString() + ");");
            }
            @Override public void onLightCan(JSONObject info) {
                javascript("if(window.rushLightCanEvent)window.rushLightCanEvent("+
                    info.toString()+");");
            }
        });
        for(String signal:new String[]{"low","high","left","right","hazard"})
            obdReader.setLightProfile(signal,loadLightCanProfile(signal));
        // Base nacional de calles: se descomprime una sola vez en segundo plano.
        offlineChile=new OfflineChileTiles(this);
        new Thread(offlineChile::warmUp,"rush-offline-chile").start();
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
                + " RushLauncher/4.20.11 (+https://github.com/jdelgado697/RushLauncher-Toyota-Rush)");
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.addJavascriptInterface(new RushBridge(), "Rush");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                    WebResourceRequest request) {
                if(request==null||request.getUrl()==null)return null;
                if("rush-offline.local".equals(request.getUrl().getHost())&&
                    offlineChile!=null)
                    return OfflineChileTiles.intercept(
                        request.getUrl().toString(),offlineChile);
                return null;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                // Inicio activo: GPS y clima se controlan desde dashboard.js.
                // El ELM327 autorizado se reconecta si está vinculado.
                obdInForeground = true;
                scheduleObdAuto(true);
                sendSpotifyState();
                sendHomeState();
                
                dispatchExternalNavigation();
                if (spotifyConnect != null) spotifyConnect.uiReady();
                sendObdAutoState();
                sendLightCanProfiles();
                nmeaInfoToWeb("GPS iPhone listo");
                connectSavedNmea();
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

    // 4.20.1: mapa ligero dentro del WebView principal, sin arrancar MapLibre GL.
    // Es compatible con los accesos del dock y con Rush.navigate(destino).
    private void openMaps(String destination) {
        String place = destination == null ? "" : destination.trim();
        if (place.length() > 140) place = place.substring(0, 140);
        javascript("if(window.rushOpenEmbeddedMap)window.rushOpenEmbeddedMap("
                + JSONObject.quote(place) + ");");
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
                new ComponentName(this, "cl.rush.launcher.obd415.HomeAlias"),
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
                new ComponentName(this, "cl.rush.launcher.obd415.HomeAlias"));
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

    private SharedPreferences obdSettings() {
        return getSharedPreferences(OBD_PREFS, MODE_PRIVATE);
    }
    private static boolean validLightTarget(String target){
        return "low".equals(target)||"high".equals(target)||
            "left".equals(target)||"right".equals(target)||"hazard".equals(target);
    }
    private LightCanMonitor.Profile loadLightCanProfile(){
        return loadLightCanProfile("low");
    }
    private LightCanMonitor.Profile loadLightCanProfile(String target){
        if(!validLightTarget(target))return null;
        SharedPreferences pref=obdSettings();
        String suffix="_"+target;
        String id=pref.getString(LIGHT_CAN_ID+suffix,
            "low".equals(target)?pref.getString(LIGHT_CAN_ID,""):"");
        int index=pref.getInt(LIGHT_CAN_BYTE+suffix,
            "low".equals(target)?pref.getInt(LIGHT_CAN_BYTE,-1):-1);
        int mask=pref.getInt(LIGHT_CAN_MASK+suffix,
            "low".equals(target)?pref.getInt(LIGHT_CAN_MASK,0):0);
        int on=pref.getInt(LIGHT_CAN_ON+suffix,
            "low".equals(target)?pref.getInt(LIGHT_CAN_ON,-1):-1);
        boolean pulse=pref.getBoolean("rush_light_can_pulse"+suffix,
            "left".equals(target)||"right".equals(target)||"hazard".equals(target));
        if(!id.matches("(?i)(?:[0-9a-f]{3}|[0-9a-f]{8})")||
           index<0||index>7||Integer.bitCount(mask)!=1||
           mask>128||on<0||on>1)return null;
        return new LightCanMonitor.Profile(id,index,mask,on,pulse);
    }
    private void sendLightCanProfile(){
        LightCanMonitor.Profile p=loadLightCanProfile();
        javascript("if(window.rushLightCanProfile)window.rushLightCanProfile("+
            (p!=null?p.json().toString():"null")+");");
        sendLightCanProfiles();
    }
    private void sendLightCanProfiles(){
        JSONObject all=new JSONObject();
        for(String signal:new String[]{"low","high","left","right","hazard"}){
            LightCanMonitor.Profile p=loadLightCanProfile(signal);
            try{all.put(signal,p==null?JSONObject.NULL:p.json());}catch(Exception ignored){}
        }
        javascript("if(window.rushLightCanProfiles)window.rushLightCanProfiles("+
            all.toString()+");");
    }
    private void saveLightCanProfile(LightCanMonitor.Profile profile){
        saveLightCanProfile("low",profile);
    }
    private void saveLightCanProfile(String target,LightCanMonitor.Profile profile){
        if(!validLightTarget(target))return;
        String suffix="_"+target;
        SharedPreferences.Editor edit=obdSettings().edit();
        if(profile==null){
            edit.remove(LIGHT_CAN_ID+suffix).remove(LIGHT_CAN_BYTE+suffix)
                .remove(LIGHT_CAN_MASK+suffix).remove(LIGHT_CAN_ON+suffix)
                .remove("rush_light_can_pulse"+suffix);
            if("low".equals(target))
                edit.remove(LIGHT_CAN_ID).remove(LIGHT_CAN_BYTE)
                    .remove(LIGHT_CAN_MASK).remove(LIGHT_CAN_ON);
        }else {
            edit.putString(LIGHT_CAN_ID+suffix,profile.id)
                .putInt(LIGHT_CAN_BYTE+suffix,profile.index)
                .putInt(LIGHT_CAN_MASK+suffix,profile.mask)
                .putInt(LIGHT_CAN_ON+suffix,profile.onBit)
                .putBoolean("rush_light_can_pulse"+suffix,profile.pulse);
        }
        edit.apply();
        if(obdReader!=null)obdReader.setLightProfile(target,profile);
        sendLightCanProfiles();
    }

    private void sendObdAutoState() {
        boolean enabled = obdSettings().getBoolean(OBD_AUTOMATIC, true);
        String remembered = obdSettings().getString(OBD_MAC, "");
        javascript("if(window.rushObdAutoState)window.rushObdAutoState("
                + enabled + "," + JSONObject.quote(remembered) + ");");
    }

    private void scheduleObdAuto(boolean soon) {
        mainHandler.removeCallbacks(obdAutoPoll);
        if (!obdInForeground || !obdSettings().getBoolean(OBD_AUTOMATIC, true))
            return;
        mainHandler.postDelayed(obdAutoPoll, soon ? 900L : 45000L);
    }

    /**
     * Conexión read-only SPP: recuerda MAC escogida por el usuario y verifica
     * que sigue vinculada. Si no hay preferencia, solo selecciona un ÚNICO
     * ELM327 reconocible. Nunca intenta conectar a teléfonos/audio u otros.
     */
    private void autoConnectObd() {
        if (obdReader == null || obdReader.isActive() || !obdInForeground
                || !obdSettings().getBoolean(OBD_AUTOMATIC, true)) return;
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                    != PackageManager.PERMISSION_GRANTED) {
            if (!obdPermissionRequested) {
                obdPermissionRequested = true;
                requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"},
                        BLUETOOTH_REQUEST_CODE);
            }
            javascript("window.rushObdStatus('Concede permiso Bluetooth para conexión automática');");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            javascript("window.rushObdStatus('Bluetooth desactivado: activa Bluetooth Android');");
            return;
        }
        String remembered = obdSettings().getString(OBD_MAC, "");
        String selected = "";
        boolean foundRemembered = false;
        int recognized = 0;
        try {
            JSONArray bonded = obdReader.pairedDevices();
            for (int i=0; i<bonded.length(); i++) {
                JSONObject item = bonded.optJSONObject(i);
                if(item==null)continue;
                String mac = item.optString("address","");
                String label = item.optString("name","").toLowerCase(Locale.ROOT);
                if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(mac)) continue;
                if (!remembered.isEmpty() && remembered.equalsIgnoreCase(mac)) {
                    selected = mac;
                    foundRemembered = true;
                    break;
                }
                // Adaptadores clásicos: no incluir cualquier periférico cercano.
                if(label.contains("elm327") || label.contains("obd")
                    || label.contains("vgate") || label.contains("vlink")
                    || label.contains("veepeak") || label.contains("konnwei")
                    || label.contains("icar")) {
                    selected = mac;
                    recognized++;
                }
            }
        } catch (SecurityException ignored) {
            javascript("window.rushObdStatus('No se pudieron leer dispositivos Bluetooth vinculados');");
            return;
        }
        if (selected.isEmpty() || (!remembered.isEmpty() && !foundRemembered)
                || (remembered.isEmpty() && recognized != 1)) {
            javascript("window.rushObdStatus('OBD automático: elige tu ELM327 una vez en Conexión');");
            return;
        }
        javascript("window.rushObdStatus('Conectando ELM327 automáticamente…');");
        obdReader.connect(selected);
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
    private void navReplyWithRequest(String callback,JSONObject result,int requestId) {
        try{if(requestId>0)result.put("requestId",requestId);}catch(Exception ignored){}
        navReply(callback,result);
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
    private volatile int latestHomeSuggestionRequest=0;
    private void suggestHomeNavigationDestination(String query,double lat,double lon,int requestId) {
        if(query==null||query.trim().length()<3||query.length()>100||requestId<=0)return;
        latestHomeSuggestionRequest=requestId;
        final String q=query.trim();
        final boolean near=validCoord(lat,lon)&&lat>=-56.5&&lat<=-17&&lon>=-76&&lon<=-66;
        suggestionsExecutor.execute(()->{
            if(requestId!=latestHomeSuggestionRequest)return;
            try{
                long delay=1300L-(System.currentTimeMillis()-lastSuggestionFetchAt);
                if(delay>0)Thread.sleep(delay);
                if(requestId!=latestHomeSuggestionRequest)return;
                lastSuggestionFetchAt=System.currentTimeMillis();
                JSONArray rows=suggestionsFromFeatures(photonFeatures(q,near,lat,lon),5);
                if(rows.length()==0&&near)
                    rows=suggestionsFromFeatures(photonFeatures(q,false,lat,lon),5);
                if(requestId!=latestHomeSuggestionRequest)return;
                JSONObject result=new JSONObject();
                result.put("ok",true);result.put("items",rows);result.put("query",q);
                navReplyWithRequest("rushHomeSuggest",result,requestId);
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
             catch(Exception error){
                if(requestId!=latestHomeSuggestionRequest)return;
                navReplyWithRequest("rushHomeSuggest",
                      navError("Sin sugerencias automáticas. Pulsa Buscar."),requestId);
            }
        });
    }
    private void searchNavigationDestination(String query) {
        searchNavigationDestination(query,0,0);
    }
    private void searchNavigationDestination(String query,double focusLat,double focusLon) {
        searchNavigationDestination(query,focusLat,focusLon,"rushNavSearch",0);
    }
    private void searchNavigationDestination(String query,double focusLat,double focusLon,
                                              String callback,int requestId) {
        if(query==null || query.trim().length()<3 || query.length()>140){
            navReplyWithRequest(callback,navError("Escribe una dirección o lugar."),requestId);
            return;
        }
        long now=System.currentTimeMillis();
        if(now-lastSearchRequest<1800L){
            navReplyWithRequest(callback,navError("Espera un momento antes de buscar de nuevo."),requestId);
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
                navReplyWithRequest(callback,result,requestId);
            }catch(Exception ignored){
                navReplyWithRequest(callback,navError(
                    "No se pudo buscar en Chile. Verifica internet o selecciona un punto."),requestId);
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
                +coords+"?overview=full&geometries=geojson&steps=true";
        return new JSONObject(getNavText(url,900000,4800,7000));
    }
    private static JSONObject retrieveOsrmRoute(double aLat,double aLon,double bLat,
                                                 double bLon) throws Exception {
        String coords=String.format(Locale.US,"%.6f,%.6f;%.6f,%.6f",
                aLon,aLat,bLon,bLat);
        String url="https://router.project-osrm.org/route/v1/driving/"
                +coords+"?overview=full&geometries=geojson&steps=true";
        return new JSONObject(getNavText(url,900000,4500,7000));
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

    /**
     * Rush 4.20.11: TomTom Routing V1 ofrece rutas alternativas,
     * indicaciones localizadas y ETA que incluye tráfico. No son datos de
     * Waze ni reportes comunitarios de usuarios Waze.
     * La clave viaja solo por HTTPS, nunca se escribe en logs o en un APK.
     */
    private static JSONObject retrieveTomTomTrafficRoute(double aLat,double aLon,
            double bLat,double bLon,String apiKey,boolean avoidTolls,
            boolean avoidMotorways,boolean avoidUnpaved) throws Exception {
        if(apiKey==null||!apiKey.matches("[A-Za-z0-9_-]{12,128}"))
            throw new IllegalArgumentException("Clave TomTom no configurada");
        String pts=String.format(Locale.US,"%.6f,%.6f:%.6f,%.6f",
                aLat,aLon,bLat,bLon);
        StringBuilder url=new StringBuilder("https://api.tomtom.com/routing/1/calculateRoute/")
            .append(pts).append("/json?key=").append(
                URLEncoder.encode(apiKey,"UTF-8"))
            .append("&traffic=true&travelMode=car&routeType=fastest")
            .append("&maxAlternatives=2&alternativeType=anyRoute")
            .append("&routeRepresentation=polyline")
            .append("&computeTravelTimeFor=all")
            .append("&instructionsType=text&language=es-ES")
            .append("&sectionType=traffic&sectionType=tollRoad")
            .append("&sectionType=roadClosure");
        if(avoidTolls)url.append("&avoid=tollRoads");
        if(avoidMotorways)url.append("&avoid=motorways");
        if(avoidUnpaved)url.append("&avoid=unpavedRoads");
        return new JSONObject(getNavText(url.toString(),2400000,6500,12000));
    }

    private static JSONObject normalizeTomTomRouteItem(JSONObject tt,int number)
            throws Exception {
        JSONObject summary=tt.optJSONObject("summary");
        if(summary==null)throw new java.io.IOException("TomTom sin resumen de ruta");
        double meters=summary.optDouble("lengthInMeters",0);
        double seconds=summary.optDouble("travelTimeInSeconds",0);
        if(!Double.isFinite(meters)||!Double.isFinite(seconds)||
                meters<=0||seconds<=0)
            throw new java.io.IOException("TomTom distancia o ETA no válida");

        JSONArray all=new JSONArray(),legs=tt.optJSONArray("legs");
        if(legs==null||legs.length()==0)
            throw new java.io.IOException("TomTom sin geometría vial");
        // Polilínea real de los tramos; conservar extremos y usar un máximo
        // ~1400 puntos por ruta para el WebView del TBox 4 GB.
        int total=0;
        for(int k=0;k<legs.length();k++){
            JSONArray p=legs.optJSONObject(k).optJSONArray("points");
            if(p!=null)total+=p.length();
        }
        int stride=Math.max(1,(int)Math.ceil(total/1400.0));
        int index=0;
        JSONArray lastPoint=null;
        for(int k=0;k<legs.length();k++){
            JSONArray p=legs.optJSONObject(k).optJSONArray("points");
            if(p==null)continue;
            for(int i=0;i<p.length();i++){
                JSONObject point=p.optJSONObject(i);
                if(point==null)continue;
                double lat=point.optDouble("latitude",Double.NaN);
                double lon=point.optDouble("longitude",Double.NaN);
                if(!validCoord(lat,lon))continue;
                JSONArray pair=new JSONArray();pair.put(lon);pair.put(lat);
                if(index%stride==0)all.put(pair);
                index++;lastPoint=pair;
            }
        }
        if(lastPoint!=null)all.put(lastPoint);
        if(all.length()<2)
            throw new java.io.IOException("TomTom devolvió geometría insuficiente");

        JSONObject normalized=new JSONObject();
        normalized.put("ok",true);normalized.put("provider","TomTom Traffic");
        normalized.put("meters",meters);normalized.put("seconds",seconds);
        normalized.put("geometry",all);
        normalized.put("traffic",true);
        normalized.put("trafficDelaySeconds",
            Math.max(0,summary.optInt("trafficDelayInSeconds",0)));
        normalized.put("arrivalTime",summary.optString("arrivalTime",""));
        normalized.put("noTrafficSeconds",
            summary.optDouble("noTrafficTravelTimeInSeconds",seconds));
        normalized.put("routeIndex",number);

        JSONArray steps=new JSONArray();
        JSONObject guidance=tt.optJSONObject("guidance");
        JSONArray instructions=guidance!=null?guidance.optJSONArray("instructions"):null;
        if(instructions!=null)for(int i=0;i<instructions.length()&&steps.length()<140;i++){
            JSONObject instr=instructions.optJSONObject(i);
            if(instr==null)continue;
            JSONObject point=instr.optJSONObject("point");
            if(point==null)continue;
            double lat=point.optDouble("latitude",Double.NaN);
            double lon=point.optDouble("longitude",Double.NaN);
            if(!validCoord(lat,lon))continue;
            String maneuver=instr.optString("maneuver","").toUpperCase(Locale.US);
            JSONObject step=new JSONObject();
            step.put("lat",lat);step.put("lon",lon);
            String type=maneuver.contains("ROUNDABOUT")?"roundabout":
                maneuver.contains("ARRIVE")?"arrive":
                maneuver.contains("DEPART")?"depart":
                maneuver.contains("U_TURN")||maneuver.contains("UTURN")?"uturn":"turn";
            String modifier=maneuver.contains("LEFT")?"left":
                maneuver.contains("RIGHT")?"right":"straight";
            step.put("type",type);step.put("modifier",modifier);
            step.put("road",instr.optString("street",""));
            step.put("text",instr.optString("message",""));
            step.put("meters",instr.optDouble("routeOffsetInMeters",0));
            steps.put(step);
        }
        normalized.put("steps",steps);
        return normalized;
    }

    private static JSONObject normalizedTomTomRoutes(JSONObject response) throws Exception {
        JSONArray routes=response.optJSONArray("routes");
        if(routes==null||routes.length()==0)
            throw new java.io.IOException("TomTom no encontró rutas");
        JSONArray found=new JSONArray();
        for(int i=0;i<routes.length()&&found.length()<3;i++){
            try{found.put(normalizeTomTomRouteItem(routes.getJSONObject(i),i));}
            catch(Exception ignored){if(i==0)throw ignored;}
        }
        if(found.length()==0)throw new java.io.IOException("TomTom sin ruta legible");
        JSONObject primary=found.getJSONObject(0);
        JSONArray alternatives=new JSONArray();
        for(int i=1;i<found.length();i++)alternatives.put(found.getJSONObject(i));
        primary.put("alternatives",alternatives);
        primary.put("optionsCount",found.length());
        return primary;
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
        int jump=Math.max(1,(int)Math.ceil(raw.length()/1600.0));
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
    private void routeReply(boolean forHome,JSONObject data,int requestId) {
        navReplyWithRequest(forHome?"rushHomeRoute":"rushNavRoute",data,requestId);
    }
    private void routeProgressFor(boolean forHome,String message,int requestId) {
        JSONObject info=new JSONObject();
        try{info.put("message",message);}catch(Exception ignored){}
        navReplyWithRequest(forHome?"rushHomeRouteProgress":"rushNavRouteProgress",info,requestId);
    }
    private void fetchNavigationRoute(double aLat,double aLon,double bLat,double bLon) {
        fetchNavigationRoute(aLat,aLon,bLat,bLon,false,0);
    }
    private void fetchNavigationRoute(double aLat,double aLon,double bLat,double bLon,
                                      boolean forHome,int requestId) {
        if(!validCoord(aLat,aLon)||!validCoord(bLat,bLon)){
            routeReply(forHome,navError("Ubicación o destino no válidos."),requestId);return;
        }
        long now=System.currentTimeMillis();
        if(now-lastRouteRequest<3000L){
            routeReply(forHome,navError("Espera unos segundos antes de calcular otra ruta."),requestId);return;
        }
        lastRouteRequest=now;
        navigationExecutor.execute(()->{
            routeProgressFor(forHome,"Comprobando proveedor de seguridad HTTPS…",requestId);
            ensureModernTlsProvider();
            routeProgressFor(forHome,tlsProviderStatus+" · calculando ruta con FOSSGIS…",requestId);
            String fossgisError="";
            try{
                JSONObject answer=normalizedRoute(
                        retrieveFossgisRoute(aLat,aLon,bLat,bLon),"OSRM FOSSGIS");
                routeReply(forHome,answer,requestId);return;
            }catch(Exception e){fossgisError=navigationFailure(e);}
            routeProgressFor(forHome,"FOSSGIS no respondió ("+fossgisError+"). Probando OSRM…",requestId);
            String osrmError="";
            try{
                JSONObject answer=normalizedRoute(
                        retrieveOsrmRoute(aLat,aLon,bLat,bLon),"OSRM alternativo");
                routeReply(forHome,answer,requestId);return;
            }catch(Exception e){osrmError=navigationFailure(e);}
            routeProgressFor(forHome,"OSRM no respondió ("+osrmError+"). Probando Valhalla…",requestId);
            String valhallaError="";
            try{
                JSONObject answer=normalizedRoute(
                        retrieveValhallaRoute(aLat,aLon,bLat,bLon),"Valhalla");
                routeReply(forHome,answer,requestId);return;
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
            routeReply(forHome,navError(message),requestId);
        });
    }

    private void fetchAdvancedHomeRoute(double aLat,double aLon,
            double bLat,double bLon,String key,boolean tolls,boolean motorways,
            boolean unpaved,int requestId){
        if(!validCoord(aLat,aLon)||!validCoord(bLat,bLon)){
            routeReply(true,navError("Origen o destino no válidos."),requestId);return;
        }
        if(key==null||!key.matches("[A-Za-z0-9_-]{12,128}")){
            fetchNavigationRoute(aLat,aLon,bLat,bLon,true,requestId);
            return;
        }
        long now=System.currentTimeMillis();
        if(now-lastRouteRequest<3000L){
            routeReply(true,navError("Espera antes de pedir una nueva ruta."),requestId);
            return;
        }
        lastRouteRequest=now;
        navigationExecutor.execute(()->{
            routeProgressFor(true,"TomTom: buscando rutas alternativas con tráfico…",requestId);
            ensureModernTlsProvider();
            String tomTomError="";
            try {
                JSONObject selected=normalizedTomTomRoutes(
                    retrieveTomTomTrafficRoute(aLat,aLon,bLat,bLon,key,tolls,
                        motorways,unpaved));
                routeReply(true,selected,requestId);
                return;
            }catch(Exception ex){
                // Jamás incluir API key en mensajes de error al WebView.
                tomTomError=ex.getClass().getSimpleName();
            }
            routeProgressFor(true,
                "TomTom no disponible ("+tomTomError+
                "). Intentando ruta básica OSRM sin tráfico en ETA…",requestId);
            try{
                JSONObject selected=normalizedRoute(
                    retrieveFossgisRoute(aLat,aLon,bLat,bLon),"OSRM FOSSGIS");
                selected.put("traffic",false);
                selected.put("warning","Respaldo OSRM: sin tráfico en ETA"+
                    ((tolls||motorways||unpaved)?" ni restricciones viales TomTom":""));
                routeReply(true,selected,requestId);return;
            }catch(Exception ignored){}
            try{
                JSONObject selected=normalizedRoute(
                    retrieveOsrmRoute(aLat,aLon,bLat,bLon),"OSRM alternativo");
                selected.put("traffic",false);
                selected.put("warning","Respaldo OSRM: sin tráfico en ETA"+
                    ((tolls||motorways||unpaved)?" ni restricciones viales TomTom":""));
                routeReply(true,selected,requestId);return;
            }catch(Exception ignored){}
            routeReply(true,navError(
                "No se pudo calcular: TomTom y OSRM sin respuesta. "+
                "Revisa internet y tu clave TomTom."),requestId);
        });
    }

    // Voz 4.20.5: audio multimedia, test de ruta y diagnóstico TTS/audio separado.
    private void acquireNavigationAudioFocus(){
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio==null)return;
        try{
            int result=audio.requestAudioFocus(navAudioFocusListener,
                AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            navFocusHeld=result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }catch(Exception ignored){}
    }
    private void releaseNavigationAudioFocus(){
        if(!navFocusHeld)return;
        navFocusHeld=false;
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio!=null)try{audio.abandonAudioFocus(navAudioFocusListener);}
            catch(Exception ignored){}
    }
    private void diagnoseNavigationAudio() {
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio==null){
            reportAudioDiagnostic("error","Android no informa AudioManager.");
            return;
        }
        int volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        String engine="Sin inicializar";
        try{
            if(navSpeech!=null){
                String selected=navSpeech.getDefaultEngine();
                int count=navSpeech.getEngines().size();
                engine=(selected==null?"sin predeterminado":selected)+
                  " ("+count+" motores)";
            }else{
                int count=getPackageManager().queryIntentServices(
                    new Intent("android.intent.action.TTS_SERVICE"),0).size();
                engine=count+" motores detectados";
            }
        }catch(Exception ignored){engine="No se pudo consultar el motor";}
        String msg="Multimedia "+volume+"/"+maximum+
            (volume==0?" (SILENCIO)":"")+
            " · TTS: "+engine+
            " · Motor listo: "+(navSpeechReady?"sí":"no");
        reportAudioDiagnostic(volume==0?"error":"diag",msg);
    }
    private void reportAudioDiagnostic(String state,String message){
        javascript("if(window.rushAudioDiagnostics)window.rushAudioDiagnostics("+
            JSONObject.quote(state)+","+JSONObject.quote(message)+");");
    }
    private void testNavigationTone(){
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio==null){reportAudioDiagnostic("error","Android no tiene salida AudioManager");return;}
        int vol=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if(vol<=0){
            reportAudioDiagnostic("error","Volumen multimedia Android 0/"+maximum+
               ". Sube volumen de medios del TBox y prueba otra vez.");
            return;
        }
        try{
            final ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,95);
            if(tone.startTone(ToneGenerator.TONE_PROP_BEEP,650)){
                reportAudioDiagnostic("diag","Tono enviado al audio multimedia ("+
                    vol+"/"+maximum+"). ¿Lo escuchaste por la radio?");
            }else{
                reportAudioDiagnostic("error","Android rechazó el tono de prueba.");
            }
            mainHandler.postDelayed(() -> tone.release(),950L);
        }catch(Exception exception){
            reportAudioDiagnostic("error",
               "No se pudo iniciar la prueba de sonido: "+exception.getClass().getSimpleName());
        }
    }

    // Voz de navegacion 4.20.4: usar el mismo canal multimedia del TBox.
    // Diagnostico visible: no confundir un boton "Voz si" con audio real.
    private void reportNavigationSpeech(String state,String message) {
        javascript("if(window.rushTtsStatus)window.rushTtsStatus("+
            JSONObject.quote(state)+","+JSONObject.quote(message)+");");
    }

    private void speakNavigationInternal(String message) {
        if(message==null||message.trim().isEmpty())return;
        final String phrase=message.length()>220?message.substring(0,220):message;
        if(navSpeech==null) {
            pendingNavSpeech=phrase;
            navSpeechReady=false;
            reportNavigationSpeech("loading","Inicializando síntesis de voz Android…");
            navSpeech=new TextToSpeech(getApplicationContext(),status -> {
                if(status!=TextToSpeech.SUCCESS||navSpeech==null) {
                    navSpeechReady=false;
                    pendingNavSpeech=null;
                    reportNavigationSpeech("error","No hay motor de voz Android disponible. Abre Ajustes de voz.");
                    return;
                }
                try {
                    // Spotify ya sale por STREAM_MUSIC. Usar el mismo canal
                    // impide que la voz quede en un stream silencioso del TBox.
                    navSpeech.setAudioAttributes(new AudioAttributes.Builder()
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC).build());
                    navSpeech.setSpeechRate(1.02f);
                    int lang=navSpeech.setLanguage(new Locale("es","CL"));
                    if(lang==TextToSpeech.LANG_MISSING_DATA||lang==TextToSpeech.LANG_NOT_SUPPORTED)
                        lang=navSpeech.setLanguage(new Locale("es","ES"));
                    if(lang==TextToSpeech.LANG_MISSING_DATA||lang==TextToSpeech.LANG_NOT_SUPPORTED)
                        lang=navSpeech.setLanguage(new Locale("es"));
                    navSpeechReady=lang!=TextToSpeech.LANG_MISSING_DATA
                        &&lang!=TextToSpeech.LANG_NOT_SUPPORTED;
                    if(!navSpeechReady){
                        reportNavigationSpeech("error",
                            "No hay voz española instalada. Instala datos de voz en Ajustes Android.");
                        pendingNavSpeech=null;
                        return;
                    }
                    navSpeech.setOnUtteranceProgressListener(
                        new android.speech.tts.UtteranceProgressListener(){
                            @Override public void onStart(String id) {
                                reportNavigationSpeech("speaking",
                                    "Motor de voz activo. Si no se oye, revisa volumen multimedia.");
                            }
                            @Override public void onDone(String id) {
                                releaseNavigationAudioFocus();
                            }
                            @Override public void onError(String id) {
                                releaseNavigationAudioFocus();
                                reportNavigationSpeech("error",
                                    "El motor de voz no pudo reproducir audio. Revisa TTS Android.");
                            }
                        });
                    reportNavigationSpeech("ready","Voz Android lista · salida multimedia");
                    if(pendingNavSpeech!=null){
                        String first=pendingNavSpeech;
                        pendingNavSpeech=null;
                        playNavigationSpeech(first);
                    }
                }catch(Exception exception){
                    navSpeechReady=false;
                    pendingNavSpeech=null;
                    reportNavigationSpeech("error",
                        "Error al iniciar la voz. Revisa motor TTS y volumen multimedia.");
                }
            });
            return;
        }
        if(navSpeechReady) {
            playNavigationSpeech(phrase);
        }else {
            pendingNavSpeech=phrase;
            reportNavigationSpeech("error",
                "Motor de voz no preparado. Revisa los ajustes de voz Android.");
        }
    }

    private void playNavigationSpeech(String phrase){
        if(navSpeech==null||!navSpeechReady)return;
        acquireNavigationAudioFocus();
        int result=navSpeech.speak(phrase,TextToSpeech.QUEUE_FLUSH,null,
            "rush-turn-"+System.currentTimeMillis());
        if(result==TextToSpeech.ERROR){
            releaseNavigationAudioFocus();
            reportNavigationSpeech("error",
                "El motor TTS rechazó el audio. Comprueba ajustes de voz.");
        }
    }

    private void stopNavigationSpeechInternal() {
        pendingNavSpeech=null;
        if(navSpeech!=null)navSpeech.stop();
        releaseNavigationAudioFocus();
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
                requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                }, LOCATION_REQUEST_CODE);
            }
            sendGpsStatus("Permiso de ubicación requerido");
            return;
        }
        if(gpsRegistered) {
            startGpsWatchdog();
            return; // No borrar lecturas al entrar al clima, Spotify o Mapa.
        }
        locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
        if(locationManager==null){
            sendGpsStatus("Proveedor GPS Android no disponible");return;
        }
        boolean started=false;
        try {
            locationManager.removeUpdates(this);
            // minDistance=0, para no congelar el mapa si la precisión GNSS
            // del TBox impide alcanzar el umbral de 2 metros de la 4.14.
            if(fine&&locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)){
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        700L,0f,this,Looper.getMainLooper());
                started=true;
            }
            if((fine||coarse)&&locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        1600L,0f,this,Looper.getMainLooper());
                started=true;
            }
            gpsRegistered=started;
            gpsRegisteredMs=android.os.SystemClock.elapsedRealtime();
            // Una posición antigua no debe sustituir una actual.
            Location recent=null;
            if(fine&&locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)){
                recent=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if(recent==null&&locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                recent=locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if(recent!=null&&System.currentTimeMillis()-recent.getTime()<12000L
                    &&recent.getTime()<=System.currentTimeMillis()+3000L
                    &&(!recent.hasAccuracy()||recent.getAccuracy()<250f)){
                onLocationChanged(recent);
            }
        }catch(SecurityException ex){
            gpsRegistered=false;
            sendGpsStatus("Permiso ubicación rechazado");
        }catch(IllegalArgumentException ex){
            gpsRegistered=false;
            sendGpsStatus("Proveedor GPS incompatible");
        }
        if(started)sendGpsStatus("Esperando siguiente lectura GPS…");
        else sendGpsStatus("GPS y red no disponibles en Android");
        // Siempre supervisar el GPS del Inicio, no solo el mapa secundario.
        startGpsWatchdog();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == BLUETOOTH_REQUEST_CODE) {
            boolean granted = results.length > 0
                    && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) { showPairedObd(); scheduleObdAuto(true); }
            else javascript("window.rushObdStatus('Permiso Bluetooth denegado');");
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
        if(location==null)return;
        // Si NMEA externo entrega posición reciente, evitar saltar a la
        // localización Wi-Fi estática del TBox sin receptor GNSS.
        if(nmeaSettings().getBoolean(NMEA_ENABLED,false)
            && lastExternalNmeaFixMs>0L &&
            android.os.SystemClock.elapsedRealtime()-lastExternalNmeaFixMs<10000L)return;
        gpsRawCounter++;
        gpsRawReceivedMs=android.os.SystemClock.elapsedRealtime();
        lastGpsProvider=LocationManager.GPS_PROVIDER.equals(location.getProvider())
                ? "GPS" : LocationManager.NETWORK_PROVIDER.equals(location.getProvider())
                ? "Red" : String.valueOf(location.getProvider());
        long fixNs=location.getElapsedRealtimeNanos();
        long nowNs=android.os.SystemClock.elapsedRealtimeNanos();
        if(fixNs<=0L||fixNs>nowNs+1000000000L||
                nowNs-fixNs>15000000000L) {
            gpsRejectReason="Lectura GPS antigua o sin fecha válida";
            return;
        }
        boolean satellite=LocationManager.GPS_PROVIDER.equals(location.getProvider());
        boolean network=LocationManager.NETWORK_PROVIDER.equals(location.getProvider());
        if(!satellite&&!network){
            gpsRejectReason="Proveedor no reconocido: "+lastGpsProvider;
            return;
        }
        if(satellite){
            if(fixNs<=lastGpsFixNs){
                gpsRejectReason="GNSS repite exactamente la misma lectura";
                return;
            }
            lastGpsFixNs=fixNs;
            if(location.hasAccuracy()&&location.getAccuracy()<=100f)
                lastAccurateGpsNs=fixNs;
        }else{
            if(fixNs<=lastNetworkFixNs){
                gpsRejectReason="Red repite una lectura anterior";
                return;
            }
            lastNetworkFixNs=fixNs;
            // No dejar que una posición Wi-Fi menos precisa desplace la
            // última lectura satelital reciente.
            if(lastAccurateGpsNs>0&&nowNs-lastAccurateGpsNs<6500000000L){
                gpsRejectReason="Priorizando satélites recientes";
                return;
            }
        }
        // Se acepta un fix del satélite aunque llegue después de un fix
        // de red con timestamp posterior. Antes se descartaba silenciosamente.
        if(!satellite&&fixNs<=lastFixElapsedNs){
            gpsRejectReason="Red con timestamp anterior al GPS";
            return;
        }
        lastFixElapsedNs=Math.max(lastFixElapsedNs,fixNs);
        lastFixReceivedMs=android.os.SystemClock.elapsedRealtime();
        gpsFixCounter++;
        gpsRejectReason="";
        String speed=location.hasSpeed()?
                String.valueOf(Math.max(0,location.getSpeed()*3.6f)):"null";
        String heading=location.hasBearing()?
                String.valueOf(location.getBearing()):"null";
        float acc=location.hasAccuracy()?location.getAccuracy():-1f;
        // Un paquete JS por lectura aceptada, con proveedor y número de fix.
        String provider=satellite?"GPS":"Red";
        javascript("window.rushGpsData("+speed+","+heading+");"
                +"window.rushPosition("+location.getLatitude()+","
                +location.getLongitude()+","+acc+");"
                +"if(window.rushGpsFixMeta)window.rushGpsFixMeta("
                +JSONObject.quote(provider)+","+gpsFixCounter+","+
                (location.hasBearing()?"true":"false")+");");
    }

    @Override public void onProviderEnabled(String provider) { sendGpsStatus("Buscando señal GPS…"); }
    @Override public void onProviderDisabled(String provider) { sendGpsStatus("GPS desactivado"); }
    @Override @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Android anteriores a 10 requieren este metodo.
    }

    @Override
    protected void onPause() {
        obdInForeground = false;
        mainHandler.removeCallbacks(obdAutoPoll);
        if (spotifyConnect != null) spotifyConnect.pause();
        super.onPause();
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        }
        gpsRegistered=false;
        stopGpsWatchdog();
        if(externalNmea!=null)externalNmea.disconnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        obdInForeground = true;
        if (pageLoaded) {
            scheduleObdAuto(true);
            connectSavedNmea();
        }
        if (spotifyConnect != null) spotifyConnect.resume();
        if (pageLoaded) {
            if (gpsRequested || mapGpsActive || weatherGpsActive) startGps();
            sendSpotifyState(); sendDockIcons(); sendHomeState(); 
            dispatchExternalNavigation();
        }
    }

    @Override
    protected void onDestroy() {
        obdInForeground = false;
        mainHandler.removeCallbacks(obdAutoPoll);
        stopGpsWatchdog();
        if(externalNmea!=null)externalNmea.disconnect();
        buildingData3D.shutdown();
        if(offlineChile!=null)offlineChile.close();
        if(locationManager!=null){
            try{locationManager.removeUpdates(this);}catch(SecurityException ignored){}
        }
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
                sendObdAutoState();
                nmeaInfoToWeb("GPS iPhone listo");
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
        @JavascriptInterface public void connectIphoneGps(String address,int port) {
            runUi(() -> {
                if(!ExternalNmeaGps.validLocalIpv4(address)||port<1024||port>65535) {
                    nmeaInfoToWeb("IP inválida: debe ser IPv4 de red Wi-Fi privada");
                    return;
                }
                nmeaSettings().edit().putString(NMEA_HOST,address)
                    .putInt(NMEA_PORT,port).putBoolean(NMEA_ENABLED,true).apply();
                externalNmeaFixCounter=0;
                nmeaFixRecentlyReceived=false;
                connectSavedNmea();
                nmeaInfoToWeb("Conectando a GPS2IP…");
            });
        }
        @JavascriptInterface public void disconnectIphoneGps() {
            runUi(() -> {
                nmeaSettings().edit().putBoolean(NMEA_ENABLED,false).apply();
                if(externalNmea!=null)externalNmea.disconnect();
                nmeaFixRecentlyReceived=false;
                nmeaInfoToWeb("GPS del iPhone desactivado");
            });
        }
        @JavascriptInterface public void getIphoneGpsSettings() {
            runUi(() -> nmeaInfoToWeb(nmeaSettings().getBoolean(NMEA_ENABLED,false) ?
                "Configurado · esperando conexión" : "GPS iPhone no conectado"));
        }
        // Edificios reales de OpenStreetMap, sin WebGL ni CORS en file://.
        // La consulta se ejecuta en un executor independiente del navegador.
        @JavascriptInterface public void fetch3dBuildings(double lat,double lon,int radius,int requestId) {
            buildingData3D.request(lat,lon,radius,requestId,(id,result) ->
                javascript("if(window.rush3dBuildings)window.rush3dBuildings("+id+
                    ","+result.toString()+");"));
        }
        @JavascriptInterface public boolean offlineMapAvailable(){
            return offlineChile!=null&&offlineChile.available();
        }
        @JavascriptInterface public boolean offlineMapReady(){
            return offlineChile!=null&&offlineChile.ready();
        }
        @JavascriptInterface public boolean hasValidatedInternet(){
            try {
                ConnectivityManager cm=(ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
                if(cm==null)return false;
                if(Build.VERSION.SDK_INT>=23){
                    android.net.Network n=cm.getActiveNetwork();
                    NetworkCapabilities caps=n!=null?cm.getNetworkCapabilities(n):null;
                    return caps!=null &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                }
                NetworkInfo info=cm.getActiveNetworkInfo();
                return info!=null&&info.isConnected();
            }catch(Exception ex){return false;}
        }
        @JavascriptInterface public void askGps() {
            runUi(() -> { gpsRequested = true; startGps(); });
        }
        @JavascriptInterface public void restartHomeGps() {
            runUi(() -> {
                gpsRequested=true;
                if(locationManager!=null) {
                    try{locationManager.removeUpdates(MainActivity.this);}
                    catch(SecurityException ignored){}
                }
                gpsRegistered=false;
                gpsRegisteredMs=android.os.SystemClock.elapsedRealtime();
                lastGpsRestartMs=gpsRegisteredMs;
                sendGpsStatus("Reiniciando GPS del Inicio…");
                startGps();
            });
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
                    gpsRegistered=false;
                }
            });
        }
        @JavascriptInterface public void startMapGps() {
            runUi(() -> { mapGpsActive=true; startGps(); });
        }
        @JavascriptInterface public void restartMapGps() {
            runUi(() -> {
                if(!mapGpsActive)return;
                if(locationManager!=null){
                    try{locationManager.removeUpdates(MainActivity.this);}
                    catch(SecurityException ignored){}
                }
                gpsRegistered=false;
                lastGpsRestartMs=android.os.SystemClock.elapsedRealtime();
                sendGpsStatus("Reactivando recepción GPS…");
                startGps();
            });
        }
        @JavascriptInterface public void stopMapGps() {
            runUi(() -> {
                mapGpsActive=false;
                stopGpsWatchdog();
                if(!gpsRequested&&!weatherGpsActive&&locationManager!=null) {
                    try{locationManager.removeUpdates(MainActivity.this);}
                    catch(SecurityException ignored){}
                    gpsRegistered=false;
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
        @JavascriptInterface public void testAudioBeep(){
            runUi(() -> testNavigationTone());
        }
        @JavascriptInterface public void diagnoseAudio(){
            runUi(() -> diagnoseNavigationAudio());
        }
        @JavascriptInterface public void openTtsSettings() {
            runUi(() -> {
                try {
                    startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                } catch(Exception missing) {
                    try {startActivity(new Intent(Settings.ACTION_SETTINGS));}
                    catch(Exception ignored) {message("No se pueden abrir los ajustes de voz.");}
                }
            });
        }
        @JavascriptInterface public void stopNavigationSpeech() {
            runUi(() -> stopNavigationSpeechInternal());
        }
        @JavascriptInterface public void routeNav(double lat1,double lon1,double lat2,double lon2) {
            fetchNavigationRoute(lat1,lon1,lat2,lon2);
        }
        @JavascriptInterface public void suggestHomeNav(String query,double lat,double lon,int requestId) {
            suggestHomeNavigationDestination(query,lat,lon,requestId);
        }
        @JavascriptInterface public void searchHomeNav(String query,double lat,double lon,int requestId) {
            searchNavigationDestination(query,lat,lon,"rushHomeSearch",requestId);
        }
        @JavascriptInterface public void routeHomeNav(double aLat,double aLon,
                        double bLat,double bLon,int requestId) {
            if(requestId>0)fetchNavigationRoute(aLat,aLon,bLat,bLon,true,requestId);
        }
        @JavascriptInterface public void routeHomeNavAdvanced(double aLat,double aLon,
                double bLat,double bLon,String apiKey,boolean avoidTolls,
                boolean avoidMotorways,boolean avoidUnpaved,int requestId){
            if(requestId>0)fetchAdvancedHomeRoute(aLat,aLon,bLat,bLon,
                apiKey,avoidTolls,avoidMotorways,avoidUnpaved,requestId);
        }
        @JavascriptInterface public void openWazeDestination(double lat,double lon) {
            runUi(()->{
                if(!validCoord(lat,lon)){
                    message("Elige un destino antes de abrir Waze.");return;
                }
                try {
                    Intent installed=getPackageManager()
                        .getLaunchIntentForPackage("com.waze");
                    if(installed==null){
                        message("Waze no está instalado en el Carlinkit. "+
                            "Úsalo en tu iPhone mediante CarPlay si está disponible.");
                        return;
                    }
                    String location=String.format(Locale.US,"%.6f,%.6f",lat,lon);
                    Intent navigate=new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://waze.com/ul?ll="+location+
                            "&navigate=yes&utm_source=rush_launcher"));
                    navigate.setPackage("com.waze");
                    startActivity(navigate);
                }catch(Exception ex){
                    message("No fue posible abrir Waze: "+ex.getClass().getSimpleName());
                }
            });
        }
        @JavascriptInterface public void getWeather(double latitude, double longitude) {
            runUi(() -> fetchWeather(latitude, longitude));
        }
        @JavascriptInterface public void calibrateObdLights(int step){
            runUi(() -> {
                if(!bluetoothAvailable()||obdReader==null)return;
                obdReader.requestLightCalibration(step);
            });
        }
        @JavascriptInterface public void selectObdLightsSignal(
                String id,int index,int mask,int whenOn){
            runUi(() -> {
                if(obdReader==null||
                   !obdReader.isLightCandidate(id,index,mask,whenOn)){
                    JSONObject error=new JSONObject();
                    try{
                        error.put("kind","error");
                        error.put("message","Calibra OFF/ON/OFF/ON antes de seleccionar la señal.");
                    }catch(Exception ignored){}
                    javascript("if(window.rushLightCanEvent)window.rushLightCanEvent("+
                        error.toString()+");");
                    return;
                }
                saveLightCanProfile(new LightCanMonitor.Profile(id,index,mask,whenOn));
            });
        }
        @JavascriptInterface public void clearObdLightsSignal(){
            runUi(() -> saveLightCanProfile(null));
        }
        @JavascriptInterface public void getObdLightsSignal(){
            runUi(() -> sendLightCanProfile());
        }
        @JavascriptInterface public void calibrateObdSignal(String target,int step){
            runUi(() -> {
                if(!validLightTarget(target)||!bluetoothAvailable()||obdReader==null)
                    return;
                obdReader.requestLightCalibration(target,step);
            });
        }
        @JavascriptInterface public void selectObdSignal(String target,String id,
                    int index,int mask,int whenOn,boolean pulse){
            runUi(() -> {
                if(!validLightTarget(target)||obdReader==null||
                    !obdReader.isLightCandidate(target,id,index,mask,whenOn,pulse)){
                    JSONObject error=new JSONObject();
                    try{
                        error.put("kind","error");error.put("target",target);
                        error.put("message","Registra las cuatro fases y elige una señal CAN válida.");
                    }catch(Exception ignored){}
                    javascript("if(window.rushLightCanEvent)window.rushLightCanEvent("+
                        error.toString()+");");
                    return;
                }
                saveLightCanProfile(target,
                    new LightCanMonitor.Profile(id,index,mask,whenOn,pulse));
            });
        }
        @JavascriptInterface public void clearObdSignal(String target){
            runUi(() -> saveLightCanProfile(target,null));
        }
        @JavascriptInterface public void getObdSignals(){
            runUi(() -> sendLightCanProfiles());
        }
        @JavascriptInterface public void requestObdDevices() {
            runUi(() -> showPairedObd());
        }
        @JavascriptInterface public void connectObd(String mac) {
            runUi(() -> {
                if (!bluetoothAvailable() || !BluetoothAdapter.checkBluetoothAddress(mac)) return;
                obdSettings().edit().putString(OBD_MAC,mac)
                        .putBoolean(OBD_AUTOMATIC,true).apply();
                obdReader.connect(mac);
                sendObdAutoState();
            });
        }
        @JavascriptInterface public void disconnectObd() {
            runUi(() -> {
                obdSettings().edit().putBoolean(OBD_AUTOMATIC,false).apply();
                mainHandler.removeCallbacks(obdAutoPoll);
                obdReader.disconnect();
                sendObdAutoState();
            });
        }
        @JavascriptInterface public void setObdAutoConnect(boolean enabled) {
            runUi(() -> {
                obdSettings().edit().putBoolean(OBD_AUTOMATIC, enabled).apply();
                sendObdAutoState();
                if (enabled) scheduleObdAuto(true);
                else mainHandler.removeCallbacks(obdAutoPoll);
            });
        }
        @JavascriptInterface public void bluetoothSettings() {
            runUi(() -> openBluetoothSettings());
        }
    }
}
