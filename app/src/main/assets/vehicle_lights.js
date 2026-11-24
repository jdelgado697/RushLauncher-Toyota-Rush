/* Rush Launcher 4.20.10 — iluminación visual y CAN pasivo rápido.
   Estado real únicamente de señales CAN calibradas. No inferir intermitentes
   por el GPS o por estar doblando. Sin señal, indicar respaldo/diagnóstico.
*/
(function(){
'use strict';
var el=function(id){return document.getElementById(id)};
var car=el('rushCarArea'),status=el('vehicleLightStatus'),detail=el('vehicleLightDetail'),
    badge=el('vehicleLightBadge'),selectType=el('vehicleLightsTarget'),
    steps=el('vehicleLightsLearnStep'),learn=el('vehicleLightsLearn'),
    profileStatus=el('vehicleLightsProfile'),signalSelect=el('vehicleLightsSignals'),
    saveSignal=el('vehicleLightsSaveSignal'),clearSignal=el('vehicleLightsClearSignal'),
    latency=el('vehicleLightsResponse');
if(!car||!status)return;
var MODES={obd:true,night:true,on:true,off:true};
var TYPES={low:'luces bajas',high:'luces altas',left:'direccional izquierda',
           right:'direccional derecha',hazard:'balizas'};
var SIGNALS=['low','high','left','right','hazard'];
var mode='obd',target='low',phase=1,candidates=[],lastMessage='';
var native=!!(window.Rush&&typeof window.Rush.calibrateObdSignal==='function');
var profiles={low:null,high:null,left:null,right:null,hazard:null};
var readings={},lastValidAt={},indicatorHighAt={left:0,right:0,hazard:0};
var startedAt=0,observedDelay=null,preview='',previewUntil=0;
try{
 var saved=localStorage.getItem('rush_vehicle_light_mode_4209');
 if(MODES[saved])mode=saved;
}catch(e){}
function store(key,val){try{localStorage.setItem(key,val)}catch(e){}}
function night(){return document.documentElement.getAttribute('data-theme')==='dark'}
function recent(type,ms){
 return typeof readings[type]==='boolean'&&
        Date.now()-(lastValidAt[type]||0)<ms;
}
function real(type){return recent(type,2200)?readings[type]:null}
function primary(){
 if(mode==='on')return {on:true,source:'Manual ON',note:'Imagen encendida manualmente'};
 if(mode==='off')return {on:false,source:'Manual OFF',note:'Imagen apagada manualmente'};
 if(mode==='night')return {on:night(),source:'Modo noche',
   note:'No es una lectura real de las luces del vehículo'};
 if(real('low')!==null)return {on:real('low'),source:'OBD CAN',
   note:'Luces bajas obtenidas de señal CAN calibrada'};
 return {on:night(),source:'Noche (respaldo)',
   note:profiles.low?'Sin lectura CAN reciente: usando tema noche':
         'Luces bajas CAN sin calibrar: usando tema noche'};
}
function busyPreview(type){return preview===type&&Date.now()<previewUntil}
function indicatorActive(type){
 if(busyPreview(type)||busyPreview('hazard'))return true;
 if(mode!=='obd')return false;
 // En indicadores intermitentes, mantener el estado visual entre pulsos
 // CAN separados hasta 1.5 s y destellar con CSS. Nunca activar con GPS.
 if(recent(type,2200)&&readings[type]===true)
  indicatorHighAt[type]=Date.now();
 return Date.now()-(indicatorHighAt[type]||0)<1550;
}
function render(){
 var chosen=primary(),high=mode!=='off'&&
   (busyPreview('high')||(mode==='obd'&&real('high')===true));
 var h=mode!=='off'&&indicatorActive('hazard');
 var left=mode!=='off'&&(h||indicatorActive('left'));
 var right=mode!=='off'&&(h||indicatorActive('right'));
 var low=chosen.on||high;
 car.classList.toggle('rush-lights-on',!!low);
 car.classList.toggle('rush-lights-high',!!high);
 car.classList.toggle('rush-turn-left-active',!!left);
 car.classList.toggle('rush-turn-right-active',!!right);
 car.classList.toggle('rush-hazard-active',!!(h||(left&&right)));
 car.setAttribute('data-lights-mode',mode);
 car.setAttribute('data-lights-source',chosen.source);
 status.textContent='Luces: '+chosen.source+(high?' · ALTAS':'')+
     (h?' · BALIZAS':left?' · IZQUIERDA':right?' · DERECHA':'');
 if(badge)badge.textContent=h?'⚠ BALIZAS':left&&right?'↔ DIRECCIONALES':
   left?'◀ GIRO':right?'▶ GIRO':high?'✦ ALTAS':low?'✦ BAJAS':'Luces OFF';
 if(detail)detail.textContent=chosen.note+(lastMessage?' · '+lastMessage:'');
 if(latency)latency.textContent=observedDelay===null?
    'CAN rápido: ventana de 260 ms; latencia real depende del ELM327':
    'Tiempo observado última transición CAN: '+observedDelay+' ms';
 document.querySelectorAll('[data-vehicle-light-mode]').forEach(function(btn){
  var active=btn.getAttribute('data-vehicle-light-mode')===mode;
  btn.classList.toggle('is-selected',active);
  btn.setAttribute('aria-pressed',String(active));
 });
 document.querySelectorAll('[data-light-preview]').forEach(function(btn){
  var active=busyPreview(btn.getAttribute('data-light-preview'));
  btn.setAttribute('aria-pressed',String(active));
 });
}
function titleFor(type){return TYPES[type]||type}
function syncCalibration(){
 var name=titleFor(target),off=phase===1||phase===3;
 if(steps)steps.textContent=phase<=4?
   phase+'/4 · '+name+' '+(off?'APAGADA':'ENCENDIDA')+
   ' — con el auto detenido, '+(off?'desactívala':'actívala')+
   ' y pulsa Registrar. Para intermitentes, déjalos parpadear durante toda la captura.':
   'Calibración completada: selecciona y verifica una señal CAN';
 if(learn){learn.textContent=phase<=4?'Registrar fase '+phase+'/4':'Repetir calibración';learn.disabled=!native;}
 if(profileStatus)profileStatus.textContent=profiles[target]?
   'Perfil CAN '+name+': '+profiles[target].id+' · byte '+
      profiles[target].byte+' · máscara '+profiles[target].mask+
      (profiles[target].pulse?' (intermitente)':'') :
   'Sin perfil CAN calibrado para '+name;
 if(signalSelect)signalSelect.hidden=!candidates.length;
 if(saveSignal)saveSignal.hidden=!candidates.length;
}
function changeTarget(t){
 if(!TYPES[t])return;
 target=t;phase=1;candidates=[];observedDelay=null;
 if(signalSelect)signalSelect.innerHTML='';
 if(selectType)selectType.value=t;
 syncCalibration();render();
}
if(selectType)selectType.addEventListener('change',function(){changeTarget(this.value)});
document.querySelectorAll('[data-vehicle-light-mode]').forEach(function(btn){
 btn.addEventListener('click',function(){
  var next=btn.getAttribute('data-vehicle-light-mode');
  if(!MODES[next])return;
  mode=next;store('rush_vehicle_light_mode_4209',next);render();
 });
});
document.querySelectorAll('[data-light-preview]').forEach(function(btn){
 btn.addEventListener('click',function(){
  var t=btn.getAttribute('data-light-preview');
  preview=busyPreview(t)?'':t;
  previewUntil=preview?Date.now()+8500:0;
  lastMessage=preview?'Simulación de '+titleFor(preview)+' por 8 s; no acciona el vehículo':
     'Prueba visual finalizada';
  render();
 });
});
if(learn)learn.addEventListener('click',function(){
 if(!native){lastMessage='Calibración solo disponible dentro del APK Android';render();return;}
 if(phase>4){phase=1;candidates=[];if(signalSelect)signalSelect.innerHTML='';}
 lastMessage='Capturando '+titleFor(target)+' · fase '+phase+'/4';
 render();
 window.Rush.calibrateObdSignal(target,phase);
});
if(saveSignal)saveSignal.addEventListener('click',function(){
 var idx=signalSelect?Number(signalSelect.value):-1;
 if(!native||idx<0||idx>=candidates.length)return;
 var p=candidates[idx];
 window.Rush.selectObdSignal(target,p.id,Number(p.byte),
    Number(p.mask),Number(p.onBit),p.pulse===true);
 lastMessage='Perfil seleccionado; verifica ON/OFF con el vehículo detenido';
 render();
});
if(clearSignal)clearSignal.addEventListener('click',function(){
 if(native&&typeof window.Rush.clearObdSignal==='function')
  window.Rush.clearObdSignal(target);
 profiles[target]=null;readings[target]=null;lastValidAt[target]=0;
 candidates=[];phase=1;lastMessage='Perfil eliminado para '+titleFor(target);
 syncCalibration();render();
});
window.rushLightCanProfiles=function(saved){
 if(!saved||typeof saved!=='object')return;
 SIGNALS.forEach(function(type){
  profiles[type]=saved[type]&&typeof saved[type].id==='string'?saved[type]:null;
 });
 syncCalibration();render();
};
// Mantener compatibilidad con callback de 4.20.9 para luces bajas.
window.rushLightCanProfile=function(saved){
 profiles.low=saved&&typeof saved.id==='string'?saved:null;
 syncCalibration();render();
};
window.rushLightCanEvent=function(event){
 if(!event||typeof event!=='object')return;
 lastMessage=String(event.message||'').slice(0,135);
 var type=event.target||'low',now=Date.now();
 if(event.kind==='readings'){
  SIGNALS.forEach(function(signal){
   var hasValue=event.valid&&event.valid[signal]===true &&
      event.values&&typeof event.values[signal]==='boolean';
   if(!hasValue){readings[signal]=null;return;}
   var next=event.values[signal];
   if(typeof readings[signal]==='boolean'&&readings[signal]!==next&&startedAt){
    observedDelay=Math.max(0,now-startedAt);
    startedAt=0;
   }
   readings[signal]=next;lastValidAt[signal]=now;
   if((signal==='left'||signal==='right'||signal==='hazard')&&next)
    indicatorHighAt[signal]=now;
  });
 }else if(event.kind==='phase'){
  if(type===target&&event.step===phase){
   phase++;
   if(phase>4){
    candidates=Array.isArray(event.candidates)?event.candidates:[];
    if(signalSelect){
     signalSelect.innerHTML='';
     candidates.forEach(function(p,i){
      var opt=document.createElement('option');
      opt.value=String(i);
      opt.textContent=(i+1)+'. '+p.id+' · byte '+p.byte+
        ' · bit '+p.mask+(p.pulse?' (pulso)':'');
      signalSelect.appendChild(opt);
     });
    }
   }
   syncCalibration();
  }
 }else if(event.kind==='unavailable'||event.kind==='offline'){
  SIGNALS.forEach(function(t){readings[t]=null;lastValidAt[t]=0;});
 }
 render();
};
window.RushVehicleLights={
 refresh:render,
 onObdDisconnected:function(){
  SIGNALS.forEach(function(t){readings[t]=null;lastValidAt[t]=0;});
  lastMessage='OBD desconectado; sin lectura de iluminación real';render();
 },
 getMode:function(){return mode}
};
syncCalibration();render();
if(native&&typeof window.Rush.getObdSignals==='function')window.Rush.getObdSignals();
setInterval(render,180);
})();