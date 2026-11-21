package cl.rush.launcher;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.LruCache;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.zip.InflaterInputStream;

/**
 * Rutas y ciudades de TODO Chile sin conexion, a partir de datos OSM/Geofabrik.
 * Render raster 256px bajo demanda a partir de una BD SQLite espacial incluida.
 * No descarga ni precarga imagenes del servidor tile.openstreetmap.org.
 * Es mapa offline de orientacion, NO enrutamiento offline ni traffic offline.
 * OpenStreetMap contributors, licencia ODbL.
 */
final class OfflineChileTiles {
    private static final String ASSET="chile_offline.db";
    private static final String HOST="rush-offline.local";
    private final Context context;
    private SQLiteDatabase db;
    private final LruCache<String, byte[]> cache = new LruCache<String, byte[]>(8*1024*1024){
        @Override protected int sizeOf(String key, byte[] value){return value.length;}
    };
    private volatile boolean ready=false;
    private volatile boolean assetPresent=false;
    private volatile boolean copyFailed=false;
    OfflineChileTiles(Context c){
        context=c.getApplicationContext();
        try(InputStream in=context.getAssets().open(ASSET)){assetPresent=true;}
        catch(Exception ignored){}
    }
    boolean available(){return assetPresent;}
    boolean ready(){return ready;}
    void warmUp(){open();}
    private synchronized void open(){
        if(ready||!assetPresent||copyFailed)return;
        File file=new File(context.getFilesDir(),"chile_offline_4208.db");
        File tmp=new File(context.getFilesDir(),"chile_offline_4208.db.part");
        try{
            if(!file.exists()||file.length()<2_000_000){
                if(tmp.exists())tmp.delete();
                try(InputStream input=context.getAssets().open(ASSET);
                    FileOutputStream output=new FileOutputStream(tmp)){
                    byte[] buf=new byte[256*1024];
                    int n;
                    while((n=input.read(buf))!=-1)output.write(buf,0,n);
                    output.getFD().sync();
                }
                if(!tmp.renameTo(file))throw new IllegalStateException("No se pudo activar BD offline");
            }
            db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,
                SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try(Cursor cur=db.rawQuery("SELECT COUNT(*) FROM roads",null)){
                if(!cur.moveToFirst()||cur.getInt(0)<20000)
                    throw new IllegalStateException("BD de Chile incompleta");
            }
            ready=true;
        }catch(Exception e){copyFailed=true;if(db!=null){try{db.close();}catch(Exception ignored){}}db=null;}
    }
    private static int color(String s){return Color.parseColor(s);}
    private static double mercatorY(double lat){
        double r=Math.toRadians(Math.max(-85.0511,Math.min(85.0511,lat)));
        return (1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2;
    }
    static WebResourceResponse intercept(String url, OfflineChileTiles self){
        try{
            android.net.Uri uri=android.net.Uri.parse(url);
            if(!HOST.equals(uri.getHost()))return null;
            String[] part=uri.getPath().split("/");
            if(part.length<4)return self.emptyResponse();
            int z=Integer.parseInt(part[1]);
            int x=Integer.parseInt(part[2]);
            String leaf=part[3];
            int dot=leaf.indexOf('.');
            int y=Integer.parseInt(dot<0?leaf:leaf.substring(0,dot));
            if(z<3||z>19||x<0||y<0||x>=(1<<z)||y>=(1<<z))
                return self.emptyResponse();
            byte[] result=self.render(z,x,y);
            return new WebResourceResponse("image/png",null,
                new ByteArrayInputStream(result));
        }catch(Exception ignored){
            return self.emptyResponse();
        }
    }
    private WebResourceResponse emptyResponse(){
        // Transparent 1x1: never request this synthetic hostname from network.
        byte[] transparent=android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWc8AAAAASUVORK5CYII=",0);
        return new WebResourceResponse("image/png",null,new ByteArrayInputStream(transparent));
    }
    private synchronized byte[] render(int z,int tx,int ty){
        String key=z+"/"+tx+"/"+ty;
        byte[] hit=cache.get(key);
        if(hit!=null)return hit;
        open();
        if(db==null)return transparentPng();
        Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        canvas.drawColor(color("#ece9e8"));
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        double n=Math.pow(2,z), west=(tx/n*360-180),east=((tx+1)/n*360-180);
        double north=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*ty/n))));
        double south=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*(ty+1)/n))));
        // Margen necesario para trazos de carretera en borde de tesela.
        double dLon=(east-west)*.07,dLat=(north-south)*.07;
        int maxType=z<9?1:z<11?2:z<13?4:z<14?6:8;
        int maxFeatures=z<10?700:z<13?1800:3000;
        String sql="SELECT roads.kind,roads.name,roads.geom FROM bounds "+
            "JOIN roads ON roads.id=bounds.id WHERE bounds.minx<=? AND "+
            "bounds.maxx>=? AND bounds.miny<=? AND bounds.maxy>=? "+
            "AND roads.kind<=? ORDER BY roads.kind DESC LIMIT "+maxFeatures;
        try(Cursor rows=db.rawQuery(sql,new String[]{
                Double.toString(east+dLon),Double.toString(west-dLon),
                Double.toString(north+dLat),Double.toString(south-dLat),
                Integer.toString(maxType)})){
            while(rows.moveToNext()){
                int kind=rows.getInt(0);
                String name=rows.getString(1);
                byte[] bytes=rows.getBlob(2);
                if(bytes==null||bytes.length<17)continue;
                byte[] raw=decompress(bytes);
                if(raw.length<16||raw.length%8!=0)continue;
                ByteBuffer points=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                Path shape=new Path();
                float prevX=0,prevY=0;boolean first=true;
                float anchorX=0,anchorY=0;
                for(int i=0;i<raw.length/8;i++){
                    double lon=points.getInt()/1e6,lat=points.getInt()/1e6;
                    float x=(float)((lon+180)/360*n*256-tx*256);
                    float y=(float)(mercatorY(lat)*n*256-ty*256);
                    if(first){shape.moveTo(x,y);anchorX=x;anchorY=y;first=false;}
                    else shape.lineTo(x,y);
                    prevX=x;prevY=y;
                }
                if(kind<0){
                    if(kind==-3){
                        boolean closed=Math.hypot(prevX-anchorX,prevY-anchorY)<2;
                        if(closed){p.setStyle(Paint.Style.FILL);p.setColor(color("#81d4ed"));canvas.drawPath(shape,p);}
                        else {p.setStyle(Paint.Style.STROKE);p.setColor(color("#8cd7e9"));p.setStrokeWidth(z>=14?2:1);canvas.drawPath(shape,p);}
                    }else if(kind==-2){
                        boolean closed=Math.hypot(prevX-anchorX,prevY-anchorY)<2;
                        p.setStyle(closed?Paint.Style.FILL:Paint.Style.STROKE);
                        p.setColor(color("#c2e5bd"));p.setStrokeWidth(1);canvas.drawPath(shape,p);
                    }else{
                        p.setStyle(Paint.Style.STROKE);p.setColor(color("#54bcd9"));p.setStrokeWidth(1.4f);canvas.drawPath(shape,p);
                    }
                    continue;
                }
                float width=z>=17?1.5f:z>=15?1.12f:z>=13?.9f:.75f;
                width*=kind<=1?5f:kind<=2?4:kind<=3?3:kind<=4?2.4f:kind<=6?1.5f:.9f;
                // Jerarquia vial dibujada de secundarias a principales.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(width+Math.max(1,width*.28f));
                p.setColor(color(kind<=3?"#d7c5ad":"#dce1dd"));
                canvas.drawPath(shape,p);
                p.setStrokeWidth(width);
                p.setColor(color(kind<=1?"#f0d4a2":kind<=3?"#f7e6c9":"#fafaf0"));
                canvas.drawPath(shape,p);
                if(z>=15 && kind<=5 && name!=null && !name.isEmpty()
                    && raw.length>=24 && anchorX>=8 && anchorX<240 &&
                    anchorY>=14 && anchorY<240){
                    // Etiqueta discreta, no forzar miles de nombres.
                    if((name.hashCode()&3)!=0)continue;
                    p.setStyle(Paint.Style.FILL);p.setColor(color("#607c8b"));
                    p.setTextSize(z>=17?10:8);
                    canvas.drawText(name.length()>22?name.substring(0,22):name,
                        anchorX,anchorY-5,p);
                }
            }
        }catch(Exception ignored){}
        if(z<=13)drawPlaces(canvas,z,tx,ty,west,east,south,north);
        ByteArrayOutputStream result=new ByteArrayOutputStream(12000);
        bitmap.compress(Bitmap.CompressFormat.PNG,85,result);
        bitmap.recycle();
        byte[] output=result.toByteArray();
        cache.put(key,output);
        return output;
    }
    private void drawPlaces(Canvas canvas,int z,int tx,int ty,
                            double west,double east,double south,double north){
        if(z<4)return;
        int maxKind=z<7?0:z<10?1:3;
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color("#426175"));paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
            android.graphics.Typeface.BOLD));
        paint.setTextSize(z<=8?12:10);
        String sql="SELECT lon,lat,kind,name FROM places WHERE lon>=? AND lon<=? "+
            "AND lat>=? AND lat<=? AND kind<=? LIMIT 100";
        try(Cursor rows=db.rawQuery(sql,new String[]{Double.toString(west),
                Double.toString(east),Double.toString(south),
                Double.toString(north),Integer.toString(maxKind)})){
            double n=Math.pow(2,z);
            while(rows.moveToNext()){
                double lon=rows.getDouble(0),lat=rows.getDouble(1);
                float x=(float)((lon+180)/360*n*256-tx*256);
                float y=(float)(mercatorY(lat)*n*256-ty*256);
                canvas.drawCircle(x,y,2.5f,paint);
                String name=rows.getString(3);
                if(name!=null)canvas.drawText(name.length()>24?name.substring(0,24):name,
                    Math.min(205,x+4),Math.max(11,y-4),paint);
            }
        }catch(Exception ignored){}
    }
    private static byte[] decompress(byte[] bytes){
        if(bytes[0]==0)return java.util.Arrays.copyOfRange(bytes,1,bytes.length);
        try(InputStream in=new InflaterInputStream(
                new ByteArrayInputStream(bytes,1,bytes.length));
            ByteArrayOutputStream out=new ByteArrayOutputStream(512)){
            byte[] buf=new byte[1024];int count;
            while((count=in.read(buf))>=0){
                if(out.size()+count>200000)return new byte[0];
                out.write(buf,0,count);
            }
            return out.toByteArray();
        }catch(Exception e){return new byte[0];}
    }
    private static byte[] transparentPng(){
        return android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWc8AAAAASUVORK5CYII=",0);
    }
    synchronized void close(){
        cache.evictAll();
        if(db!=null){db.close();db=null;}
        ready=false;
    }
}
