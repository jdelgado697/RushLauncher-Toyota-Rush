/* Rush Launcher 4.13 — seguimiento, flecha GPS y mapa rumbo arriba.
 * OpenStreetMap raster: solo teselas visibles; sin precarga ni descargas masivas.
 * Sin biblioteca remota, sin rutas simuladas, siempre muestra atribución.
 * https://operations.osmfoundation.org/policies/tiles/
 */
(function(){
'use strict';
var host=document.getElementById('realMap');
var layer=document.getElementById('mapTileLayer');
var marker=document.getElementById('mapPositionMarker');
var message=document.getElementById('mapWaiting');
var subtitle=document.getElementById('mapSubtitle');
var note=document.getElementById('locationStatus');
if(!host||!layer||!marker)return;
var zoom=14, center=null,position=null,heading=null,accuracy=null,following=true,loadedTiles=0,failedTiles=0;
var MIN_FOLLOW_METERS=4.0, active=false, lastDrawAt=0, drawTimer=null, drawRaf=null;
var pointers=new Map(),dragging=false,lastX=0,lastY=0,pinchDistance=0;
var tiles=new Map(),TILE=256,MAX_LAT=85.05112878,MAX_TILES=24,MAX_CACHED_TILES=32;
var drawPending=false,lastStatus='Esperando ubicación Android';
var osmtile='https://tile.openstreetmap.org/';
var routeGeometry=null,destination=null,selecting=false,downPoint=null,moved=0;
var driving=false,courseDegrees=0,hasCourse=false,speedKmh=0,visualShift=0,visualRotation=0;
function updateWorldTransform(h){
 visualShift=driving&&following?Math.round(Math.min(72,Math.max(28,h*.16))):0;
 visualRotation=driving&&following&&hasCourse?-courseDegrees:0;
 var transform=driving&&following?
   'translate3d(0,'+visualShift+'px,0) rotate('+visualRotation+'deg)':'';
 layer.style.transformOrigin='50% 50%';
 routeSvg.style.transformOrigin='50% 50%';
 layer.style.transform=transform;routeSvg.style.transform=transform;
 host.classList.toggle('is-navigating',driving&&following);
 marker.classList.toggle('is-navigation',driving&&following);
 marker.classList.toggle('is-unoriented',driving&&following&&!hasCourse);
}
function drivingMode(value){
 driving=!!value;following=true;selecting=false;
 if(driving){
  zoom=16;
  if(position)center={lat:position.lat,lon:position.lon};
 }else{layer.style.transform='';routeSvg.style.transform='';}
 requestDraw();
}
function overviewRoute(){
 driving=false;following=false;
 var coords=routeGeometry;
 if(!coords||coords.length<2){requestDraw();return;}
 var minLat=90,maxLat=-90,minLon=180,maxLon=-180;
 for(var i=0;i<coords.length;i++){
  var lon=coords[i][0],lat=coords[i][1];
  if(!Number.isFinite(lat)||!Number.isFinite(lon))continue;
  minLat=Math.min(minLat,lat);maxLat=Math.max(maxLat,lat);
  minLon=Math.min(minLon,lon);maxLon=Math.max(maxLon,lon);
 }
 if(minLat>maxLat){requestDraw();return;}
 center={lat:(minLat+maxLat)/2,lon:(minLon+maxLon)/2};
 var w=host.clientWidth||800,h=host.clientHeight||300;
 for(var z=17;z>=3;z--){
  var nw=mercator(maxLat,minLon,z),se=mercator(minLat,maxLon,z);
  if(Math.abs(se.x-nw.x)<=w*.75&&Math.abs(se.y-nw.y)<=h*.68){zoom=z;break;}
 }
 requestDraw();
}
function smoothCourse(next){
 if(!Number.isFinite(next))return;
 next=(next%360+360)%360;
 if(!hasCourse){courseDegrees=next;hasCourse=true;return;}
 var diff=((next-courseDegrees+540)%360)-180;
 courseDegrees=(courseDegrees+diff*.4+360)%360;
}

var routeShadow=document.getElementById('navRouteShadow'),routeLine=document.getElementById('navRouteLine');
var destinationPin=document.getElementById('navDestinationPin'),routeSvg=document.getElementById('navRouteOverlay');
function screenPoint(lat,lon,w,h,world){
 var pt=mercator(lat,lon,zoom),size=TILE*Math.pow(2,zoom),dx=pt.x-world.x;
 if(dx>size/2)dx-=size;if(dx<-size/2)dx+=size;
 return {x:w/2+dx,y:h/2+pt.y-world.y};
}
function drawNavigation(w,h,world){
 routeSvg.setAttribute('viewBox','0 0 '+w+' '+h);
 if(routeGeometry&&routeGeometry.length>1){
  var points=routeGeometry.map(function(c){
   var p=screenPoint(c[1],c[0],w,h,world);
   return p.x.toFixed(1)+','+p.y.toFixed(1);
  }).join(' ');
  routeLine.setAttribute('points',points);routeShadow.setAttribute('points',points);
 }else{routeLine.setAttribute('points','');routeShadow.setAttribute('points','');}
 if(destination){
  var p=screenPoint(destination.lat,destination.lon,w,h,world);
  destinationPin.style.left=p.x+'px';destinationPin.style.top=p.y+'px';
  destinationPin.hidden=p.x < -35||p.x>w+35||p.y < -35||p.y>h+35;
 }else destinationPin.hidden=true;
}
function setDestination(lat,lon){
 if(!Number.isFinite(lat)||!Number.isFinite(lon))return;
 destination={lat:lat,lon:lon};routeGeometry=null;selecting=false;requestDraw();
}
function setRoute(coords){
 routeGeometry=Array.isArray(coords)?coords.filter(function(p){
  return Array.isArray(p)&&p.length>=2&&Number.isFinite(p[0])&&Number.isFinite(p[1]);
 }).slice(0,1000):null;
 requestDraw();
}
function clearRoute(){routeGeometry=null;destination=null;selecting=false;requestDraw()}
function enableSelection(flag){selecting=!!flag;host.style.cursor=selecting?'crosshair':'default'}

function clamp(x,min,max){return Math.min(Math.max(x,min),max)}
function wrapLon(lon){return ((lon+180)%360+360)%360-180}
function mercator(lat,lon,z){
 var scale=TILE*Math.pow(2,z),r=clamp(lat,-MAX_LAT,MAX_LAT)*Math.PI/180;
 return {x:(wrapLon(lon)+180)/360*scale,y:(1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*scale};
}
function reverse(x,y,z){
 var world=TILE*Math.pow(2,z);
 return {lat:Math.atan(Math.sinh(Math.PI*(1-2*clamp(y,0,world)/world)))*180/Math.PI,lon:wrapLon(x/world*360-180)};
}
function status(value){lastStatus=String(value||'GPS no disponible');if(active&&!position)message.textContent=lastStatus}
// A lo sumo 5 repintados/segundo, sin consumir CPU en Inicio.
function requestDraw(){
 if(!active||drawPending)return;
 drawPending=true;
 var wait=Math.max(0,200-(Date.now()-lastDrawAt));
 drawTimer=setTimeout(function(){
  drawTimer=null;
  drawRaf=requestAnimationFrame(function(){
   drawRaf=null;drawPending=false;
   if(!active)return;
   lastDrawAt=Date.now();draw();
  });
 },wait);
}
function setCenter(lat,lon){if(!Number.isFinite(lat)||!Number.isFinite(lon))return;center={lat:clamp(lat,-MAX_LAT,MAX_LAT),lon:wrapLon(lon)};requestDraw()}
function metersBetween(a,b){
 if(!a||!b)return Infinity;
 var radians=Math.PI/180;
 var dlat=(a.lat-b.lat)*111320;
 var dlon=(a.lon-b.lon)*111320*Math.cos(a.lat*radians);
 return Math.sqrt(dlat*dlat+dlon*dlon);
}
// Sin animación por cuadro: solo seguimos ubicaciones recibidas.
function followTo(lat,lon){
 if(!active||!following||!Number.isFinite(lat)||!Number.isFinite(lon))return;
 if(!center||metersBetween(center,{lat:lat,lon:lon})>=MIN_FOLLOW_METERS)setCenter(lat,lon);
}
function moveBy(dx,dy){
 if(!center)return;
 var world=mercator(center.lat,center.lon,zoom);
 var next=reverse(world.x-dx,world.y-dy,zoom);
 center=next;requestDraw();
}
function setZoom(z){
 if(!center)return;
 var next=clamp(Math.round(z),3,18);
 if(zoom===next)return;
 zoom=next;requestDraw();
}
function tileName(z,x,y){return z+':'+x+':'+y}
function draw(){
 if(!active||!center||!host.clientWidth||!host.clientHeight)return;
 var w=host.clientWidth,h=host.clientHeight;
 var world=mercator(center.lat,center.lon,zoom);
 var worldWidth=TILE*Math.pow(2,zoom);
 var startX=world.x-w/2,startY=world.y-h/2;
 var xMin=Math.floor(startX/TILE),yMin=Math.max(0,Math.floor(startY/TILE));
 var xMax=Math.floor((startX+w-1)/TILE),yMax=Math.min(Math.pow(2,zoom)-1,Math.floor((startY+h-1)/TILE));
 var keep=new Set(),needed=0,changed=false;
 for(var y=yMin;y<=yMax;y++){
  for(var x=xMin;x<=xMax;x++){
   if(needed++>=MAX_TILES)break;
   var tx=((x%Math.pow(2,zoom))+Math.pow(2,zoom))%Math.pow(2,zoom);
   var id=tileName(zoom,tx,y);keep.add(id);
   var img=tiles.get(id);
   if(!img){
    img=document.createElement('img');
    img.className='map-tile';img.width=TILE;img.height=TILE;img.alt='';
    img.decoding='async';img.loading='eager';img.draggable=false;
    img.onload=function(){
     loadedTiles++;
     if(position&&message)message.hidden=true;
    };
    img.onerror=function(){
     failedTiles++;
     if(loadedTiles===0&&failedTiles>2){
      message.textContent='Mapa sin conexión: comprueba internet. Puedes abrir CarPlay.';
      message.hidden=false;
     }
    };
    img.src=osmtile+zoom+'/'+tx+'/'+y+'.png';
    layer.appendChild(img);tiles.set(id,img);
   }
   img.style.display='block';
   img.style.left=Math.round(x*TILE-startX)+'px';
   img.style.top=Math.round(y*TILE-startY)+'px';
  }
 }
 // No descargar una tesela de nuevo en cuanto el GPS oscila al cambiar de calle:
 // conservar solo un anillo pequeño de las ya vistas, sin precargar nuevas.
 for(var entry of tiles){
  if(!keep.has(entry[0]))entry[1].style.display='none';
 }
 if(tiles.size>MAX_CACHED_TILES){
  for(var old of tiles){
   if(tiles.size<=MAX_CACHED_TILES)break;
   if(!keep.has(old[0])){old[1].remove();tiles.delete(old[0])}
  }
 }
 drawNavigation(w,h,world);
 if(position){
  var point=mercator(position.lat,position.lon,zoom);
  var dx=point.x-world.x;
  if(dx>worldWidth/2)dx-=worldWidth;
  if(dx<-worldWidth/2)dx+=worldWidth;
  var px=w/2+dx,py=h/2+(point.y-world.y);
  marker.style.left=px+'px';
  marker.style.top=py+'px';
  marker.style.display=(px>-40&&px<w+40&&py>-40&&py<h+40)?'block':'none';
  marker.style.setProperty('--heading',Number.isFinite(heading)?heading+'deg':'0deg');
 }else marker.style.display='none';
 if(loadedTiles>0){
  message.hidden=true;
 }else{
  message.textContent=lastStatus||'Cargando mapa…';
  message.hidden=false;
 }
 if(subtitle&&position)subtitle.textContent=following?'Mapa 2D · seguimiento activo':'Mapa 2D · desliza para explorar';
 if(note&&position)note.textContent=Number.isFinite(accuracy)?'Ubicación Android · ±'+Math.round(accuracy)+' m':'Ubicación Android';
 document.getElementById('mapRecenter').classList.toggle('not-following',!following);
}
function updatePosition(lat,lon,acc){
 if(!Number.isFinite(lat)||!Number.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
 var previous=position;
 position={lat:lat,lon:lon};
 accuracy=Number.isFinite(acc)?Math.max(0,acc):null;
 if(!active)return;
 if(!center){setCenter(lat,lon)}
 else if(following){followTo(lat,lon);requestDraw()}
 else if(!previous||metersBetween(previous,position)>2){requestDraw()}
 if(!loadedTiles){message.textContent='Cargando calles reales…'}
}
function recenter(){
 following=true;
 if(position){setCenter(position.lat,position.lon);return}
 status('Solicitando ubicación del TBox…');
 if(window.Rush&&typeof window.Rush.startMapGps==='function')window.Rush.startMapGps();
}
document.getElementById('mapZoomIn').addEventListener('click',function(e){e.stopPropagation();setZoom(zoom+1)});
document.getElementById('mapZoomOut').addEventListener('click',function(e){e.stopPropagation();setZoom(zoom-1)});
document.getElementById('mapRecenter').addEventListener('click',function(e){e.stopPropagation();recenter()});
host.addEventListener('pointerdown',function(e){
 if(e.target.closest('button')||e.target.closest('a'))return;
 host.setPointerCapture(e.pointerId);
 pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
 downPoint={x:e.clientX,y:e.clientY};moved=0;
 dragging=true;lastX=e.clientX;lastY=e.clientY;
 if(pointers.size===2){
  var a=[...pointers.values()];
  pinchDistance=Math.hypot(a[0].x-a[1].x,a[0].y-a[1].y);
 }
});
host.addEventListener('pointermove',function(e){
 if(!pointers.has(e.pointerId))return;
 var old=pointers.get(e.pointerId);
 moved+=Math.abs(e.clientX-old.x)+Math.abs(e.clientY-old.y);
 pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
 if(pointers.size===2){
  var vals=[...pointers.values()],d=Math.hypot(vals[0].x-vals[1].x,vals[0].y-vals[1].y);
  if(pinchDistance>0&&d/pinchDistance>1.40){setZoom(zoom+1);pinchDistance=d}
  else if(pinchDistance>0&&d/pinchDistance<.72){setZoom(zoom-1);pinchDistance=d}
 }else if(pointers.size===1&&center&&!selecting){
  var dx=e.clientX-old.x,dy=e.clientY-old.y;
  if(Math.abs(dx)+Math.abs(dy)>0.6){
   following=false;
   moveBy(dx,dy);
  }
 }
 e.preventDefault();
},{passive:false});
function release(e){
 if(selecting&&downPoint&&moved<15&&center&&pointers.size===1){
  var r=host.getBoundingClientRect(),p=mercator(center.lat,center.lon,zoom);
  var target=reverse(p.x+(e.clientX-r.left-r.width/2),
                     p.y+(e.clientY-r.top-r.height/2),zoom);
  setDestination(target.lat,target.lon);
  if(typeof window.rushNavPicked==='function')window.rushNavPicked(target);
 }
 downPoint=null;pointers.delete(e.pointerId);
 dragging=pointers.size>0;if(pointers.size<2)pinchDistance=0;
}
host.addEventListener('pointerup',release);
host.addEventListener('pointercancel',release);
host.addEventListener('lostpointercapture',release);
host.addEventListener('wheel',function(e){if(!center)return;e.preventDefault();setZoom(zoom+(e.deltaY<0?1:-1))},{passive:false});
host.addEventListener('keydown',function(e){
 if(e.target!==host)return;
 if(e.key==='ArrowLeft'){following=false;moveBy(60,0)}
 else if(e.key==='ArrowRight'){following=false;moveBy(-60,0)}
 else if(e.key==='ArrowUp'){following=false;moveBy(0,60)}
 else if(e.key==='ArrowDown'){following=false;moveBy(0,-60)}
 else if(e.key==='+'||e.key==='=')setZoom(zoom+1);
 else if(e.key==='-')setZoom(zoom-1);
 else return;
 e.preventDefault();
});
window.addEventListener('resize',requestDraw);
var observer=typeof ResizeObserver==='function'?new ResizeObserver(requestDraw):null;
if(observer)observer.observe(host);
function activate(){
 if(active){requestDraw();return;}
 active=true;following=true;loadedTiles=0;failedTiles=0;
 if(position&&!center)center={lat:position.lat,lon:position.lon};
 message.textContent=position?'Cargando calles reales…':'Esperando ubicación del TBox…';
 message.hidden=false;requestDraw();
}
function deactivate(){
 active=false;following=true;
 if(drawTimer!==null){clearTimeout(drawTimer);drawTimer=null;}
 if(drawRaf!==null){cancelAnimationFrame(drawRaf);drawRaf=null;}
 drawPending=false;
 for(var entry of tiles){
  var image=entry[1];image.onload=null;image.onerror=null;
  image.removeAttribute('src');image.remove();
 }
 tiles.clear();loadedTiles=0;failedTiles=0;
 position=null;center=null;heading=null;accuracy=null;
 routeGeometry=null;destination=null;selecting=false;
 marker.style.display='none';message.textContent='Mapa pausado';message.hidden=false;
 if(subtitle)subtitle.textContent='Mapa pausado · sin descargas';
 if(note)note.textContent='Ubicación desactivada';
}
window.RushLiveMap={
 activate:activate,
 deactivate:deactivate,
 updatePosition:updatePosition,
 updateHeading:function(h){heading=Number.isFinite(h)?h:null;requestDraw()},
 setStatus:status,
 recenter:recenter,
 setDestination:setDestination,
 setRoute:setRoute,
 clearRoute:clearRoute,
 enableSelection:enableSelection,
 getDestination:function(){return destination},
 getPosition:function(){return position},
 getState:function(){return {active:active,position:position,center:center,zoom:zoom,following:following,tiles:tiles.size,route:!!routeGeometry}}
};
status('Esperando ubicación Android…');
})();
