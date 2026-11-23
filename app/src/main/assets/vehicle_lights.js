/* Rush Launcher 4.20.9 - iluminación visual Toyota Rush con OBD/CAN calibrado.
   No existe un PID OBD-II estándar de luces, no estimar usando RPM ni
   voltaje. Modo OBD sólo afirma estado real con trama CAN reciente.
   Fallback declarado a modo noche y override manual ON/OFF.
*/
(function(){
'use strict';
var find=function(id){return document.getElementById(id)};
var car=find('rushCarArea'),status=find('vehicleLightStatus'),
    detail=find('vehicleLightDetail'),badge=find('vehicleLightBadge'),
    steps=find('vehicleLightsLearnStep'),learnButton=find('vehicleLightsLearn'),
    profileStatus=find('vehicleLightsProfile'),signalSelect=find('vehicleLightsSignals'),
    saveSignal=find('vehicleLightsSaveSignal'),clearSignal=find('vehicleLightsClearSignal');
if(!car||!status)return;
var modes={obd:true,night:true,on:true,off:true};
var mode='obd';
try{
 var persisted=localStorage.getItem('rush_vehicle_light_mode_4209');
 if(modes[persisted])mode=persisted;
}catch(e){}
var sensor=null,reading=null,lastMessage='OBD: no calibrado',phase=1,candidates=[];
var native=!!(window.Rush&&typeof window.Rush.calibrateObdLights==='function');
var phases=[
 {title:'1/4 · Luces apagadas',hint:'Apaga las luces exteriores. Vehículo detenido. Pulsa Registrar.'},
 {title:'2/4 · Luces encendidas',hint:'Enciende las luces exteriores y espera un instante. Pulsa Registrar.'},
 {title:'3/4 · Luces apagadas de nuevo',hint:'Apaga nuevamente los faros. Pulsa Registrar.'},
 {title:'4/4 · Luces encendidas de nuevo',hint:'Enciende los faros otra vez. Pulsa Registrar.'}
];
function save(name,value){try{localStorage.setItem(name,value)}catch(e){}}
function currentNight(){return document.documentElement.getAttribute('data-theme')==='dark'}
function selectedSource(){
 if(mode==='on')return {on:true,label:'Manual ON',note:'Luces visuales encendidas manualmente'};
 if(mode==='off')return {on:false,label:'Manual OFF',note:'Luces visuales apagadas manualmente'};
 if(mode==='night')return {on:currentNight(),label:'Modo noche',
    note:'Automático por tema de Android/Rush; no confirma faros reales'};
 var ready=sensor&&reading&&reading.valid&&
      Date.now()-reading.received<18000;
 if(ready)return {on:reading.on,label:'OBD CAN '+(reading.on?'ON':'OFF'),
      note:'Estado recibido de señal CAN calibrada'};
 return {on:currentNight(),label:'Noche (respaldo)',
    note:sensor?'Sin lectura CAN válida reciente; se usa tema noche':
       'Sin señal OBD calibrada; se usa tema noche. Calibra con el auto detenido'};
}
function refresh(){
 var selected=selectedSource();
 car.classList.toggle('rush-lights-on',!!selected.on);
 car.setAttribute('data-lights-mode',mode);
 car.setAttribute('data-lights-source',selected.label);
 status.textContent='Luces: '+selected.label;
 if(badge)badge.textContent=selected.on?'✦ Luces ON':'Luces OFF';
 if(detail)detail.textContent=selected.note+(lastMessage?' · '+lastMessage:'');
 Array.prototype.forEach.call(document.querySelectorAll('[data-vehicle-light-mode]'),
  function(button){
   var active=button.getAttribute('data-vehicle-light-mode')===mode;
   button.classList.toggle('is-selected',active);
   button.setAttribute('aria-pressed',String(active));
  });
}
function syncLearnControls(){
 var state=phases[phase-1];
 if(steps)steps.textContent=state?state.title+' — '+state.hint:
   'Calibración terminada: selecciona una señal detectada';
 if(learnButton){
  learnButton.textContent=state?'Registrar fase '+phase+'/4':'Repetir calibración';
  learnButton.disabled=!native;
 }
 if(profileStatus)profileStatus.textContent=sensor?
    'Señal registrada '+sensor.id+' · byte '+sensor.byte+' · bit '+sensor.mask+
    '. Verifica ON y OFF con el vehículo detenido.':
    'Sin señal de faros verificada. OBD-II estándar no incluye este dato.';
 if(signalSelect)signalSelect.hidden=candidates.length===0;
 if(saveSignal)saveSignal.hidden=candidates.length===0;
}
function setMode(value){
 if(!modes[value])return;
 mode=value;save('rush_vehicle_light_mode_4209',value);
 refresh();
}
Array.prototype.forEach.call(document.querySelectorAll('[data-vehicle-light-mode]'),
 function(button){button.addEventListener('click',function(){
  setMode(button.getAttribute('data-vehicle-light-mode'));
 })});
if(learnButton)learnButton.addEventListener('click',function(){
 if(!native){lastMessage='Calibración disponible solo en la APK Android';refresh();return}
 if(phase>4){phase=1;candidates=[];if(signalSelect)signalSelect.innerHTML=''}
 if(!phases[phase-1])return;
 lastMessage='Escuchando CAN sin escribir en el vehículo. Mantén luces '+(phase%2===1?'OFF':'ON')+'…';
 refresh();
 window.Rush.calibrateObdLights(phase);
});
if(saveSignal)saveSignal.addEventListener('click',function(){
 if(!native||!candidates.length)return;
 var idx=signalSelect?Number(signalSelect.value):NaN;
 if(!Number.isInteger(idx)||idx<0||idx>=candidates.length)return;
 var candidate=candidates[idx];
 window.Rush.selectObdLightsSignal(candidate.id,candidate.byte,
   candidate.mask,candidate.onBit);
 lastMessage='Verificando señal candidata en tiempo real…';
 refresh();
});
if(clearSignal)clearSignal.addEventListener('click',function(){
 if(native&&typeof window.Rush.clearObdLightsSignal==='function')
  window.Rush.clearObdLightsSignal();
 sensor=null;reading=null;candidates=[];phase=1;
 if(signalSelect)signalSelect.innerHTML='';
 lastMessage='Calibración borrada';
 syncLearnControls();refresh();
});
window.rushLightCanProfile=function(data){
 sensor=data&&typeof data.id==='string'?data:null;
 reading=null;
 lastMessage=sensor?'Perfil CAN cargado; esperando datos del vehículo':
                     'Sin perfil CAN de faros';
 syncLearnControls();refresh();
};
window.rushLightCanEvent=function(event){
 if(!event||typeof event!=='object')return;
 lastMessage=String(event.message||'').slice(0,180);
 if(event.kind==='reading'){
  reading={
   valid:event.valid===true&&typeof event.on==='boolean',
   on:event.on===true,received:Date.now()
  };
 }else if(event.kind==='offline'||event.kind==='unavailable'){
  reading=null;
 }else if(event.kind==='phase'){
  if(event.step===phase){
   phase+=1;
   if(phase===5){
    candidates=Array.isArray(event.candidates)?event.candidates:[];
    if(signalSelect){
     signalSelect.innerHTML='';
     candidates.forEach(function(p,i){
      var opt=document.createElement('option');
      opt.value=String(i);
      opt.textContent='Señal '+(i+1)+' · '+p.id+
       ' / byte '+p.byte+' / bit '+p.mask;
      signalSelect.appendChild(opt);
     });
    }
   }
  }
  syncLearnControls();
 }
 if(detail)detail.textContent=lastMessage;
 refresh();
};
window.RushVehicleLights={
 refresh:refresh,
 onObdDisconnected:function(){reading=null;lastMessage='ELM327 desconectado';refresh()},
 getMode:function(){return mode}
};
syncLearnControls();refresh();
if(native&&typeof window.Rush.getObdLightsSignal==='function')
 window.Rush.getObdLightsSignal();
// Las señales ELM llegan cada pocos segundos; caducar un fix ausente.
setInterval(refresh,3500);
})();
