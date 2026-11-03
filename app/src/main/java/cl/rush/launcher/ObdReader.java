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
import java.util.Set;
import java.util.UUID;

/**
 * Lector ELM327 Bluetooth Classic (SPP), exclusivamente comandos de diagnostico
 * Mode 01: velocidad, RPM y temperatura de refrigerante. No envia comandos ECU.
 */
public final class ObdReader {
    public interface Listener {
        void onStatus(String message);
        void onData(int kmh, int rpm, int coolantC);
    }

    private static final UUID SERIAL_PORT =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile BluetoothSocket socket;
    private Thread worker;

    public ObdReader(Listener listener) {
        this.listener = listener;
    }

    private void status(String message) {
        ui.post(() -> listener.onStatus(message));
    }

    private void data(int speed, int rpm, int temp) {
        ui.post(() -> listener.onData(speed, rpm, temp));
    }

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
            } catch (Exception ignored) {}
        }
        return result;
    }

    @SuppressWarnings("MissingPermission")
    public synchronized void connect(String macAddress) {
        disconnect();
        if (macAddress == null || !BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            status("Direccion Bluetooth no valida");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status("Bluetooth desactivado en Android");
            return;
        }
        running = true;
        worker = new Thread(() -> {
            BluetoothSocket localSocket = null;
            try {
                status("Conectando OBD-II...");
                BluetoothDevice device = adapter.getRemoteDevice(macAddress);
                localSocket = device.createRfcommSocketToServiceRecord(SERIAL_PORT);
                socket = localSocket;
                localSocket.connect();
                InputStream in = localSocket.getInputStream();
                OutputStream out = localSocket.getOutputStream();
                send(out, in, "ATZ", 3500);
                send(out, in, "ATE0", 2500);
                send(out, in, "ATL0", 2500);
                send(out, in, "ATS0", 2500);
                send(out, in, "ATH0", 2500);
                String protocol = send(out, in, "ATSP0", 4000);
                if (protocol.contains("ERROR")) throw new IllegalStateException("ELM327 no responde");
                status("OBD-II conectado · leyendo datos");
                while (running) {
                    int speed = parsePid(send(out, in, "010D", 2800), "0D");
                    int rpm = parsePid(send(out, in, "010C", 2800), "0C");
                    int coolant = parsePid(send(out, in, "0105", 2800), "05");
                    if (!running) break;
                    data(speed, rpm, coolant);
                    Thread.sleep(650);
                }
            } catch (Exception ex) {
                if (running) status("OBD no disponible: " + shortMessage(ex));
            } finally {
                closeSocket(localSocket);
                if (socket == localSocket) socket = null;
                running = false;
            }
        }, "Rush-OBD-ReadOnly");
        worker.start();
    }

    public synchronized void disconnect() {
        running = false;
        closeSocket(socket);
        socket = null;
        status("OBD desconectado");
        data(-1, -1, -1000);
    }

    private static String shortMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) return ex.getClass().getSimpleName();
        return message.length() <= 100 ? message : message.substring(0, 100);
    }

    private static void closeSocket(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }

    private static String send(OutputStream out, InputStream in, String command, long timeout)
            throws Exception {
        while (in.available() > 0) in.read();
        out.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        long end = System.currentTimeMillis() + timeout;
        StringBuilder response = new StringBuilder();
        while (System.currentTimeMillis() < end) {
            int available = in.available();
            if (available > 0) {
                int value = in.read();
                if (value < 0) throw new IllegalStateException("OBD cerro conexion");
                if ((char) value == '>') return response.toString().toUpperCase();
                if (response.length() < 1024) response.append((char) value);
            } else {
                Thread.sleep(30);
            }
        }
        return response.toString().toUpperCase();
    }

    /** Devuelve -1 si PID no es compatible. Temperatura utiliza -1000 en ausencia de dato. */
    static int parsePid(String response, String pid) {
        if (response == null) return pid.equals("05") ? -1000 : -1;
        String flat = response.toUpperCase().replaceAll("\\s+", "");
        String header = "41" + pid;
        int index = flat.indexOf(header);
        if (index < 0) return pid.equals("05") ? -1000 : -1;
        String values = flat.substring(index + header.length());
        try {
            if (pid.equals("0C") && values.length() >= 4) {
                int a = Integer.parseInt(values.substring(0, 2), 16);
                int b = Integer.parseInt(values.substring(2, 4), 16);
                return (a * 256 + b) / 4;
            }
            if (values.length() >= 2) {
                int a = Integer.parseInt(values.substring(0, 2), 16);
                if (pid.equals("0D")) return a;
                if (pid.equals("05")) return a - 40;
            }
        } catch (NumberFormatException ignored) {}
        return pid.equals("05") ? -1000 : -1;
    }
}
