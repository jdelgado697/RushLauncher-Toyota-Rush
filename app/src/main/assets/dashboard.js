/* Rush Launcher 3.3 · iconos originales de las aplicaciones instaladas; mapa GPS real y música Android. */
(function(){
'use strict';
var $=function(id){return document.getElementById(id)};
var nativeApp=!!(window.Rush&&typeof window.Rush.open==='function');
var saved={};
try{saved={name:localStorage.getItem('rush_user_name')||'',theme:localStorage.getItem('rush_theme')||'auto',weather:localStorage.getItem('rush_weather')==='yes'}}catch(e){saved={name:'',theme:'auto',weather:false}};
var state={gps:null,gpsStatus:'Esperando señal GPS',location:null,obdConnected:false,obdSpeed:null,rpm:null,coolant:null,weather:null,weatherLoading:false,spotify:null,spotifyConnected:false,spotifyConfigured:false,apps:[],appIcons:{},dockApps:{},weatherAsked:0};
function write(id,value){var el=$(id);if(el)el.textContent=value}
function show(el,visible){if(el)el.hidden=!visible}
function app(action){if(nativeApp)window.Rush.open(action);else notice('Disponible al instalar en Android')}
function notice(message){var e=$('toast');e.textContent=message;show(e,true);clearTimeout(notice.timer);notice.timer=setTimeout(function(){show(e,false)},2800)}
function greeting(hour){return hour>=6&&hour<12?'Buenos días':hour>=12&&hour<20?'Buenas tardes':'Buenas noches'}
function updateTime(){
 var d=new Date(),h=d.getHours();
 write('greeting',greeting(h)+(saved.name?', '+saved.name:''));
 write('today',d.toLocaleDateString('es-CL',{weekday:'long',day:'numeric',month:'long'}));
 write('clock',d.toLocaleTimeString('es-CL',{hour:'numeric',minute:'2-digit',hour12:true}));
 applyTheme();
}
function applyTheme(){
 var dark=saved.theme==='dark'||(saved.theme==='auto'&&(new Date().getHours()<7||new Date().getHours()>=19));
 document.documentElement.setAttribute('data-theme',dark?'dark':'light');
 write('modeLabel',saved.theme==='auto'?'Automático':dark?'Oscuro':'Claro');
 write('modeSwitch',dark?'☾':'☀');
 Array.prototype.forEach.call(document.querySelectorAll('[data-theme-choice]'),function(b){
  b.classList.toggle('is-selected',b.getAttribute('data-theme-choice')===saved.theme);
 });
}
function store(key,value){try{localStorage.setItem(key,value)}catch(e){}}
function openOverlay(id){show($(id),true);if(id==='appsPanel'&&nativeApp){write('appsCount','Buscando…');$('appsSearch').value='';window.Rush.listApps()}}
function closeOverlay(id){show($(id),false)}
Array.prototype.forEach.call(document.querySelectorAll('[data-close]'),function(b){b.addEventListener('click',function(){closeOverlay(b.getAttribute('data-close'))})});
Array.prototype.forEach.call(document.querySelectorAll('.scrim'),function(p){p.addEventListener('click',function(ev){if(ev.target===p)show(p,false)})});
Array.prototype.forEach.call(document.querySelectorAll('[data-action]'),function(b){b.addEventListener('click',function(){var action=b.getAttribute('data-action');
 if(action==='apps'){openOverlay('appsPanel');return}
 if(action==='route'){openOverlay('routePanel');return}
 if(action==='settings'){openOverlay('settingsPanel');return}
 if(action==='obd'){openOverlay('obdPanel');loadDevices();return}
 if(action==='map'){app('waze');return}
 if(action==='radio'){openOverlay('radioPanel');return}
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
$('returnOriginal').addEventListener('click',function(){app('exit')});
$('requestGps').addEventListener('click',function(){if(nativeApp)window.Rush.askGps();else notice('GPS requiere Android')});
$('openBluetooth').addEventListener('click',function(){app('bluetoothSettings')});
$('weatherConsent').addEventListener('click',function(){
 if(saved.weather){saved.weather=false;store('rush_weather','no');state.weather=null;updateWeather();notice('Clima desactivado');return}
 saved.weather=true;store('rush_weather','yes');updateWeather();
 if(nativeApp)window.Rush.askGps();
 refreshWeather(true);
});
$('weatherRefresh').addEventListener('click',function(){if(!saved.weather){saved.weather=true;store('rush_weather','yes')}refreshWeather(true)});
function renderMetrics(){
 var speed=typeof state.obdSpeed==='number'&&state.obdConnected?state.obdSpeed:state.gps;
 var speedText=typeof speed==='number'&&isFinite(speed)?String(Math.round(Math.max(0,speed))):'—';
 write('speedBadge',speedText+' km/h');
 write('gpsSmall',typeof speed==='number'?'Velocidad '+speedText+' km/h':'GPS sin velocidad');
 write('rpmSmall',typeof state.rpm==='number'&&state.obdConnected?state.rpm+' RPM':'OBD sin conectar');
 write('obdInfo',state.obdConnected?'OBD conectado':'OBD sin conectar');
 write('obdResult',state.obdConnected?'Conectado':'Sin conexión');
 write('coolantResult',typeof state.coolant==='number'&&state.obdConnected?state.coolant+' °C':'—');
 write('rpmResult',typeof state.rpm==='number'&&state.obdConnected?state.rpm+' rpm':'—');
 write('gpsDetail',state.gpsStatus);
}
window.rushGpsStatus=function(status){
 state.gpsStatus=String(status||'Sin información');if(/desactivado|sin permiso|no disponible|sin proveedor|requerido/i.test(state.gpsStatus))state.gps=null;
 if(window.RushLiveMap)window.RushLiveMap.setStatus(state.gpsStatus);
 renderMetrics();
};
window.rushGpsData=function(speed,heading){
 state.gps=(typeof speed==='number'&&isFinite(speed))?Math.max(0,speed):null;
 if(state.gps!==null)state.gpsStatus='GPS disponible';
 if(window.RushLiveMap)window.RushLiveMap.updateHeading(heading);
 renderMetrics();
};
window.rushPosition=function(lat,lon,accuracy){
 if(!Number.isFinite(lat)||!Number.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
 var old=state.location;state.location={lat:lat,lon:lon,accuracy:accuracy};
 if(window.RushLiveMap)window.RushLiveMap.updatePosition(lat,lon,accuracy);
 if(saved.weather&&(!old||Math.abs(old.lat-lat)+Math.abs(old.lon-lon)>.07))refreshWeather(false);
};
window.rushObdStatus=function(status){
 var val=String(status||'Sin conexión');
 state.obdConnected=/conectado|leyendo|datos obd/i.test(val)&&!/desconectado|no conectado/i.test(val);
 if(!state.obdConnected){state.obdSpeed=null;state.rpm=null;state.coolant=null}
 write('obdStatusText',val);renderMetrics();
};
window.rushObdData=function(speed,rpm,coolant){
 state.obdConnected=true;
 state.obdSpeed=typeof speed==='number'&&speed>=0?speed:null;
 state.rpm=typeof rpm==='number'&&rpm>=0?rpm:null;
 state.coolant=typeof coolant==='number'&&coolant>-500?coolant:null;
 renderMetrics();
};
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
 var url=api&&typeof s.cover==='string'?s.cover:'';
 var allowed=/^https:\/\/(?:i|mosaic)\.scdn\.co\/[a-zA-Z0-9/_-]+$/.test(url);
 if(cover)cover.hidden=!allowed;
 if(album)album.classList.toggle('spotify-cover',allowed);
 if(allowed&&cover)cover.src=url;
 if(!allowed&&cover)cover.removeAttribute('src');
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
$('startRoute').addEventListener('click',function(){
 var value=$('routeDestination').value.trim();
 if(!value){notice('Escribe un destino para iniciar ruta');return}
 if(nativeApp&&typeof window.Rush.navigate==='function')window.Rush.navigate(value);
 else notice('La navegación requiere Android');
});
$('justMap').addEventListener('click',function(){
 closeOverlay('routePanel');
 app('maps');
});
$('routeDestination').addEventListener('keydown',function(ev){
 if(ev.key==='Enter'){$('startRoute').click();ev.preventDefault()}
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
 var defaults={route:'Mapas',spotify:'Spotify',autokit:'AutoKit'};
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
  if(caption)caption.textContent=String(a.name||defaults[a.action]).slice(0,28);
 });
 setMusicIcon(state.currentPlayback);
};

function iconFor(name){
 var n=String(name||'').trim().split(/\s+/);
 return n.length>1?(n[0][0]+n[1][0]).toUpperCase():String(name||'?').slice(0,2).toUpperCase();
}
function paintApps(){
 var root=$('appsList');root.textContent='';
 var filter=$('appsSearch').value.toLocaleLowerCase();
 var items=state.apps.filter(function(a){return a.name.toLocaleLowerCase().includes(filter)});
 write('appsCount',items.length+' aplicaciones');
 if(!items.length){root.textContent='No se encontraron aplicaciones visibles en Android.';return}
 items.forEach(function(item){
  var b=document.createElement('button');b.type='button';b.className='app-choice';
  var icon=document.createElement('span');icon.className='app-mini';icon.textContent=iconFor(item.name);
  if(validAppIcon(item.icon)){icon.textContent='';setRealLogo(icon,item.icon)}
  var title=document.createElement('span');title.textContent=item.name;
  b.appendChild(icon);b.appendChild(title);
  b.addEventListener('click',function(){closeOverlay('appsPanel');if(nativeApp)window.Rush.launchApp(item.package)});
  root.appendChild(b);
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
document.addEventListener('keydown',function(e){if(e.key==='Escape')Array.prototype.forEach.call(document.querySelectorAll('.scrim'),function(p){show(p,false)})});
window.addEventListener('focus',function(){if(nativeApp)window.Rush.ready()});
updateTime();updateWeather();renderMetrics();
setInterval(updateTime,30000);
setInterval(updateMusicProgress,1000);
renderMusic(null);
if(nativeApp){window.Rush.ready();window.Rush.askGps();}
})();