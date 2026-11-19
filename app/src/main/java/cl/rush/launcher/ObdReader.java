package cl.rush.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ELM327 Bluetooth Classic SPP. Exclusivamente Mode 01 de lectura y ATRV.
 * Consulta PIDs soportados, nunca escribe parámetros ni borra errores ECU.
 */
public final class ObdReader {
    public interface Listener {
        void onStatus(String message);
        void onData(JSONObject snapshot);
    }

    private static final UUID SERIAL_PORT =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String[] EXTRA = {"04","0B","0F","11","06","07","3C","RV"};
    private static final String[] ALL = {"0D","0C","05","04","0B","0F","11","06","07","3C"};
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile BluetoothSocket socket;
    private volatile Thread worker;

    public ObdReader(Listener listener) { this.listener=listener; }
    /** Conexión Bluetooth o lectura en curso; evita múltiples RFCOMM simultáneos. */
    public boolean isActive() { return running; }
    private void status(String msg) { ui.post(() -> listener.onStatus(msg)); }
    private void data(JSONObject snapshot) { ui.post(() -> listener.onData(snapshot)); }

    @SuppressWarnings("MissingPermission")
    public JSONArray pairedDevices() {
        JSONArray result = new JSONArray();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return result;
        Set<BluetoothDevice> paired = adapter.getBondedDevices();
        if (paired == null) return result;
        for (BluetoothDevice device : paired) {
            JSONObject d = new JSONObject();
            try {
                d.put("name", device.getName() == null ? "OBD Bluetooth" : device.getName());
                d.put("address", device.getAddress());
                result.put(d);
            } catch (Exception ignored) { }
        }
        return result;
    }

    @SuppressWarnings("MissingPermission")
    public synchronized void connect(String macAddress) {
        disconnect();
        if (macAddress == null || !BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            status("Direccion Bluetooth no valida"); return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status("Bluetooth desactivado en Android"); return;
        }
        running=true;
        worker = new Thread(() -> {
            BluetoothSocket local = null;
            try {
                status("Conectando ELM327...");
                BluetoothDevice device = adapter.getRemoteDevice(macAddress);
                local = device.createRfcommSocketToServiceRecord(SERIAL_PORT);
                socket=local;
                local.connect();
                InputStream in = local.getInputStream();
                OutputStream out = local.getOutputStream();
                send(out,in,"ATZ",3500);
                send(out,in,"ATE0",2000);
                send(out,in,"ATL0",2000);
                send(out,in,"ATS0",2000);
                send(out,in,"ATH0",2000);
                String protocol=send(out,in,"ATSP0",4000);
                if (protocol.contains("ERROR")) throw new IllegalStateException("ELM327 no responde");
                send(out,in,"ATAT1",1600);
                send(out,in,"ATST32",1600);
                Set<String> supported = new HashSet<>();
                boolean known = readSupport(out,in,"00",supported);
                // El bloque 0120 solo es válido si la ECU indica continuidad en 20.
                if (known && supported.contains("20")) {
                    boolean next = readSupport(out,in,"20",supported);
                    if (!next) known=false;
                }
                Map<String,Double> values=new HashMap<>();
                status(known ? "OBD-II conectado · sensores detectados" :
                        "OBD-II conectado · sondeo de sensores");
                int cycle=0;
                while(running && Thread.currentThread()==worker) {
                    for(String pid : new String[]{"0D","0C","05"}) {
                        if(!running) break;
                        if(known && !supported.contains(pid)) {
                            values.remove(pid); continue;
                        }
                        Double v=readPid(out,in,pid,1500);
                        if(v==null)values.remove(pid); else values.put(pid,v);
                    }
                    String extra=EXTRA[cycle % EXTRA.length];
                    if(running) {
                        if("RV".equals(extra)) {
                            Double voltage=readVoltage(send(out,in,"ATRV",1500));
                            if(voltage==null) values.remove("RV"); else values.put("RV",voltage);
                        } else if(!known || supported.contains(extra)) {
                            Double v=readPid(out,in,extra,1500);
                            if(v==null) values.remove(extra); else values.put(extra,v);
                        }
                        data(snapshot(values,supported,known));
                    }
                    cycle++;
                    Thread.sleep(220);
                }
            } catch(Exception ex) {
                if(running && Thread.currentThread()==worker)
                    status("OBD no disponible: "+shortMessage(ex));
            } finally {
                closeSocket(local);
                if(socket==local)socket=null;
                if(Thread.currentThread()==worker){running=false;worker=null;}
            }
        },"Rush-OBD-ReadOnly");
        worker.start();
    }

    public synchronized void disconnect() {
        running=false;
        Thread previous=worker;
        worker=null;
        closeSocket(socket);
        socket=null;
        if(previous!=null)previous.interrupt();
        status("OBD desconectado");
        data(new JSONObject());
    }

