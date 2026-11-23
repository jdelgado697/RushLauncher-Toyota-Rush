package cl.rush.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Calibrador CAN pasivo; no conoce ni inventa un PID de luces de Toyota.
 * Escucha brevemente ATMA del ELM327 y compara OFF/ON/OFF/ON con el
 * vehículo DETENIDO. No manda mensajes CAN de control ni peticiones UDS.
 * Algunos gateways no exponen luces en OBD2: entonces no habrá candidatos.
 */
final class LightCanMonitor {
    static final class Profile {
        final String id;
        final int index;
        final int mask;
        final int onBit;
        Profile(String frameId,int byteIndex,int bitMask,int valueWhenOn) {
            id=frameId.toUpperCase(Locale.US);
            index=byteIndex;mask=bitMask;onBit=valueWhenOn;
        }
        JSONObject json(){
            JSONObject result=new JSONObject();
            try {
                result.put("id",id);result.put("byte",index);
                result.put("mask",mask);result.put("onBit",onBit);
            } catch(Exception ignored){}
            return result;
        }
    }

    static final class Stats {
        int count=0;
        int[] and=new int[8],or=new int[8];
        Stats(){for(int i=0;i<8;i++)and[i]=255;}
        void add(int[] bytes){
            count++;
            for(int i=0;i<Math.min(8,bytes.length);i++){
                and[i]&=bytes[i];or[i]|=bytes[i];
            }
        }
        boolean stableBit(int index,int bit) {
            return count>=4 && (and[index]&bit)==(or[index]&bit);
        }
        int value(int index,int bit){return (and[index]&bit)!=0?1:0;}
    }

    static final class Capture {
        final Map<String,Stats> frames=new HashMap<>();
        int messages=0;
        void add(String frameId,int[] bytes){
            if(bytes.length<1 || bytes.length>8)return;
            Stats s=frames.get(frameId);
            if(s==null){s=new Stats();frames.put(frameId,s);}
            s.add(bytes);messages++;
        }
    }

    static Capture listen(OutputStream out,InputStream in,long ms) throws Exception {
        Capture result=new Capture();
        String protocol=exchange(out,in,"ATDPN",1600).replaceAll("[^A-Z0-9]","");
        // Sólo ISO 15765-4 CAN (6/7/8/9) y protocolo ya negociado.
        if(!protocol.matches(".*[A]?[6789].*"))
            throw new IllegalStateException("ELM327 no usa protocolo CAN (ATDPN="+protocol+")");
        exchange(out,in,"ATH1",1200);
        exchange(out,in,"ATS1",1200);
        StringBuilder raw=new StringBuilder(48000);
        try{
            while(in.available()>0)in.read();
            out.write("ATMA\r".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long deadline=System.currentTimeMillis()+ms;
            while(System.currentTimeMillis()<deadline&&raw.length()<115000){
                int avail=in.available();
                if(avail>0){
                    int c=in.read();
                    if(c<0)break;
                    if(c=='>')break; // monitor sin soporte / buffer lleno
                    raw.append((char)c);
                }else Thread.sleep(10);
            }
            // ATMA en modo continuo: CR lo interrumpe antes de otra consulta.
            out.write('\r');
            out.flush();
            long stop=System.currentTimeMillis()+1200;
            while(System.currentTimeMillis()<stop){
                if(in.available()>0){if(in.read()=='>')break;}
                else Thread.sleep(10);
            }
        }finally{
            try{exchange(out,in,"ATH0",1300);}catch(Exception ignored){}
            try{exchange(out,in,"ATS0",1300);}catch(Exception ignored){}
        }
        for(String line:raw.toString().toUpperCase(Locale.US).split("[\\r\\n]+")){
            String trimmed=line.trim();
            if(trimmed.startsWith("ATMA") || trimmed.contains("BUFFER FULL") ||
                trimmed.contains("STOPPED") || trimmed.contains("SEARCHING"))continue;
            String[] parts=trimmed.split("\\s+");
            if(parts.length<3)continue;
            String id=parts[0];
            if(!(id.matches("[0-9A-F]{3}")||id.matches("[0-9A-F]{8}")))continue;
            // Evitar confundir respuestas diagnósticas del motor con señales
            // broadcast y excluir las identificaciones UDS/OBD comunes.
            if(id.length()==3){
                int arb=Integer.parseInt(id,16);
                if(arb>=0x7E0&&arb<=0x7EF)continue;
            }
            int[] bytes=new int[Math.min(8,parts.length-1)];
            int n=0;
            for(int i=1;i<parts.length&&n<8;i++){
                if(!parts[i].matches("[0-9A-F]{2}"))break;
                bytes[n++]=Integer.parseInt(parts[i],16);
            }
            if(n<2)continue;
            int[] value=new int[n];
            System.arraycopy(bytes,0,value,0,n);
            result.add(id,value);
        }
        return result;
    }

    static ArrayList<Profile> candidates(Capture[] four){
        ArrayList<Profile> candidates=new ArrayList<>();
        if(four.length!=4)return candidates;
        for(Capture capture:four)if(capture==null)return candidates;
        Set<String> ids=new HashSet<>(four[0].frames.keySet());
        for(int s=1;s<4;s++)ids.retainAll(four[s].frames.keySet());
        for(String id:ids){
            Stats[] z=new Stats[4];
            for(int s=0;s<4;s++)z[s]=four[s].frames.get(id);
            for(int i=0;i<8;i++){
                for(int mask=1;mask<256;mask*=2){
                    if(!z[0].stableBit(i,mask)||!z[1].stableBit(i,mask)||
                       !z[2].stableBit(i,mask)||!z[3].stableBit(i,mask))continue;
                    int off=z[0].value(i,mask),on=z[1].value(i,mask);
                    if(on==off||z[2].value(i,mask)!=off||z[3].value(i,mask)!=on)
                        continue;
                    candidates.add(new Profile(id,i,mask,on));
                }
            }
        }
        candidates.sort(Comparator.comparing((Profile p)->p.id)
                .thenComparingInt(p->p.index).thenComparingInt(p->p.mask));
        if(candidates.size()>32)
            return new ArrayList<>(candidates.subList(0,32));
        return candidates;
    }

    static Boolean read(Profile profile,Capture capture) {
        if(profile==null||capture==null)return null;
        Stats stats=capture.frames.get(profile.id);
        if(stats==null||stats.count<2||!stats.stableBit(profile.index,profile.mask))
            return null; // tramas ausentes o bits inestables no son luces OFF
        return stats.value(profile.index,profile.mask)==profile.onBit;
    }

    static String exchange(OutputStream out,InputStream in,String cmd,long timeout)
            throws Exception {
        while(in.available()>0)in.read();
        out.write((cmd+"\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder response=new StringBuilder();
        long end=System.currentTimeMillis()+timeout;
        while(System.currentTimeMillis()<end){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(in.available()>0) {
                int c=in.read();
                if(c<0)break;
                if(c=='>')break;
                if(response.length()<8192)response.append((char)c);
            } else Thread.sleep(12);
        }
        return response.toString().toUpperCase(Locale.US);
    }
}
