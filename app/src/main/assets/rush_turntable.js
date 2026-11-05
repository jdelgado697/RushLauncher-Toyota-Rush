/* Rush Launcher 2.3 · visor de 8 ángulos Toyota Rush 2021
   Atlas WebP incorporado al APK. Sin conexiones ni APIs externas.
   Fotogramas de referencia, no modelo CAD ni imágenes de un vehículo físico del usuario. */
(function(){
 'use strict';
 var ids=['rushViewHome','rushViewGarage'];
 var viewers=ids.map(function(id){return document.getElementById(id)}).filter(Boolean);
 if(!viewers.length)return;
 var frame=0,holdUntil=0,ready=false,view=image();
 function image(){var obj=new Image();obj.onload=function(){ready=true;paintAll()};obj.onerror=function(){ready=false};obj.src='rush_turntable.webp';return obj;}
 function wrap(n){return (n%8+8)%8}
 function paint(c){
   if(!ready||!c.offsetWidth||!c.offsetHeight)return;
   var dpr=Math.min(window.devicePixelRatio||1,2);
   var w=Math.max(1,Math.round(c.clientWidth*dpr)),h=Math.max(1,Math.round(c.clientHeight*dpr));
   if(c.width!==w||c.height!==h){c.width=w;c.height=h}
   var ctx=c.getContext('2d');if(!ctx)return;
   ctx.clearRect(0,0,w,h);
   var scale=Math.min(w/112,h/62);
   var dw=112*scale,dh=62*scale;
   ctx.imageSmoothingEnabled=true;
   ctx.imageSmoothingQuality='high';
   ctx.drawImage(view,wrap(frame)*112,0,112,62,(w-dw)/2,(h-dh)/2,dw,dh);
 }
 function paintAll(){viewers.forEach(paint)}
 viewers.forEach(function(c){
  var touching=false,px=0,accum=0;
  c.addEventListener('pointerdown',function(e){
    touching=true;px=e.clientX;accum=0;holdUntil=Date.now()+4500;
    if(c.setPointerCapture)try{c.setPointerCapture(e.pointerId)}catch(_){}
  });
  c.addEventListener('pointermove',function(e){
    if(!touching)return;
    accum+=e.clientX-px;px=e.clientX;
    if(Math.abs(accum)>=18){
      var steps=accum>0?Math.floor(accum/18):Math.ceil(accum/18);
      frame=wrap(frame-steps);accum-=steps*18;paintAll();
    }
    holdUntil=Date.now()+4500;
    e.preventDefault();
  },{passive:false});
  function release(){touching=false;holdUntil=Date.now()+4000}
  c.addEventListener('pointerup',release);
  c.addEventListener('pointercancel',release);
  c.addEventListener('keydown',function(e){
    if(e.key==='ArrowRight'||e.key==='ArrowLeft'){
      frame=wrap(frame+(e.key==='ArrowRight'?1:-1));holdUntil=Date.now()+5000;
      paintAll();e.preventDefault();
    }
  });
 });
 setInterval(function(){
   if(!ready)return;
   if(Date.now()>holdUntil){frame=wrap(frame+1);paintAll()}
 },760);
 document.addEventListener('visibilitychange',function(){if(!document.hidden)paintAll()});
 window.addEventListener('resize',paintAll);
})();