    private static JSONObject snapshot(Map<String,Double> values,Set<String> supported,
                                       boolean known) {
        JSONObject obj=new JSONObject();
        String[] keys={"speed","rpm","coolant","engineLoad","map","iat",
                "throttle","stft1","ltft1","catalyst","voltage"};
        String[] pids={"0D","0C","05","04","0B","0F","11","06","07","3C","RV"};
        try{
            for(int i=0;i<keys.length;i++) {
                Double value=values.get(pids[i]);
                obj.put(keys[i],value==null?JSONObject.NULL:value);
            }
            JSONArray detected=new JSONArray();
            for(String pid: ALL) if(supported.contains(pid))detected.put(pid);
            obj.put("supported",detected);
            obj.put("supportedKnown",known);
            obj.put("updatedAt",System.currentTimeMillis());
        }catch(Exception ignored){ }
        return obj;
    }

    // Mode 01 support bitmap: first response "41 XX A B C D".
    private static boolean readSupport(OutputStream out,InputStream in,String block,
                                       Set<String> detected) throws Exception {
        String answer=send(out,in,"01"+block,2200);
        int[] bytes=responseBytes(answer,block,4);
        if(bytes==null)return false;
        long mask=((long)bytes[0]<<24)|((long)bytes[1]<<16)|
                ((long)bytes[2]<<8)|bytes[3];
        int base=Integer.parseInt(block,16);
        for(int bit=0;bit<32;bit++)
            if((mask & (1L << (31-bit)))!=0)
                detected.add(String.format(Locale.US,"%02X",base+bit+1));
        return true;
    }

    private static Double readPid(OutputStream out,InputStream in,String pid,long timeout)
            throws Exception {
        String response=send(out,in,"01"+pid,timeout);
        int n=("0C".equals(pid)||"3C".equals(pid))?2:1;
        int[] a=responseBytes(response,pid,n);
        if(a==null)return null;
        switch(pid){
            case "0D": return (double)a[0];
            case "0C": return (a[0]*256.0+a[1])/4.0;
            case "05": case "0F": return (double)(a[0]-40);
            case "04": case "11": return a[0]*100.0/255.0;
            case "0B": return (double)a[0];
            case "06": case "07": return (a[0]-128.0)*100.0/128.0;
            case "3C": return (a[0]*256.0+a[1])/10.0-40.0;
            default: return null;
        }
    }

    // Ignora ecos, prefijos de CAN y respuestas NO DATA; nunca inventa números.
    private static int[] responseBytes(String response,String pid,int required) {
        if(response==null)return null;
        String compact=response.toUpperCase(Locale.US).replaceAll("[^0-9A-F]","");
        String header="41"+pid;
        int idx=compact.indexOf(header);
        if(idx<0 || compact.length()<idx+header.length()+required*2)return null;
        int[] bytes=new int[required];
        try{
            for(int j=0;j<required;j++) {
                int p=idx+header.length()+j*2;
                bytes[j]=Integer.parseInt(compact.substring(p,p+2),16);
            }
            return bytes;
        }catch(Exception ex){return null;}
    }

    private static Double readVoltage(String response) {
        if(response==null)return null;
        Matcher m=Pattern.compile("(\\d{1,2}(?:\\.\\d{1,2})?)\\s*V",
                Pattern.CASE_INSENSITIVE).matcher(response);
        if(!m.find())return null;
        try{
            double v=Double.parseDouble(m.group(1));
            return v>=0 && v<=30 ? v:null;
        }catch(Exception ignored){return null;}
    }
    private static String shortMessage(Exception ex) {
        String msg=ex.getMessage();
        if(msg==null||msg.trim().isEmpty())return ex.getClass().getSimpleName();
        return msg.length()>90?msg.substring(0,90):msg;
    }
    private static void closeSocket(BluetoothSocket s) {
        if(s!=null)try{s.close();}catch(Exception ignored){}
    }
    private static String send(OutputStream out,InputStream in,String command,long timeout)
            throws Exception {
        while(in.available()>0)in.read();
        out.write((command+"\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        long end=System.currentTimeMillis()+timeout;
        StringBuilder response=new StringBuilder();
        while(System.currentTimeMillis()<end) {
            if(Thread.currentThread().isInterrupted())
                throw new InterruptedException("Lectura interrumpida");
            int available=in.available();
            if(available>0) {
                int c=in.read();
                if(c<0)throw new IllegalStateException("OBD cerro conexion");
                if(c=='>')return response.toString().toUpperCase(Locale.US);
                if(response.length()<1024)response.append((char)c);
            } else Thread.sleep(25);
        }
        return response.toString().toUpperCase(Locale.US);
    }
}
