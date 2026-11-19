/* Rush Launcher 4.15 · GPS vivo con detección de datos obsoletos. */
(function(){
'use strict';
var $=function(id){return document.getElementById(id)};
var nativeApp=!!(window.Rush&&typeof window.Rush.open==='function');
var saved={};
try{
 // Migración única solicitada: clima activo al instalar 4.15.4.
 if(localStorage.getItem('rush_weather_4154')!=='yes'){
  localStorage.setItem('rush_weather','yes');
  localStorage.setItem('rush_weather_4154','yes');
 }
 saved={name:localStorage.getItem('rush_user_name')||'',
        theme:localStorage.getItem('rush_theme')||'auto',
        weather:localStorage.getItem('rush_weather')!=='no',
        drive:localStorage.getItem('rush_drive_ui')!=='no'};
}catch(e){saved={name:'',theme:'auto',weather:true,drive:true}};
var state={gps:null,gpsStatus:'Esperando señal GPS',location:null,obdConnected:false,obdSpeed:null,rpm:null,coolant:null,obd:null,weather:null,weatherLoading:false,spotify:null,spotifyConnected:false,spotifyConfigured:false,apps:[],appIcons:{},dockApps:{},weatherAsked:0};
function write(id,value){var el=$(id);if(el)el.textContent=value}
function show(el,visible){if(el)el.hidden=!visible}
function app(action){if(nativeApp)window.Rush.open(action);else notice('Disponible al instalar en Android')}
function notice(message){var e=$('toast');e.textContent=message;show(e,true);clearTimeout(notice.timer);notice.timer=setTimeout(function(){show(e,false)},2800)}
function greeting(hour){return hour>=6&&hour<12?'Buenos días':hour>=12&&hour<20?'Buenas tardes':'Buenas noches'}
function setDrivingMode(enabled, persist) {
 saved.drive = enabled !== false;
 var screen = $('rushScreen');
 if (screen) screen.classList.toggle('drive-ui', saved.drive);
 var button = $('driveModeToggle');
 if (button) {
  button.textContent = saved.drive ? 'Panel clásico' : 'Modo conducción';
  button.setAttribute('aria-pressed', saved.drive ? 'true' : 'false');
  button.title = saved.drive ? 'Mostrar Inicio clásico' : 'Mostrar mapa, música, clima y OBD juntos';
 }
 if (persist) store('rush_drive_ui', saved.drive ? 'yes':'no');
 // El mapa está dentro del mismo DOM: no duplicar motores ni reiniciar Spotify.
 if (window.RushHomeMap) {
  if (saved.drive) window.RushHomeMap.follow();
  if (typeof window.RushHomeMap.refresh === 'function') {
   requestAnimationFrame(function() {
    window.RushHomeMap.refresh();
    requestAnimationFrame(function(){window.RushHomeMap.refresh()});
   });
  }
 }
}
$('driveModeToggle').addEventListener('click',function(){
 setDrivingMode(!saved.drive,true);
});
function updateTime(){
 var d=new Date(),h=d.getHours();
 write('greeting',greeting(h)+(saved.name?', '+saved.name:''));
 write('today',d.toLocaleDateString('es-CL',{weekday:'long',day:'numeric',month:'long'}));
 write('clock',d.toLocaleTimeString('es-CL',{hour:'numeric',minute:'2-digit',hour12:true}));
 applyTheme();
}
function applyTheme(){
 // Auto usa la hora LOCAL del Android del Carlinkit.
 var currentHour=new Date().getHours();
 var dark=saved.theme==='dark'||(saved.theme==='auto'&&(currentHour<7||currentHour>=19));
 document.documentElement.setAttribute('data-theme',dark?'dark':'light');
 var bar=document.querySelector('meta[name="theme-color"]');
 if(bar)bar.setAttribute('content',dark?'#0e1036':'#e3defc');
 write('modeLabel',saved.theme==='auto'?'Automático ('+(dark?'noche':'día')+')':dark?'Oscuro':'Claro');
 write('modeSwitch',dark?'☾':'☀');
 Array.prototype.forEach.call(document.querySelectorAll('[data-theme-choice]'),function(b){
  b.classList.toggle('is-selected',b.getAttribute('data-theme-choice')===saved.theme);
 });
}
function store(key,value){try{localStorage.setItem(key,value)}catch(e){}}
function openOverlay(id){show($(id),true);if(id==='appsPanel'&&nativeApp){write('appsCount','Buscando…');$('appsSearch').value='';window.Rush.listApps()}}
function closeOverlay(id){
 if(id==='mapPanel'){
  navClear();
  if(window.RushLiveMap)window.RushLiveMap.deactivate();
  if(nativeApp&&typeof window.Rush.stopMapGps==='function')window.Rush.stopMapGps();
 }
 show($(id),false);
}
// El archivo de cartografía no se carga ni se ejecuta hasta tocar MAPA.
var mapScriptLoading=false,mapScriptLoaded=false;

// Navegación integrada: las peticiones de búsqueda y ruta se lanzan por toque.
var nav={destination:null,route:null,step:0};
function navStatus(message){write('mapSubtitle',message)}
function navMeters(a,b){
 if(!a||!b)return Infinity;
 var dlat=(a.lat-b.lat)*111320,dlon=(a.lon-b.lon)*111320*Math.cos(a.lat*Math.PI/180);
 return Math.sqrt(dlat*dlat+dlon*dlon);
}
function navInstruction(s){
 if(!s)return 'Sigue la ruta';
 var m=s.modifier||'',t=s.type||'',road=s.road||'';
 var txt=t==='arrive'?'Llegada al destino':t==='depart'?'Inicia el recorrido':
  t==='roundabout'?'Entra en la rotonda':m==='left'||m==='slight left'?'Gira a la izquierda':
  m==='right'||m==='slight right'?'Gira a la derecha':m==='sharp left'?'Gira cerrado a la izquierda':
  m==='sharp right'?'Gira cerrado a la derecha':m==='uturn'?'Cambio de sentido':'Continúa recto';
 return txt+(road&&t!=='arrive'?' · '+road:'');
}
function navGuidance(){
 if(!nav.route||!Array.isArray(nav.route.steps)||!state.location)return;
 var steps=nav.route.steps,loc=state.location;
 while(nav.step+1<steps.length&&navMeters(loc,steps[nav.step])<25)nav.step++;
 var turn=steps[nav.step];if(!turn)return;
 var meters=navMeters(loc,turn);
 write('navGuideTitle',navInstruction(turn));
 write('navGuideDetail',(meters<1000?Math.round(meters)+' m':(meters/1000).toFixed(1)+' km')+
  (loc.accuracy>75?' · precisión limitada ±'+Math.round(loc.accuracy)+' m':' · ubicación aproximada'));
}
function navSelect(lat,lon,name){
 if(!Number.isFinite(lat)||!Number.isFinite(lon))return;
 nav.destination={lat:lat,lon:lon,name:name||'Punto del mapa'};nav.route=null;nav.step=0;
 if(window.RushLiveMap){
   window.RushLiveMap.setDestination(lat,lon);
   window.RushLiveMap.enableSelection(false);
   window.RushLiveMap.drivingMode(true);
 }
 $('navStartRoute').disabled=false;
 show($('navResults'),false);show($('navGuide'),false);show($('navRouteInfo'),false);
 navStatus('Destino seleccionado · pulsa Calcular ruta');
}
window.rushNavPicked=function(point){navSelect(point.lat,point.lon,'Punto en el mapa')};
window.rushNavSearch=function(result){
 var box=$('navResults');box.textContent='';
 if(!result||!result.ok){navStatus(result&&result.message||'No se pudo buscar');return;}
 var items=Array.isArray(result.items)?result.items:[];
 if(!items.length){navStatus('Lugar no encontrado. Selecciona un punto en el mapa.');return;}
 items.forEach(function(hit){
  var b=document.createElement('button');b.type='button';
  b.textContent=String(hit.name||'Destino').slice(0,115);
  b.addEventListener('click',function(){navSelect(hit.lat,hit.lon,hit.name);$('navQuery').value=hit.name});
  box.appendChild(b);
 });
 show(box,true);navStatus('Selecciona un destino en la lista');
};
function navHideDiagnostics(){show($('navDiagnostics'),false)}
function navShowDiagnostics(message){
 write('navDiagMessage',String(message||'No fue posible calcular el recorrido.'));
 show($('navDiagnostics'),true);
}
$('navDiagDismiss').addEventListener('click',navHideDiagnostics);
window.rushNavRouteProgress=function(data){
 if(!data||!data.message)return;
 navStatus(String(data.message));
};
window.rushNavRoute=function(data){
 if(!data||!data.ok){
  var problem=data&&data.message||'Ruta no disponible';
  navStatus('No se calculó la ruta · consulta el diagnóstico');
  navShowDiagnostics(problem);
  show($('navGuide'),false);
  show($('navRouteInfo'),false);
  return;
 }
 navHideDiagnostics();
 nav.route=data;nav.step=0;
 if(window.RushLiveMap)window.RushLiveMap.setRoute(data.geometry);
 write('navRouteInfo',((Number(data.meters)||0)/1000).toFixed(1)+' km · '+
   Math.round((Number(data.seconds)||0)/60)+' min aprox.');
 show($('navRouteInfo'),true);show($('navGuide'),true);
 navStatus('Ruta calculada con '+String(data.provider||'motor de rutas')+' · sin tráfico en vivo');
 navGuidance();
};
function navClear(){
 navHideDiagnostics();
 nav.destination=null;nav.route=null;nav.step=0;
 if(window.RushLiveMap)window.RushLiveMap.clearRoute();
 $('navStartRoute').disabled=true;
 show($('navResults'),false);show($('navGuide'),false);show($('navRouteInfo'),false);
 navStatus('Busca una dirección o marca un punto en el mapa');
}
$('navSearchBtn').addEventListener('click',function(){
 var q=$('navQuery').value.trim();
 if(q.length<3){navStatus('Escribe una dirección');return;}
 if(!nativeApp||typeof window.Rush.searchNav!=='function'){
  navStatus('Búsqueda disponible en la APK Android');return;
 }
 show($('navResults'),false);navStatus('Buscando en Chile cerca de tu ubicación…');
 var loc=state.location;
 var near=loc&&loc.receivedAt&&Date.now()-loc.receivedAt<180000;
 if(typeof window.Rush.searchNavNear==='function'){
  window.Rush.searchNavNear(q,near?loc.lat:0,near?loc.lon:0);
 }else window.Rush.searchNav(q);
});
$('navQuery').addEventListener('keydown',function(e){
 if(e.key==='Enter'){e.preventDefault();$('navSearchBtn').click();}
});
$('navSelectMap').addEventListener('click',function(){
 if(!window.RushLiveMap){navStatus('Mapa todavía no cargado');return;}
 window.RushLiveMap.enableSelection(true);navStatus('Toca el mapa para elegir destino');
});
$('navStartRoute').addEventListener('click',function(){
 if(!nav.destination){navStatus('Selecciona primero un destino');return;}
 var loc=state.location;
 if(!loc||!loc.receivedAt||Date.now()-loc.receivedAt>30000){
  navStatus('Sin ubicación reciente. Pulsa Ubicación.');return;
 }
 if(Number.isFinite(loc.accuracy)&&loc.accuracy>150){
  navStatus('Ubicación imprecisa ±'+Math.round(loc.accuracy)+' m. Revisa GPS.');return;
 }
 if(!nativeApp||typeof window.Rush.routeNav!=='function'){
  navStatus('Cálculo de ruta disponible en Android');return;
 }
 navHideDiagnostics();
 navStatus('Calculando ruta con FOSSGIS…');
 window.Rush.routeNav(loc.lat,loc.lon,nav.destination.lat,nav.destination.lon);
});
$('navClearRoute').addEventListener('click',navClear);

function openMap(){
 // En Android abre MapLibre Native dentro del mismo APK.
 // El mapa 2D de la 4.15 se conserva como respaldo para previsualización HTML.
 if(nativeApp){
  app('maps');
  return;
 }
 navGpsMeta.started=Date.now();
 openOverlay('mapPanel');
 write('mapSubtitle','Busca un destino o elige un punto en el mapa');
 function activateMap(){
  if($('mapPanel').hidden||!window.RushLiveMap)return;
  window.RushLiveMap.activate();
  if(typeof window.rushNavMapOpened==='function')window.rushNavMapOpened();
  if(state.location&&state.location.receivedAt&&Date.now()-state.location.receivedAt<15000){
   window.RushLiveMap.updatePosition(state.location.lat,state.location.lon,state.location.accuracy);
  }
  if(nativeApp&&typeof window.Rush.startMapGps==='function')window.Rush.startMapGps();
  else window.RushLiveMap.setStatus('Ubicación Android no disponible');
 }
 if(mapScriptLoaded){activateMap();return;}
 if(mapScriptLoading)return;
 mapScriptLoading=true;
 var script=document.createElement('script');script.src='live_map.js';
 script.onload=function(){mapScriptLoading=false;mapScriptLoaded=!!window.RushLiveMap;activateMap()};
 script.onerror=function(){
  mapScriptLoading=false;
  write('mapSubtitle','No se pudo cargar el mapa. Comprueba la instalación.');
  write('mapWaiting','No se pudo iniciar el mapa integrado.');
 };
 document.body.appendChild(script);
}
function rushReturnHome(){
 Array.prototype.forEach.call(document.querySelectorAll('.scrim'),function(p){
  if(!p.hidden)closeOverlay(p.id);
 });
}
window.rushHandleBack=function(){
 var visible=Array.prototype.filter.call(document.querySelectorAll('.scrim'),function(p){return !p.hidden;});
 if(!visible.length)return false;
 closeOverlay(visible[visible.length-1].id);
 return true;
};
window.rushExternalNav=function(action){
 rushReturnHome();
 if(action==='apps')openOverlay('appsPanel');
};
Array.prototype.forEach.call(document.querySelectorAll('[data-close]'),function(b){b.addEventListener('click',function(){closeOverlay(b.getAttribute('data-close'))})});
Array.prototype.forEach.call(document.querySelectorAll('.scrim'),function(p){p.addEventListener('click',function(ev){if(ev.target===p)closeOverlay(p.id)})});
Array.prototype.forEach.call(document.querySelectorAll('[data-action]'),function(b){b.addEventListener('click',function(){var action=b.getAttribute('data-action');
 if(action==='home'){rushReturnHome();return}
 if(action==='apps'){openOverlay('appsPanel');return}
 if(action==='route'){openOverlay('routePanel');return}
 if(action==='settings'){openOverlay('settingsPanel');return}
 if(action==='obd'){openOverlay('obdPanel');loadDevices();return}
 if(action==='obdDashboard'){openOverlay('obdDashboardPanel');renderObdAdvanced();return}
 if(action==='map'){openMap();return}
 if(action==='radio'){openOverlay('radioPanel');return}
 if(action==='autokit'&&!$('mapPanel').hidden)closeOverlay('mapPanel');
 app(action);
})});
function switchTheme(mode){
 saved.theme=mode;store('rush_theme',mode);applyTheme();
}
Array.prototype.forEach.call(document.querySelectorAll('[data-theme-choice]'),function(btn){btn.addEventListener('click',function(){switchTheme(btn.getAttribute('data-theme-choice'))})});
$('modeButton').addEventListener('click',function(){
 switchTheme(document.documentElement.getAttribute('data-theme')==='dark'?'light':'dark')
});
$('saveName').addEventListener('click',function(){
 saved.name=$('nameInput').value.trim().slice(0,32);store('rush_user_name',saved.name);updateTime();notice('Nombre actualizado')
});
$('nameInput').value=saved.name;
$('chooseDefaultHome').addEventListener('click',function(){
 if(!nativeApp){notice('Esta opción solo funciona en el Android del TBox');return}
 app('enableHome');
});
window.rushHomeState=function(info){
 var txt=info&&info.selected?'Rush Launcher es el inicio predeterminado de Android':
         info&&info.available?'Rush Launcher disponible para elegir como inicio':
         'Pulsa Elegir Rush Launcher como inicio para configurar Android';
 write('homeStatus',txt);
};
$('rushVehicle').addEventListener('error',function(){
 if(this.getAttribute('src')!=='rush_hero.jpg'){
  this.src='rush_hero.jpg';
  this.classList.add('fallback-gray-car');
 }
});
$('returnOriginal').addEventListener('click',function(){app('exit')});
$('requestGps').addEventListener('click',function(){if(nativeApp)window.Rush.askGps();else notice('GPS requiere Android')});
$('openBluetooth').addEventListener('click',function(){app('bluetoothSettings')});
$('weatherConsent').addEventListener('click',function(){
 if(saved.weather){
  saved.weather=false;store('rush_weather','no');state.weather=null;updateWeather();
  if(nativeApp&&typeof window.Rush.stopWeatherGps==='function')window.Rush.stopWeatherGps();
  // El seguimiento adicional puede seguir activo si el usuario abrió Rush Navigator.
  notice('Clima desactivado');return;
 }
 saved.weather=true;store('rush_weather','yes');updateWeather();
 if(nativeApp&&typeof window.Rush.startWeatherGps==='function')window.Rush.startWeatherGps();
 refreshWeather(true);
});
$('weatherRefresh').addEventListener('click',function(){
 if(!saved.weather){saved.weather=true;store('rush_weather','yes');if(nativeApp&&typeof window.Rush.startWeatherGps==='function')window.Rush.startWeatherGps();}
 refreshWeather(true);
});
function renderMetrics(){
 var speed=typeof state.obdSpeed==='number'&&state.obdConnected?state.obdSpeed:state.gps;
 var speedText=typeof speed==='number'&&isFinite(speed)?String(Math.round(Math.max(0,speed))):'—';
 write('speedBadge',speedText+' km/h');
 write('gpsSmall',typeof speed==='number'?'Velocidad '+speedText+' km/h':'GPS sin velocidad');
 write('rpmSmall',typeof state.rpm==='number'&&state.obdConnected?state.rpm+' RPM':'OBD sin conectar');
 write('obdInfo',state.obdConnected?'OBD conectado':'OBD sin conectar');
 write('headerObd',state.obdConnected?'OBD conectado':'OBD sin conectar');
 write('obdResult',state.obdConnected?'Conectado':'Sin conexión');
 write('coolantResult',typeof state.coolant==='number'&&state.obdConnected?state.coolant+' °C':'—');
 write('rpmResult',typeof state.rpm==='number'&&state.obdConnected?state.rpm+' rpm':'—');
 write('gpsDetail',state.gpsStatus);
 renderGauges();
}
// Relojes SVG: únicamente actualizamos el trazo cuando llega un dato real.
// Sin renderizado de mapas, canvases, ni bucles de animación constantes.
function renderGauges(){
 var info=state.obd||{};
 var obdSpeed=state.obdConnected&&typeof state.obdSpeed==='number'&&isFinite(state.obdSpeed);
 var gpsSpeed=typeof state.gps==='number'&&isFinite(state.gps);
 var speed=obdSpeed?state.obdSpeed:(gpsSpeed?state.gps:null);
 var rpm=state.obdConnected&&typeof state.rpm==='number'&&isFinite(state.rpm)?state.rpm:null;
 var gauges=[['speed',speed,200,speed===null?'Sin lectura':obdSpeed?'ECU OBD':'GPS'],
             ['rpm',rpm,8000,rpm===null?'Sin lectura':'ECU OBD']];
 gauges.forEach(function(item){
  var kind=item[0],value=item[1],max=item[2],source=item[3];
  Array.prototype.forEach.call(document.querySelectorAll('[data-gauge-kind="'+kind+'"]'),function(box){
   var number=box.querySelector('.dial-number');
   var progress=box.querySelector('.dial-progress');
   var detail=box.querySelector('.dial-source');
   if(number)number.textContent=value===null?'—':kind==='rpm'?(value/1000).toFixed(1):String(Math.round(value));
   if(progress)progress.setAttribute('stroke-dasharray',
     value===null?'0 383.3':(Math.max(0,Math.min(1,value/max))*287.5).toFixed(1)+' 383.3');
   if(detail)detail.textContent=source;
  });
 });
 write('homeObdStatus',state.obdConnected?'OBD conectado · datos en vivo':gpsSpeed?'GPS · OBD sin conectar':'Conecta tu ELM327');
 write('homeCoolant',state.obdConnected&&typeof state.coolant==='number'?Math.round(state.coolant)+' °C':'— °C');
 write('homeVoltage',state.obdConnected&&typeof info.voltage==='number'&&isFinite(info.voltage)?info.voltage.toFixed(2)+' V':'— V');
 write('driveCoolantTemp',state.obdConnected&&typeof state.coolant==='number'&&isFinite(state.coolant)?
   Math.round(state.coolant)+' °C':'— °C');
 write('driveBattery',state.obdConnected&&typeof info.voltage==='number'&&isFinite(info.voltage)?
   info.voltage.toFixed(1)+' V':'— V');
}
// Estado GPS diagnóstico: diferencia receptor estancado de mapa sin redibujar.
// La edad se obtiene del último paquete REAL de Android, no de la animación.
var navGpsMeta={source:'Sin datos',counter:0,at:0,hasBearing:false,started:0};
function updateNavGpsHealth(){
 var box=$('navGpsHealth'),label=$('navGpsHealthText'),restart=$('navGpsRestart');
 if(!box||!label||!restart||$('mapPanel').hidden)return;
 var now=Date.now(),started=navGpsMeta.started||now;
 var age=navGpsMeta.at?Math.max(0,Math.floor((now-navGpsMeta.at)/1000)):
              Math.max(0,Math.floor((now-started)/1000));
 var stale=age>=10;
 box.classList.toggle('is-stale',stale);
 restart.hidden=!stale;
 var acc=state.location&&Number.isFinite(state.location.accuracy)?
            ' · ±'+Math.round(state.location.accuracy)+' m':'';
 if(navGpsMeta.at){
  label.textContent=(stale?'SIN GPS NUEVO':'GPS '+navGpsMeta.source)+
    ' · hace '+age+' s'+acc+(stale?' · última posición':'');
 }else{
  label.textContent=stale?'SIN POSICIÓN NUEVA · '+age+' s':
                 'Esperando lectura GPS · '+age+' s';
 }
}
window.rushGpsFixMeta=function(source,counter,hasBearing){
 navGpsMeta.source=source==='GPS'?'Satélite':'Red/Wi-Fi';
 navGpsMeta.counter=Number(counter)||0;
 navGpsMeta.hasBearing=!!hasBearing;
 navGpsMeta.at=Date.now();
 updateNavGpsHealth();
};
if($('navGpsRestart'))$('navGpsRestart').addEventListener('click',function(){
 navGpsMeta.at=0;navGpsMeta.started=Date.now();
 write('navGpsHealthText','Reactivando receptor Android…');
 updateNavGpsHealth();
 if(nativeApp&&window.Rush&&typeof window.Rush.restartMapGps==='function')
   window.Rush.restartMapGps();
});
setInterval(function(){
 if(!$('mapPanel').hidden)updateNavGpsHealth();
},2000);
window.rushGpsStatus=function(status){
 state.gpsStatus=String(status||'Sin información');if(/desactivado|sin permiso|no disponible|sin proveedor|requerido/i.test(state.gpsStatus))state.gps=null;
 if(window.RushLiveMap)window.RushLiveMap.setStatus(state.gpsStatus);
 renderMetrics();
};
window.rushGpsData=function(speed,heading){
 state.gps=(typeof speed==='number'&&isFinite(speed))?Math.max(0,speed):null;
 if(state.gps!==null)state.gpsStatus='GPS disponible';
 if(window.RushLiveMap)window.RushLiveMap.updateHeading(heading,state.gps);
 if(window.RushHomeMap)window.RushHomeMap.setHeading(heading,state.gps);
 renderMetrics();
};

/* 4.15.4: home_map.js dibuja mapa ligero con arrastre, zoom, posición GPS,
   rumbo arriba y seguimiento. El panel evita recargar teselas en bucles. */
function updateHomeMap(lat,lon,accuracy){
 if(window.RushHomeMap)window.RushHomeMap.setPosition(lat,lon,accuracy);
}

window.rushPosition=function(lat,lon,accuracy){
 if(!Number.isFinite(lat)||!Number.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
 var old=state.location;state.location={lat:lat,lon:lon,accuracy:accuracy,receivedAt:Date.now()};
 write('navTopStatus',Number.isFinite(accuracy)?'±'+Math.round(accuracy)+' m':'Ubicación');
 if(nav.route)navGuidance();
 if(window.RushLiveMap)window.RushLiveMap.updatePosition(lat,lon,accuracy);
 updateHomeMap(lat,lon,accuracy);
 if(saved.weather&&(!old||Math.abs(old.lat-lat)+Math.abs(old.lon-lon)>.07))refreshWeather(false);
};
window.rushObdStatus=function(status){
 var val=String(status||'Sin conexión');
 state.obdConnected=/conectado|leyendo|datos obd/i.test(val)&&!/desconectado|no conectado/i.test(val);
 if(!state.obdConnected){state.obdSpeed=null;state.rpm=null;state.coolant=null;state.obd=null}
 write('obdStatusText',val);renderMetrics();renderObdAdvanced();
};
// El lector entrega un JSON exclusivamente con respuestas OBD-II reales.
var obdMetricFields=[
 ['speed','0D','km/h',0],['rpm','0C','RPM',0],['coolant','05','°C',0],
 ['voltage','RV','V',2],['engineLoad','04','%',0],['map','0B','kPa',0],
 ['iat','0F','°C',0],['throttle','11','%',0],
 ['stft1','06','%',1],['ltft1','07','%',1],['catalyst','3C','°C',1]
];
function renderObdAdvanced(){
 var info=state.obd||{},known=info.supportedKnown===true;
 var pids=Array.isArray(info.supported)?info.supported:[];
 obdMetricFields.forEach(function(f){
  var value=document.querySelector('[data-obd-metric="'+f[0]+'"]');
  var detail=document.querySelector('[data-obd-state="'+f[0]+'"]');
  if(!value||!detail)return;
  var num=info[f[0]];
  if(!state.obdConnected){
   value.textContent='—';detail.textContent='Desconectado';return;
  }
  if(typeof num==='number'&&isFinite(num)){
   value.textContent=num.toFixed(f[3])+' '+f[2];
   detail.textContent='Lectura ECU / ELM';return;
  }
  value.textContent='—';
  detail.textContent=known&&f[1]!=='RV'&&pids.indexOf(f[1])<0?
    'No compatible':'Sin lectura todavía';
 });
 var available=obdMetricFields.filter(function(f){
  return typeof info[f[0]]==='number'&&isFinite(info[f[0]]);
 }).length;
 write('obdAdvancedState',state.obdConnected?
   'OBD conectado · '+available+' lecturas disponibles'+
    (known?' · Compatibilidad detectada':' · Compatibilidad sin confirmar'):
   'OBD desconectado · Conecta tu ELM327');
 write('obdUpdatedAt',state.obdConnected&&typeof info.updatedAt==='number'?
   'Última respuesta '+new Date(info.updatedAt).toLocaleTimeString('es-CL'):'Sin datos');
}
window.rushObdData=function(data){
 if(!data||typeof data!=='object'||!Object.keys(data).length){
  state.obd=null;state.obdConnected=false;
  state.obdSpeed=null;state.rpm=null;state.coolant=null;
  renderMetrics();renderObdAdvanced();return;
 }
 state.obdConnected=true;
 state.obd=data;
 state.obdSpeed=typeof data.speed==='number'&&isFinite(data.speed)?data.speed:null;
 state.rpm=typeof data.rpm==='number'&&isFinite(data.rpm)?data.rpm:null;
 state.coolant=typeof data.coolant==='number'&&isFinite(data.coolant)?data.coolant:null;
 renderMetrics();renderObdAdvanced();
};
$('obdAdvancedConnect').addEventListener('click',function(){
 closeOverlay('obdDashboardPanel');
 openOverlay('obdPanel');loadDevices();
});
function loadDevices(){
 if(!nativeApp){write('deviceList','Disponible en Android');return}
 write('deviceList','Buscando dispositivos vinculados…');
 window.Rush.requestObdDevices();
}
window.rushObdDevices=function(devices){
 var box=$('deviceList');box.textContent='';
 if(!Array.isArray(devices)||!devices.length){box.textContent='No se encontraron adaptadores vinculados. Vincula un ELM327 Bluetooth Classic desde Ajustes.';return}
 devices.forEach(function(d){
  if(!d||!d.address)return;
  var btn=document.createElement('button');btn.className='item-row';btn.type='button';
  btn.textContent=(d.name||'ELM327')+' · '+d.address;
  btn.addEventListener('click',function(){window.Rush.connectObd(String(d.address));notice('Conectando al adaptador…')});box.appendChild(btn);
 });
};
$('scanDevices').addEventListener('click',loadDevices);
$('disconnectObd').addEventListener('click',function(){if(nativeApp)window.Rush.disconnectObd()});
var obdAutoEnabled=true;
window.rushObdAutoState=function(enabled,selectedMac){
 obdAutoEnabled=enabled!==false;
 var btn=$('obdAutoToggle');
 if(btn){btn.textContent=obdAutoEnabled?'Desactivar conexión automática':'Activar conexión automática';
  btn.setAttribute('aria-pressed',obdAutoEnabled?'true':'false')}
 write('obdAutoInfo',obdAutoEnabled?
   (selectedMac?'ELM327 recordado · conexión automática al abrir':'Automático activo · selecciona ELM327 si hay varios') :
   'Automático desactivado · puedes conectar manualmente');
};
$('obdAutoToggle').addEventListener('click',function(){
 if(!nativeApp||!window.Rush.setObdAutoConnect)return;
 window.Rush.setObdAutoConnect(!obdAutoEnabled);
});
function weatherWord(code){
 if(code===0)return ['Despejado','☀'];
 if(code<=3)return ['Parcialmente nublado','⛅'];
 if(code===45||code===48)return ['Niebla','☁'];
 if(code>=51&&code<=67)return ['Lluvia','☂'];
 if(code>=71&&code<=77)return ['Nieve','❄'];
 if(code>=80&&code<=82)return ['Chubascos','☂'];
 if(code>=95)return ['Tormenta','⚡'];
 return ['Condiciones variables','☁'];
}
function updateWeather(){
 write('weatherToggle',saved.weather?'Desactivar clima':'Activar clima');
 write('weatherConsent',saved.weather?'Desactivar clima':'Activar clima');
 if(!saved.weather){write('weatherTemp','—°');write('weatherDescription','Clima desactivado');write('weatherRange','Actívalo para consultar tu ubicación');write('weatherIcon','—');return}
 if(state.weather&&state.weather.ok){
   var v=state.weather.current||{},day=state.weather.daily||{};
   write('weatherTemp',typeof v.temperature_2m==='number'?Math.round(v.temperature_2m)+'°':'—°');
   var desc=weatherWord(Number(v.weather_code));write('weatherDescription',desc[0]);write('weatherIcon',v.is_day===0?'☾':desc[1]);
   var high=Array.isArray(day.temperature_2m_max)?day.temperature_2m_max[0]:null;
   var low=Array.isArray(day.temperature_2m_min)?day.temperature_2m_min[0]:null;
   write('weatherRange',typeof high==='number'&&typeof low==='number'?'Máx. '+Math.round(high)+'° · Mín. '+Math.round(low)+'°':'Pronóstico parcial');
 }else{
   write('weatherTemp','—°');write('weatherDescription',state.weatherLoading?'Consultando…':'Sin datos de clima');
   write('weatherRange',state.location?'Revisa tu conexión a internet':'GPS necesario');
   write('weatherIcon','· · ·');
 }
}
function refreshWeather(force){
 if(!saved.weather||!nativeApp)return;
 if(!state.location){updateWeather();return}
 if(state.weatherLoading)return;
 if(!force&&Date.now()-state.weatherAsked<15*60*1000)return;
 state.weatherAsked=Date.now();state.weatherLoading=true;updateWeather();
 window.Rush.getWeather(state.location.lat,state.location.lon);
}
window.rushWeather=function(data){
 state.weatherLoading=false;
 state.weather=(data&&data.ok)?data:null;
 if(!state.weather&&data&&data.message)write('weatherRange',String(data.message).slice(0,70));
 updateWeather();
};
/* Controles multimedia Android. Los metadatos son opcionales:
   algunos Spotify ofrecen broadcasts, pero no existe acceso a sesiones globales. */
function renderMusic(info){
 var s=info&&info.hasData?info:null;
 var api=!!(s&&s.source==='api');
 var cover=$('musicCover')||null;
 var album=document.querySelector('.album');
 if(!cover&&album){
  cover=document.createElement('img');
  cover.id='musicCover';
  cover.className='rush-cover';
  cover.alt='Carátula del álbum de Spotify';
  album.appendChild(cover);
 }
 // La carátula llega tanto por la API autenticada como por oEmbed público
 // asociado al id de la pista en los broadcasts Android de Spotify.
 var url=s&&typeof s.cover==='string'?s.cover:'';
 var allowed=/^https:\/\/(?:(?:i|mosaic)\.scdn\.co|image-cdn-[a-z0-9-]+\.spotifycdn\.com)\/[a-zA-Z0-9/_-]+$/.test(url);
 if(cover&&album){
  if(allowed){
   cover.onerror=function(){
    if(this.getAttribute('src')!==url)return;
    this.hidden=true;
    album.classList.remove('spotify-cover');
   };
   cover.onload=function(){
    if(this.getAttribute('src')!==url)return;
    this.hidden=false;
    album.classList.add('spotify-cover');
   };
   if(cover.getAttribute('src')!==url){
    cover.hidden=true;
    album.classList.remove('spotify-cover');
    cover.src=url;
   }
  }else{
   cover.hidden=true;
   cover.removeAttribute('src');
   album.classList.remove('spotify-cover');
  }
 }
 state.currentPlayback=s;
 if(s&&s.package&&validAppIcon(s.icon))state.appIcons[s.package]=s.icon;
 setMusicIcon(s);
 write('songTitle',s?(s.track||'Reproducción activa'):'Sin datos de música');
 write('songArtist',s?(s.artist||'Artista no disponible'):'Inicia Spotify en el TBox');
 write('songState',s?(s.playing===true?'Reproduciendo':s.playing===false?'En pausa':'Estado no disponible'):(state.spotifyConnected?'Sin reproducción activa en Spotify':'Abre Spotify en el TBox para escuchar música'));
 write('musicToggle',s&&s.playing?'Ⅱ':'▶');
 write('mediaBrand',s?(s.app||'Spotify'):'Música');
 $('musicToggle').setAttribute('aria-label',s&&s.playing?'Pausar música':'Reproducir música');
 if(s&&!s._receivedAt)s._receivedAt=Date.now();
 updateMusicProgress();
}
function spotifyTime(ms){
 var seconds=Math.max(0,Math.floor((Number(ms)||0)/1000));
 return Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0');
}
function updateMusicProgress(){
 var s=state.currentPlayback,progress=$('musicProgress');
 var duration=s?Math.max(0,Number(s.duration)||0):0;
 show($('mediaProgressRow'),duration>0);
 if(!duration)return;
 var position=Math.max(0,Number(s.position)||0);
 var receivedAt=Number(s.updatedAt||s._receivedAt)||Date.now();
 if(s.playing===true){
  var elapsed=Date.now()-receivedAt;
  if(elapsed>=0&&elapsed<120000)position+=elapsed;
 }
 position=Math.min(duration,position);
 progress.max=String(duration);
 progress.disabled=s.source!=='api';
 if(document.activeElement!==progress)progress.value=String(position);
 write('musicTime',spotifyTime(position)+' / '+spotifyTime(duration)+(s.source==='broadcast'?' aprox.':''));
}

window.rushSpotifyState=function(info){
 state.spotify=info;
 if(!state.spotifyConnected)renderMusic(info);
};
window.rushSpotifyConnectState=function(result){
 state.spotifyConnected=!!(result&&result.connected);
 state.spotifyConfigured=!!(result&&result.configured);
 write('spotifyConnectStatus',result&&result.message?result.message:'Estado de Spotify desconocido');
 if(!state.spotifyConnected){
  renderMusic(state.spotify);
  write('mediaBrand','Música');
 }else{
  write('mediaBrand','Spotify');
  if(!state.currentPlayback||state.currentPlayback.source!=='api')
   write('songState','Spotify conectado · consultando reproducción…');
 }
};
window.rushSpotifyApiPlayback=function(data){
 if(!state.spotifyConnected)return;
 if(!data||!data.hasData){
  renderMusic(null);
  write('mediaBrand','Spotify');
  write('songTitle','Sin canción activa');
  write('songArtist',data&&data.message?data.message:'Reproduce una canción en Spotify');
  write('songState','Conectado · sin reproducción');
 }else{
  renderMusic(data);
  if(data.device)write('songState',(data.playing?'Reproduciendo':'En pausa')+' en '+data.device);
 }
};
Array.prototype.forEach.call(document.querySelectorAll('[data-media]'),function(b){
 b.addEventListener('click',function(){
  if(!nativeApp){notice('Requiere Android');return}
  var cmd=b.getAttribute('data-media');
  if(state.spotifyConnected&&typeof window.Rush.spotifyControl==='function')window.Rush.spotifyControl(cmd);
  else window.Rush.media(cmd);
 });
});
$('musicProgress').addEventListener('change',function(){
 if(nativeApp&&state.spotifyConnected&&typeof window.Rush.spotifySeek==='function')
  window.Rush.spotifySeek(Math.round(Number(this.value)||0));
});
$('spotifySaveClientId').addEventListener('click',function(){
 var id=$('spotifyClientId').value.trim();
 if(!/^[A-Za-z0-9]{32}$/.test(id)){notice('Client ID debe tener 32 caracteres');return}
 if(nativeApp)window.Rush.spotifyClientId(id);
 else notice('Configura el Client ID en el TBox');
 $('spotifyClientId').value='';
});
$('spotifyLogin').addEventListener('click',function(){
 if(nativeApp)window.Rush.spotifyConnect();
 else notice('Spotify requiere aplicación Android');
});
$('spotifyDisconnect').addEventListener('click',function(){
 if(nativeApp)window.Rush.spotifyDisconnect();
});
$('spotifyRefresh').addEventListener('click',function(){
 if(nativeApp)window.Rush.spotifyRefresh();
});
$('spotifyDevelopers').addEventListener('click',function(){
 if(nativeApp)window.Rush.spotifyDevelopers();
});
$('musicHelp').addEventListener('click',function(){openOverlay('musicPanel')});
$('spotifyEmbedOpen').addEventListener('click',function(){
 var host=$('spotifyEmbedContainer');
 if(!host.querySelector('iframe')){
  var frame=document.createElement('iframe');
  frame.title='Spotify · Tu playlist';
  frame.src='https://open.spotify.com/embed/playlist/6jaGMO7U1463pu2nSX2V6v?theme=0';
  frame.style.width='100%';
  frame.style.height='352px';
  frame.style.minHeight='352px';
  frame.style.border='0';
  frame.style.borderRadius='12px';
  frame.setAttribute('allow','autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture');
  host.appendChild(frame);
 }
 openOverlay('spotifyEmbedPanel');
});
$('spotifyEmbedClose').addEventListener('click',function(){
 $('spotifyEmbedContainer').textContent='';
 closeOverlay('spotifyEmbedPanel');
});
$('mapRetryGps').addEventListener('click',function(){
 navGpsMeta.started=Date.now();
 if(nativeApp&&typeof window.Rush.restartMapGps==='function')
   window.Rush.restartMapGps();
 else if(nativeApp&&typeof window.Rush.startMapGps==='function')
   window.Rush.startMapGps();
 if(window.RushLiveMap)window.RushLiveMap.recenter();
 updateNavGpsHealth();
});
$('startRoute').addEventListener('click',function(){
 closeOverlay('routePanel');
 openMap();
});
$('justMap').addEventListener('click',function(){
 closeOverlay('routePanel');
 openMap();
});

/** URL local generado desde PackageManager de Android; nunca logos descargados. */
function validAppIcon(uri){
 return typeof uri==='string'&&uri.startsWith('data:image/png;base64,')&&
        uri.length<130000&&/^[A-Za-z0-9+/=]+$/.test(uri.slice(22));
}
function setRealLogo(slot,uri){
 if(!slot)return;
 var img=slot.querySelector('img.rush-app-icon');
 if(!validAppIcon(uri)){
  if(img)img.remove();
  slot.classList.remove('real-icon');
  return;
 }
 if(!img){
  img=document.createElement('img');
  img.className='rush-app-icon';
  img.alt='';
  img.draggable=false;
  img.setAttribute('aria-hidden','true');
  slot.appendChild(img);
 }
 img.src=uri;
 img.onerror=function(){this.remove();slot.classList.remove('real-icon')};
 slot.classList.add('real-icon');
}
function setMusicIcon(media){
 var p=media&&media.package?media.package:'com.spotify.music';
 setRealLogo(document.querySelector('.album'),state.appIcons[p]||'');
}
window.rushDockApps=function(apps){
 var available=Array.isArray(apps)?apps:[];
 var defaults={spotify:'Spotify',autokit:'CarPlay'};
 state.dockApps={};
 Object.keys(defaults).forEach(function(action){
  var button=document.querySelector('.dockbtn[data-action="'+action+'"]');
  if(!button)return;
  setRealLogo(button.querySelector('.dockicon'),'');
  var caption=button.querySelector('.dock-label');
  if(caption)caption.textContent=defaults[action];
 });
 available.forEach(function(a){
  if(!a||!a.action||!a.package||!validAppIcon(a.icon))return;
  if(!Object.prototype.hasOwnProperty.call(defaults,a.action))return;
  var button=document.querySelector('.dockbtn[data-action="'+a.action+'"]');
  if(!button)return;
  state.appIcons[a.package]=a.icon;
  state.dockApps[a.action]=a.package;
  setRealLogo(button.querySelector('.dockicon'),a.icon);
  var caption=button.querySelector('.dock-label');
  if(caption)caption.textContent=a.action==='autokit'?'CarPlay':String(a.name||defaults[a.action]).slice(0,28);
 });
 setMusicIcon(state.currentPlayback);
};

function iconFor(name){
 var n=String(name||'').trim().split(/\s+/);
 return n.length>1?(n[0][0]+n[1][0]).toUpperCase():String(name||'?').slice(0,2).toUpperCase();
}
function makeAppButton(item){
 var button=document.createElement('button');
 button.type='button';
 button.className='app-choice';
 button.setAttribute('aria-label','Abrir '+item.name);
 var icon=document.createElement('span');
 icon.className='app-mini';
 icon.textContent=iconFor(item.name);
 if(validAppIcon(item.icon)){icon.textContent='';setRealLogo(icon,item.icon)}
 var title=document.createElement('span');
 title.textContent=item.name;
 button.appendChild(icon);
 button.appendChild(title);
 button.addEventListener('click',function(){
  closeOverlay('appsPanel');
  if(nativeApp)window.Rush.launchApp(item.package);
 });
 return button;
}
function paintApps(){
 var root=$('appsList');
 root.textContent='';
 var filter=String($('appsSearch').value||'').normalize('NFD')
  .replace(/[\u0300-\u036f]/g,'').toLocaleLowerCase().trim();
 var items=state.apps.filter(function(app){
  var name=String(app.name||'').normalize('NFD')
   .replace(/[\u0300-\u036f]/g,'').toLocaleLowerCase();
  return name.includes(filter);
 });
 write('appsCount',items.length+' aplicaciones');
 if(!items.length){
  var empty=document.createElement('p');
  empty.className='muted';
  empty.textContent='No se encontraron aplicaciones instaladas con ese nombre.';
  root.appendChild(empty);
  return;
 }
 // Streaming/vídeo arriba, audio después; todas las demás al final.
 var catalog=window.RushAppCategories;
 var groups=catalog.categorize(items);
 catalog.sections.forEach(function(section){
  var apps=groups[section.id];
  if(!apps||!apps.length)return;
  var container=document.createElement('section');
  container.className='apps-section';
  container.setAttribute('aria-label',section.title);
  var heading=document.createElement('h3');
  heading.className='apps-section-title';
  var name=document.createElement('span');
  name.textContent=section.title;
  var count=document.createElement('small');
  count.className='apps-section-count';
  count.textContent=apps.length+' apps';
  heading.appendChild(name);
  heading.appendChild(count);
  var grid=document.createElement('div');
  grid.className='appgrid';
  apps.forEach(function(item){grid.appendChild(makeAppButton(item))});
  container.appendChild(heading);
  container.appendChild(grid);
  root.appendChild(container);
 });
}
window.rushInstalledApps=function(items){
 state.apps=Array.isArray(items)?items.filter(function(x){return x&&typeof x.name==='string'&&typeof x.package==='string'}):[];
 state.apps.sort(function(a,b){return a.name.localeCompare(b.name,'es')});
 state.apps.forEach(function(a){if(validAppIcon(a.icon))state.appIcons[a.package]=a.icon});
 setMusicIcon(state.currentPlayback);
 paintApps();
};
$('appsSearch').addEventListener('input',paintApps);
document.addEventListener('keydown',function(e){
 if(e.key==='Escape')Array.prototype.forEach.call(document.querySelectorAll('.scrim'),function(p){if(!p.hidden)closeOverlay(p.id)});
});
document.addEventListener('visibilitychange',function(){
 if(document.hidden&&!$('mapPanel').hidden)closeOverlay('mapPanel');
});
window.addEventListener('focus',function(){
 applyTheme();
 if(nativeApp){window.Rush.ready();}
});
document.addEventListener('visibilitychange',function(){
 if(!document.hidden)applyTheme();
});
// La pantalla de conducción se activa por defecto y se puede desactivar
// sin detener GPS, clima, Spotify ni conexión ELM327.
setDrivingMode(saved.drive,false);
updateTime();updateWeather();renderMetrics();renderObdAdvanced();
setInterval(updateTime,30000);
setInterval(updateMusicProgress,1000);
// Si el vehículo permanece estacionado, también renovar clima cada 15 min.
// No se lanzan solicitudes cuando el clima está apagado o no hay GPS.
setInterval(function(){
 if(saved.weather && state.location)refreshWeather(false);
},60000);
renderMusic(null);
if(nativeApp){
 window.Rush.ready();
 // Solicitud de GPS al iniciar el launcher para mapa de Inicio continuo.
 if(typeof window.Rush.askGps==='function')window.Rush.askGps();
 // Clima autorizado: reanudar ubicación al abrir el launcher, sin pedirla a usuarios que no optaron.
 if(saved.weather&&typeof window.Rush.startWeatherGps==='function')window.Rush.startWeatherGps();
}
})();