package cl.rush.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rush Navigator: pantalla interna del MISMO APK (sin intents a navegadores externos).
 * MapLibre Native dibuja en OpenGL; Photon busca lugares en Chile; OSRM calcula
 * rutas en línea. Servidores de demostración: no se promete servicio comercial
 * continuo ni funcionamiento de rutas sin internet.
 */
public final class NavigationActivity extends Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 402;
    private static final String MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty";
    private static final String SEARCH = "https://photon.komoot.io/api/";
    private static final String ROUTE = "https://router.project-osrm.org/route/v1/driving/";
    private static final String USER_AGENT = "RushLauncher/4.15.3 (personal Android car navigation; github.com/jdelgado697/RushLauncher-Toyota-Rush)";
    private static final int BLUE = Color.rgb(55, 129, 255);
    private static final String NOMINATIM_SEARCH = "https://nominatim.openstreetmap.org/search";
    // Público, sin SLA: el servidor Photon puede aplicar cuotas/errores temporales.
    private long photonRetryAfterMs = 0L;
    private long lastNominatimRequestMs = 0L;
    private final Map<String, List<SearchHit>> manualSearchCache = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService searchNetwork = Executors.newFixedThreadPool(2);
    private ScrollView resultsScroll;
    private long lastSearchStartedAt=0L;
    private final List<Point> routePoints = new ArrayList<>();
    private final List<Step> instructions = new ArrayList<>();

    private MapView mapView;
    private MapLibreMap map;
    private GeoJsonSource userSource, destinationSource, lineSource;
    private SymbolLayer userArrowLayer;
    private EditText query;
    private Button routeButton;
    private LinearLayout suggestionBox;
    private TextView status, guidance;
    private LocationManager locations;
    private Location fix;
    private Point destination;
    private boolean following = true;
    private boolean requestingRoute = false;
    private int searchRequest = 0;
    private long lastGpsElapsed = -1;
    private long lastRouteElapsed = -120000;
    private int instructionIndex = 0;
    private Location lastCameraFix;
    private long lastCameraElapsed = 0L;
    private static final String ROUTE_BACKUP = "https://routing.openstreetmap.de/routed-car/route/v1/driving/";
    private Runnable pendingSearch;

    private static final class Step {
        String instruction;
        double lat, lon;
        Step(String text, double latitude, double longitude) {
            instruction = text; lat = latitude; lon = longitude;
        }
    }

    /** Solo lugares reales devueltos por proveedores de mapas o geocodificación. */
    private static final class SearchHit {
        final String name;
        final Point point;
        SearchHit(String name, Point point) {
            this.name = name;
            this.point = point;
        }
    }

    private static final class SearchAnswer {
        final List<SearchHit> hits;
        final String provider;
        final String diagnostic;
        SearchAnswer(List<SearchHit> hits, String provider, String diagnostic) {
            this.hits = hits;
            this.provider = provider;
            this.diagnostic = diagnostic;
        }
    }

    private int dp(float amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(10), dp(5), dp(10), dp(5));
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value); b.setAllCaps(false); b.setTextSize(13);
        b.setTextColor(Color.WHITE); b.setBackground(bg(0xFF354491, 12));
        return b;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        MapLibre.getInstance(getApplicationContext());
        mapView = new MapView(this, new MapLibreMapOptions().textureMode(true));
        mapView.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101830);
        root.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(8), dp(5), dp(8), dp(7));
        top.setBackgroundColor(0xEA0D1532);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(top, tp);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(45)));
        Button home = button("⌂ Inicio");
        searchRow.addView(home, new LinearLayout.LayoutParams(dp(93), -1));
        home.setOnClickListener(v -> finish());
        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE); query.setHintTextColor(0xFFBBC7EA);
        query.setTextSize(14); query.setHint("Buscar lugar en Chile…");
        query.setBackground(bg(0xFF273559, 10));
        query.setPadding(dp(12), 0, dp(8), 0);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(0, -1, 1);
        qp.setMargins(dp(7), 0, dp(7), 0);
        searchRow.addView(query, qp);
        Button find = button("Buscar");
        LinearLayout.LayoutParams findSize = new LinearLayout.LayoutParams(dp(76), -1);
        findSize.rightMargin = dp(5);
        searchRow.addView(find, findSize);
        find.setOnClickListener(v -> {
            String destinationText = query.getText().toString().trim();
            if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
            search(destinationText, true);
        });
        Button center = button("▲ Conducir");
        searchRow.addView(center, new LinearLayout.LayoutParams(dp(96), -1));
        center.setOnClickListener(v -> {
            following = true;
            if (fix != null) moveCamera(fix, true);
            else Toast.makeText(this, "Esperando ubicación Android", Toast.LENGTH_SHORT).show();
        });

        resultsScroll = new ScrollView(this);
        resultsScroll.setFillViewport(false);
        resultsScroll.setBackground(bg(0xF51A2341, 12));
        suggestionBox = new LinearLayout(this);
        suggestionBox.setOrientation(LinearLayout.VERTICAL);
        resultsScroll.addView(suggestionBox);
        top.addView(resultsScroll, new LinearLayout.LayoutParams(-1, -2));
        resultsScroll.setVisibility(View.GONE);
        suggestionBox.setTag(resultsScroll);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xE9101937);
        bottom.setPadding(dp(10), dp(5), dp(10), dp(5));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottom, bp);
        guidance = text("MODO CONDUCCIÓN · GPS y rumbo arriba", 14, Color.WHITE);
        guidance.setMaxLines(2);
        bottom.addView(guidance, new LinearLayout.LayoutParams(-1, -2));
        status = text("Esperando señal GPS · mapa necesita internet", 11, 0xFFAFC7E8);
        bottom.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1,dp(40));
        ap.topMargin=dp(3); bottom.addView(actions,ap);
        routeButton=button("Calcular ruta");
        routeButton.setEnabled(false);routeButton.setAlpha(.5f);
        actions.addView(routeButton,new LinearLayout.LayoutParams(0,-1,1));
        routeButton.setOnClickListener(v->{
            if(destination==null)status.setText("Primero elige destino");
            else if(fix==null||lastGpsElapsed<0||SystemClock.elapsedRealtime()-lastGpsElapsed>15000)
                status.setText("Sin GPS reciente: activa ubicación precisa");
            else calculateRoute(false);
        });
        Button pick=button("Elegir en mapa");
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,-1,1);
        pp.leftMargin=dp(5);actions.addView(pick,pp);
        pick.setOnClickListener(v->guidance.setText("Mantén pulsado el mapa para elegir destino"));
        Button clear=button("Borrar");
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(72),-1);
        cp.leftMargin=dp(5);actions.addView(clear,cp);
        clear.setOnClickListener(v->clearDestination());
        top.bringToFront();bottom.bringToFront();

        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleSearch(s.toString());
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        String initialDestination = getIntent().getStringExtra("rush_destination");
        if (initialDestination != null && !initialDestination.trim().isEmpty())
            query.setText(initialDestination.trim());
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
            search(query.getText().toString().trim(), true);
            return true;
        });
        mapView.getMapAsync(ready -> {
            map = ready;
            map.getUiSettings().setCompassEnabled(true);
            map.getUiSettings().setLogoEnabled(true);
            map.setStyle(MAP_STYLE, style -> {
                userSource = new GeoJsonSource("rush-user", FeatureCollection.fromFeatures(new Feature[0]));
                destinationSource = new GeoJsonSource("rush-destination", FeatureCollection.fromFeatures(new Feature[0]));
                lineSource = new GeoJsonSource("rush-route", FeatureCollection.fromFeatures(new Feature[0]));
                style.addSource(lineSource);
                style.addSource(destinationSource);
                style.addSource(userSource);
                style.addLayer(new LineLayer("rush-route-line", "rush-route").withProperties(
                        PropertyFactory.lineColor(BLUE), PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineOpacity(0.90f)));
                style.addLayer(new CircleLayer("rush-end-dot", "rush-destination").withProperties(
                        PropertyFactory.circleColor(0xFFFF4D6D),
                        PropertyFactory.circleRadius(10f),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(3f)));
                style.addLayer(new CircleLayer("rush-user-halo", "rush-user").withProperties(
                        PropertyFactory.circleColor(0x553781FF), PropertyFactory.circleRadius(16f)));
                style.addImage("rush-nav-arrow", makeArrow());
                userArrowLayer = new SymbolLayer("rush-user-arrow", "rush-user").withProperties(
                        PropertyFactory.iconImage("rush-nav-arrow"),
                        PropertyFactory.iconSize(0.9f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotate(0f));
                style.addLayer(userArrowLayer);
                if (fix != null) renderFix(fix);
                else map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(-38.74, -72.59), 11));
            });
            map.addOnMapLongClickListener(point->{
                selectDestination(Point.fromLngLat(point.getLongitude(),point.getLatitude()),"Punto del mapa");
                return true;
            });
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false;
            });
        });
        locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        updateFreshness();
    }

    private void scheduleSearch(String term) {
        searchRequest++;
        if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);
        String q=term.trim();
        if(q.length()<2){hideSuggestions();return;}
        suggestionBox.removeAllViews();
        suggestionBox.addView(text("Buscando lugares cercanos en Chile…",13,Color.WHITE));
        resultsScroll.getLayoutParams().height=dp(45);
        resultsScroll.setVisibility(View.VISIBLE);
        long throttle=Math.max(0L,1200L-(SystemClock.elapsedRealtime()-lastSearchStartedAt));
        pendingSearch=()->search(q);
        handler.postDelayed(pendingSearch,Math.max(450L,throttle));
    }

    private void hideSuggestions() {
        if (suggestionBox != null && suggestionBox.getTag() instanceof View)
            ((View) suggestionBox.getTag()).setVisibility(View.GONE);
    }

    private void search(String term) {
        search(term, false);
    }

    private void search(String term, boolean explicit) {
        final String cleaned = term == null ? "" : term.trim();
        if (cleaned.length() < 2) {
            status.setText("Escribe al menos 2 letras para buscar");
            hideSuggestions();
            return;
        }
        // Un resultado antiguo no debe reemplazar otro término recién escrito.
        final int ticket = ++searchRequest;
        lastSearchStartedAt = SystemClock.elapsedRealtime();
        final double lat = fix != null ? fix.getLatitude() : -39.367;
        final double lon = fix != null ? fix.getLongitude() : -72.632;
        status.setText(explicit ? "Buscando destino en Chile…" : "Prediciendo lugares cercanos…");
        searchNetwork.execute(() -> {
            SearchAnswer result = searchWithFallback(cleaned, lat, lon, explicit);
            runOnUiThread(() -> {
                if (ticket != searchRequest || isFinishing()) return;
                suggestionBox.removeAllViews();
                if (result.hits.isEmpty()) {
                    String label = result.diagnostic.isEmpty()
                            ? "Sin coincidencias. Prueba con nombre y ciudad."
                            : "Sin resultados · " + result.diagnostic;
                    suggestionBox.addView(text(label, 12, Color.WHITE));
                    resultsScroll.getLayoutParams().height = dp(60);
                    resultsScroll.setVisibility(View.VISIBLE);
                    guidance.setText(explicit ? "No se encontró el lugar. Prueba otra dirección o mantén pulsado el mapa."
                            : "MODO CONDUCCIÓN · GPS y rumbo arriba");
                    status.setText(result.diagnostic.isEmpty() ? "No hay coincidencias" : result.diagnostic);
                    return;
                }
                int count = Math.min(8, result.hits.size());
                for (int i = 0; i < count; i++) {
                    final SearchHit found = result.hits.get(i);
                    Button option = button(found.name);
                    option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    option.setBackground(bg(0xFF1C294C, 6));
                    LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(44));
                    row.setMargins(0, dp(2), 0, dp(2));
                    suggestionBox.addView(option, row);
                    option.setOnClickListener(v -> selectDestination(found.point, found.name));
                }
                resultsScroll.getLayoutParams().height = dp(Math.min(185, Math.max(45, count * 45)));
                resultsScroll.scrollTo(0, 0);
                resultsScroll.setVisibility(View.VISIBLE);
                status.setText(count + " destinos · " + result.provider
                        + ("Nominatim".equals(result.provider) ? " · © OpenStreetMap" : ""));
            });
        });
    }

    /**
     * Orden: Photon (autocompletar), Geocoder nativo Android,
     * y SOLO en búsquedas explícitas Nominatim (sin autocomplete).
     * Nunca se falsifican sugerencias.
     */
    private SearchAnswer searchWithFallback(String term, double lat, double lon, boolean explicit) {
        String errors = "";
        List<SearchHit> hits = new ArrayList<>();
        if (SystemClock.elapsedRealtime() >= photonRetryAfterMs) {
            try {
                // Consultas mínimas; algunos despliegues de Photon rechazan lang/countrycode.
                String endpoint = SEARCH + "?q=" + URLEncoder.encode(term, "UTF-8")
                        + "&limit=8&lat=" + lat + "&lon=" + lon + "&zoom=14";
                JSONObject json = getJson(endpoint);
                JSONArray found = json.optJSONArray("features");
                if (found == null) throw new Exception("Respuesta Photon sin features");
                for (int i = 0; i < found.length() && hits.size() < 8; i++) {
                    JSONObject item = found.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject p = item.optJSONObject("properties");
                    JSONObject geom = item.optJSONObject("geometry");
                    if (p == null || geom == null) continue;
                    String country = p.optString("countrycode", "");
                    if (!country.isEmpty() && !"CL".equalsIgnoreCase(country)) continue;
                    JSONArray c = geom.optJSONArray("coordinates");
                    if (c == null || c.length() < 2) continue;
                    double longitude = c.optDouble(0, Double.NaN);
                    double latitude = c.optDouble(1, Double.NaN);
                    if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) continue;
                    if (latitude < -57 || latitude > -17 || longitude < -78 || longitude > -66) continue;
                    String name = p.optString("name", p.optString("street", "Lugar"));
                    String local = p.optString("city", p.optString("county", p.optString("state", "")));
                    String full = name + (local.isEmpty() ? "" : " · " + local);
                    if (!name.isEmpty()) hits.add(new SearchHit(full, Point.fromLngLat(longitude, latitude)));
                }
                if (!hits.isEmpty()) return new SearchAnswer(hits, "Photon", "");
            } catch (Exception e) {
                errors = "Photon " + shortSearchError(e);
                // Evita bombardear la API pública si está temporalmente caída.
                photonRetryAfterMs = SystemClock.elapsedRealtime() + 25000L;
            }
        } else errors = "Photon en pausa por error reciente";

        // Android Geocoder aprovecha el servicio de geocodificación del sistema si existe.
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(this, new Locale("es", "CL"));
                @SuppressWarnings("deprecation")
                List<Address> results = geocoder.getFromLocationName(term, 8,
                        -56.5, -77.0, -17.0, -66.0);
                if (results != null) for (Address address : results) {
                    if (!address.hasLatitude() || !address.hasLongitude()) continue;
                    String title = address.getAddressLine(0);
                    if (title == null || title.trim().isEmpty()) {
                        title = address.getFeatureName();
                        if (title == null || title.trim().isEmpty()) title = term;
                    }
                    hits.add(new SearchHit(title, Point.fromLngLat(
                            address.getLongitude(), address.getLatitude())));
                    if (hits.size() >= 8) break;
                }
                if (!hits.isEmpty()) return new SearchAnswer(hits, "Geocoder Android", "");
            }
        } catch (Exception e) {
            errors += (errors.isEmpty() ? "" : " · ") + "Android " + shortSearchError(e);
        }

        if (explicit) {
            String key = term.toLowerCase(Locale.ROOT);
            List<SearchHit> cached = manualSearchCache.get(key);
            if (cached != null && !cached.isEmpty()) return new SearchAnswer(cached, "Caché", "");
            // La política de Nominatim prohíbe su uso como autocomplete.
            // Solo buscar cuando el usuario pulsa Buscar/Enter, con uso moderado.
            long now = SystemClock.elapsedRealtime();
            if (now - lastNominatimRequestMs >= 2500L) {
                lastNominatimRequestMs = now;
                try {
                    String url = NOMINATIM_SEARCH + "?format=jsonv2&limit=7&countrycodes=cl&q="
                            + URLEncoder.encode(term, "UTF-8");
                    JSONArray results = getJsonArray(url);
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject hit = results.optJSONObject(i);
                        if (hit == null) continue;
                        double hlat = hit.optDouble("lat", Double.NaN);
                        double hlon = hit.optDouble("lon", Double.NaN);
                        if (!Double.isFinite(hlat) || !Double.isFinite(hlon)) continue;
                        String title = hit.optString("display_name", "");
                        if (title.isEmpty()) continue;
                        hits.add(new SearchHit(title, Point.fromLngLat(hlon, hlat)));
                    }
                    if (!hits.isEmpty()) {
                        if (manualSearchCache.size() > 16) manualSearchCache.clear();
                        manualSearchCache.put(key, new ArrayList<>(hits));
                        return new SearchAnswer(hits, "Nominatim", "");
                    }
                } catch (Exception e) {
                    errors += (errors.isEmpty() ? "" : " · ") + "Nominatim " + shortSearchError(e);
                }
            } else errors += (errors.isEmpty() ? "" : " · ") + "Espera para buscar otra vez";
        }
        return new SearchAnswer(hits, "", errors);
    }

    private String shortSearchError(Exception e) {
        String detail = e.getMessage();
        if (detail != null && detail.matches("HTTP \\d{3}")) return detail;
        String kind = e.getClass().getSimpleName();
        if (kind.contains("Timeout")) return "tiempo agotado";
        if (kind.contains("UnknownHost")) return "sin DNS";
        if (kind.contains("SSL")) return "error TLS";
        return kind;
    }

    private JSONArray getJsonArray(String endpoint) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(9000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int statusCode = conn.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) throw new Exception("HTTP " + statusCode);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && body.length() < 400000)
                body.append(line);
            reader.close();
            return new JSONArray(body.toString());
        } finally { conn.disconnect(); }
    }

    private void selectDestination(Point point, String title) {
        destination=point;
        routePoints.clear();instructions.clear();
        if(lineSource!=null)lineSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
        routeButton.setEnabled(true);routeButton.setAlpha(1f);
        query.setText(title);
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        ++searchRequest; // invalida cualquier búsqueda antigua
        hideSuggestions();
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
        if (destinationSource != null) destinationSource.setGeoJson(Feature.fromGeometry(point));
        if (map != null) map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(new LatLng(point.latitude(), point.longitude()), 14), 500);
        if (fix != null && lastGpsElapsed >= 0 && SystemClock.elapsedRealtime()-lastGpsElapsed<15000) calculateRoute(false);
        else {
            guidance.setText("Destino seleccionado · esperando ubicación GPS");
            status.setText("Activa Ubicación precisa en Android");
        }
    }

    private void clearDestination(){
        destination=null;routePoints.clear();instructions.clear();
        routeButton.setEnabled(false);routeButton.setAlpha(.5f);
        if(lineSource!=null)lineSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
        if(destinationSource!=null)destinationSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
        guidance.setText("MODO CONDUCCIÓN · GPS y rumbo arriba");
        status.setText("Busca destino o pulsa sobre mapa");
        query.setText("");hideSuggestions();following=true;
        if(fix!=null)moveCamera(fix,true);
    }

    private JSONObject getJson(String location) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(location).openConnection();
        conn.setConnectTimeout(9000); conn.setReadTimeout(11000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Language", "es-CL,es;q=0.9");
        try {
            int http = conn.getResponseCode();
            if (http < 200 || http >= 300) throw new Exception("HTTP " + http);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && result.length() < 1500000) result.append(line);
            reader.close();
            return new JSONObject(result.toString());
        } finally { conn.disconnect(); }
    }

    private void calculateRoute(boolean automatic) {
        if (fix == null || destination == null || requestingRoute) return;
        long now = SystemClock.elapsedRealtime();
        if (automatic && now - lastRouteElapsed < 35000) return;
        requestingRoute = true;
        lastRouteElapsed = now;
        final Location origin = new Location(fix);
        final Point target = destination;
        guidance.setText(automatic ? "Actualizando ruta…" : "Calculando ruta desde tu GPS…");
        network.execute(() -> {
            try {
                String url = ROUTE + String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f",
                        origin.getLongitude(), origin.getLatitude(), target.longitude(), target.latitude())
                        + "?overview=full&geometries=geojson&steps=true";
                JSONObject response;
                try {
                    response = getJson(url);
                    if (!"Ok".equalsIgnoreCase(response.optString("code","Ok")))
                        throw new Exception("No hay ruta desde el servidor principal");
                } catch (Exception primary) {
                    String backupUrl = ROUTE_BACKUP + String.format(Locale.US,
                            "%.6f,%.6f;%.6f,%.6f",
                            origin.getLongitude(), origin.getLatitude(),
                            target.longitude(), target.latitude())
                            + "?overview=full&geometries=geojson&steps=true";
                    response = getJson(backupUrl);
                }
                JSONArray routes = response.optJSONArray("routes");
                if (routes == null || routes.length() == 0) throw new Exception("No se encontró una ruta");
                JSONObject route = routes.getJSONObject(0);
                JSONArray points = route.getJSONObject("geometry").getJSONArray("coordinates");
                List<Point> poly = new ArrayList<>();
                for (int i = 0; i < points.length(); i++) {
                    JSONArray c = points.getJSONArray(i);
                    poly.add(Point.fromLngLat(c.getDouble(0), c.getDouble(1)));
                }
                List<Step> steps = new ArrayList<>();
                JSONArray legs = route.getJSONArray("legs");
                for (int l = 0; l < legs.length(); l++) {
                    JSONArray s = legs.getJSONObject(l).getJSONArray("steps");
                    for (int i = 0; i < s.length(); i++) {
                        JSONObject item = s.getJSONObject(i);
                        JSONObject maneuver = item.getJSONObject("maneuver");
                        JSONArray at = maneuver.getJSONArray("location");
                        String type = maneuver.optString("type", "");
                        String modifier = maneuver.optString("modifier", "");
                        String road = item.optString("name", "");
                        String verb = "Sigue recto";
                        if ("turn".equals(type) || "fork".equals(type)) {
                            verb = modifier.contains("left") ? "Gira a la izquierda"
                                    : modifier.contains("right") ? "Gira a la derecha" : "Continúa";
                        } else if ("roundabout".equals(type) || "rotary".equals(type)) verb = "Entra a la rotonda";
                        else if ("arrive".equals(type)) verb = "Llegaste a tu destino";
                        else if ("depart".equals(type)) verb = "Inicia el recorrido";
                        steps.add(new Step(verb + (road.isEmpty() ? "" : " · " + road),
                                at.getDouble(1), at.getDouble(0)));
                    }
                }
                double distance = route.optDouble("distance", 0);
                double duration = route.optDouble("duration", 0);
                runOnUiThread(() -> {
                    if (isFinishing() || destination != target) return;
                    routePoints.clear(); routePoints.addAll(poly);
                    instructions.clear(); instructions.addAll(steps);
                    instructionIndex = 0;
                    if (lineSource != null && poly.size() > 1)
                        lineSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(poly)));
                    guidance.setText(String.format(Locale.getDefault(),
                            "Ruta lista · %.1f km · aprox. %.0f min", distance / 1000., duration / 60.));
                    status.setText("Ruta en línea · seguimiento GPS activo");
                    following = true;
                    if (fix != null) moveCamera(fix, true);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        guidance.setText("No se calculó la ruta · pulsa Calcular ruta para reintentar");
                        status.setText("Servicio de rutas no disponible: "+ex.getClass().getSimpleName());
                    }
                });
            } finally {
                runOnUiThread(() -> requestingRoute = false);
            }
        });
    }

    private Bitmap makeArrow() {
        Bitmap bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(7f); border.setColor(Color.WHITE);
        border.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(BLUE);
        Path triangle = new Path();
        triangle.moveTo(40, 6);
        triangle.lineTo(14, 70);
        triangle.lineTo(40, 57);
        triangle.lineTo(66, 70);
        triangle.close();
        canvas.drawPath(triangle, border);
        canvas.drawPath(triangle, fill);
        return bitmap;
    }

    private void renderFix(Location value) {
        if (userSource != null) {
            userSource.setGeoJson(Feature.fromGeometry(
                    Point.fromLngLat(value.getLongitude(), value.getLatitude())));
        }
        if (userArrowLayer != null && value.hasBearing() && value.getSpeed() >= 0.8f)
            userArrowLayer.setProperties(PropertyFactory.iconRotate(value.getBearing()));
        if (following) moveCamera(value, false);
    }

    private void moveCamera(Location value, boolean immediate) {
        if (map == null) return;
        float bearing = value.hasBearing() && value.getSpeed() >= 1.0f ? value.getBearing() : 0f;
        CameraPosition camera = new CameraPosition.Builder()
                .target(new LatLng(value.getLatitude(), value.getLongitude()))
                .zoom(routePoints.size() > 0 ? 16.5 : 16.0)
                .tilt(45.0)
                .bearing(bearing)
                .build();
        long now=SystemClock.elapsedRealtime();
        if(!immediate && lastCameraFix!=null &&
           value.distanceTo(lastCameraFix)<2.0f && now-lastCameraElapsed<1800L)return;
        lastCameraFix = new Location(value);
        lastCameraElapsed=now;
        if (immediate) map.moveCamera(CameraUpdateFactory.newCameraPosition(camera));
        else map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 600);
    }

    @Override public void onLocationChanged(Location value) {
        if (value == null || !value.hasAccuracy()) return;
        if (value.getAccuracy() > 150) {
            status.setText("GPS impreciso (" + Math.round(value.getAccuracy()) + " m)");
            return;
        }
        if (fix != null && value.getTime() < fix.getTime()) return;
        fix = value;
        lastGpsElapsed = SystemClock.elapsedRealtime();
        renderFix(value);
        status.setText("GPS activo · ±" + Math.round(value.getAccuracy()) + " m"
                + (value.hasSpeed() ? " · " + Math.round(value.getSpeed() * 3.6f) + " km/h" : ""));
        if (destination == null) return;
        if (routePoints.isEmpty()) { calculateRoute(true); return; }
        Point p = Point.fromLngLat(value.getLongitude(), value.getLatitude());
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < routePoints.size(); i += Math.max(1, routePoints.size() / 250)) {
            Point rp = routePoints.get(i);
            double dx = (p.longitude() - rp.longitude()) * 111320.0
                    * Math.cos(Math.toRadians(p.latitude()));
            double dy = (p.latitude() - rp.latitude()) * 111320.0;
            nearest = Math.min(nearest, Math.hypot(dx, dy));
        }
        if (nearest > 125) calculateRoute(true); // prueba reconexión con intervalo mínimo
        if (instructionIndex < instructions.size()) {
            Step step = instructions.get(instructionIndex);
            Location checkpoint = new Location("route");
            checkpoint.setLatitude(step.lat); checkpoint.setLongitude(step.lon);
            if (value.distanceTo(checkpoint) < 30 && instructionIndex < instructions.size() - 1)
                instructionIndex++;
            step = instructions.get(instructionIndex);
            checkpoint.setLatitude(step.lat); checkpoint.setLongitude(step.lon);
            guidance.setText(step.instruction + " · " + Math.round(value.distanceTo(checkpoint)) + " m");
        }
        Location end = new Location("end");
        end.setLatitude(destination.latitude());
        end.setLongitude(destination.longitude());
        if (value.distanceTo(end) < 30) guidance.setText("Has llegado al destino");
    }

    private void updateFreshness() {
        if (isFinishing()) return;
        if (lastGpsElapsed >= 0) {
            long age = (SystemClock.elapsedRealtime() - lastGpsElapsed) / 1000;
            if (age > 5) status.setText("GPS sin actualizar · hace " + age + " s (última posición)");
        }
        handler.postDelayed(this::updateFreshness, 1000);
    }

    private void startLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        try {
            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 750, 0, this);
            if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1250, 0, this);
            Location old = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (old != null && System.currentTimeMillis() - old.getTime() < 15000)
                onLocationChanged(old);
            else status.setText("Buscando satélites GPS…");
        } catch (SecurityException e) {
            status.setText("Concede permisos de ubicación precisa");
        } catch (Exception e) {
            status.setText("GPS Android no disponible en este dispositivo");
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == LOCATION_REQUEST && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            startLocation();
        else status.setText("Activa ubicación precisa en ajustes Android");
    }

    @Override protected void onStart() { super.onStart(); mapView.onStart(); startLocation(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() { mapView.onPause(); super.onPause(); }
    @Override protected void onStop() {
        try { locations.removeUpdates(this); } catch (Exception ignored) {}
        mapView.onStop(); super.onStop();
    }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onDestroy() {
        ++searchRequest;
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        searchNetwork.shutdownNow();
        mapView.onDestroy(); super.onDestroy();
    }
}
