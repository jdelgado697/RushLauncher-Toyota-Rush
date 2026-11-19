/* Rush Launcher 4.15.4 — mapa de Inicio manipulable y seguimiento de conducción.
 * No incrusta el mapa GL pesado de la pantalla de navegación: utiliza pocas
 * teselas raster para proteger los 4 GB del TBox. GPS real o referencia explícita.
 * © OpenStreetMap contributors. No tráfico en tiempo real ni navegación offline.
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

var current={lat:-39.367,lon:-72.632},position=null;
var zoom=15,following=true,bearing=0,live=false,accuracy=null,tiles=Object.create(null),motionSpeed=0;
var lastDraw=0,drawQueued=false,activePointer=null,lastTouch=0;
var maxTiles=25, tile=256, minLat=-85.05112878, maxLat=85.05112878;
var lastGpsAt=0,lastRenderedCenter=null;
var routeCoords=[],routeSvg=document.getElementById('homeRouteSvg'),
    routeShadow=document.getElementById('homeRouteBorder'),
    routeLine=document.getElementById('homeRouteLine'),
    routeDestination=document.getElementById('homeRouteDestination');
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
function setStatus(){
 if(!live){setText(label,'Loncoche · mapa de referencia');return;}
 var age=(Date.now()-lastGpsAt)/1000;
 var fresh=age<=10;
 setText(label,following?(fresh?'CONDUCIENDO · GPS ±'+Math.round(accuracy||0)+' m':'GPS sin actualizar'):
     'Explorando mapa · pulsa Seguir');
 setText(info,following?(fresh?'Mapa sigue el vehículo':'Esperando señal GPS…'):
     'Mapa desplazado · toca Seguir para volver');
}
function render(){
 if(!host.offsetWidth||!host.offsetHeight)return;
 var w=layer.clientWidth,h=layer.clientHeight;
 if(w<15||h<15)return;
 var center=project(current.lat,current.lon),n=numZoom();
 var minX=Math.floor((center.x-w/2)/tile),maxX=Math.floor((center.x+w/2)/tile);
 var minY=Math.floor((center.y-h/2)/tile),maxY=Math.floor((center.y+h/2)/tile);
 var names=Object.create(null),count=0;
 for(var y=minY;y<=maxY;y++){
  if(y<0||y>=n)continue;
  for(var x=minX;x<=maxX;x++){
   if(count>=maxTiles)break;
   var wrapped=((x%n)+n)%n,key=zoom+'/'+wrapped+'/'+y;
   names[key]=true;
   var img=tiles[key];
   if(!img){
    img=document.createElement('img');
    img.alt='';img.draggable=false;img.decoding='async';
    img.setAttribute('loading','eager');
    img.referrerPolicy='strict-origin-when-cross-origin';
    img.style.transition='none';
    img.src='https://tile.openstreetmap.org/'+key+'.png';
    img.onerror=function(){this.style.visibility='hidden'};
    tiles[key]=img;layer.appendChild(img);
   }else{
    // Pequeña interpolación entre lecturas GPS (sin descargar imágenes extra).
    img.style.transition=following&&motionSpeed>8?'left 600ms linear, top 600ms linear':'none';
   }
   img.style.left=Math.round(x*tile-center.x+w/2)+'px';
   img.style.top=Math.round(y*tile-center.y+h/2)+'px';
   count++;
  }
  if(count>=maxTiles)break;
 }
 Object.keys(tiles).forEach(function(key){
  if(!names[key]){tiles[key].remove();delete tiles[key]}
 });
 host.classList.add('home-map-has-tiles');
 layer.style.transform=following&&live?'rotate('+(-bearing)+'deg)':'none';
 drawRoute(center,w,h);
 drawMarker();
 setStatus();
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
 routeSvg.removeAttribute('hidden');routeSvg.setAttribute('viewBox','0 0 '+w+' '+h);
 var path='',step=Math.max(1,Math.ceil(routeCoords.length/340));
 for(var i=0;i<routeCoords.length;i+=step){
  var p=project(routeCoords[i].lat,routeCoords[i].lon);
  var x=Math.round((p.x-center.x+w/2)*10)/10;
  var y=Math.round((p.y-center.y+h/2)*10)/10;
  path+=(path?' L':'M')+x+' '+y;
 }
 var last=routeCoords[routeCoords.length-1],end=project(last.lat,last.lon);
 path+=' L'+(end.x-center.x+w/2).toFixed(1)+' '+(end.y-center.y+h/2).toFixed(1);
 routeShadow.setAttribute('d',path);
 routeLine.setAttribute('d',path);
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
function clearRoute(){routeCoords=[];queueDraw()}
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
 marker.style.top='calc(50% + '+Math.round(dy)+'px)';
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
 position={lat:lat,lon:lon};
 live=true;accuracy=Number.isFinite(acc)?acc:null;lastGpsAt=Date.now();
 if(following)current={lat:lat,lon:lon};
 var moved=!lastRenderedCenter||Math.abs(lastRenderedCenter.lat-current.lat)+
    Math.abs(lastRenderedCenter.lon-current.lon)>0.000015;
 if(moved || Date.now()-lastDraw>4500)queueDraw();
 else {drawMarker();setStatus()}
}
function setHeading(heading,speed){
 motionSpeed=Number.isFinite(speed)?Math.max(0,speed):0;
 if(Number.isFinite(heading)&&motionSpeed>8){
  var next=(heading+360)%360;
  var delta=Math.abs(((next-bearing+540)%360)-180);
  if(delta>3){bearing=next;queueDraw()}
 }
}
function activateFollow(){
 following=true;
 if(position)current={lat:position.lat,lon:position.lon};
 followButton.textContent='▲ Conduciendo';
 followButton.setAttribute('aria-pressed','true');
 queueDraw();
}
function freeMove(){
 if(!following)return;
 following=false;layer.style.transform='none';
 followButton.textContent='◎ Seguir';
 followButton.setAttribute('aria-pressed','false');
}
function scaleZoom(change){
 zoom=clamp(zoom+change,12,17);
 queueDraw();
}
function changeCenter(dx,dy){
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
  var dx=activePointer.x-rect.left-rect.width/2,dy=activePointer.y-rect.top-rect.height/2;
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
zoomIn.addEventListener('click',function(){scaleZoom(1)});
zoomOut.addEventListener('click',function(){scaleZoom(-1)});
window.addEventListener('resize',queueDraw);
if(typeof ResizeObserver==='function')new ResizeObserver(queueDraw).observe(host);

window.RushHomeMap={
 setPosition:setPosition,setHeading:setHeading,follow:activateFollow,
 setRoute:setRoute,clearRoute:clearRoute,
 zoomIn:function(){scaleZoom(1)},zoomOut:function(){scaleZoom(-1)},
 refresh:queueDraw,
 isFollowing:function(){return following},
 getBearing:getBearing
};
// Visible desde el inicio, sin fingir que el vehículo se encuentra aquí.
followButton.textContent='▲ Conduciendo';
followButton.setAttribute('aria-pressed','true');
queueDraw();
setInterval(function(){if(live&&host.offsetWidth)setStatus()},5000);
})();
