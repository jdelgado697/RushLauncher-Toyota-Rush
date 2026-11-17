/* Rush Navigator 4.9: resultados de Chile y prioridad GPS local.
 * Interfaz responde al instante, consulta tras 400 ms y un envío por ~1.2 s. */
(function(){
 'use strict';
 var input=document.getElementById('navQuery');
 var results=document.getElementById('navResults');
 var mapPanel=document.getElementById('mapPanel');
 if(!input||!results||!mapPanel)return;
 var timer=null,seq=0,lastRequestAt=0,cache=Object.create(null);

 function norm(v){return String(v||'').toLowerCase().trim().replace(/\s+/g,' ')}
 function recent(){
  try{
   var list=JSON.parse(localStorage.getItem('rush_nav_recent')||'[]');
   return Array.isArray(list)?list.filter(function(x){
    return x&&Number.isFinite(Number(x.lat))&&Number.isFinite(Number(x.lon))&&
       typeof x.name==='string';
   }).slice(0,8):[];
  }catch(e){return []}
 }
 function remember(hit){
  var list=recent().filter(function(x){
   return !(x.name===hit.name||
    (Math.abs(x.lat-hit.lat)<.00001 && Math.abs(x.lon-hit.lon)<.00001));
  });
  list.unshift({name:String(hit.name).slice(0,110),
   detail:String(hit.detail||'').slice(0,110),
   lat:Number(hit.lat),lon:Number(hit.lon)});
  try{localStorage.setItem('rush_nav_recent',JSON.stringify(list.slice(0,8)))}catch(e){}
 }
 function cancel(){
  if(timer!==null){clearTimeout(timer);timer=null}
  ++seq;
 }
 function dismiss(){
  cancel();
  results.hidden=true;
 }
 function display(items,heading){
  results.textContent='';
  if(!Array.isArray(items)||!items.length){results.hidden=true;return}
  var top=document.createElement('div');
  top.className='nav-suggest-heading';
  top.textContent=heading;
  results.appendChild(top);
  items.slice(0,5).forEach(function(hit){
   var lat=Number(hit.lat),lon=Number(hit.lon);
   if(!Number.isFinite(lat)||!Number.isFinite(lon))return;
   var b=document.createElement('button');
   b.type='button';b.className='nav-suggest-option';
   var title=document.createElement('strong');
   title.textContent=String(hit.name||'Destino').slice(0,92);
   var subtitle=document.createElement('small');
   subtitle.textContent=String(hit.detail||'Seleccionar este lugar').slice(0,118);
   b.appendChild(title);b.appendChild(subtitle);
   b.addEventListener('click',function(){
    dismiss();
    input.value=String(hit.name||'Destino').slice(0,130);
    remember({name:hit.name,detail:hit.detail,lat:lat,lon:lon});
    navSelect(lat,lon,hit.name);
    input.blur();
   });
   results.appendChild(b);
  });
  var credit=document.createElement('div');
  credit.className='nav-suggest-credit';
  credit.textContent='Sugerencias basadas en OpenStreetMap · Photon';
  results.appendChild(credit);
  results.hidden=results.querySelectorAll('button').length===0;
 }
 function pending(query){
  results.textContent='';
  var item=document.createElement('div');
  item.className='nav-suggest-pending';
  var dot=document.createElement('span');
  dot.className='nav-suggest-spinner';
  dot.setAttribute('aria-hidden','true');
  var caption=document.createElement('span');
  caption.textContent='Buscando «'+String(query).slice(0,40)+'» cerca de ti, en Chile…';
  item.appendChild(dot);item.appendChild(caption);
  results.appendChild(item);
  results.hidden=false;
 }
 function update(){
  if(mapPanel.hidden)return;
  cancel();
  var q=input.value.trim(),key=norm(q);
  var matching=recent().filter(function(hit){
   return !key||norm(hit.name+' '+hit.detail).indexOf(key)!==-1;
  });
  if(q.length<3){
   display(matching,q?'Destinos recientes':'Tus destinos recientes');
   return;
  }
  if(Object.prototype.hasOwnProperty.call(cache,key)){
   display(cache[key],'Sugerencias: toca un destino');
   return;
  }
  if(matching.length)display(matching,'Destinos recientes');
  else pending(q);
  if(!window.Rush||typeof window.Rush.suggestNav!=='function'){
   if(!matching.length)results.hidden=true;
   return;
  }
  var requestId=seq;
  timer=setTimeout(function(){
   timer=null;
   if(requestId!==seq||mapPanel.hidden)return;
   var now=Date.now();
   lastRequestAt=now;
   var loc=typeof state==='object'&&state?state.location:null;
   var valid=loc&&Number.isFinite(loc.lat)&&Number.isFinite(loc.lon)&&
      loc.receivedAt&&now-loc.receivedAt<180000;
   window.Rush.suggestNav(q,valid?loc.lat:0,valid?loc.lon:0,requestId);
  },Math.max(400,1200-(Date.now()-lastRequestAt)));
 }
 window.rushNavSuggest=function(payload){
  if(!payload||mapPanel.hidden||payload.requestId!==seq||
       norm(payload.query)!==norm(input.value))return;
  if(!payload.ok){
   results.hidden=true;
   if(typeof navStatus==='function')
    navStatus('El servicio de Chile no respondió. Pulsa Buscar.');
   return;
  }
  var key=norm(payload.query);
  var items=Array.isArray(payload.items)?payload.items:[];
  cache[key]=items;
  if(Object.keys(cache).length>25)cache=Object.create(null);
  if(items.length)display(items,'Chile · lugares sugeridos cerca de ti');
  else {
   results.hidden=true;
   if(typeof navStatus==='function')
    navStatus('No hay lugares coincidentes en Chile. Prueba Buscar.');
  }
 };
 input.addEventListener('input',update);
 input.addEventListener('focus',update);
 input.addEventListener('keydown',function(e){
  if(e.key==='Escape'){dismiss();return}
  if(e.key==='ArrowDown'&&!results.hidden){
   var first=results.querySelector('button');
   if(first){e.preventDefault();first.focus()}
  }
 });
 results.addEventListener('keydown',function(e){
  if(e.key==='Escape'){dismiss();input.focus();return}
  if(e.key!=='ArrowDown'&&e.key!=='ArrowUp')return;
  var buttons=Array.prototype.slice.call(results.querySelectorAll('button'));
  var idx=buttons.indexOf(document.activeElement);
  var next=buttons[idx+(e.key==='ArrowDown'?1:-1)];
  if(next){e.preventDefault();next.focus()}
 });
 document.getElementById('navSearchBtn').addEventListener('click',dismiss);
 document.getElementById('navSelectMap').addEventListener('click',dismiss);
 document.addEventListener('click',function(e){
  if(!mapPanel.hidden && !input.contains(e.target) &&
     !results.contains(e.target) && !e.target.closest('.nav-search'))dismiss();
 });
 // El botón Finalizar y el cierre del mapa invalidan respuestas pendientes.
 document.getElementById('navClearRoute').addEventListener('click',dismiss);
 Array.prototype.forEach.call(mapPanel.querySelectorAll('[data-close="mapPanel"]'),function(x){
  x.addEventListener('click',dismiss);
 });
})();
