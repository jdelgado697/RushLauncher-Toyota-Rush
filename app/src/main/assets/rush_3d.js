/* Rush Launcher 4.20.3 — edificios volumetricos y camara inclinada.
 * Mismo motor raster de RushHomeMap, ninguna Activity ni WebGL adicional.
 * Geometrias reales OSM obtenidas por el puente Android, nunca inventadas.
 * Alturas reales cuando existen; si no, se estiman desde building:levels.
 * © OpenStreetMap contributors / ODbL.
 */
(function(){
 'use strict';
 var host=null,layer=null,canvas=null,ctx=null,button=null;
 var enabled=true,buildings=[],scene=null,coverage=null;
 var lastRequest=0,pending=false,pendingId=0,requestAt=0,blockUntil=0;
 try{enabled=localStorage.getItem('rush_map_3d_4203')!=='no'}catch(e){}
 function clamp(v,a,b){return Math.max(a,Math.min(b,v))}
 function distance(a,b){
  if(!a||!b)return Infinity;
  var lat=(a.lat+b.lat)*Math.PI/360;
  return Math.hypot((a.lat-b.lat)*111320,
    (a.lon-b.lon)*111320*Math.cos(lat));
 }
 function isEnabled(){return enabled}
 function updateButton(){
  if(host)host.classList.toggle('rush-map-3d',enabled);
  if(button){
   button.setAttribute('aria-pressed',enabled?'true':'false');
   button.textContent=enabled?'3D ✓':'2D';
   button.title=enabled?'Cámara 3D activa; cambiar a 2D':'Activar edificios y perspectiva 3D';
  }
  if(canvas)canvas.hidden=!enabled;
 }
 function setEnabled(next){
  enabled=!!next;
  try{localStorage.setItem('rush_map_3d_4203',enabled?'yes':'no')}catch(e){}
  updateButton();
  if(window.RushHomeMap&&typeof window.RushHomeMap.refresh==='function')
   window.RushHomeMap.refresh();
 }
 function mount(mapHost,mapLayer){
  host=mapHost;layer=mapLayer;
  if(!host||!layer)return;
  canvas=document.createElement('canvas');
  canvas.id='rush3dBuildingsLayer';
  canvas.setAttribute('aria-hidden','true');
  canvas.style.cssText='position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:2;';
  layer.appendChild(canvas);
  ctx=canvas.getContext('2d',{alpha:true,desynchronized:true})||canvas.getContext('2d');
  button=document.getElementById('homeMap3d');
  if(button)button.addEventListener('click',function(){setEnabled(!enabled)});
  updateButton();
 }
 function cameraTransform(bearing,offset,following,live){
  var rotation=following&&live?-bearing:0;
  var shift=following&&live?offset:0;
  if(!enabled)
   return following&&live?'translate3d(0,'+shift+'px,0) rotate('+rotation+'deg)':'none';
  // Inclinacion estable: rotar mapa ANTES de inclinar la camara.
  return 'translate3d(0,'+shift+'px,0) perspective(1120px) rotateX(46deg) rotateZ('+
    rotation+'deg) scale(1.30)';
 }
 function inputScale(){
  return enabled?{x:1/1.30,y:1/(1.30*Math.cos(46*Math.PI/180))}:{x:1,y:1};
 }
 function drawPoly(points,dy){
  ctx.beginPath();
  for(var i=0;i<points.length;i++){
   if(i===0)ctx.moveTo(points[i].x,points[i].y-dy);
   else ctx.lineTo(points[i].x,points[i].y-dy);
  }
  ctx.closePath();
 }
 function draw(){
  if(!ctx||!canvas||!scene)return;
  var w=scene.w,h=scene.h;
  if(w<20||h<20)return;
  var dpr=clamp(window.devicePixelRatio||1,1,1.25);
  var cw=Math.round(w*dpr),ch=Math.round(h*dpr);
  if(canvas.width!==cw||canvas.height!==ch){canvas.width=cw;canvas.height=ch}
  if(!enabled){ctx.clearRect(0,0,cw,ch);return}
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);
  if(!buildings.length||scene.zoom<16)return;
  var center=scene.project(scene.lat,scene.lon), metersPerPixel=
   156543.03392*Math.max(.08,Math.cos(scene.lat*Math.PI/180))/Math.pow(2,scene.zoom);
  var dark=document.documentElement.getAttribute('data-theme')==='dark';
  var shapes=[];
  for(var k=0;k<buildings.length&&shapes.length<150;k++){
   var building=buildings[k],points=[],left=Infinity,right=-Infinity,top=Infinity,bottom=-Infinity;
   var outline=building[1],height=Number(building[0])||9;
   if(!Array.isArray(outline)||outline.length<3)continue;
   for(var j=0;j<outline.length;j++){
    var lat=Number(outline[j][0]),lon=Number(outline[j][1]);
    if(!Number.isFinite(lat)||!Number.isFinite(lon))continue;
    var p=scene.project(lat,lon),x=p.x-center.x+w/2,y=p.y-center.y+h/2;
    points.push({x:x,y:y});
    if(x<left)left=x;if(x>right)right=x;if(y<top)top=y;if(y>bottom)bottom=y;
   }
   if(points.length<3||right< -35||left>w+35||bottom< -35||top>h+65)continue;
   if(right-left>450||bottom-top>450)continue;
   var lift=clamp(height/metersPerPixel*.88,7,54);
   shapes.push({points:points,lift:lift,order:bottom});
  }
  shapes.sort(function(a,b){return a.order-b.order});
  ctx.lineWidth=.7;
  for(var b=0;b<shapes.length;b++){
   var s=shapes[b],pts=s.points,lift=s.lift;
   // Fachadas reales sobre la huella georreferenciada.
   for(var edge=0;edge<pts.length;edge++){
    var a=pts[edge],c=pts[(edge+1)%pts.length];
    ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(c.x,c.y);
    ctx.lineTo(c.x,c.y-lift);ctx.lineTo(a.x,a.y-lift);ctx.closePath();
    ctx.fillStyle=c.x-a.x>0?
      'rgba(115,153,182,.83)':
      'rgba(92,130,169,.82)';
    ctx.fill();
   }
   drawPoly(pts,lift);
   ctx.fillStyle='rgba(165,197,222,.88)';
   ctx.fill();
   ctx.strokeStyle='rgba(237,250,255,.72)';
   ctx.stroke();
  }
 }
 function maybeRequest(){
  if(!scene||!enabled||!scene.live||scene.zoom<16)return;
  if(!window.Rush||typeof window.Rush.fetch3dBuildings!=='function')return;
  var point={lat:scene.lat,lon:scene.lon},now=Date.now();
  if(coverage&&now-coverage.time<18*60000&&distance(point,coverage)<230)return;
  if(pending){
   if(now-requestAt<30000)return;
   pending=false;
  }
  if(now<blockUntil||now-lastRequest<100000)return;
  pending=true;requestAt=now;lastRequest=now;
  var id=++pendingId;
  try{window.Rush.fetch3dBuildings(point.lat,point.lon,425,id)}
  catch(e){pending=false;blockUntil=now+180000}
 }
 // Llamado desde el MISMO render de RushHomeMap (Inicio o pantalla completa).
 function update(lat,lon,zoom,project,w,h,live){
  if(!host||!canvas)return;
  scene={lat:lat,lon:lon,zoom:zoom,project:project,w:w,h:h,live:live};
  if(enabled)maybeRequest();
  draw();
 }
 window.rush3dBuildings=function(id,payload){
  if(id!==pendingId)return;
  pending=false;
  if(payload&&payload.ok&&Array.isArray(payload.buildings)){
   buildings=payload.buildings.slice(0,180);
   coverage={lat:Number(payload.lat),lon:Number(payload.lon),time:Date.now()};
   blockUntil=0;
   if(button)button.title='Edificios OSM: '+buildings.length+' · tocar para 2D';
  }else{
   blockUntil=Date.now()+300000;
   if(button)button.title='Edificios no disponibles (red/OSM); mantener perspectiva o cambiar a 2D';
  }
  draw();
 };
 window.Rush3D={mount:mount,update:update,cameraTransform:cameraTransform,
  inputScale:inputScale,isEnabled:isEnabled,setEnabled:setEnabled};
})();
