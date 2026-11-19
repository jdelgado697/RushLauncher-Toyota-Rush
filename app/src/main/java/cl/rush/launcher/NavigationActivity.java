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
    private static final String USER_AGENT = "RushLauncher/4.6.0 (personal Android car navigation; github.com/jdelgado697/RushLauncher-Toyota-Rush)";
    private static final int BLUE = Color.rgb(55, 129, 255);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Point> routePoints = new ArrayList<>();
    private final List<Step> instructions = new ArrayList<>();

    private MapView mapView;
    private MapLibreMap map;
    private GeoJsonSource userSource, destinationSource, lineSource;
    private SymbolLayer userArrowLayer;
    private EditText query;
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
    private Runnable pendingSearch;

    private static final class Step {
        String instruction;
        double lat, lon;
        Step(String text, double latitude, double longitude) {
            instruction = text; lat = latitude; lon = longitude;
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
        mapView = new MapView(this);
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
        Button center = button("◎ GPS");
        searchRow.addView(center, new LinearLayout.LayoutParams(dp(84), -1));
        center.setOnClickListener(v -> {
            following = true;
            if (fix != null) moveCamera(fix, true);
            else Toast.makeText(this, "Esperando ubicación Android", Toast.LENGTH_SHORT).show();
        });

        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(false);
        resultScroll.setBackground(bg(0xF51A2341, 12));
        suggestionBox = new LinearLayout(this);
        suggestionBox.setOrientation(LinearLayout.VERTICAL);
        resultScroll.addView(suggestionBox);
        top.addView(resultScroll, new LinearLayout.LayoutParams(-1, -2));
        resultScroll.setVisibility(View.GONE);
        suggestionBox.setTag(resultScroll);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xE9101937);
        bottom.setPadding(dp(10), dp(5), dp(10), dp(5));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottom, bp);
        guidance = text("Rush Navigator · busca un destino para calcular ruta", 14, Color.WHITE);
        guidance.setMaxLines(2);
        bottom.addView(guidance, new LinearLayout.LayoutParams(-1, -2));
        status = text("Esperando señal GPS · mapa necesita internet", 11, 0xFFAFC7E8);
        bottom.addView(status, new LinearLayout.LayoutParams(-1, -2));

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
            search(query.getText().toString().trim());
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
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false;
            });
        });
        locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        updateFreshness();
    }

    private void scheduleSearch(String term) {
        searchRequest++;
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        if (term.trim().length() < 3) {
            hideSuggestions();
            return;
        }
        pendingSearch = () -> search(term.trim());
        handler.postDelayed(pendingSearch, 650); // Mantiene uso moderado de Photon
    }

    private void hideSuggestions() {
        if (suggestionBox != null && suggestionBox.getTag() instanceof View)
            ((View) suggestionBox.getTag()).setVisibility(View.GONE);
    }

    private void search(String term) {
        if (term.length() < 3) return;
        final int ticket = ++searchRequest;
        final double lat = fix != null ? fix.getLatitude() : -38.74;
        final double lon = fix != null ? fix.getLongitude() : -72.59;
        status.setText("Buscando destinos en Chile…");
        network.execute(() -> {
            try {
                String url = SEARCH + "?q=" + URLEncoder.encode(term, "UTF-8")
                        + "&limit=5&lang=es&countrycode=CL&lat="
                        + lat + "&lon=" + lon;
                JSONObject json = getJson(url);
                JSONArray found = json.optJSONArray("features");
                List<String> titles = new ArrayList<>();
                List<Point> coords = new ArrayList<>();
                if (found != null) for (int i = 0; i < found.length(); i++) {
                    JSONObject f = found.optJSONObject(i);
                    if (f == null) continue;
                    JSONObject prop = f.optJSONObject("properties");
                    JSONObject geo = f.optJSONObject("geometry");
                    if (prop == null || geo == null) continue;
                    JSONArray c = geo.optJSONArray("coordinates");
                    if (c == null || c.length() < 2) continue;
                    String name = prop.optString("name", "Lugar");
                    String city = prop.optString("city", prop.optString("state", ""));
                    titles.add(name + (city.isEmpty() ? "" : " · " + city));
                    coords.add(Point.fromLngLat(c.optDouble(0), c.optDouble(1)));
                }
                runOnUiThread(() -> {
                    if (ticket != searchRequest || isFinishing()) return;
                    suggestionBox.removeAllViews();
                    if (titles.isEmpty()) {
                        TextView t = text("Sin coincidencias. Prueba otro nombre.", 13, Color.WHITE);
                        suggestionBox.addView(t);
                    }
                    for (int i = 0; i < titles.size(); i++) {
                        final int index = i;
                        Button option = button(titles.get(i));
                        option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                        option.setBackground(bg(0xFF1C294C, 6));
                        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44));
                        p.setMargins(0, dp(2), 0, dp(2));
                        suggestionBox.addView(option, p);
                        option.setOnClickListener(v -> selectDestination(coords.get(index), titles.get(index)));
                    }
                    ((View) suggestionBox.getTag()).setVisibility(View.VISIBLE);
                    status.setText("Resultados en Chile · selecciona un destino");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (ticket == searchRequest && !isFinishing())
                        status.setText("No se pudo buscar: comprueba internet");
                });
            }
        });
    }

    private void selectDestination(Point point, String title) {
        destination = point;
        hideSuggestions();
        query.setText(title);
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        ++searchRequest; // invalida cualquier búsqueda antigua
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
        if (destinationSource != null) destinationSource.setGeoJson(Feature.fromGeometry(point));
        if (map != null) map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(new LatLng(point.latitude(), point.longitude()), 14), 500);
        if (fix != null) calculateRoute(false);
        else {
            guidance.setText("Destino seleccionado · esperando ubicación GPS");
            status.setText("Activa Ubicación precisa en Android");
        }
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
                JSONObject response = getJson(url);
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
                        guidance.setText("No se pudo calcular ruta. Reintenta con internet.");
                        status.setText("El servicio de rutas puede estar temporalmente no disponible");
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
                .zoom(routePoints.size() > 0 ? 16.5 : 15.0)
                .tilt(routePoints.size() > 0 ? 45.0 : 0.0)
                .bearing(bearing)
                .build();
        if (immediate) map.moveCamera(CameraUpdateFactory.newCameraPosition(camera));
        else map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 650);
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
        if (routePoints.isEmpty()) { calculateRoute(false); return; }
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
        mapView.onDestroy(); super.onDestroy();
    }
}
