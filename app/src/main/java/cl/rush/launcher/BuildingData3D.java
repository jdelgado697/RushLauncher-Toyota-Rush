package cl.rush.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Huellas REALES de edificios OSM para Rush 4.20.3.
 * No usa WebGL ni bloquea el hilo UI o las peticiones de navegación.
 * Consulta una ventana de 850 m; la interfaz limita las solicitudes a 100 s.
 * Si Overpass no responde, el mapa, GPS, tráfico y rutas siguen funcionando.
 */
final class BuildingData3D {
    interface Listener {
        void onResult(int requestId, JSONObject result);
    }
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    void request(double latitude, double longitude, int radius, int requestId,
                 Listener listener) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) ||
                Math.abs(latitude)>85 || Math.abs(longitude)>180 ||
                requestId<=0 || requestId>10000000) {
            listener.onResult(requestId,error("Coordenadas inválidas"));
            return;
        }
        final int r=Math.max(180,Math.min(500,radius));
        executor.execute(() -> {
            JSONObject output;
            try { output=query(latitude,longitude,r); }
            catch (Exception exception) { output=error("Edificios OSM no disponibles"); }
            listener.onResult(requestId,output);
        });
    }

    private static JSONObject error(String reason) {
        JSONObject result=new JSONObject();
        try {result.put("ok",false);result.put("error",reason);}
        catch(Exception ignored){}
        return result;
    }

    private static JSONObject query(double lat,double lon,int radius) throws Exception {
        double latDelta=radius/111320.0;
        double lonDelta=radius/(111320.0*Math.max(.2,Math.cos(Math.toRadians(lat))));
        String bounds=String.format(Locale.US,"%.6f,%.6f,%.6f,%.6f",
                lat-latDelta,lon-lonDelta,lat+latDelta,lon+lonDelta);
        String q="[out:json][timeout:12];way[\"building\"]("+bounds+");out geom 220;";
        String[] hosts={
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass-api.de/api/interpreter"
        };
        Exception lastError=null;
        for(String host:hosts) {
            HttpURLConnection connection=null;
            try {
                String encoded=URLEncoder.encode(q,"UTF-8");
                URL url=new URL(host+"?data="+encoded);
                connection=(HttpURLConnection)url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12500);
                connection.setRequestProperty("Accept","application/json");
                connection.setRequestProperty("User-Agent",
                   "RushLauncher/4.20.3 (OSM building visualization; github.com/jdelgado697/RushLauncher-Toyota-Rush)");
                int http=connection.getResponseCode();
                if(http!=200)throw new IllegalStateException("Overpass HTTP "+http);
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                try(InputStream stream=connection.getInputStream()){
                    byte[] buffer=new byte[8192];
                    int count;
                    while((count=stream.read(buffer))!=-1){
                        if(output.size()+count>850000)
                            throw new IllegalStateException("Respuesta OSM demasiado extensa");
                        output.write(buffer,0,count);
                    }
                }
                JSONObject json=new JSONObject(output.toString("UTF-8"));
                JSONArray elements=json.optJSONArray("elements");
                if(elements==null)throw new IllegalStateException("JSON OSM no válido");
                JSONArray buildings=new JSONArray();
                for(int i=0;i<elements.length()&&buildings.length()<180;i++){
                    JSONObject element=elements.optJSONObject(i);
                    if(element==null || !"way".equals(element.optString("type")))continue;
                    JSONObject tags=element.optJSONObject("tags");
                    if(tags!=null && "no".equals(tags.optString("building")))continue;
                    JSONArray geometry=element.optJSONArray("geometry");
                    if(geometry==null||geometry.length()<4)continue;
                    JSONArray coords=new JSONArray();
                    int stride=Math.max(1,(int)Math.ceil(geometry.length()/48.0));
                    for(int j=0;j<geometry.length();j+=stride){
                        JSONObject point=geometry.optJSONObject(j);
                        if(point==null)continue;
                        double pLat=point.optDouble("lat",Double.NaN);
                        double pLon=point.optDouble("lon",Double.NaN);
                        if(!Double.isFinite(pLat)||!Double.isFinite(pLon))continue;
                        JSONArray coordinate=new JSONArray();
                        coordinate.put(pLat);coordinate.put(pLon);
                        coords.put(coordinate);
                    }
                    if(coords.length()<3)continue;
                    double height=9; // Solo la ALTURA puede estimarse, no la huella.
                    if(tags!=null){
                        double explicit=parseMeasure(tags.optString("height"));
                        double floors=parseMeasure(tags.optString("building:levels"));
                        if(explicit>0)height=explicit;
                        else if(floors>0)height=floors*3.2;
                    }
                    height=Math.max(4,Math.min(75,height));
                    JSONArray building=new JSONArray();
                    building.put(height);building.put(coords);
                    buildings.put(building);
                }
                JSONObject result=new JSONObject();
                result.put("ok",true);
                result.put("lat",lat);
                result.put("lon",lon);
                result.put("buildings",buildings);
                result.put("source","OpenStreetMap/Overpass");
                return result;
            }catch(Exception ex){
                lastError=ex;
            }finally{
                if(connection!=null)connection.disconnect();
            }
        }
        if(lastError!=null)throw lastError;
        return error("Consulta OSM no disponible");
    }

    private static double parseMeasure(String raw){
        if(raw==null)return Double.NaN;
        try {
            String clean=raw.trim().toLowerCase(Locale.ROOT).replace("metres","")
                .replace("meters","").replace("m","").trim();
            int separator=clean.indexOf(';');
            if(separator>=0)clean=clean.substring(0,separator);
            return Double.parseDouble(clean.trim());
        }catch(Exception ex){return Double.NaN;}
    }

    void shutdown(){executor.shutdownNow();}
}
