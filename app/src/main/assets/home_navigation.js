/* Rush Launcher 4.17 — guiado giro a giro en el Inicio del vehículo.
   Consume el motor de rutas NATIVO existente de Rush (OSRM/Valhalla).
   Servicios comunitarios: requieren internet; nunca simula una ruta o GPS.
   No calcula rutas hasta que el conductor seleccione un destino estando detenido.
 */
(function(){
'use strict';
var $=function(id){return document.getElementById(id)};
var host=document.querySelector('.home-map');
var query=$('homeNavInput'),find=$('homeNavSearchButton'),results=$('homeNavResults');
var guide=$('homeNavGuide'),title=$('homeNavInstruction'),arrow=$('homeNavTurnIcon');
var nextDistance=$('homeNavDistance'),summary=$('homeNavSummary');
var status=$('homeNavStatus'),finish=$('homeNavFinish'),voiceButton=$('homeNavVoice');
if(!host||!query||!find||!results||!guide||!finish||!voiceButton)return;
var nativeApp=!!(window.Rush&&typeof window.Rush.routeHomeNav==='function');
var destination=null,route=null,routeBusy=false,requestSeq=30,routeRequest=0;
var searchSeq=100,searchTimer=null,lastSearchAt=0,lastRouteAt=0,lastReroute=0;
var fix=null,accuracy=999,speed=0,lastBearing=0,lastPositionAt=0;
var currentStep=0,lastStepDistance=Infinity,offRouteCount=0,voiceStage='';
var voiceEnabled=true,heldStatusTimer=null;
var peek=$('homeNavPeek'),guideTimer=null,lastGuideStep=-1,lastGuideStage='';
var routeCumulative=[],routeGeometryMeters=0,bestProgressMeters=0;
/* Rush 4.20.11 · Alternativas TomTom, preferencias y Waze externo.
   Nunca se afirman alertas comunitarias exclusivas de Waze. */
var routeOptions=[],selectedOption=0,lastTrafficRefresh=0;
var altPanel=$('homeNavAlternatives'),optionsButton=$('homeNavOptions'),
    wazeButton=$('homeNavWaze'),avoidTolls=$('rushAvoidTolls'),
    avoidMotorways=$('rushAvoidMotorways'),avoidUnpaved=$('rushAvoidUnpaved');
function storedBool(key){try{return localStorage.getItem(key)==='yes'}catch(e){return false}}
[[avoidTolls,'rush_nav_avoid_tolls'],
 [avoidMotorways,'rush_nav_avoid_motorways'],
 [avoidUnpaved,'rush_nav_avoid_unpaved']].forEach(function(pair){
 var input=pair[0];if(!input)return;
 input.checked=storedBool(pair[1]);
 input.addEventListener('change',function(){
  try{localStorage.setItem(pair[1],input.checked?'yes':'no'}catch(e){}
  if(destination&&route&&stopped()){
   lastRouteAt=0;requestRoute(false);
  }else if(destination)showStatus('Se aplicará al próximo recálculo detenido',true);
 });
});
function preferences(){
 return {tolls:!!(avoidTolls&&avoidTolls.checked),
   motorways:!!(avoidMotorways&&avoidMotorways.checked),
   unpaved:!!(avoidUnpaved&&avoidUnpaved.checked)};
}
function ttKey(){
 try{var v=localStorage.getItem('rush_tomtom_key_420')||'';
  return /^[A-Za-z0-9_-]{12,128}$/.test(v)?v:''}
 catch(e){return ''}
}
function minLabel(s){return Math.max(1,Math.ceil(Number(s||0)/60))+' min'}
function renderAltButtons(){
 if(!altPanel)return;
 altPanel.textContent='';
 if(routeOptions.length<=1){
  altPanel.hidden=true;if(optionsButton)optionsButton.hidden=true;return;
 }
 var caption=document.createElement('strong');
 caption.textContent='Rutas · selección con vehículo detenido';
 altPanel.appendChild(caption);
 routeOptions.forEach(function(item,i){
  var button=document.createElement('button');button.type='button';
  button.disabled=!stopped();
  button.className=i===selectedOption?'rush-alt-selected':'';
  button.textContent=(i===0?'Rápida':'Alternativa '+(i+1))+
    ' · '+minLabel(item.seconds)+' · '+distanceLabel(item.meters);
  if(item.traffic&&Number(item.trafficDelaySeconds)>30)
   button.textContent+=' · +'+minLabel(item.trafficDelaySeconds)+' tráfico';
  button.addEventListener('click',function(){
   if(!stopped()){showStatus('Detén el vehículo para seleccionar otra ruta',true);return;}
   chooseAlternative(i);
  });
  altPanel.appendChild(button);
 });
 if(optionsButton)optionsButton.hidden=false;
}
function chooseAlternative(index){
 if(!routeOptions[index]||!routeResultValid(routeOptions[index]))return;
 route=routeOptions[index];selectedOption=index;lastTrafficRefresh=Date.now();
 currentStep=(route.steps[0]&&route.steps[0].type==='depart'&&
   route.steps.length>1)?1:0;
 lastStepDistance=Infinity;voiceStage='';offRouteCount=0;
 indexRoute();
 if(window.RushHomeMap){
  window.RushHomeMap.setRoute(route.geometry);
  if(window.RushHomeMap.setAlternates)
   window.RushHomeMap.setAlternates(routeOptions.filter(function(x,i){return i!==index}));
  window.RushHomeMap.follow();
 }
 renderAltButtons();
 if(altPanel)altPanel.hidden=true;
 updateGuidance();showGuideBriefly();
}
if(optionsButton)optionsButton.addEventListener('click',function(){
 if(!stopped()){showStatus('Detén el vehículo para cambiar ruta',true);return;}
 renderAltButtons();
 if(altPanel&&routeOptions.length>1)altPanel.hidden=!altPanel.hidden;
});
if(wazeButton)wazeButton.addEventListener('click',function(){
 if(!destination){showStatus('Selecciona primero un destino',true);return;}
 if(!stopped()){showStatus('Detén el vehículo antes de abrir Waze',true);return;}
 if(window.Rush&&typeof window.Rush.openWazeDestination==='function')
  window.Rush.openWazeDestination(destination.lat,destination.lon);
 else showStatus('Waze externo requiere aplicación Android instalada',true);
});

// ETA según distancia realmente recorrida sobre la polilínea OSRM.
function measureSegment(a,b){
 var dx=(Number(b[0])-Number(a[0]))*111320*
     Math.cos((Number(a[1])+Number(b[1]))*Math.PI/360);
 var dy=(Number(b[1])-Number(a[1]))*111320;
 return Math.hypot(dx,dy);
}
function indexRoute(){
 routeCumulative=[0];routeGeometryMeters=0;bestProgressMeters=0;
 var coords=route&&route.geometry||[];
 for(var i=1;i<coords.length;i++){
  var d=measureSegment(coords[i-1],coords[i]);
  routeGeometryMeters+=Number.isFinite(d)?d:0;
  routeCumulative.push(routeGeometryMeters);
 }
}
function progressOnRoute(point){
 if(!point||!route||routeGeometryMeters<1)return null;
 var coords=route.geometry,cos=Math.cos(point.lat*Math.PI/180);
 var closest=Infinity,along=0;
 for(var i=1;i<coords.length;i++){
  var a=coords[i-1],b=coords[i];
  var ax=(Number(a[0])-point.lon)*111320*cos,ay=(Number(a[1])-point.lat)*111320;
  var bx=(Number(b[0])-point.lon)*111320*cos,by=(Number(b[1])-point.lat)*111320;
  var vx=bx-ax,vy=by-ay,len=vx*vx+vy*vy;
  var t=len>.01?Math.max(0,Math.min(1,-(ax*vx+ay*vy)/len)):0;
  var px=ax+t*vx,py=ay+t*vy,squared=px*px+py*py;
  if(squared<closest){
   closest=squared;
   along=routeCumulative[i-1]+t*(routeCumulative[i]-routeCumulative[i-1]);
  }
 }
 var offset=Math.sqrt(closest);
 if(offset<=85)bestProgressMeters=Math.max(bestProgressMeters,along);
 return {offset:offset,along:bestProgressMeters};
}
function etaForCurrentFix(){
 if(!route||!fix||!(Number(route.seconds)>0)||routeGeometryMeters<1)return null;
 var prog=progressOnRoute(fix);
 if(!prog)return null;
 var fraction=Math.max(0,Math.min(1,1-prog.along/routeGeometryMeters));
 var metersLeft=Math.max(0,(Number(route.meters)||0)*fraction);
 var minutesLeft=Math.ceil((Number(route.seconds)||0)*fraction/60);
 var eta=new Date(Date.now()+minutesLeft*60000);
 var hh=String(eta.getHours()).padStart(2,'0');
 var mm=String(eta.getMinutes()).padStart(2,'0');
 return {meters:metersLeft,minutes:minutesLeft,time:hh+':'+mm,offset:prog.offset};
}

function showGuideBriefly(){
 if(!route)return;
 if(guideTimer!==null){clearTimeout(guideTimer);guideTimer=null}
 showing(guide,true);
 // No dejar un recuadro permanente sobre las calles ni tapar el giro.
 guideTimer=setTimeout(function(){showing(guide,false);guideTimer=null},4500);
}
function closeGuide(){
 if(guideTimer!==null)clearTimeout(guideTimer);
 guideTimer=null;showing(guide,false);
}
if(peek)peek.addEventListener('click',showGuideBriefly);

try{voiceEnabled=localStorage.getItem('rush_home_voice')!=='no'}catch(e){}
function syncVoiceButton(){
 voiceButton.textContent=voiceEnabled?'🔊':'🔇';
 voiceButton.title=voiceEnabled?'Desactivar voz':'Activar voz y probar audio';
 voiceButton.setAttribute('aria-label',voiceEnabled?'Desactivar indicaciones por voz':'Activar y probar indicaciones por voz');
 voiceButton.setAttribute('aria-pressed',String(voiceEnabled));
}
syncVoiceButton();
function write(el,text){if(el)el.textContent=String(text)}
function showing(element,yes){if(element)element.hidden=!yes}
function valid(lat,lon){return Number.isFinite(lat)&&Number.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180}
function meters(a,b){
 if(!a||!b)return Infinity;
 var dx=(a.lon-b.lon)*111320*Math.cos(a.lat*Math.PI/180);
 var dy=(a.lat-b.lat)*111320;return Math.hypot(dx,dy);
}
function distanceLabel(m){
 if(!Number.isFinite(m))return '—';
 return m<950?Math.max(0,Math.round(m/10)*10)+' m':(m/1000).toFixed(1)+' km';
}
function freshGps(){
 var recent=fix&&Date.now()-lastPositionAt<15000;
 // iPhone RMC provee fix válido pero NO información de precisión (GGA/GST).
 // Permitir cálculo manual de ruta, sin habilitar voz ni reruteo automático
 // basado en precisión no informada.
 var external=window.RushExternalGps &&
     window.RushExternalGps.active && window.RushExternalGps.recent &&
     window.RushExternalGps.hasFix;
 return !!(recent&&(accuracy<=120||external));
}
function stopped(){return !Number.isFinite(speed)||speed<=5}
function showStatus(text,temporary){
 if(!status)return;
 write(status,text);showing(status,true);
 if(heldStatusTimer!==null)clearTimeout(heldStatusTimer);
 if(temporary)heldStatusTimer=setTimeout(function(){showing(status,false)},5500);
}
function recent(){
 try{
  var data=JSON.parse(localStorage.getItem('rush_nav_recent')||'[]');
  return Array.isArray(data)?data.filter(function(item){
   return item&&valid(Number(item.lat),Number(item.lon))&&typeof item.name==='string';
  }).slice(0,7):[];
 }catch(e){return []}
}
function remember(hit){
 var list=recent().filter(function(x){
  return x.name!==hit.name&&meters(x,hit)>8;
 });
 list.unshift({name:String(hit.name).slice(0,110),
  detail:String(hit.detail||'').slice(0,100),lat:hit.lat,lon:hit.lon});
 try{localStorage.setItem('rush_nav_recent',JSON.stringify(list.slice(0,8)))}catch(e){}
}
function syncSearchOverlay(){
 // Evita texto de GPS/mapa debajo del input cuando el teclado ocupa pantalla.
 host.classList.toggle('search-keyboard',
   document.activeElement===query || !results.hidden);
}
function clearResults(){
 results.textContent='';
 showing(results,false);
 host.classList.remove('is-searching');
 syncSearchOverlay();
}
function addResults(items,caption){
 results.textContent='';
 if(!items.length){
  clearResults();return;
 }
 var heading=document.createElement('div');
 heading.className='home-nav-msg';heading.textContent=caption;
 results.appendChild(heading);
 items.slice(0,5).forEach(function(item){
  var lat=Number(item.lat),lon=Number(item.lon);
  if(!valid(lat,lon))return;
  var btn=document.createElement('button');btn.type='button';
  var name=document.createElement('strong');name.textContent=String(item.name||'Destino').slice(0,100);
  var sub=document.createElement('small');sub.textContent=String(item.detail||'Toca para iniciar ruta').slice(0,105);
  btn.appendChild(name);btn.appendChild(sub);
  btn.addEventListener('click',function(){
   clearResults();
   query.value=String(item.name||'Destino').slice(0,100);
   query.blur();
   remember({name:query.value,detail:item.detail||'',lat:lat,lon:lon});
   selectDestination(lat,lon,query.value);
  });
  results.appendChild(btn);
 });
 if(results.querySelectorAll('button').length){
  showing(results,true);host.classList.add('is-searching');
 }
 syncSearchOverlay();
}
function pending(text){write(status,text);showing(status,true)}
function scheduleSuggestions(){
 syncSearchOverlay();
 if(searchTimer!==null)clearTimeout(searchTimer);
 var ticket=++searchSeq,q=query.value.trim(),key=q.toLowerCase();
 if(!stopped()){clearResults();return}
 var matching=recent().filter(function(x){
  return !key||(x.name+' '+x.detail).toLowerCase().indexOf(key)>=0;
 });
 if(q.length<3){
  addResults(matching,'Destinos recientes');
  return;
 }
 if(matching.length)addResults(matching,'Destinos recientes');
 else{clearResults();pending('Buscando lugares cercanos en Chile…')}
 if(!nativeApp||typeof window.Rush.suggestHomeNav!=='function'){
  if(!matching.length)showStatus('El buscador necesita el APK Android',true);
  return;
 }
 var delay=Math.max(500,1300-(Date.now()-lastSearchAt));
 searchTimer=setTimeout(function(){
  if(ticket!==searchSeq||!stopped())return;
  lastSearchAt=Date.now();
  window.Rush.suggestHomeNav(q,freshGps()?fix.lat:0,freshGps()?fix.lon:0,ticket);
 },delay);
}
window.rushHomeSuggest=function(reply){
 if(!reply||reply.requestId!==searchSeq||query.value.trim().toLowerCase()!==
      String(reply.query||query.value).trim().toLowerCase())return;
 if(reply.ok&&Array.isArray(reply.items)&&reply.items.length){
  addResults(reply.items,'Lugares cercanos · Chile · OpenStreetMap');
  showStatus('Selecciona un lugar de la lista',true);
 }else{
  var found=recent().filter(function(x){
   return (x.name+' '+x.detail).toLowerCase().includes(query.value.trim().toLowerCase());
  });
  if(found.length)addResults(found,'Destinos recientes');
  else{clearResults();showStatus('Sin predicciones: pulsa Buscar para una búsqueda completa',true)}
 }
};
function explicitSearch(){
 if(!stopped()){showStatus('Detén el vehículo para buscar un destino',true);return}
 query.blur();
 var q=query.value.trim();
 if(q.length<3){showStatus('Escribe al menos tres letras',true);return}
 if(searchTimer!==null)clearTimeout(searchTimer);
 var ticket=++searchSeq;
 clearResults();showStatus('Buscando '+q+' en Chile…',false);
 if(!nativeApp||typeof window.Rush.searchHomeNav!=='function'){
  showStatus('Búsqueda disponible al instalar el APK Android',true);return;
 }
 window.Rush.searchHomeNav(q,freshGps()?fix.lat:0,freshGps()?fix.lon:0,ticket);
}
window.rushHomeSearch=function(reply){
 if(!reply||reply.requestId!==searchSeq)return;
 if(!reply.ok||!Array.isArray(reply.items)||!reply.items.length){
  showStatus(reply&&reply.message||'Lugar no encontrado; mantén pulsado el mapa',true);
  return;
 }
 addResults(reply.items,'Búsqueda en Chile · OpenStreetMap');
 showStatus('Selecciona el destino para calcular la ruta',true);
};
function selectDestination(lat,lon,name){
 if(!stopped()){showStatus('Detén el vehículo antes de fijar destino',true);return}
 if(!valid(lat,lon))return;
 // Ignorar cualquier respuesta antigua cuando cambia el destino.
 ++routeRequest;
 routeBusy=false;route=null;offRouteCount=0;currentStep=0;voiceStage='';
 lastGuideStage='';lastGuideStep=-1;closeGuide();
 routeCumulative=[];routeGeometryMeters=0;bestProgressMeters=0;
 if(peek)showing(peek,false);
 host.classList.remove('has-active-route');
 lastRouteAt=0;
 destination={lat:lat,lon:lon,name:name||'Destino seleccionado'};
 if(window.RushHomeMap)window.RushHomeMap.clearRoute();
 showing(guide,false);
 clearResults();
 showStatus('Destino: '+destination.name+'. Calculando ruta…',false);
 if(freshGps())requestRoute(false);
 else showStatus('Destino listo. Esperando señal GPS reciente para calcular ruta.',false);
}
function routeResultValid(reply){
 return reply&&reply.ok&&Array.isArray(reply.geometry)&&reply.geometry.length>=2&&
   Array.isArray(reply.steps)&&Number.isFinite(Number(reply.meters));
}
function requestRoute(rerouting){
 if(!destination||routeBusy||!freshGps())return;
 if(!nativeApp){showStatus('Cálculo de rutas disponible solo en el APK Android',false);return}
 var now=Date.now();
 // No saturar proveedores públicos cuando el cálculo falla.
 if(now-lastRouteAt<45000)return;
 routeBusy=true;lastRouteAt=now;
 var token=++routeRequest;
 showStatus(rerouting?'Fuera de ruta: recalculando…':'Calculando ruta hacia '+destination.name+'…',false);
 window.Rush.routeHomeNav(fix.lat,fix.lon,destination.lat,destination.lon,token);
}
window.rushHomeRouteProgress=function(reply){
 if(!reply||reply.requestId!==routeRequest||!routeBusy)return;
 if(reply.message)showStatus(String(reply.message).slice(0,145),false);
};
window.rushHomeRoute=function(reply){
 if(!reply||reply.requestId!==routeRequest)return;
 routeBusy=false;
 if(!destination)return;
 if(!routeResultValid(reply)){
  showStatus((reply.message||'No se pudo calcular ruta')+
    '. Comprueba internet. Puedes elegir el mismo destino de nuevo para reintentar.',false);
  return;
 }
 route=reply;currentStep=(route.steps[0]&&route.steps[0].type==='depart'&&route.steps.length>1)?1:0;
 lastStepDistance=Infinity;offRouteCount=0;voiceStage='';
 indexRoute();
 if(window.RushHomeMap){
  window.RushHomeMap.setRoute(route.geometry);
  window.RushHomeMap.follow();
 }
 host.classList.add('has-active-route');
 showing(status,false);
 if(peek)showing(peek,true);
 updateGuidance();
 showGuideBriefly();
 speak('Ruta calculada. Comienza la navegación.',true);
};
function describeStep(step){
 if(!step)return {text:'Sigue tu ruta',icon:'↑'};
 var type=String(step.type||'').toLowerCase();
 var m=String(step.modifier||'').toLowerCase();
 var road=String(step.road||'').trim();
 var right=m.indexOf('right')>=0,left=m.indexOf('left')>=0;
 var text=type==='arrive'?'Llegaste a tu destino':
  type==='depart'?'Inicia el recorrido':
  type==='roundabout'||type==='rotary'?'Entra a la rotonda':
  type==='uturn'||m.indexOf('uturn')>=0?'Realiza cambio de sentido':
  left?'Gira a la izquierda':right?'Gira a la derecha':'Continúa por la vía';
 var icon=type==='arrive'?'●':left?'↰':right?'↱':
  type==='roundabout'||type==='rotary'?'⟳':type==='uturn'?'↶':'↑';
 return {text:text+(road&&type!=='arrive'?' · '+road:''),icon:icon};
}
function nearestToRoute(point){
 if(!route||!Array.isArray(route.geometry))return Infinity;
 var coords=route.geometry,cos=Math.cos(point.lat*Math.PI/180);
 var best=Infinity,step=Math.max(1,Math.floor(coords.length/240));
 for(var i=step;i<coords.length;i+=step){
  var p=coords[i-step],q=coords[i];
  if(!Array.isArray(p)||!Array.isArray(q))continue;
  var ax=(Number(p[0])-point.lon)*111320*cos, ay=(Number(p[1])-point.lat)*111320;
  var bx=(Number(q[0])-point.lon)*111320*cos, by=(Number(q[1])-point.lat)*111320;
  var vx=bx-ax,vy=by-ay;
  var t=(vx*vx+vy*vy)>1?Math.max(0,Math.min(1,-(ax*vx+ay*vy)/(vx*vx+vy*vy))):0;
  var dist=Math.hypot(ax+t*vx,ay+t*vy);
  if(dist<best)best=dist;
 }
 return best;
}
function externalGpsFresh(){
 return !!(window.RushExternalGps&&window.RushExternalGps.active&&
   window.RushExternalGps.recent&&window.RushExternalGps.hasFix&&
   fix&&Date.now()-lastPositionAt<10000);
}
function speak(text,force){
 if(!voiceEnabled||!nativeApp||!window.Rush.speakNavigation)return;
 // El iPhone transmite NMEA RMC sin precisión informada.
 if(!force&&(Date.now()-lastPositionAt>10000||
   !(accuracy<=80||externalGpsFresh())))return;
 window.Rush.speakNavigation(String(text).slice(0,190));
}
function testSpeech(){
 if(!nativeApp||typeof window.Rush.speakNavigation!=='function'){
  showStatus('La voz necesita el APK Android',true);return;
 }
 window.Rush.speakNavigation('Prueba de voz de Rush Launcher. En doscientos metros, gira a la derecha.');
}
window.rushTtsStatus=function(state,message){
 var text=String(message||'').slice(0,140);
 var output=document.getElementById('navTtsStatus');
 if(output)output.textContent=text;
 if(state==='error'){
  host.classList.add('rush-tts-error');
  showStatus(text,true);
  setTimeout(function(){host.classList.remove('rush-tts-error')},6500);
 }else host.classList.remove('rush-tts-error');
};
var testButton=document.getElementById('navTestSpeech');
if(testButton)testButton.addEventListener('click',testSpeech);
var soundButton=document.getElementById('navTestSound');
if(soundButton)soundButton.addEventListener('click',function(){
 if(nativeApp&&typeof window.Rush.testAudioBeep==='function')window.Rush.testAudioBeep();
 else showStatus('Prueba de sonido disponible en Android',true);
});
var diagButton=document.getElementById('navAudioDiag');
if(diagButton)diagButton.addEventListener('click',function(){
 if(nativeApp&&typeof window.Rush.diagnoseAudio==='function')window.Rush.diagnoseAudio();
 else showStatus('Diagnóstico de audio disponible en Android',true);
});
window.rushAudioDiagnostics=function(state,message){
 var diag=document.getElementById('navAudioDiagStatus');
 if(diag)diag.textContent=String(message||'').slice(0,280);
 if(state==='error')showStatus(String(message||'').slice(0,130),true);
};
var openTtsSettings=document.getElementById('navTtsSettings');
if(openTtsSettings)openTtsSettings.addEventListener('click',function(){
 if(nativeApp&&typeof window.Rush.openTtsSettings==='function')window.Rush.openTtsSettings();
 else showStatus('Ajustes de voz disponibles en Android',true);
});
function updateGuidance(){
 if(!route||!destination||!fix)return;
 if(!freshGps()){
  write(nextDistance,'GPS sin actualizar · última posición');
  if(peek)write(peek,'GPS sin actualizar · ETA pendiente');
  return;
 }
 var destDistance=meters(fix,destination);
 if(destDistance<35){
  if(peek)write(peek,'● Llegada');
  if(lastGuideStage!=='arrived'){lastGuideStage='arrived';showGuideBriefly()}
  write(arrow,'●');write(title,'Llegaste al destino');
  write(nextDistance,'Destino alcanzado');
  write(summary,'Finaliza la ruta para continuar sin destino');
  if(voiceStage!=='arrived'){voiceStage='arrived';speak('Llegaste a tu destino')}
  return;
 }
 var steps=route.steps||[],peekIcon='↑';
 if(!steps.length){
  write(arrow,'↑');write(title,'Sigue la ruta señalada');
  write(nextDistance,distanceLabel(destDistance)+' hasta destino');
  if(peek)write(peek,'↑ '+distanceLabel(destDistance));
 }else{
  var turn=steps[Math.min(currentStep,steps.length-1)];
  var d=meters(fix,turn);
  // Tras aproximarse a una maniobra, cambiar a la siguiente al superarla,
  // incluso si el GPS saltó sobre el radio de 30 m.
  if(currentStep<steps.length-1 &&
      (d<=28||(lastStepDistance<70&&d>lastStepDistance+24))){
   currentStep++;voiceStage='';
   turn=steps[currentStep];d=meters(fix,turn);
  }
  lastStepDistance=d;
  var message=describeStep(turn);
  write(arrow,message.icon);write(title,message.text);
  write(nextDistance,distanceLabel(d)+' para maniobra');
  peekIcon=message.icon;
  var guideStage=d<=50?'imminent':d<=160?'near':d<=440?'upcoming':'cruise';
  // Mostrar indicación solo cuando cambia el giro o se entra en un tramo
  // próximo: no volver a abrir el cartel con cada lectura GPS (1Hz).
  if(currentStep!==lastGuideStep||
     (guideStage!==lastGuideStage&&guideStage!=='cruise')){
   lastGuideStep=currentStep;lastGuideStage=guideStage;
   showGuideBriefly();
  }else{lastGuideStep=currentStep;lastGuideStage=guideStage}

  if(voiceEnabled && speed>5 && (accuracy<=70||externalGpsFresh())){
   var stage=d<=85?'short':d<=280?'near':'far';
   if(stage!=='far'&&voiceStage!==stage){
    voiceStage=stage;
    speak((stage==='short'?'En '+distanceLabel(d)+', ':'En aproximadamente '+distanceLabel(d)+', ')
      +message.text);
   }
  }
 }
 var eta=etaForCurrentFix();
 if(eta){
  var remaining=distanceLabel(eta.meters);
  if(peek)write(peek,peekIcon+' '+remaining+' · ETA '+eta.time);
  write(summary,'Restan '+remaining+' · '+eta.minutes+
    ' min · llegada estimada '+eta.time+' · sin tráfico en tiempo real');
 }else{
  var km=(Number(route.meters)||0)/1000;
  var minutes=Math.round((Number(route.seconds)||0)/60);
  if(peek)write(peek,peekIcon+' '+km.toFixed(1)+' km');
  write(summary,km.toFixed(1)+' km · '+minutes+' min estimados');
 }
}
function updateFix(lat,lon,acc){
 if(!valid(lat,lon))return;
 fix={lat:lat,lon:lon};accuracy=Number.isFinite(acc)?acc:999;lastPositionAt=Date.now();
 if(destination&&!route&&!routeBusy){
  requestRoute(false);
 }
 if(route){
  updateGuidance();
  if((accuracy<=65||externalGpsFresh())&&speed>10){
   var off=nearestToRoute(fix);
   // Un fix NMEA RMC reciente basta para detectar desvío. Cuatro
   // lecturas consecutivas evitan rerutas por un único salto de GPS.
   offRouteCount=off>70?offRouteCount+1:0;
   if(offRouteCount>=4&&!routeBusy&&Date.now()-lastReroute>90000){
    offRouteCount=0;lastReroute=Date.now();requestRoute(true);
   }
  }else offRouteCount=0;
 }
}
function updateSpeed(kmh,bearing){
 speed=Number.isFinite(kmh)?Math.max(0,kmh):0;
 if(Number.isFinite(bearing))lastBearing=bearing;
 var locked=!stopped();
 query.disabled=locked;find.disabled=locked;
 if(locked){query.blur();clearResults()}
}
function cancelRoute(){
 ++routeRequest;destination=null;route=null;routeBusy=false;
 currentStep=0;voiceStage='';offRouteCount=0;
 routeCumulative=[];routeGeometryMeters=0;bestProgressMeters=0;
 if(window.RushHomeMap)window.RushHomeMap.clearRoute();
 closeGuide();if(peek)showing(peek,false);
 lastGuideStage='';lastGuideStep=-1;
 host.classList.remove('has-active-route');clearResults();query.value='';
 showStatus('Modo conducción sin destino · mapa y GPS activos',true);
 if(nativeApp&&window.Rush.stopNavigationSpeech)window.Rush.stopNavigationSpeech();
}
function pickCoordinate(lat,lon){
 if(!stopped()){showStatus('Selecciona destinos únicamente con el vehículo detenido',true);return}
 selectDestination(lat,lon,'Punto seleccionado');
}
voiceButton.addEventListener('click',function(){
 voiceEnabled=!voiceEnabled;
 syncVoiceButton();
 try{localStorage.setItem('rush_home_voice',voiceEnabled?'yes':'no')}catch(e){}
 if(!voiceEnabled&&nativeApp&&window.Rush.stopNavigationSpeech)window.Rush.stopNavigationSpeech();
 if(voiceEnabled)testSpeech();
});
finish.addEventListener('click',cancelRoute);
query.addEventListener('input',scheduleSuggestions);
query.addEventListener('focus',function(){
 syncSearchOverlay();
 scheduleSuggestions();
});
query.addEventListener('blur',function(){
 // Pequeño margen para que el toque sobre un resultado no cambie el layout
 // antes de procesarse el click.
 setTimeout(syncSearchOverlay,250);
});
query.addEventListener('keydown',function(e){
 if(e.key==='Enter'){e.preventDefault();explicitSearch()}
 if(e.key==='Escape'){clearResults();query.blur();syncSearchOverlay()}
});
find.addEventListener('click',explicitSearch);
document.addEventListener('click',function(event){
 if(!query.contains(event.target)&&!results.contains(event.target)&&
    !find.contains(event.target))clearResults();
});
var originalPos=window.rushPosition;
window.rushPosition=function(lat,lon,acc){
 if(typeof originalPos==='function')originalPos(lat,lon,acc);
 updateFix(lat,lon,acc);
};
var originalSpeed=window.rushGpsData;
window.rushGpsData=function(kmh,bearing){
 if(typeof originalSpeed==='function')originalSpeed(kmh,bearing);
 updateSpeed(kmh,bearing);
};
window.RushHomeNavigation={
 showGuide:showGuideBriefly,
 pickCoordinate:pickCoordinate,clearRoute:cancelRoute,
 choose:selectDestination,getDestination:function(){return destination},
 isActive:function(){return !!route}
};
if(!nativeApp)showStatus('La búsqueda y rutas requieren instalar Rush Launcher en el Carlinkit',false);
})();
