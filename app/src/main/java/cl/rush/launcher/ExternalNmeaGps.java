package cl.rush.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Receptor TCP NMEA para GPS Metrics o GPS2IP en Wi-Fi local.
 * NO inyecta ubicaciones simuladas en Android ni modifica otros programas.
 * Acepta RMC con checksum válido y estado A. RMC no proporciona precisión;
 * por tanto se notifica accuracy=null al tablero.
 */
final class ExternalNmeaGps {
    interface Listener {
        void onStatus(String status);
        void onFix(double lat, double lon, Double kmh, Double bearing);
    }

    private final Listener listener;
    private volatile boolean active;
    private volatile Socket socket;
    private volatile Thread worker;
    private volatile String host = "";
    private volatile int port = 10110;

    ExternalNmeaGps(Listener listener) {
        this.listener = listener;
    }

    static boolean validLocalIpv4(String input) {
        if (input == null || !input.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] parts = input.split("\\.");
        int[] n = new int[4];
        try {
            for (int i=0;i<4;i++) {
                n[i]=Integer.parseInt(parts[i]);
                if (n[i]<0 || n[i]>255) return false;
            }
        } catch(NumberFormatException ignored) { return false; }
        return n[0]==10 || (n[0]==192 && n[1]==168)
            || (n[0]==172 && n[1]>=16 && n[1]<=31);
    }

    synchronized void connect(String address, int tcpPort) {
        if (!validLocalIpv4(address) || tcpPort<1024 || tcpPort>65535) {
            listener.onStatus("IP o puerto no válidos: usa IPv4 privada del iPhone");
            return;
        }
        disconnect();
        host=address;port=tcpPort;
        active=true;
        worker=new Thread(this::runLoop,"Rush-NMEA-TCP");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void disconnect() {
        active=false;
        Socket prior=socket;socket=null;
        if(prior!=null)try{prior.close();}catch(Exception ignored){}
        Thread t=worker;worker=null;
        if(t!=null)t.interrupt();
    }

    private void runLoop() {
        int failures=0;
        while(active && !Thread.currentThread().isInterrupted()) {
            Socket client=new Socket();
            socket=client;
            try {
                listener.onStatus("Conectando al GPS del iPhone: "+host+":"+port);
                client.connect(new InetSocketAddress(host,port),6000);
                client.setSoTimeout(12000);
                client.setTcpNoDelay(true);
                failures=0;
                listener.onStatus("TCP conectado · esperando RMC del iPhone");
                try(BufferedReader input = new BufferedReader(
                        new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII))) {
                    String line;
                    int invalid=0;
                    long lastFix=0;
                    while(active && !Thread.currentThread().isInterrupted() && (line=input.readLine())!=null) {
                        Fix fix=parseRmc(line);
                        if(fix!=null) {
                            long now=android.os.SystemClock.elapsedRealtime();
                            // 1+ Hz basta para un mapa en TBox 4 GB.
                            if(now-lastFix>650) {
                                lastFix=now;
                                listener.onFix(fix.latitude,fix.longitude,fix.speedKmh,fix.bearing);
                            }
                            invalid=0;
                        } else if ((line.startsWith("$GPRMC") || line.startsWith("$GNRMC"))
                                && ++invalid==8) {
                            listener.onStatus("NMEA RMC recibido, pero sin posición válida");
                        }
                    }
                }
            } catch(SocketTimeoutException e) {
                if(active)listener.onStatus("GPS iPhone sin datos nuevos durante 12 segundos");
            } catch(Exception e) {
                if(active)listener.onStatus("GPS iPhone TCP: "+e.getClass().getSimpleName());
            } finally {
                try{client.close();}catch(Exception ignored){}
                if(socket==client)socket=null;
            }
            if(!active)break;
            failures++;
            long pause=Math.min(12000L,2500L*failures);
            try{Thread.sleep(pause);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        listener.onStatus("GPS iPhone desconectado");
    }

    static final class Fix {
        final double latitude,longitude;
        final Double speedKmh,bearing;
        Fix(double lat,double lon,Double speed,Double angle) {
            latitude=lat;longitude=lon;speedKmh=speed;bearing=angle;
        }
    }

    /** NMEA 0183 RMC: $GPRMC,time,A,ddmm.mmmmm,N,dddmm.mmmmm,E,knots,course,...*CS */
    static Fix parseRmc(String raw) {
        if(raw==null)return null;
        String line=raw.trim();
        if(!line.startsWith("$") || line.length()>256)return null;
        int star=line.indexOf('*');
        if(star<0 || star+3!=line.length())return null;
        String payload=line.substring(1,star);
        int check=0;
        for(int i=0;i<payload.length();i++)check^=payload.charAt(i);
        int stated;
        try { stated=Integer.parseInt(line.substring(star+1),16); }
        catch(NumberFormatException ex) { return null; }
        if(check!=stated)return null;
        String[] v=payload.split(",",-1);
        if(v.length<10 || (!"GPRMC".equals(v[0]) && !"GNRMC".equals(v[0]) &&
                !"GLRMC".equals(v[0])))return null;
        if(!"A".equals(v[2]))return null;
        double latitude=decimalDegrees(v[3],v[4],true);
        double longitude=decimalDegrees(v[5],v[6],false);
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude))return null;
        Double speed=nonnegative(v[7]);
        if(speed!=null)speed*=1.852;
        Double course=nonnegative(v[8]);
        if(course!=null)course=course%360.0;
        return new Fix(latitude,longitude,speed,course);
    }

    private static double decimalDegrees(String position,String hemisphere,boolean latitude) {
        int digits=latitude?2:3;
        if(position==null || position.length()<digits+2 || hemisphere.length()!=1)
            return Double.NaN;
        try {
            double degrees=Double.parseDouble(position.substring(0,digits));
            double minutes=Double.parseDouble(position.substring(digits));
            if(minutes>=60 || minutes<0 || !Double.isFinite(minutes))return Double.NaN;
            double value=degrees+minutes/60.0;
            if(latitude && value>90 || !latitude&&value>180)return Double.NaN;
            if(("S".equals(hemisphere) && latitude) ||
               ("W".equals(hemisphere) && !latitude))return -value;
            if(("N".equals(hemisphere)&&latitude) ||
               ("E".equals(hemisphere)&&!latitude))return value;
        }catch(NumberFormatException ignored){}
        return Double.NaN;
    }

    private static Double nonnegative(String text) {
        try {
            if(text==null||text.isEmpty())return null;
            double num=Double.parseDouble(text);
            return Double.isFinite(num)&&num>=0?num:null;
        }catch(NumberFormatException ignored){return null;}
    }
}
