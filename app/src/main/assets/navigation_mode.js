/* Rush Navigator 4.14: seguimiento continuo por defecto al abrir el mapa.
 * Requiere live_map.js. No reproduce tráfico, radares ni datos Waze.
 * Distancias aproximadas a trazado para detectar desvíos, tolerancia al GPS.
 */
(function(){
 'use strict';
 var map=document.getElementById('mapPanel'),follow=document.getElementById('navDriveFollow');
 var overview=document.getElementById('navDriveOverview');
 var voiceButton=document.getElementById('navVoiceToggle');
 var panel=document.getElementById('navDriveControls');
 var info=document.getElementById('navRouteInfo');
 if(!map||!follow||!overview||!voiceButton||!panel)return;
 var data=null,position=null,speed=0,stepIndex=0,offCount=0;
 var lastOffCheck=0,lastReroute=0,rerouting=false,driving=false;
 var spokenIndex=-1,spokenCloseIndex=-1,lastInstructionAt=0;
 var voiceEnabled=true;
 try{voiceEnabled=localStorage.getItem('rush_nav_voice')!=='no'}catch(e){}
 function text(id,value){var x=document.getElementById(id);if(x)x.textContent=value}
 function radians(x){return x*Math.PI/180}
 function meters(a,b){
  if(!a||!b)return Infinity;
  var lat=radians((a.lat+b.lat)/2),dy=(a.lat-b.lat)*111320;
  var dx=(a.lon-b.lon)*111320*Math.cos(lat);
  return Math.hypot(dx,dy);
 }
 function polyDistance(loc,geometry){
  var best=Infinity,bestIndex=-1,remaining=0;
  if(!loc||!Array.isArray(geometry)||geometry.length<2)return null;
  var pt={lat:loc.lat,lon:loc.lon},total=0;
  var lengths=[];
  for(var j=1;j<geometry.length;j++){
   var prev={lat:geometry[j-1][1],lon:geometry[j-1][0]};
   var next={lat:geometry[j][1],lon:geometry[j][0]};
   var length=meters(prev,next);
   lengths.push(Number.isFinite(length)?length:0);total+=lengths[j-1];
  }
  var done=0,referenceLat=radians(loc.lat);
  for(var i=1;i<geometry.length;i++){
   var a={lat:geometry[i-1][1],lon:geometry[i-1][0]};
   var b={lat:geometry[i][1],lon:geometry[i][0]};
   var ax=(a.lon-loc.lon)*111320*Math.cos(referenceLat),ay=(a.lat-loc.lat)*111320;
   var bx=(b.lon-loc.lon)*111320*Math.cos(referenceLat),by=(b.lat-loc.lat)*111320;
   var sx=bx-ax,sy=by-ay;
   var dot=sx*sx+sy*sy,t=dot>0?Math.min(1,Math.max(0,-(ax*sx+ay*sy)/dot)):0;
   var dist=Math.hypot(ax+t*sx,ay+t*sy);
   if(dist<best){best=dist;bestIndex=i-1;remaining=total-done-t*lengths[i-1];}
   done+=lengths[i-1];
  }
  return {distance:best,remaining:Math.max(0,remaining),index:bestIndex};
 }
 function instruction(s){
  if(!s)return 'Continúa por la ruta azul';
  var type=String(s.type||''),modifier=String(s.modifier||''),road=String(s.road||'');
  var message=type==='arrive'?'Llegada al destino':
   type==='roundabout'||type==='rotary'?'Entra en la rotonda':
   modifier==='left'||modifier==='slight left'?'Gira a la izquierda':
   modifier==='sharp left'?'Gira pronunciado a la izquierda':
   modifier==='right'||modifier==='slight right'?'Gira a la derecha':
   modifier==='sharp right'?'Gira pronunciado a la derecha':
   modifier==='uturn'?'Haz un cambio de sentido':
   'Continúa recto';
  return message+(type!=='arrive'&&road?' · '+road:'');
 }
 function formatDistance(m){
  return m<1000?Math.round(m/10)*10+' m':(m/1000).toFixed(1)+' km';
 }
 function speak(s){
  if(!voiceEnabled||!window.Rush||typeof window.Rush.speakNavigation!=='function')return;
  window.Rush.speakNavigation(String(s).slice(0,200));
 }
 function mode(isDriving){
  driving=!!isDriving;
  map.classList.toggle('is-driving',!!data);
  panel.hidden=!data;
  follow.classList.toggle('active',driving);
  overview.classList.toggle('active',!driving);
  text('navGuideMode',driving?'NAVEGANDO · GPS':'VISTA GENERAL');
  if(window.RushLiveMap){
   if(driving)window.RushLiveMap.drivingMode(true);
   else window.RushLiveMap.overviewRoute();
  }
 }
 function clear(){
  data=null;position=null;stepIndex=0;offCount=0;rerouting=false;driving=true;
  spokenIndex=-1;spokenCloseIndex=-1;
  map.classList.remove('is-driving');panel.hidden=true;
  if(!map.hidden&&window.RushLiveMap&&typeof window.RushLiveMap.drivingMode==='function')
    window.RushLiveMap.drivingMode(true);
  if(window.Rush&&typeof window.Rush.stopNavigationSpeech==='function')
    window.Rush.stopNavigationSpeech();
 }
 function guidance(){
  if(!data||!driving||!position)return;
  var steps=Array.isArray(data.steps)?data.steps:[];
  var target=steps[stepIndex];
  if(target&&position.accuracy<=95){
   var turnMeters=meters(position,target);
   while(turnMeters<30&&stepIndex<steps.length-1){
    stepIndex++;target=steps[stepIndex];
    turnMeters=meters(position,target);
   }
   var msg=instruction(target);
   text('navGuideTitle',msg);
   text('navGuideDetail',formatDistance(turnMeters)+' hasta maniobra · precisión GPS ±'+
    Math.round(position.accuracy)+' m');
   if(voiceEnabled&&speed>=5&&Date.now()-lastInstructionAt>9000){
    if(turnMeters<500 && stepIndex!==spokenIndex){
     speak('En '+formatDistance(turnMeters)+', '+msg);
     spokenIndex=stepIndex;lastInstructionAt=Date.now();
    }else if(turnMeters<75&&stepIndex!==spokenCloseIndex){
     speak(msg+' ahora');
     spokenCloseIndex=stepIndex;lastInstructionAt=Date.now();
    }
   }
  }else if(target){
   text('navGuideTitle',instruction(target));
   text('navGuideDetail','Ubicación imprecisa; espera señal GPS estable');
  }
  var check=polyDistance(position,data.geometry);
  if(check&&position.accuracy<=95){
   var remaining=check.remaining, seconds=Number(data.seconds)||0;
   var total=Number(data.meters)||1;
   var eta=Math.max(0,Math.round(seconds*remaining/total/60));
   info.textContent=formatDistance(remaining)+' restantes · '+eta+' min aprox.';
  }
  // Tres lecturas consecutivas, no recalcular si el GPS está impreciso.
  if(Date.now()-lastOffCheck<1000)return;
  lastOffCheck=Date.now();
  if(!check||position.accuracy>60||speed<8||!Number.isFinite(check.distance))return;
  if(check.distance>Math.max(85,position.accuracy*2+25))offCount++;
  else offCount=0;
  if(offCount>=3&&!rerouting&&Date.now()-lastReroute>90000&&
    data&&data.geometry&&window.Rush&&typeof window.Rush.routeNav==='function'){
   var dest=window.RushLiveMap&&window.RushLiveMap.getDestination();
   if(!dest)return;
   rerouting=true;lastReroute=Date.now();offCount=0;
   text('navGuideMode','RECÁLCULO DE RUTA');
   text('navGuideDetail','Te alejaste de la ruta. Buscando otro camino…');
   window.Rush.routeNav(position.lat,position.lon,dest.lat,dest.lon);
  }
 }
 window.rushNavMapOpened=function(){
  driving=true;
  if(!data){map.classList.remove('is-driving');panel.hidden=true;}
  if(window.RushLiveMap&&typeof window.RushLiveMap.drivingMode==='function')
   window.RushLiveMap.drivingMode(true);
 };
 window.rushNavFollowing=function(){
  driving=true;
  follow.classList.add('active');overview.classList.remove('active');
  text('navGuideMode',data?'NAVEGANDO · GPS':'SEGUIMIENTO GPS');
 };
 window.rushNavExploring=function(){
  if(!data){driving=false;return;}
  driving=false;
  follow.classList.remove('active');
  overview.classList.add('active');
  text('navGuideMode','VISTA GENERAL');
 };
 var oldRoute=window.rushNavRoute;
 window.rushNavRoute=function(result){
  if(!result||!result.ok){
   if(rerouting){
    rerouting=false;
    text('navGuideMode','RECALCULO NO DISPONIBLE');
    text('navGuideDetail','Manteniendo ruta anterior; utiliza señales reales');
    return;
   }
   if(typeof oldRoute==='function')oldRoute(result);
   return;
  }
  rerouting=false;
  if(typeof oldRoute==='function')oldRoute(result);
  data=result;stepIndex=Array.isArray(result.steps)?
     Math.max(0,result.steps.findIndex(function(s){return s.type!=='depart'})):0;
  spokenIndex=-1;spokenCloseIndex=-1;offCount=0;
  mode(true);
  guidance();
 };
 var oldPosition=window.rushPosition;
 window.rushPosition=function(lat,lon,accuracy){
  if(typeof oldPosition==='function')oldPosition(lat,lon,accuracy);
  if(!Number.isFinite(lat)||!Number.isFinite(lon))return;
  position={lat:lat,lon:lon,accuracy:Number.isFinite(accuracy)?accuracy:999};
  guidance();
 };
 var oldSpeed=window.rushGpsData;
 window.rushGpsData=function(kmh,bearing){
  if(typeof oldSpeed==='function')oldSpeed(kmh,bearing);
  speed=Number.isFinite(kmh)?kmh:0;
 };
 follow.addEventListener('click',function(){if(data)mode(true)});
 overview.addEventListener('click',function(){if(data)mode(false)});
 voiceButton.textContent=voiceEnabled?'Voz: sí':'Voz: no';
 voiceButton.addEventListener('click',function(){
  voiceEnabled=!voiceEnabled;
  voiceButton.textContent=voiceEnabled?'Voz: sí':'Voz: no';
  try{localStorage.setItem('rush_nav_voice',voiceEnabled?'yes':'no')}catch(e){}
  if(!voiceEnabled&&window.Rush&&typeof window.Rush.stopNavigationSpeech==='function')
    window.Rush.stopNavigationSpeech();
 });
 document.getElementById('navClearRoute').addEventListener('click',clear);
 Array.prototype.forEach.call(map.querySelectorAll('[data-close="mapPanel"]'),function(b){
  b.addEventListener('click',clear);
 });
 document.addEventListener('visibilitychange',function(){
  if(document.hidden&&data)clear();
 });
})();
