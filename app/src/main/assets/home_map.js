/* Rush Launcher 4.15.4 — mapa de Inicio manipulable y seguimiento de conducción.
 * No incrusta el mapa GL pesado de la pantalla de navegación: utiliza pocas
 * teselas raster para proteger los 4 GB del TBox. GPS real o referencia explícita.
 * © OpenStreetMap contributors. Con API TomTom opcional: © TomTom.
 * Tráfico sujeto a la disponibilidad del proveedor y clave propia.
 */
(function(){
'use strict';
var host=document.querySelector('.home-map');
var layer=document.getElementById('homeMapTiles');
var marker=document.getElementById('homeMapMarker');
var surface=document.getElementById('homeMapTouch');
var label=document.getElementById('homeMapStatus');
var info=document.getElementById('homeMapInfo');
var followButton=document.getElementById('homeMapFollow');
var zoomIn=document.getElementById('homeMapZoomIn');
var zoomOut=document.getElementById('homeMapZoomOut');
if(!host||!layer||!marker||!surface||!followButton||!zoomIn||!zoomOut)return;
if(window.Rush3D)window.Rush3D.mount(host,layer);

var current={lat:-39.367,lon:-72.632},position=null;
// Vista de conducción cercana a nivel calle; los cambios de rumbo no
// cambian la fuente GPS, únicamente hacen más ágil su presentación.
var mapStyle='drive';
try{mapStyle=localStorage.getItem('rush_map_style')==='classic'?'classic':'drive'}catch(e){}
var zoom=mapStyle==='drive'?18:15,following=true,bearing=0,visualBearing=0,
    live=false,accuracy=null,tiles=Object.create(null),motionSpeed=0,
    autoZoom=mapStyle==='drive',lastAutoZoomAt=0;
host.classList.toggle('rush-map-drive',mapStyle==='drive');
var lastDirectionAt=0;
var lastDraw=0,drawQueued=false,activePointer=null,lastTouch=0;
var maxTiles=25, tile=256, minLat=-85.05112878, maxLat=85.05112878;
function driveOffset(){return following&&live&&mapStyle==='drive'?
  Math.round(host.clientHeight*0.115):0}
var lastGpsAt=0,lastRenderedCenter=null;
var externalActive=false;
var lastActualMoveAt=0,rawDiagnostic=null,lastManualPanAt=0;
var previousFix=null;
var centerMotion=null,centerMotionFrame=0,lastSmoothDraw=0;
var offlineAvailable=!!(window.Rush&&typeof window.Rush.offlineMapAvailable==='function'&&window.Rush.offlineMapAvailable());
var hasInternet=true,lastNetworkCheck=0,offlineMode=false,offlineReady=false;
var offlineChip=document.getElementById('homeMapOfflineChip');
function localTileUrl(z,x,y){return 'https://rush-offline.local/'+z+'/'+x+'/'+y+'.png'}
function checkNetwork(){
 if(Date.now()-lastNetworkCheck<5000)return;
 lastNetworkCheck=Date.now();
 if(window.Rush&&typeof window.Rush.hasValidatedInternet==='function'){
  try{hasInternet=!!window.Rush.hasValidatedInternet()}catch(e){hasInternet=navigator.onLine}
 }else hasInternet=navigator.onLine;
 offlineMode=offlineAvailable&&!hasInternet;
 if(window.Rush&&typeof window.Rush.offlineMapReady==='function'){
  try{offlineReady=!!window.Rush.offlineMapReady()}catch(e){}
 }
 if(offlineChip)offlineChip.hidden=!offlineMode;
}
function smoothCenterFrame(now){
 if(!centerMotion||!following){centerMotionFrame=0;return}
 var fraction=Math.min(1,Math.max(0,(now-centerMotion.started)/centerMotion.duration));
 current={
  lat:centerMotion.from.lat+(centerMotion.to.lat-centerMotion.from.lat)*fraction,
  lon:centerMotion.from.lon+(centerMotion.to.lon-centerMotion.from.lon)*fraction
 };
 if(now-lastSmoothDraw>=65||fraction>=1){lastSmoothDraw=now;queueDraw()}
 if(fraction<1)centerMotionFrame=requestAnimationFrame(smoothCenterFrame);
 else{centerMotion=null;centerMotionFrame=0}
}
function followSmoothly(lat,lon){
 var meters=Math.hypot((lat-current.lat)*111320,
   (lon-current.lon)*111320*Math.cos(lat*Math.PI/180));
 if(meters<1||meters>200||motionSpeed<2){
  centerMotion=null;current={lat:lat,lon:lon};queueDraw();return;
 }
 centerMotion={from:{lat:current.lat,lon:current.lon},to:{lat:lat,lon:lon},
               started:performance.now(),duration:960};
 if(!centerMotionFrame)centerMotionFrame=requestAnimationFrame(smoothCenterFrame);
}

// Rush 4.20: cartografía y tráfico TomTom Orbis v2. Sin credenciales,
// OSM continúa siendo el mapa estable; nunca se dibuja tráfico inventado.
var mapProvider='osm',tomtomKey='',trafficEnabled=false;
var trafficTiles=Object.create(null),lastTrafficSuccess=0,lastTrafficError=0;
var trafficChecked=0,lastMapError=0,trafficErrorCount=0;
var trafficNotice=document.getElementById('homeMapTrafficStatus');
var trafficLegend=document.getElementById('homeMapTrafficLegend');
var trafficButton=document.getElementById('homeMapTraffic');
var attribution=document.getElementById('homeMapAttribution');
var trafficCachePeriod=180000; // 3 min para limitar consumo y costos.
var lastThemeOnTiles='';
function syncPanelZoom(){
 // 4.20.4: layout clasico y conduccion comparten zoom, color y rumbo.
 // No modificar zoom al redimensionar ni al alternar paneles.
}
try {
  tomtomKey=localStorage.getItem('rush_tomtom_key_420')||'';
  mapProvider=localStorage.getItem('rush_map_provider_420')==='tomtom'?'tomtom':'osm';
  trafficEnabled=localStorage.getItem('rush_traffic_enabled_420')==='yes';
}catch(e){}
function validTomtomKey(){return /^[A-Za-z0-9_-]{12,128}$/.test(tomtomKey)}
if(!validTomtomKey()){mapProvider='osm';trafficEnabled=false;}
function trafficReady(){return trafficEnabled&&validTomtomKey()&&!offlineMode}
function currentMapTheme(){return document.documentElement.getAttribute('data-theme')==='dark'?'dark':'light'}
function baseTileUrl(z,x,y){
 if(offlineMode)return localTileUrl(z,x,y);
 if(mapProvider!=='tomtom'||!validTomtomKey())
  return 'https://tile.openstreetmap.org/'+z+'/'+x+'/'+y+'.png';
 var style=currentMapTheme()==='dark'?'street-dark':'street-light';
 return 'https://api.tomtom.com/maps/orbis/display/raster/tile/'+z+'/'+x+'/'+y+
  '?apiVersion=2&style='+style+'&tileSize=256&geopoliticalView=CL&key='+encodeURIComponent(tomtomKey);
}
function trafficTileUrl(type,z,x,y){
 var path=type==='incidents'?'incidents':'flow';
 return 'https://api.tomtom.com/maps/orbis/traffic/'+path+'/raster/tile/'+z+'/'+x+'/'+y+
  '?apiVersion=2&style='+currentMapTheme()+'&tileSize=256&key='+encodeURIComponent(tomtomKey);
}
function trafficStatus(){
 if(!trafficNotice)return;
 var ready=trafficReady(),time=lastTrafficSuccess?
   new Date(lastTrafficSuccess).toLocaleTimeString('es-CL',{hour:'2-digit',minute:'2-digit'}):'';
 if(!validTomtomKey()){
  trafficNotice.textContent='Tráfico: requiere clave TomTom';trafficNotice.hidden=true;
 }else if(!ready){
  trafficNotice.textContent='Tráfico desactivado';trafficNotice.hidden=true;
 }else if(lastTrafficSuccess) {
  trafficNotice.textContent='Tráfico TomTom · datos cargados '+time;
  trafficNotice.hidden=false;
 }else if(lastTrafficError) {
  trafficNotice.textContent='Tráfico no disponible · revisa clave o red';
  trafficNotice.hidden=false;
 }else{
  trafficNotice.textContent='Consultando tráfico TomTom…';
  trafficNotice.hidden=false;
 }
 if(trafficLegend)trafficLegend.hidden=!ready||!lastTrafficSuccess;
 if(trafficButton)trafficButton.setAttribute('aria-pressed',ready?'true':'false');
 if(trafficButton)trafficButton.textContent=ready?'Tráfico ✓':'Tráfico';
 if(attribution)attribution.textContent=
  mapProvider==='tomtom'||ready?'© TomTom · © OpenStreetMap':'© OpenStreetMap contributors';
 host.classList.toggle('rush-tomtom-map',mapProvider==='tomtom');
}
function resetMapTiles(){
 Object.keys(tiles).forEach(function(k){tiles[k].remove();delete tiles[k]});
 Object.keys(trafficTiles).forEach(function(k){trafficTiles[k].remove();delete trafficTiles[k]});
}
function configureCartography(options){
 options=options||{};
 if(typeof options.key==='string'&&options.key.trim()){
  var candidate=options.key.trim();
  if(!/^[A-Za-z0-9_-]{12,128}$/.test(candidate))
   return {ok:false,message:'Clave API TomTom inválida'};
  tomtomKey=candidate;
  try{localStorage.setItem('rush_tomtom_key_420',tomtomKey)}catch(e){}
 }
 if(options.clearKey===true){
  tomtomKey='';
  try{localStorage.removeItem('rush_tomtom_key_420')}catch(e){}
 }
 if(options.provider==='osm'||options.provider==='tomtom'){
  mapProvider=options.provider;
 }
 if(options.traffic===true||options.traffic===false)trafficEnabled=options.traffic;
 // Evitar desalineación geométrica entre carreteras de distintos mapas:
 // el tráfico se dibuja sobre la misma cartografía TomTom Orbis.
 if(trafficEnabled&&validTomtomKey())mapProvider='tomtom';
 if(mapProvider==='osm')trafficEnabled=false;
 if(!validTomtomKey()){
  mapProvider='osm';trafficEnabled=false;
 }
 try {
  localStorage.setItem('rush_map_provider_420',mapProvider);
  localStorage.setItem('rush_traffic_enabled_420',trafficEnabled?'yes':'no');
 }catch(e){}
 lastTrafficSuccess=0;lastTrafficError=0;trafficErrorCount=0;
 resetMapTiles();trafficStatus();queueDraw();
 return {ok:true,provider:mapProvider,traffic:trafficReady(),hasKey:validTomtomKey()};
}
function getCartography(){return {
 provider:mapProvider,traffic:trafficReady(),hasKey:validTomtomKey(),
 lastTrafficSuccess:lastTrafficSuccess,
 error:!lastTrafficSuccess&&lastTrafficError>0
}}

var routeCoords=[],routeSvg=document.getElementById('homeRouteSvg'),
    routeShadow=document.getElementById('homeRouteBorder'),
    routeLine=document.getElementById('homeRouteLine'),
    routeDestination=document.getElementById('homeRouteDestination');
var alternativeCoords=[],alternativeGroup=document.getElementById('homeAlternateRoutes');
var alternativeNodes=[];
function setAlternates(routes){
 alternativeCoords=[];
 if(Array.isArray(routes))routes.slice(0,2).forEach(function(route){
  if(!route||!Array.isArray(route.geometry))return;
  var pts=[],jump=Math.max(1,Math.ceil(route.geometry.length/260));
  for(var i=0;i<route.geometry.length;i+=jump){
   var pair=route.geometry[i];
   if(Array.isArray(pair)&&valid(Number(pair[1]),Number(pair[0])))
    pts.push({lat:Number(pair[1]),lon:Number(pair[0])});
  }
  if(pts.length>1)alternativeCoords.push(pts);
 });
 if(alternativeGroup){
  while(alternativeGroup.firstChild)alternativeGroup.removeChild(alternativeGroup.firstChild);
  alternativeNodes=[];
  alternativeCoords.forEach(function(){
   var p=document.createElementNS('http://www.w3.org/2000/svg','path');
   p.setAttribute('class','rush-alternate-path');
   p.setAttribute('fill','none');p.setAttribute('stroke','#8ba7b3');
   p.setAttribute('stroke-width','5');p.setAttribute('stroke-opacity','.52');
   p.setAttribute('stroke-linecap','round');p.setAttribute('stroke-linejoin','round');
   alternativeGroup.appendChild(p);alternativeNodes.push(p);
  });
 }
 queueDraw();
}
function drawAlternates(center,w,h){
 if(!alternativeNodes.length)return;
 for(var j=0;j<alternativeCoords.length;j++){
  var points=alternativeCoords[j],draw='',prev=null,visible=false;
  for(var i=0;i<points.length;i++){
   var pt=project(points[i].lat,points[i].lon);
   var cur={x:pt.x-center.x+w/2,y:pt.y-center.y+h/2};
   if(prev){
    var inside=Math.max(prev.x,cur.x)>=-100&&Math.min(prev.x,cur.x)<=w+100&&
      Math.max(prev.y,cur.y)>=-100&&Math.min(prev.y,cur.y)<=h+100;
    if(inside){
     if(!visible)draw+='M'+prev.x.toFixed(1)+' '+prev.y.toFixed(1);
     draw+='L'+cur.x.toFixed(1)+' '+cur.y.toFixed(1);
    }
    visible=inside;
   }
   prev=cur;
  }
  alternativeNodes[j].setAttribute('d',draw);
 }
}
var longTimer=null,longTriggered=false;

function clamp(n,lower,upper){return Math.min(upper,Math.max(lower,n))}
function valid(lat,lon){return Number.isFinite(lat)&&Number.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180}
function setText(el,t){if(el)el.textContent=t}
function numZoom(){return Math.pow(2,zoom)}
function project(lat,lon){
 var safe=clamp(lat,minLat,maxLat),rad=safe*Math.PI/180,n=numZoom();
 return {x:(lon+180)/360*n*tile,y:(1-Math.log(Math.tan(rad)+1/Math.cos(rad))/Math.PI)/2*n*tile};
}
function unproject(x,y){
 var n=numZoom()*tile,angle=Math.PI*(1-2*y/n);
 return {lat:Math.atan(Math.sinh(angle))*180/Math.PI,lon:((x/n)*360-180+540)%360-180};
}
function setExternalActive(enabled){externalActive=!!enabled;setStatus();}
function setDiagnostics(data) {
 if(data&&typeof data==='object') {
  rawDiagnostic=data;
  setStatus();
 }
}
function setStatus(){
 var suffix=externalActive?' · iPhone NMEA':
    rawDiagnostic?' · GPS '+(rawDiagnostic.provider||'—')+
     ' #'+(rawDiagnostic.acceptedCount||0):'';
 if(!live) {
  setText(label,'Esperando GPS real'+suffix);
  setText(info,rawDiagnostic&&rawDiagnostic.rawCount>0?
    'Datos recibidos: '+rawDiagnostic.rawCount+' · '+(rawDiagnostic.rejectReason||'sin posición válida'):
    'Sin lectura GPS · comprueba Ubicación Android');
  return;
 }
 var age=(Date.now()-lastGpsAt)/1000;
 var fresh=age<=10;
 if(!following) {
  setText(label,'Mapa desplazado · toca Seguir'+suffix);
  setText(info,'El mapa está en exploración, no seguirá al vehículo');
 } else if(fresh) {
  var precision=Number.isFinite(accuracy)&&accuracy>=0?
    '±'+Math.round(accuracy)+' m':'precisión no informada';
  setText(label,'CONDUCIENDO · '+precision+suffix);
  setText(info,motionSpeed>8&&lastActualMoveAt&&Date.now()-lastActualMoveAt>15000?
    'GPS recibe datos pero no cambia las coordenadas':'Seguimiento GPS activo');
 } else {
  setText(label,'GPS SIN ACTUALIZAR · '+Math.round(age)+' s'+suffix);
  setText(info,externalActive?
      'GPS iPhone sin nuevas lecturas: revisa GPS2IP/Wi-Fi':
      'Pulsa ↻ GPS · si persiste, revisa el receptor del Carlinkit');
 }
}
function render(){
 if(!host.offsetWidth||!host.offsetHeight)return;
 // En el panel clásico mostrar menos etiquetas raster por nivel de zoom.
 // En conducción mantener la cartografía cercana de seguimiento.
 syncPanelZoom();
 checkNetwork();
 var w=layer.clientWidth,h=layer.clientHeight;
 if(w<15||h<15)return;
 var center=project(current.lat,current.lon),n=numZoom();
 var minX=Math.floor((center.x-w/2)/tile),maxX=Math.floor((center.x+w/2)/tile);
 var minY=Math.floor((center.y-h/2)/tile),maxY=Math.floor((center.y+h/2)/tile);
 var names=Object.create(null),tNames=Object.create(null),count=0;
 // Pantalla completa: presupuesto adaptativo para evitar zonas vacias.
 var tileLimit=host.classList.contains('rush-map-fullscreen')?48:maxTiles;
 var theme=currentMapTheme()+':'+(offlineMode?'offline':'online')+':'+offlineReady;
 if(lastThemeOnTiles!==theme){
  lastThemeOnTiles=theme;
  resetMapTiles();
 }
 var trafficEpoch=Math.floor(Date.now()/trafficCachePeriod);
 for(var y=minY;y<=maxY;y++){
  if(y<0||y>=n)continue;
  for(var x=minX;x<=maxX;x++){
   if(count>=tileLimit)break;
   var wrapped=((x%n)+n)%n,key=zoom+'/'+wrapped+'/'+y;
   names[key]=true;
   var img=tiles[key];
   if(!img){
    img=document.createElement('img');
    img.className='rush-base-tile';
    img.alt='';img.draggable=false;img.decoding='async';
    img.setAttribute('loading','eager');
    img.referrerPolicy='strict-origin-when-cross-origin';
    img.style.transition='none';
    img.dataset.xyz=key;
    img.onerror=function(){
       if(this.dataset.fallback!=='osm'&&mapProvider==='tomtom'&&!offlineMode){
        this.dataset.fallback='osm';
        this.src='https://tile.openstreetmap.org/'+this.dataset.xyz+'.png';
       }else if(offlineAvailable&&this.dataset.fallback!=='offline'){
        this.dataset.fallback='offline';
        this.src='https://rush-offline.local/'+this.dataset.xyz+'.png';
       }else this.style.visibility='hidden';
       lastMapError=Date.now();
     };
     img.src=baseTileUrl(zoom,wrapped,y);
    tiles[key]=img;layer.appendChild(img);
   }else{
    img.style.transition='none';
   }
   var px=Math.round(x*tile-center.x+w/2)+'px',py=Math.round(y*tile-center.y+h/2)+'px';
   img.style.left=px;img.style.top=py;
   if(trafficReady()){
    ['flow','incidents'].forEach(function(type){
     var tkey=type+'/'+trafficEpoch+'/'+key;
     tNames[tkey]=true;
     var overlay=trafficTiles[tkey];
     if(!overlay){
      overlay=document.createElement('img');
      overlay.className=type==='flow'?'rush-traffic-flow':'rush-traffic-incidents';
      overlay.alt='';
      overlay.decoding='async';overlay.draggable=false;
      overlay.referrerPolicy='strict-origin-when-cross-origin';
      overlay.style.transition='none';
      overlay.style.pointerEvents='none';
      overlay.onload=function(){
       lastTrafficSuccess=Date.now();trafficStatus();
      };
      overlay.onerror=function(){
       lastTrafficError=Date.now();trafficErrorCount++;trafficStatus();
       this.style.visibility='hidden';
      };
      overlay.src=trafficTileUrl(type,zoom,wrapped,y);
      trafficTiles[tkey]=overlay;layer.appendChild(overlay);
     }else{
      overlay.style.transition='none';
     }
     overlay.style.left=px;overlay.style.top=py;
    });
   }
   count++;
  }
  if(count>=tileLimit)break;
 }
 Object.keys(tiles).forEach(function(key){
  if(!names[key]){tiles[key].remove();delete tiles[key]}
 });
 Object.keys(trafficTiles).forEach(function(key){
  if(!tNames[key]){trafficTiles[key].remove();delete trafficTiles[key]}
 });
 host.classList.add('home-map-has-tiles');
 if(window.Rush3D){
  layer.style.transform=window.Rush3D.cameraTransform(visualBearing,driveOffset(),following,live);
 }else{
  layer.style.transform=following&&live?
    'translate3d(0,'+driveOffset()+'px,0) rotate('+(-visualBearing)+'deg)':'none';
 }
 drawRoute(center,w,h);
 if(window.Rush3D)window.Rush3D.update(current.lat,current.lon,zoom,project,w,h,live);
 drawMarker();
 setStatus();
 trafficStatus();
 lastDraw=Date.now();
 lastRenderedCenter={lat:current.lat,lon:current.lon};
}
function drawRoute(center,w,h){
 if(!routeSvg||!routeLine||!routeShadow||!routeDestination)return;
 if(routeCoords.length<2){
  routeSvg.setAttribute('hidden','');
  routeLine.setAttribute('d','');routeShadow.setAttribute('d','');
  routeDestination.style.display='none';return;
 }
 // Todos los segmentos que cruzan la ventana del mapa se proyectan
 // con la MISMA matriz Mercator que las teselas. Ya NO se salta 1 de
 // cada N puntos: eso trazaba cuerdas sobre curvas de la carretera.
 // Los segmentos fuera de pantalla no se dibujan: menos trabajo en TBox.
 routeSvg.removeAttribute('hidden');
 routeSvg.setAttribute('viewBox','0 0 '+w+' '+h);
 var margin=160,lastVisible=false,path='',previous=null;
 for(var i=0;i<routeCoords.length;i++){
  var pt=project(routeCoords[i].lat,routeCoords[i].lon);
  var cur={x:pt.x-center.x+w/2,y:pt.y-center.y+h/2};
  if(previous){
   var intersects=Math.max(previous.x,cur.x)>=-margin &&
       Math.min(previous.x,cur.x)<=w+margin &&
       Math.max(previous.y,cur.y)>=-margin &&
       Math.min(previous.y,cur.y)<=h+margin;
   if(intersects){
    if(!lastVisible)path+='M'+previous.x.toFixed(1)+' '+previous.y.toFixed(1);
    path+='L'+cur.x.toFixed(1)+' '+cur.y.toFixed(1);
   }
   lastVisible=intersects;
  }
  previous=cur;
 }
 drawAlternates(center,w,h);
 routeShadow.setAttribute('d',path);
 routeLine.setAttribute('d',path);
 var last=routeCoords[routeCoords.length-1],end=project(last.lat,last.lon);
 routeDestination.style.display='';
 routeDestination.setAttribute('cx',(end.x-center.x+w/2).toFixed(1));
 routeDestination.setAttribute('cy',(end.y-center.y+h/2).toFixed(1));
}
function setRoute(points){
 routeCoords=[];
 if(Array.isArray(points))points.forEach(function(c){
  if(!Array.isArray(c)||c.length<2)return;
  var lon=Number(c[0]),lat=Number(c[1]);
  if(valid(lat,lon))routeCoords.push({lat:lat,lon:lon});
 });
 queueDraw();
}
function clearRoute(){routeCoords=[];setAlternates([]);queueDraw()}
function drawMarker(){
 if(!live||!position){marker.hidden=true;return;}
 var p=project(position.lat,position.lon),c=project(current.lat,current.lon);
 var dx=p.x-c.x,dy=p.y-c.y;
 if(following){dx=0;dy=0}
 else{
  if(dx>host.clientWidth/2+40||dx< -host.clientWidth/2-40||
     dy>host.clientHeight/2+40||dy< -host.clientHeight/2-40){
    marker.hidden=true;return;
  }
 }
 marker.hidden=false;
 marker.style.left='calc(50% + '+Math.round(dx)+'px)';
 marker.style.top='calc(50% + '+Math.round(dy+(following?driveOffset():0))+'px)';
 var rotation=following?'0deg':bearing+'deg';
 marker.style.transform='translate(-50%,-50%) rotate('+rotation+')';
}
function queueDraw(){
 if(drawQueued)return;
 drawQueued=true;
 requestAnimationFrame(function(){drawQueued=false;render()});
}
function getBearing(){return bearing}
function setPosition(lat,lon,acc){
 if(!valid(lat,lon))return;
 if(Number.isFinite(acc)&&acc>1000)return;
 if(previousFix) {
  var dLat=(lat-previousFix.lat)*111320;
  var dLon=(lon-previousFix.lon)*111320*Math.cos(lat*Math.PI/180);
  if(Math.hypot(dLat,dLon)>3) lastActualMoveAt=Date.now();
 } else lastActualMoveAt=Date.now();
 previousFix={lat:lat,lon:lon};
 // Si se exploró el mapa durante un viaje, volver al seguimiento automático.
 if(!following&&motionSpeed>8&&lastManualPanAt &&
       Date.now()-lastManualPanAt>12000)activateFollow();
 var firstFix=!live;
 position={lat:lat,lon:lon};
 live=true;accuracy=Number.isFinite(acc)&&acc>=0?acc:null;lastGpsAt=Date.now();
 if(following){
  if(firstFix){centerMotion=null;current={lat:lat,lon:lon};queueDraw()}
  else followSmoothly(lat,lon);
 }else if(Date.now()-lastDraw>4500)queueDraw();
 else{drawMarker();setStatus()}
}
function setHeading(heading,speed){
 motionSpeed=Number.isFinite(speed)?Math.max(0,speed):0;
 // Cambiar nivel con histéresis evita tirones en autopista cuando
 // la velocidad oscila alrededor de 50 u 85 km/h.
 if(autoZoom && mapStyle==='drive' && following && live){
  var target=zoom;
  if(zoom===16&&motionSpeed<72)target=17;
  else if(zoom===17&&motionSpeed>100)target=16;
  else if(zoom===17&&motionSpeed<37)target=18;
  else if(zoom===18&&motionSpeed>57)target=17;
  if(target!==zoom && Date.now()-lastAutoZoomAt>25000){
   zoom=target;lastAutoZoomAt=Date.now();queueDraw();
  }
 }
 // El rumbo RMC es course-over-ground, no el giro del volante: cuando
 // el vehículo está parado puede variar aleatoriamente. Evitar ese ruido.
 if(!Number.isFinite(heading)||motionSpeed<3.5)return;
 var next=(heading+360)%360;
 var delta=((next-bearing+540)%360)-180; // -180..180
 if(Math.abs(delta)<0.75)return;
 bearing=next;
 visualBearing+=delta; // sin salto de 359° a 0°
 lastDirectionAt=Date.now();
 queueDraw();
}
function activateFollow(){
 following=true;
 autoZoom=mapStyle==='drive';
 if(autoZoom)zoom=motionSpeed>=85?16:motionSpeed>=50?17:18;
 else zoom=15;
 centerMotion=null;if(position)current={lat:position.lat,lon:position.lon};
 followButton.textContent='➤';followButton.title='GPS: siguiendo vehículo';
 followButton.setAttribute('aria-pressed','true');
 queueDraw();
}
function freeMove(){
 lastManualPanAt=Date.now();
 if(!following)return;
 following=false;centerMotion=null;layer.style.transform='none';
 followButton.textContent='◎';followButton.title='Reanudar seguimiento GPS';
 followButton.setAttribute('aria-pressed','false');
}
function setStyle(mode){
 mapStyle=mode==='classic'?'classic':'drive';
 try{localStorage.setItem('rush_map_style',mapStyle)}catch(e){}
 host.classList.toggle('rush-map-drive',mapStyle==='drive');
 autoZoom=mapStyle==='drive';
 zoom=mapStyle==='drive'?(motionSpeed>=85?16:motionSpeed>=50?17:18):15;
 centerMotion=null;if(following&&position)current={lat:position.lat,lon:position.lon};
 if(document.getElementById('mapStyleDrive'))
  document.getElementById('mapStyleDrive').setAttribute('aria-pressed',
    mapStyle==='drive'?'true':'false');
 if(document.getElementById('mapStyleClassic'))
  document.getElementById('mapStyleClassic').setAttribute('aria-pressed',
    mapStyle==='classic'?'true':'false');
 setStatus();queueDraw();
}
function scaleZoom(change){
 // Zoom manual conservado hasta pulsar de nuevo 'Conduciendo'.
 autoZoom=false;
 zoom=clamp(zoom+change,13,19);
 queueDraw();
}
function changeCenter(dx,dy){
 var factor=window.Rush3D?window.Rush3D.inputScale():{x:1,y:1};
 dx*=factor.x;dy*=factor.y;
 var center=project(current.lat,current.lon),angle=following&&live?bearing*Math.PI/180:0;
 var mx=dx*Math.cos(angle)-dy*Math.sin(angle);
 var my=dx*Math.sin(angle)+dy*Math.cos(angle);
 current=unproject(center.x-mx,center.y-my);
 queueDraw();
}
function pointerStart(event){
 if(event.button!==undefined&&event.button!==0)return;
 if(activePointer!==null)return;
 longTriggered=false;
 activePointer={id:event.pointerId,x:event.clientX,y:event.clientY,lastX:event.clientX,lastY:event.clientY,moved:false};
 if(surface.setPointerCapture)surface.setPointerCapture(event.pointerId);
 // Pulsación larga con vehículo detenido selecciona destino sin abrir otra pantalla.
 longTimer=setTimeout(function(){
  if(!activePointer||activePointer.moved||motionSpeed>5||!window.RushHomeNavigation)return;
  var rect=host.getBoundingClientRect();
  var dx=activePointer.x-rect.left-rect.width/2,
      dy=activePointer.y-rect.top-rect.height/2-driveOffset();
  var factor=window.Rush3D?window.Rush3D.inputScale():{x:1,y:1};
  dx*=factor.x;dy*=factor.y;
  var radians=following&&live?bearing*Math.PI/180:0;
  var x=dx*Math.cos(radians)-dy*Math.sin(radians);
  var y=dx*Math.sin(radians)+dy*Math.cos(radians);
  var c=project(current.lat,current.lon),pos=unproject(c.x+x,c.y+y);
  longTriggered=true;
  window.RushHomeNavigation.pickCoordinate(pos.lat,pos.lon);
 },780);
}
function pointerMove(event){
 if(!activePointer||activePointer.id!==event.pointerId)return;
 var dx=event.clientX-activePointer.lastX,dy=event.clientY-activePointer.lastY;
 activePointer.lastX=event.clientX;activePointer.lastY=event.clientY;
 if(Math.abs(event.clientX-activePointer.x)+Math.abs(event.clientY-activePointer.y)>7){
  if(longTimer!==null){clearTimeout(longTimer);longTimer=null}
  activePointer.moved=true;
  freeMove();
  changeCenter(dx,dy);
 }
 if(event.cancelable)event.preventDefault();
}
function pointerEnd(event){
 if(!activePointer||activePointer.id!==event.pointerId)return;
 var moved=activePointer.moved;
 activePointer=null;
 if(longTimer!==null){clearTimeout(longTimer);longTimer=null}
 if(longTriggered){longTriggered=false;return}
 if(moved){queueDraw();return}
 var now=Date.now();
 if(now-lastTouch<360)scaleZoom(1);
 lastTouch=now;
}
surface.addEventListener('pointerdown',pointerStart);
surface.addEventListener('pointermove',pointerMove);
surface.addEventListener('pointerup',pointerEnd);
surface.addEventListener('pointercancel',pointerEnd);
surface.addEventListener('wheel',function(e){
 if(e.cancelable)e.preventDefault();
 scaleZoom(e.deltaY<0?1:-1);
},{passive:false});
surface.addEventListener('keydown',function(e){
 var movement=55;
 if(e.key==='ArrowLeft'){freeMove();changeCenter(movement,0);e.preventDefault()}
 if(e.key==='ArrowRight'){freeMove();changeCenter(-movement,0);e.preventDefault()}
 if(e.key==='ArrowUp'){freeMove();changeCenter(0,movement);e.preventDefault()}
 if(e.key==='ArrowDown'){freeMove();changeCenter(0,-movement);e.preventDefault()}
 if(e.key==='+'||e.key==='='){scaleZoom(1);e.preventDefault()}
 if(e.key==='-'){scaleZoom(-1);e.preventDefault()}
});
followButton.addEventListener('click',activateFollow);
if(trafficButton)trafficButton.addEventListener('click',function(){
 if(!validTomtomKey()){
  if(host.classList.contains('rush-map-fullscreen')&&
     typeof window.rushCloseFullMap==='function')window.rushCloseFullMap();
  var st=document.getElementById('cartoTomtomStatus');
  if(st)st.textContent='Configura tu clave TomTom en Ajustes antes de activar tráfico.';
  var settings=document.getElementById('settingsPanel');
  if(settings)settings.hidden=false;
  return;
 }
 configureCartography({traffic:!trafficEnabled});
});
var styleDriveButton=document.getElementById('mapStyleDrive');
var styleClassicButton=document.getElementById('mapStyleClassic');
if(styleDriveButton)styleDriveButton.addEventListener('click',function(){setStyle('drive')});
if(styleClassicButton)styleClassicButton.addEventListener('click',function(){setStyle('classic')});
if(styleDriveButton)styleDriveButton.setAttribute('aria-pressed',
  mapStyle==='drive'?'true':'false');
if(styleClassicButton)styleClassicButton.setAttribute('aria-pressed',
  mapStyle==='classic'?'true':'false');
zoomIn.addEventListener('click',function(){scaleZoom(1)});
zoomOut.addEventListener('click',function(){scaleZoom(-1)});
window.addEventListener('resize',queueDraw);
if(typeof ResizeObserver==='function')new ResizeObserver(queueDraw).observe(host);

window.RushHomeMap={
 setPosition:setPosition,setHeading:setHeading,follow:activateFollow,
 setRoute:setRoute,setAlternates:setAlternates,clearRoute:clearRoute,
 setDiagnostics:setDiagnostics,setExternalActive:setExternalActive,
 zoomIn:function(){scaleZoom(1)},zoomOut:function(){scaleZoom(-1)},
 refresh:queueDraw,
 isFollowing:function(){return following},
 getBearing:getBearing,
 getZoom:function(){return zoom},
 getStyle:function(){return mapStyle},setStyle:setStyle,
 configureCartography:configureCartography,getCartography:getCartography
};
// Visible desde el inicio, sin fingir que el vehículo se encuentra aquí.
followButton.textContent='➤';followButton.title='GPS: siguiendo vehículo';
followButton.setAttribute('aria-pressed','true');
trafficStatus();
queueDraw();
setInterval(function(){
 if(host.offsetWidth){
  if(live)setStatus();
  // Repintar cada 30 s solo si hay tráfico visible y el mapa está en pantalla;
  // los mosaicos se actualizan con una ventana temporal de tres minutos.
  if(trafficReady()&&document.visibilityState!=='hidden')queueDraw();
  if(currentMapTheme()!==lastThemeOnTiles)queueDraw();
 }
},30000);
// Cambiar entre fuentes raster online y locales cuando se va la señal 4G.
setInterval(function(){
 if(!host.offsetWidth)return;
 var before=offlineMode,wasReady=offlineReady;
 lastNetworkCheck=0;checkNetwork();
 if(before!==offlineMode||wasReady!==offlineReady)queueDraw();
},7000);
})();
