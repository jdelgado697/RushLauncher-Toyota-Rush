package cl.rush.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rush Diagnostic 1.0 — SAE/J1979 OBD-II genérico de SOLO LECTURA.
 * No emite Service 04 (borrado), comandos UDS de escritura, ni pruebas activas.
 * La conexión serial pertenece al único worker Bluetooth de ObdReader.
 * Un dato no soportado NO equivale a "sin fallas".
 */
final class RushObdDiagnostics {
    interface Command { String ask(String command,long timeout) throws Exception; }
    interface Progress { void message(String progress); }
    private RushObdDiagnostics(){}

    static JSONObject scan(Command command,Progress progress) throws Exception {
        JSONObject result=new JSONObject();
        JSONArray notes=new JSONArray();
        result.put("kind","result");
        result.put("version",1);
        result.put("timestamp",System.currentTimeMillis());
        result.put("readOnly",true);
        result.put("scope","OBD-II genérico de motor/emisiones; no ABS, SRS ni caja Toyota");

        progress.message("Leyendo testigo motor y preparación de emisiones…");
        try{
            result.put("readiness",readReadiness(command.ask("0101",1650)));
        }catch(Exception e){
            result.put("readiness",JSONObject.NULL);
            notes.put("Preparación de emisiones: "+errorLabel(e));
        }

        String[] commands={"03","07","0A"};
        String[] names={"confirmed","pending","permanent"};
        String[] labels={"confirmadas","pendientes","permanentes"};
        for(int i=0;i<commands.length;i++){
            progress.message("Consultando fallas "+labels[i]+"…");
            try{
                JSONObject section=readCodes(command.ask(commands[i],1850),commands[i]);
                result.put(names[i],section);
                if(!section.optBoolean("supported",false))
                    notes.put("El módulo no confirmó la lectura de fallas "+labels[i]+".");
            }catch(Exception e){
                JSONObject unavailable=new JSONObject();
                unavailable.put("supported",false);
                unavailable.put("codes",new JSONArray());
                unavailable.put("message",errorLabel(e));
                result.put(names[i],unavailable);
            }
        }

        progress.message("Buscando datos congelados de una falla…");
        JSONObject frozen=new JSONObject();
        String[] framePids={"02","04","05","0C","0D"};
        for(String pid:framePids){
            try {
                int[] bytes=responseData(command.ask("02"+pid+"00",1300),
                        "42",pid,1);
                if(bytes==null)continue;
                if("02".equals(pid) && bytes.length>=3){
                    String code=dtc(bytes[1],bytes[2]);
                    if(!"P0000".equals(code))frozen.put("triggerCode",code);
                }else if("04".equals(pid)&&bytes.length>=2){
                    frozen.put("engineLoadPct",round(bytes[1]*100.0/255.0));
                }else if("05".equals(pid)&&bytes.length>=2){
                    frozen.put("coolantC",bytes[1]-40);
                }else if("0C".equals(pid)&&bytes.length>=3){
                    frozen.put("rpm",round((bytes[1]*256.0+bytes[2])/4.0));
                }else if("0D".equals(pid)&&bytes.length>=2){
                    frozen.put("speedKmh",bytes[1]);
                }
            }catch(Exception e){
                // Algunos ELM/ECU no implementan Mode 02.
            }
        }
        frozen.put("supported",frozen.length()>0);
        if(frozen.length()==1)notes.put("ECU no devolvió datos congelados disponibles.");
        result.put("freezeFrame",frozen);

        progress.message("Consultando información de identificación de vehículo…");
        try{
            String vin=decodeVin(command.ask("0902",2300));
            if(vin!=null)result.put("vin",vin);
            else notes.put("VIN no disponible mediante Service 09.");
        }catch(Exception e){notes.put("VIN no disponible mediante este adaptador.");}
        result.put("notes",notes);
        return result;
    }

    static JSONObject readReadiness(String response) throws Exception {
        int[] data=responseData(response,"41","01",4);
        if(data==null)return JSONObject.NULL instanceof JSONObject?null:unsupportedReadiness();
        int a=data[0],b=data[1],c=data[2],d=data[3];
        JSONObject result=new JSONObject();
        result.put("supported",true);
        result.put("milOn",(a&0x80)!=0);
        result.put("storedDtcCount",a&0x7F);
        result.put("sparkIgnition",(b&0x08)==0);
        result.put("supportedContinuous",b&0x07);
        result.put("incompleteContinuous",(b>>4)&0x07);
        result.put("supportedOther",c);
        result.put("incompleteOther",d);
        int continuous=Integer.bitCount(((b>>4)&0x07)&(b&0x07));
        int nonContinuous=Integer.bitCount(c&d);
        result.put("incompleteMonitors",continuous+nonContinuous);
        result.put("monitorsComplete",continuous+nonContinuous==0);
        return result;
    }
    private static JSONObject unsupportedReadiness() throws Exception {
        JSONObject o=new JSONObject();o.put("supported",false);
        o.put("message","ECU no confirmó Service 01 PID 01");
        return o;
    }
    private static double round(double d) {
        return Math.round(d*10.0)/10.0;
    }
    private static String errorLabel(Exception e){
        String s=e.getMessage();
        return s==null?"Sin respuesta válida":s.length()>100?s.substring(0,100):s;
    }

