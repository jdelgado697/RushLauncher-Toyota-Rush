/* Rush Launcher 2.4 · visor Toyota Rush: interpolación visual continua.
 * El atlas contiene 8 perspectivas originales. El fundido entre fotogramas
 * reduce los saltos, pero NO representa un modelo 3D geométrico real.
 * Sin internet, permisos adicionales ni renderizado WebGL.
 */
(function(){
 'use strict';
 var ids=['rushViewHome','rushViewGarage'];
 var canvases=ids.map(function(id){return document.getElementById(id)}).filter(Boolean);
 if(!canvases.length)return;
 var atlas=new Image(),loaded=false,position=0,holdUntil=0,previousTime=0;
 var FRAME_COUNT=8, FRAME_WIDTH=112, FRAME_HEIGHT=62;
 // 1.14 fotogramas/segundo => vuelta en ~7 segundos; continuo a 30 FPS.
 var AUTO_RATE=1.14, lastDraw=-1, lastSize='';
 var running=true;
 function wrap(v){return ((v%FRAME_COUNT)+FRAME_COUNT)%FRAME_COUNT}
 function paint(canvas,phase){
   if(!loaded || !canvas.clientWidth || !canvas.clientHeight)return;
   var dpr=Math.min(window.devicePixelRatio||1,1.5);
   var w=Math.max(1,Math.round(canvas.clientWidth*dpr));
   var h=Math.max(1,Math.round(canvas.clientHeight*dpr));
   if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h}
   var ctx=canvas.getContext('2d');if(!ctx)return;
   var scale=Math.min(w/FRAME_WIDTH,h/FRAME_HEIGHT);
   var dw=FRAME_WIDTH*scale,dh=FRAME_HEIGHT*scale;
   var x=(w-dw)/2,y=(h-dh)/2;
   var current=Math.floor(phase),next=(current+1)%FRAME_COUNT;
   var fraction=phase-current;
   ctx.clearRect(0,0,w,h);
   ctx.imageSmoothingEnabled=true;
   ctx.imageSmoothingQuality='high';
   // Fundido lineal y continuo: sin cambiar a saltos cada 760 ms.
   ctx.globalAlpha=1;
   ctx.drawImage(atlas,current*FRAME_WIDTH,0,FRAME_WIDTH,FRAME_HEIGHT,x,y,dw,dh);
   if(fraction>0.002){
     ctx.globalAlpha=fraction;
     ctx.drawImage(atlas,next*FRAME_WIDTH,0,FRAME_WIDTH,FRAME_HEIGHT,x,y,dw,dh);
   }
   ctx.globalAlpha=1;
 }
 function paintAll(force){
   var phase=wrap(position);
   var dims=canvases.map(function(c){return c.clientWidth+'x'+c.clientHeight}).join(':');
   if(!force && Math.abs(phase-lastDraw)<0.016 && dims===lastSize)return;
   lastDraw=phase;lastSize=dims;
   canvases.forEach(function(c){if(c.getClientRects().length)paint(c,phase)});
 }
 atlas.onload=function(){loaded=true;paintAll(true)};
 atlas.onerror=function(){loaded=false};
 atlas.src='rush_turntable.webp';
 canvases.forEach(function(canvas){
   var dragging=false, lastX=0;
   canvas.addEventListener('pointerdown',function(ev){
     dragging=true;lastX=ev.clientX;holdUntil=performance.now()+2000;
     try{canvas.setPointerCapture(ev.pointerId)}catch(e){}
   });
   canvas.addEventListener('pointermove',function(ev){
     if(!dragging)return;
     var dx=ev.clientX-lastX;lastX=ev.clientX;
     position=wrap(position-dx/75);
     holdUntil=performance.now()+2100;
     paintAll(true);
     ev.preventDefault();
   },{passive:false});
   var endDrag=function(){dragging=false;holdUntil=performance.now()+1600};
   canvas.addEventListener('pointerup',endDrag);
   canvas.addEventListener('pointercancel',endDrag);
   canvas.addEventListener('lostpointercapture',endDrag);
   canvas.addEventListener('keydown',function(ev){
     if(ev.key==='ArrowRight'||ev.key==='ArrowLeft'){
       position=wrap(position+(ev.key==='ArrowRight'?.22:-.22));
       holdUntil=performance.now()+2600;
       paintAll(true);ev.preventDefault();
     }
   });
 });
 function loop(time){
   if(!previousTime)previousTime=time;
   var dt=Math.min((time-previousTime)/1000,.1);
   previousTime=time;
   if(!document.hidden&&running&&time>holdUntil){
     position=wrap(position+AUTO_RATE*dt);
   }
   if(!document.hidden)paintAll(false);
   window.requestAnimationFrame(loop);
 }
 window.addEventListener('resize',function(){paintAll(true)});
 document.addEventListener('visibilitychange',function(){
   previousTime=0;if(!document.hidden)paintAll(true);
 });
 window.requestAnimationFrame(loop);
})();