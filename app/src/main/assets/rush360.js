/* Rush Launcher 360: modelo 3D ilustrativo renderizado en Canvas 2D.
 * Sin red, sin bibliotecas externas y sin telemetría inventada.
 * Permite giro continuo y arrastre táctil completo alrededor del eje vertical.
 */
(function () {
  'use strict';
  var stageIds = ['rush360Home', 'rush360Garage', 'rush360Full'];
  var scenes = [], angle = 0.55, running = true, holdUntil = 0;
  var polygons = [];
  function polygon(points, color, outline, lineWidth) {
    polygons.push({ p: points, c: color, o: outline || '', lw: lineWidth || 0.6 });
  }
  function P(x, y, z) { return [x, y, z]; }
  function side(z) {
    var k = z > 0 ? 1 : -1, outer = 0.905 * k;
    polygon([P(-1.96,0.36,outer),P(2.04,0.36,outer),P(2.04,1.03,outer),
      P(1.68,1.19,outer),P(-1.83,1.15,outer)], z>0 ? '#f6f9fc':'#d2dbe5', '#b0bdc9');
    // Lateral elevado, umbral y detalles de puertas.
    polygon([P(-1.75,.53,outer+.014*k),P(1.77,.53,outer+.014*k),
      P(1.75,.61,outer+.014*k),P(-1.75,.61,outer+.014*k)], '#a5b1bd');
    var bodyZ = outer+.02*k;
    polygon([P(-1.73,.84,bodyZ),P(1.78,.84,bodyZ),P(1.75,.87,bodyZ),
      P(-1.73,.87,bodyZ)], z>0?'#e3ebf3':'#aebfce');
    [-.45,.85].forEach(function(x) {
      polygon([P(x,.62,bodyZ),P(x+.018,.62,bodyZ),P(x+.018,1.18,bodyZ),
        P(x,1.18,bodyZ)], '#b6c2ce');
      polygon([P(x-.07,1.01,bodyZ),P(x+.17,1.01,bodyZ),P(x+.17,1.04,bodyZ),
        P(x-.07,1.04,bodyZ)], '#94a7b7');
    });
    // Parte alta cabinada y cristales tintados.
    var zGlass=.832*k;
    polygon([P(-1.59,1.15,zGlass),P(1.33,1.15,zGlass),
      P(.79,1.80,zGlass),P(-1.23,1.80,zGlass)], z>0?'#e4ebf2':'#c4cfda','#a6b4c0');
    var glassOffset=.019*k;
    polygon([P(.05,1.24,zGlass+glassOffset),P(1.13,1.24,zGlass+glassOffset),
      P(.71,1.72,zGlass+glassOffset),P(.05,1.72,zGlass+glassOffset)],
      z>0?'#385164':'#2b465b','#91a6b5');
    polygon([P(-.99,1.24,zGlass+glassOffset),P(-.03,1.24,zGlass+glassOffset),
      P(-.03,1.72,zGlass+glassOffset),P(-1.11,1.72,zGlass+glassOffset)],
      z>0?'#3c586a':'#2b4458','#91a6b5');
    polygon([P(-1.47,1.23,zGlass+glassOffset),P(-1.05,1.23,zGlass+glassOffset),
      P(-1.16,1.71,zGlass+glassOffset),P(-1.23,1.71,zGlass+glassOffset)],
      z>0?'#465e6b':'#2d4758','#91a6b5');
    // Ventana: reflejos sobre vidrio para sensación de profundidad.
    polygon([P(.52,1.69,zGlass+.025*k),P(.73,1.69,zGlass+.025*k),
      P(1.03,1.29,zGlass+.025*k),P(.84,1.29,zGlass+.025*k)], '#6e8796');
    polygon([P(-.85,1.67,zGlass+.025*k),P(-.72,1.67,zGlass+.025*k),
      P(-.34,1.28,zGlass+.025*k),P(-.49,1.28,zGlass+.025*k)], '#647d8d');
    // Marco de espejo lateral.
    polygon([P(.82,1.15,1.04*k),P(1.08,1.16,1.04*k),
      P(1.09,1.30,1.04*k),P(.86,1.30,1.04*k)],z>0?'#edf2f7':'#bac7d3','#8194a6');
    // Estribos laterales negros.
    polygon([P(-1.59,.40,1.005*k),P(1.65,.40,1.005*k),
      P(1.64,.46,1.005*k),P(-1.59,.46,1.005*k)], '#394653');
  }
  // Chasis bajo / fascia / capó.
  polygon([P(-1.98,.36,-.90),P(2.05,.36,-.90),
    P(2.05,.36,.90),P(-1.98,.36,.90)], '#65707c');
  polygon([P(-1.98,1.13,-.90),P(1.64,1.15,-.90),
    P(1.64,1.15,.90),P(-1.98,1.13,.90)], '#f4f8fd','#c7d1df');
  // Capó inclinado y cara delantera.
  polygon([P(1.28,1.25,-.84),P(2.04,1.08,-.84),
    P(2.04,1.08,.84),P(1.28,1.25,.84)], '#eef3fa','#9dabb9');
  polygon([P(2.045,.37,-.89),P(2.045,1.075,-.89),
    P(2.045,1.075,.89),P(2.045,.37,.89)], '#e5edf5','#a6b7c6');
  polygon([P(2.069,.80,-.64),P(2.069,1.05,-.64),
    P(2.069,1.05,.64),P(2.069,.80,.64)], '#1b2a39','#657c8e');
  [0,.065,.13].forEach(function(t) {
    polygon([P(2.075,.85+t,-.60),P(2.075,.868+t,-.60),
      P(2.075,.868+t,.60),P(2.075,.85+t,.60)], '#b3c2d0');
  });
  polygon([P(2.079,.55,-.65),P(2.079,.67,-.65),
    P(2.079,.67,.65),P(2.079,.55,.65)], '#3c4b5a');
  [-1,1].forEach(function(k){
    polygon([P(2.073,.99,.65*k),P(2.073,1.115,.63*k),
      P(2.073,1.115,.88*k),P(2.073,.99,.88*k)], '#f7fcff','#8295a8');
    polygon([P(2.077,.51,.73*k),P(2.077,.62,.73*k),
      P(2.077,.62,.83*k),P(2.077,.51,.83*k)], '#d8e9f5','#8b9fb1');
  });
  // Óvalo frontal.
  polygon([P(2.083,.952,-.13),P(2.083,1.01,-.10),
    P(2.083,1.024,.08),P(2.083,.966,.13)], '#eef4fa');
  // Cara posterior con pilotos.
  polygon([P(-1.99,.37,-.9),P(-1.99,1.15,-.9),P(-1.99,1.15,.9),P(-1.99,.37,.9)], '#cbd6e0','#92a5b7');
  [-1,1].forEach(function(k){
    polygon([P(-2.001,.85,.71*k),P(-2.001,1.18,.69*k),
      P(-2.001,1.18,.89*k),P(-2.001,.85,.89*k)], '#bf343f');
    polygon([P(-2.007,.95,.79*k),P(-2.007,1.02,.79*k),
      P(-2.007,1.02,.87*k),P(-2.007,.95,.87*k)], '#f5d3c6');
  });
  // Techo y cristales delantero y trasero.
  polygon([P(-1.24,1.81,-.79),P(.83,1.81,-.79),
    P(.83,1.81,.79),P(-1.24,1.81,.79)], '#f6f9fc','#9caabb');
  polygon([P(.82,1.79,-.79),P(1.31,1.22,-.84),
    P(1.31,1.22,.84),P(.82,1.79,.79)], '#3f586a','#94aab8');
  polygon([P(-1.24,1.79,-.79),P(-1.63,1.18,-.84),
    P(-1.63,1.18,.84),P(-1.24,1.79,.79)], '#344b5e','#92a6b8');
  // Barras techo.
  [-1,1].forEach(function(k){
    polygon([P(-1.20,1.85,.66*k),P(.71,1.85,.66*k),
      P(.71,1.87,.66*k),P(-1.20,1.87,.66*k)], '#616e7d');
  });
  side(-1); side(1);
  // Ruedas: geometría radial real en planos laterales.
  [-1.31,1.30].forEach(function(cx){
    [-1,1].forEach(function(k){
      var z=k*1.045,cy=.38,segments=18;
      function circle(r){var q=[];for(var j=0;j<segments;j++){var a=j*2*Math.PI/segments;q.push(P(cx+r*Math.sin(a),cy+r*Math.cos(a),z))}return q}
      polygon(circle(.475),'#202833','#111820',1.15);
      polygon(circle(.338),'#748393','#101923');
      polygon(circle(.269),'#bfcbd8','#69788a');
      for(var i=0;i<7;i++){
        var a=i*2*Math.PI/7;
        function wp(r,t){return P(cx+r*Math.sin(t),cy+r*Math.cos(t),z+k*.009)}
        polygon([wp(.088,a-.23),wp(.242,a-.14),wp(.242,a+.14),wp(.088,a+.23)], '#748899','#425263');
      }
      polygon(circle(.09),'#dfe9f2','#8396a8');
    });
  });
  // Front and rear surface sorting in painter order (no GPU).
  function rotate(pt,a){
    var c=Math.cos(a),s=Math.sin(a),x=pt[0],z=pt[2];
    return [x*c-z*s,pt[1],x*s+z*c];
  }
  function draw(canvas,a) {
    var rect=canvas.getBoundingClientRect();
    if(rect.width<12||rect.height<12)return;
    var dpr=Math.min(window.devicePixelRatio||1,1.5);
    var w=Math.max(1,Math.round(rect.width*dpr)),h=Math.max(1,Math.round(rect.height*dpr));
    if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h}
    var c2=canvas.getContext('2d');if(!c2)return;
    c2.clearRect(0,0,w,h);
    var scale=Math.min(w/4.7,h/2.68),cx=w*.5,cy=h*.71;
    var shadow=c2.createRadialGradient(cx,cy+scale*.27,scale*.08,cx,cy+scale*.27,scale*2);
    shadow.addColorStop(0,'rgba(46,64,85,.21)');shadow.addColorStop(1,'rgba(46,64,85,0)');
    c2.fillStyle=shadow;c2.beginPath();c2.ellipse(cx,cy+scale*.32,scale*2.17,scale*.46,0,0,Math.PI*2);c2.fill();
    c2.strokeStyle='rgba(141,181,218,.52)';c2.lineWidth=Math.max(1,scale*.028);
    c2.beginPath();c2.ellipse(cx,cy+scale*.26,scale*2.05,scale*.42,0,0,Math.PI*2);c2.stroke();
    function project(v){var q=rotate(v,a);return [cx+(q[2]-q[0])*scale*.65,cy+(q[0]+q[2])*scale*.16-q[1]*scale*.77]}
    var faces=polygons.map(function(f) {
      var pts=f.p.map(project),cent=f.p.reduce(function(sum,v){var q=rotate(v,a);return sum+q[0]+q[2]+q[1]*.03},0)/f.p.length;
      return {pts:pts,depth:cent,color:f.c,outline:f.o,lw:f.lw};
    }).sort(function(x,y){return x.depth-y.depth});
    faces.forEach(function(face){
      c2.beginPath();
      face.pts.forEach(function(pt,i){if(i===0)c2.moveTo(pt[0],pt[1]);else c2.lineTo(pt[0],pt[1])});
      c2.closePath();c2.fillStyle=face.color;c2.fill();
      if(face.outline){c2.strokeStyle=face.outline;c2.lineWidth=Math.max(.6,face.lw*dpr);c2.stroke()}
    });
  }
  var overlay=document.getElementById('rush360Overlay');
  var autoButton=document.getElementById('rush360Auto');
  var canvases=stageIds.map(function(id){return document.getElementById(id)}).filter(Boolean);
  if(!canvases.length)return;
  canvases.forEach(function(canvas){
    var touching=false,lastX=0;
    canvas.addEventListener('pointerdown',function(ev){
      touching=true;lastX=ev.clientX;holdUntil=Date.now()+6000;
      try{canvas.setPointerCapture(ev.pointerId)}catch(e){}
    });
    canvas.addEventListener('pointermove',function(ev){
      if(!touching)return;
      var dx=ev.clientX-lastX;lastX=ev.clientX;
      angle+=(dx*.012);holdUntil=Date.now()+6000;
      canvases.forEach(function(c){if(c.getClientRects().length)draw(c,angle)});
      ev.preventDefault();
    },{passive:false});
    function release(){touching=false}
    canvas.addEventListener('pointerup',release);
    canvas.addEventListener('pointercancel',release);
    canvas.addEventListener('lostpointercapture',release);
  });
  document.querySelectorAll('[data-rush360-open]').forEach(function(b){
    b.addEventListener('click',function(){
      overlay.hidden=false;holdUntil=Date.now()+1800;
      draw(document.getElementById('rush360Full'),angle);
    });
  });
  document.getElementById('rush360Close').addEventListener('click',function(){overlay.hidden=true});
  document.getElementById('rush360Reset').addEventListener('click',function(){angle=.55;holdUntil=Date.now()+2000;draw(document.getElementById('rush360Full'),angle)});
  autoButton.addEventListener('click',function(){
    running=!running;autoButton.textContent=running?'Pausar giro':'Reanudar giro';
    autoButton.setAttribute('aria-pressed',String(!running));
    holdUntil=Date.now()+100;
  });
  overlay.addEventListener('click',function(ev){if(ev.target===overlay)overlay.hidden=true});
  var last=0;
  function tick(time){
    var delta=Math.max(0,Math.min((time-last)/1000,.075));last=time;
    if(running&&Date.now()>holdUntil)angle+=delta*.23;
    canvases.forEach(function(c){if(c.getClientRects().length&&c.offsetWidth>12)draw(c,angle)});
    window.requestAnimationFrame(tick);
  }
  window.requestAnimationFrame(tick);
})();