    static JSONObject readCodes(String response,String service) throws Exception{
        String marker="0A".equals(service)?"4A":"07".equals(service)?"47":"43";
        JSONObject section=new JSONObject();
        JSONArray list=new JSONArray();
        Set<String> found=new LinkedHashSet<>();
        boolean valid=false;
        for(String line:cleanLines(response)){
            byte[] bytes=extractMode(line,marker);
            if(bytes==null)continue;
            valid=true;
            // J1979 Service 03/07/0A: pares de bytes, 00 00 = relleno.
            for(int i=0;i+1<bytes.length && found.size()<32;i+=2) {
                int a=bytes[i]&0xFF,b=bytes[i+1]&0xFF;
                if(a==0&&b==0)continue;
                String code=dtc(a,b);
                if(!"P0000".equals(code))found.add(code);
            }
        }
        for(String code:found)list.put(code);
        section.put("supported",valid);
        section.put("codes",list);
        section.put("count",list.length());
        if(!valid)section.put("message",
            response!=null&&response.toUpperCase(Locale.US).contains("NO DATA")?
            "ELM indicó NO DATA; no se confirma ausencia de fallas":
            "Sin respuesta válida de este servicio OBD-II");
        return section;
    }

    static String dtc(int a,int b){
        final char[] groups={'P','C','B','U'};
        return String.format(Locale.US,"%c%01X%01X%02X",
            groups[(a>>6)&3],(a>>4)&3,a&0x0F,b&0xFF);
    }

    static int[] responseData(String response,String mode,String pid,int minData){
        if(response==null)return null;
        for(String line:cleanLines(response)){
            byte[] a=extractMode(line,mode);
            if(a==null)continue;
            if(pid!=null){
                if(a.length<1 || (a[0]&0xff)!=Integer.parseInt(pid,16))continue;
                // Mode 02 includes index of freeze frame immediately after PID.
                if("42".equals(mode)){
                    if(a.length<2 || (a[1]&0xff)!=0)return null;
                }
            }
            int dataOffset=pid==null?0:1;
            if(a.length-dataOffset<minData)return null;
            int[] out=new int[a.length-dataOffset];
            for(int i=0;i<out.length;i++)out[i]=a[dataOffset+i]&0xff;
            return out;
        }
        return null;
    }

    private static String[] cleanLines(String response){
        return response==null?new String[0]:
            response.toUpperCase(Locale.US).replace('>',' ').split("[\\r\\n]+");
    }

    private static byte[] extractMode(String line,String mode){
        if(line==null)return null;
        // ELM AT headers disabled (ATH0): lines normally "43 01 33".
        // Some dongles include CAN header and length: "7E8 05 43 01 33".
        // Some remove spaces: "430133".
        String raw=line.replaceAll("[^0-9A-F ]"," ").trim();
        if(raw.isEmpty())return null;
        String[] tokens=raw.split("\\s+");
        java.util.ArrayList<Integer> bytes=new java.util.ArrayList<>();
        for(String tok:tokens){
            if(tok.matches("[0-9A-F]{2}"))
                bytes.add(Integer.parseInt(tok,16));
            else if(tok.matches("[0-9A-F]{4,64}") && tok.length()%2==0){
                for(int i=0;i<tok.length();i+=2)
                    bytes.add(Integer.parseInt(tok.substring(i,i+2),16));
            } else if(tok.matches("[0-9A-F]{3}") || tok.matches("[0-9A-F]{8}")){
                // Encabezado de CAN, no forma parte de carga OBD.
            }
        }
        int marker=Integer.parseInt(mode,16);
        int index=-1;
        for(int i=0;i<bytes.size();i++) {
            if(bytes.get(i)==marker){index=i;break;}
        }
        if(index<0)return null;
        int count=Math.min(90,bytes.size()-index-1);
        byte[] out=new byte[count];
        for(int i=0;i<count;i++)out[i]=(byte)(int)bytes.get(index+i+1);
        return out;
    }

    static String decodeVin(String response){
        if(response==null)return null;
        // ELM con CAF1 puede devolver "49 02 01 <ASCII>" en múltiples
        // tramas etiquetadas "0: ...", "1: ...".
        StringBuilder ascii=new StringBuilder();
        boolean started=false;
        for(String line:cleanLines(response)){
            String t=line.trim().replaceFirst("^[0-9A-F]{1,2}:","").trim();
            String[] tokens=t.split("\\s+");
            java.util.ArrayList<Integer> bytes=new java.util.ArrayList<>();
            for(String token:tokens){
                if(token.matches("[0-9A-F]{2}"))
                    bytes.add(Integer.parseInt(token,16));
                else if(token.matches("[0-9A-F]{4,50}")&&token.length()%2==0)
                    for(int j=0;j<token.length();j+=2)
                        bytes.add(Integer.parseInt(token.substring(j,j+2),16));
            }
            int index=0;
            for(int j=0;j+2<bytes.size();j++){
                if(bytes.get(j)==0x49&&bytes.get(j+1)==0x02){
                    index=j+3;started=true;break;
                }
            }
            if(!started)continue;
            for(int j=index;j<bytes.size();j++){
                int c=bytes.get(j);
                if(c>=48 && c<=90)ascii.append((char)c);
            }
            if(ascii.length()>=17)break;
        }
        String value=ascii.toString();
        Matcher matcher=Pattern.compile("[A-HJ-NPR-Z0-9]{17}").matcher(value);
        return matcher.find()?matcher.group():null;
    }
}
