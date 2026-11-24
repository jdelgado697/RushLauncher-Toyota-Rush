/* Rush Diagnostic 1.0 — complemento interno del mismo Rush Launcher.
 * Sólo lecturas OBD-II del ELM327 ya conectado. No Service 04 ni ECU write.
 * Historial en el WebView Android: sin envíos a servidores externos.
 */
(function(){
'use strict';
var $=function(id){return document.getElementById(id)};
var panel=$('obdDiagnosticPanel'),scanBtn=$('rushDiagScan'),
    exportBtn=$('rushDiagExport'),connectBtn=$('rushDiagConnect'),
    historyBox=$('rushDiagHistory'),clearBtn=$('rushDiagClearHistory');
if(!panel||!scanBtn)return;
var STORAGE='rush_diagnostic_history_42012',current=null,busy=false,history=[];
var native=!!(window.Rush&&typeof window.Rush.scanRushObdDiagnostics==='function');
try{
 var json=localStorage.getItem(STORAGE);
 var items=json?JSON.parse(json):[];
 if(Array.isArray(items))history=items.slice(0,10);
}catch(e){history=[]}

var COMMON={
 'P0010':'Circuito del actuador de posición del árbol de levas',
 'P0011':'Sincronización adelantada del árbol de levas',
 'P0030':'Calefactor del sensor de oxígeno: circuito',
 'P0100':'Circuito del sensor de flujo de aire',
 'P0101':'Lectura fuera de rango del sensor MAF',
 'P0110':'Circuito de temperatura del aire de admisión',
 'P0113':'Temperatura de admisión: señal alta',
 'P0120':'Circuito del sensor de posición del acelerador',
 'P0130':'Circuito del sensor de oxígeno',
 'P0133':'Respuesta lenta del sensor de oxígeno',
 'P0135':'Calentador del sensor de oxígeno',
 'P0141':'Calentador del sensor de oxígeno posterior',
 'P0171':'Mezcla demasiado pobre, banco 1',
 'P0172':'Mezcla demasiado rica, banco 1',
 'P0300':'Fallas de encendido aleatorias o múltiples',
 'P0301':'Falla de encendido, cilindro 1',
 'P0302':'Falla de encendido, cilindro 2',
 'P0303':'Falla de encendido, cilindro 3',
 'P0304':'Falla de encendido, cilindro 4',
 'P0325':'Circuito del sensor de detonación',
 'P0335':'Circuito del sensor de posición del cigüeñal',
 'P0340':'Circuito del sensor de posición del árbol de levas',
 'P0420':'Eficiencia del catalizador por debajo del umbral',
 'P0440':'Sistema de control de emisiones evaporativas',
 'P0442':'Pequeña fuga en sistema de evaporación EVAP',
 'P0455':'Fuga importante en sistema EVAP',
 'P0500':'Sensor de velocidad del vehículo',
 'P0505':'Sistema de control de ralentí',
 'P0606':'Procesador interno del módulo de control',
 'P0700':'La transmisión solicita encender el testigo MIL'
};
function description(code){
 return Object.prototype.hasOwnProperty.call(COMMON,code)?COMMON[code]:
  'Código registrado por la ECU; consulta información técnica del fabricante';
}
function fmtTime(ms){
 var num=Number(ms);
 return Number.isFinite(num)&&num>0?
  new Date(num).toLocaleString('es-CL',{dateStyle:'short',timeStyle:'short'}):
  'Sin fecha';
}
function showStatus(kind,text){
 var el=$('rushDiagStatus');
 if(!el)return;
 el.dataset.kind=kind;
 el.textContent=String(text||'').slice(0,220);
}
function setBusy(on){
 busy=!!on;
 scanBtn.disabled=busy;
 scanBtn.textContent=busy?'Leyendo ECU…':'Escanear ECU';
}
function showCodes(elementId,section){
 var box=$(elementId);if(!box)return;
 box.textContent='';
 if(!section||section.supported!==true){
  box.textContent='Sin respuesta / no compatible. No implica ausencia de fallas.';
  return;
 }
 var codes=Array.isArray(section.codes)?section.codes:[];
 if(!codes.length){box.textContent='ECU no informa códigos en esta categoría.';return;}
 codes.forEach(function(code){
  if(typeof code!=='string'||!/^[PCBU][0-3][0-9A-F]{3}$/.test(code))return;
  var row=document.createElement('div');row.className='rush-diag-code';
  var strong=document.createElement('b');strong.textContent=code;
  var meaning=document.createElement('span');meaning.textContent=description(code);
  row.appendChild(strong);row.appendChild(meaning);box.appendChild(row);
 });
}
function safeReport(report){
 // VIN identificador privado: no guardar el número íntegro en historial.
 var obj={kind:'result',timestamp:Number(report.timestamp)||Date.now(),
  readiness:report.readiness||null,
  confirmed:report.confirmed||null,pending:report.pending||null,
  permanent:report.permanent||null,freezeFrame:report.freezeFrame||null,
  scope:report.scope||'OBD-II genérico',
  vinEnd:typeof report.vin==='string'?report.vin.slice(-6):null,
  notes:Array.isArray(report.notes)?report.notes.slice(0,8):[]};
 return obj;
}
function paint(report){
 if(!report)return;
 var readiness=report.readiness||null;
 var valid=readiness&&readiness.supported===true;
 var mil=valid?(readiness.milOn?'ENCENDIDO':'Apagado'):'Sin lectura';
 var count=report.confirmed&&report.confirmed.supported===true?
   String(report.confirmed.count===undefined?
     (report.confirmed.codes||[]).length:report.confirmed.count):'Sin lectura';
 $('rushDiagMil').textContent=mil;
 $('rushDiagConfirmedCount').textContent=count;
 $('rushDiagMonitors').textContent=valid?
   readiness.incompleteMonitors+' incompletos':'No disponibles';
 $('rushDiagLastTime').textContent=fmtTime(report.timestamp);
 $('rushDiagVin').textContent=report.vinEnd?'••••••'+report.vinEnd:'No disponible';
 $('rushDiagReadiness').textContent=valid?
   (readiness.monitorsComplete?'Preparación completa':'Monitores sin completar'):
   'No disponible';
 showCodes('rushDiagConfirmed',report.confirmed);
 showCodes('rushDiagPending',report.pending);
 showCodes('rushDiagPermanent',report.permanent);
 var freeze=$('rushDiagFreeze'),data=report.freezeFrame||{};
 var fields=[
   ['triggerCode','Código inicial',''],
   ['rpm','RPM al fallar','rpm'],
   ['speedKmh','Velocidad al fallar','km/h'],
   ['coolantC','Refrigerante','°C'],
   ['engineLoadPct','Carga motor','%']
 ];
 var parts=[];
 fields.forEach(function(f){
  if(data[f[0]]!==undefined&&data[f[0]]!==null)
   parts.push(f[1]+': '+data[f[0]]+' '+f[2]);
 });
 freeze.textContent=parts.length?parts.join('\n'):
   'La ECU no devolvió datos congelados de avería.';
}
function paintHistory(){
 if(!historyBox)return;
 historyBox.textContent='';
 if(!history.length){
  historyBox.textContent='Todavía no hay lecturas guardadas en este Carlinkit.';
  return;
 }
 history.forEach(function(report,i){
  var row=document.createElement('div');row.className='rush-diag-history-row';
  row.style.cssText='display:flex;align-items:center;gap:8px;justify-content:space-between;margin:4px 0;';
  var title=document.createElement('span');
  var counts=['confirmed','pending','permanent'].map(function(k){
   var s=report[k];return s&&s.supported===true?
      (s.codes||[]).length:'?';
  }).join('/');
  title.textContent=fmtTime(report.timestamp)+' · DTC C/P/P: '+counts;
  var b=document.createElement('button');b.type='button';b.className='actionbtn';
  b.textContent='Ver';b.style.padding='5px 9px';
  b.addEventListener('click',function(){
   current=report;paint(current);showStatus('ready','Historial: '+fmtTime(report.timestamp));
  });
  row.appendChild(title);row.appendChild(b);historyBox.appendChild(row);
 });
}
function persist(report){
 history.unshift(report);
 history=history.slice(0,10);
 try{localStorage.setItem(STORAGE,JSON.stringify(history))}catch(e){}
 paintHistory();
}
function open(){
 var old=$('obdPanel'),dashboard=$('obdDashboardPanel');
 if(old)old.hidden=true;
 if(dashboard)dashboard.hidden=true;
 panel.hidden=false;
 paintHistory();
 if(current)paint(current);
}
['rushDiagOpenFromConnect','rushDiagOpenFromDashboard'].forEach(function(id){
 var button=$(id);if(button)button.addEventListener('click',open);
});
connectBtn.addEventListener('click',function(){
 panel.hidden=true;
 var target=$('obdPanel');if(target)target.hidden=false;
 if(window.Rush&&typeof window.Rush.requestObdDevices==='function')
  window.Rush.requestObdDevices();
});
scanBtn.addEventListener('click',function(){
 if(busy)return;
 if(!native){
  showStatus('error','La lectura OBD solo funciona en la APK del Carlinkit.');
  return;
 }
 setBusy(true);
 showStatus('progress',
  'Comprobando que el vehículo esté detenido; no apagar el contacto durante la lectura.');
 window.Rush.scanRushObdDiagnostics();
});
window.rushDiagnosticEvent=function(event){
 if(!event||typeof event!=='object')return;
 if(event.kind==='progress'){
  showStatus('progress',event.message||'Leyendo ECU…');return;
 }
 if(event.kind==='error'){
  setBusy(false);showStatus('error',event.message||'No se pudo escanear.');return;
 }
 if(event.kind!=='result')return;
 setBusy(false);
 current=safeReport(event);
 persist(current);
 paint(current);
 var confirmed=current.confirmed,
   count=confirmed&&confirmed.supported===true?(confirmed.codes||[]).length:null;
 showStatus('ready',count===null?
  'Escaneo finalizado; la ECU no confirmó lectura de códigos almacenados.':
  'Escaneo de solo lectura finalizado · '+count+
   ' código(s) confirmados. Revisa las categorías y datos disponibles.');
};
function csvEscape(value){
 var raw=String(value===undefined||value===null?'':value);
 return '"'+raw.replace(/"/g,'""').replace(/[\r\n]+/g,' ')+'"';
}
function diagnosticCsv(){
 var rows=[['Fecha','MIL','Estado','Codigo','Descripcion',
   'Monitores incompletos','Refrigerante fallo C','RPM fallo',
   'Velocidad fallo km/h','VIN últimos 6']];
 history.slice().reverse().forEach(function(item){
  var date=fmtTime(item.timestamp),r=item.readiness||{},f=item.freezeFrame||{};
  var mil=r.supported===true?(r.milOn?'Encendido':'Apagado'):'No disponible';
  var categories=[
   ['Confirmados',item.confirmed],['Pendientes',item.pending],
   ['Permanentes',item.permanent]
  ];
  categories.forEach(function(pair){
   var section=pair[1],codes=section&&section.supported===true?section.codes:null;
   if(codes===null||!Array.isArray(codes)){
    rows.push([date,mil,pair[0],'No disponible','Sin respuesta de ECU',
      r.incompleteMonitors,f.coolantC,f.rpm,f.speedKmh,item.vinEnd]);return;
   }
   if(!codes.length){
    rows.push([date,mil,pair[0],'Sin códigos','ECU respondió sin códigos',
      r.incompleteMonitors,f.coolantC,f.rpm,f.speedKmh,item.vinEnd]);return;
   }
   codes.forEach(function(code){
    rows.push([date,mil,pair[0],code,description(code),
      r.incompleteMonitors,f.coolantC,f.rpm,f.speedKmh,item.vinEnd]);
   });
  });
 });
 return '\uFEFF'+rows.map(function(fields){
  return fields.map(csvEscape).join(';');
 }).join('\r\n')+'\r\n';
}
exportBtn.addEventListener('click',function(){
 if(!history.length){showStatus('error','Primero realiza al menos un escaneo.');return;}
 if(!native||typeof window.Rush.saveRushDiagnosticCsv!=='function'){
  showStatus('error','Exportar CSV está disponible en la APK Android.');return;
 }
 window.Rush.saveRushDiagnosticCsv(diagnosticCsv());
 showStatus('ready','Android abrirá el selector para guardar el CSV localmente.');
});
var confirmClear=false,clearTimer=null;
clearBtn.addEventListener('click',function(){
 if(!confirmClear){
  confirmClear=true;clearBtn.textContent='Confirmar borrar historial';
  if(clearTimer)clearTimeout(clearTimer);
  clearTimer=setTimeout(function(){
   confirmClear=false;clearBtn.textContent='Borrar historial local';
  },6000);
  return;
 }
 confirmClear=false;clearTimeout(clearTimer);
 clearBtn.textContent='Borrar historial local';
 history=[];current=null;
 try{localStorage.removeItem(STORAGE)}catch(e){}
 ['rushDiagMil','rushDiagConfirmedCount','rushDiagMonitors',
 'rushDiagLastTime','rushDiagVin','rushDiagReadiness'].forEach(function(id){
   if($(id))$(id).textContent='—';
 });
 ['rushDiagConfirmed','rushDiagPending','rushDiagPermanent',
  'rushDiagFreeze'].forEach(function(id){
  if($(id))$(id).textContent='Sin escanear';
 });
 paintHistory();
 showStatus('ready','Historial local borrado. No se borraron códigos de la ECU.');
});
paintHistory();
})();
