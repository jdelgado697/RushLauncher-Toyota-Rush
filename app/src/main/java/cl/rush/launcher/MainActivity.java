package cl.rush.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
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

import java.util.List;

/**
 * Rush Launcher: interfaz local sobre Android WebView en TBox Lite S.
 * No modifica firmware Toyota ni controla la camara o la radio FM original.
 */
public class MainActivity extends Activity implements LocationListener {

    private static final int LOCATION_REQUEST_CODE = 1101;
    private static final int BLUETOOTH_REQUEST_CODE = 1102;
    private ObdReader obdReader;
    private final Runnable mediaRefresh = new Runnable() {
        @Override public void run() {
            refreshMediaState();
            mainHandler.postDelayed(this, 1100L);
        }
    };
    private WebView webView;
    private LocationManager locationManager;
    private boolean pageLoaded = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        obdReader = new ObdReader(new ObdReader.Listener() {
            @Override public void onStatus(String status) {
                javascript("window.rushObdStatus(" + JSONObject.quote(status) + ");");
            }
            @Override public void onData(int speed, int rpm, int coolant) {
                javascript("window.rushObdData(" + speed + "," + rpm + "," + coolant + ");");
            }
        });
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF050B19);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new RushBridge(), "Rush");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                sendGpsStatus("Toca para activar GPS");
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

    private void openMaps() {
        try {
            launchPackage("com.waze", "Waze");
        } catch (Exception ignored) {
            message("Waze no esta disponible");
        }
    }

    private void openRoute(String action) {
        switch (action) {
            case "spotify":
                launchPackage("com.spotify.music", "Spotify");
                break;
            case "maps":
            case "waze":
                openMaps();
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

    private MediaController spotifySession() {
        MediaSessionManager msm =
                (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (msm == null) return null;
        try {
            List<MediaController> sessions = msm.getActiveSessions(
                    new ComponentName(this, MediaAccessListener.class));
            MediaController first = null;
            for (MediaController session : sessions) {
                if (first == null) first = session;
                if ("com.spotify.music".equals(session.getPackageName())) return session;
            }
            return first;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private void refreshMediaState() {
        if (!pageLoaded) return;
        MediaController controller = spotifySession();
        if (controller == null) {
            javascript("window.rushMediaState(null, '', '');");
            return;
        }
        PlaybackState state = controller.getPlaybackState();
        String playing = state == null ? "null"
                : (state.getState() == PlaybackState.STATE_PLAYING ? "true" : "false");
        MediaMetadata metadata = controller.getMetadata();
        String title = metadata == null ? "" :
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String app = "com.spotify.music".equals(controller.getPackageName())
                ? "Spotify" : "Reproductor";
        javascript("window.rushMediaState(" + playing + ","
                + JSONObject.quote(title == null ? "" : title) + ","
                + JSONObject.quote(app) + ");");
    }

    private void openMediaAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception ex) {
            message("Permite acceso a notificaciones desde Ajustes de Android.");
        }
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
        MediaController controller = spotifySession();
        if (controller != null) {
            PlaybackState state = controller.getPlaybackState();
            MediaController.TransportControls controls = controller.getTransportControls();
            if (controls != null) {
                switch (command) {
                    case "previous": controls.skipToPrevious(); break;
                    case "next": controls.skipToNext(); break;
                    case "toggle":
                        if (state != null && state.getState() == PlaybackState.STATE_PLAYING)
                            controls.pause();
                        else controls.play();
                        break;
                    default: return;
                }
                mainHandler.postDelayed(this::refreshMediaState, 450L);
                return;
            }
        }
        int code;
        switch (command) {
            case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "toggle": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            default: return;
        }
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null) {
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
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
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, this);
                started = true;
            }
            if ((fine || coarse) && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, this);
                started = true;
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
    }

    @Override public void onProviderEnabled(String provider) { sendGpsStatus("Buscando señal GPS…"); }
    @Override public void onProviderDisabled(String provider) { sendGpsStatus("GPS desactivado"); }
    @Override @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Android anteriores a 10 requieren este metodo.
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(mediaRefresh);
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(mediaRefresh);
        mainHandler.post(mediaRefresh);
        if (pageLoaded && locationManager != null) startGps();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(mediaRefresh);
        if (obdReader != null) obdReader.disconnect();
        if (webView != null) {
            webView.removeJavascriptInterface("Rush");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        exitToOriginalHome();
    }

    private final class RushBridge {
        @JavascriptInterface public void ready() { /* Ya se abrio la pantalla local. */ }
        @JavascriptInterface public void open(String action) {
            runUi(() -> openRoute(action));
        }
        @JavascriptInterface public void media(String action) {
            runUi(() -> mediaKey(action));
        }
        @JavascriptInterface public void askGps() {
            runUi(() -> startGps());
        }
        @JavascriptInterface public void requestMediaAccess() {
            runUi(() -> openMediaAccessSettings());
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
