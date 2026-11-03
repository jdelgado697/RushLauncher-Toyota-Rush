(function () {
  'use strict';
  var android = !!(window.Rush && typeof window.Rush.open === 'function');
  var $ = function (selector) { return document.querySelector(selector); };
  var modal = $('#modal');
  var toastTimer;
  function toast(message) {
    var el = $('#toast'); el.textContent = message; el.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { el.hidden = true; }, 3500);
  }
  function clock() {
    var now = new Date();
    $('#clock').textContent = now.toLocaleTimeString('es-CL', { hour:'2-digit', minute:'2-digit', hour12:false });
    var hour = now.getHours();
    $('.hello').childNodes[0].textContent = (hour < 12 ? 'Buenos días' : hour < 20 ? 'Buenas tardes' : 'Buenas noches') + ' ';
    $('#date').textContent = now.toLocaleDateString('es-CL', { weekday:'long', day:'numeric', month:'long' });
  }
  clock(); setInterval(clock, 20000);
  function openModal(title, html) {
    $('#dialogTitle').textContent = title; $('#dialogBody').innerHTML = html; modal.hidden = false;
  }
  function closeModal() { modal.hidden = true; }
  $('#closeModal').addEventListener('click', closeModal);
  modal.addEventListener('click', function (event) { if (event.target === modal) closeModal(); });
  function route(name) {
    if (android) { window.Rush.open(name); return; }
    if (name === 'exit') { toast('Vista de navegador: en Android este botón vuelve al inicio original.'); return; }
    if (name === 'radio') {
      showRadioHelp(); return;
    }
    toast('En la aplicación instalada se abrirá ' + ({spotify:'Spotify',maps:'Waze',waze:'Waze',youtube:'YouTube',netflix:'Netflix',autokit:'AutoKit',phone:'AutoKit',settings:'Ajustes Android'}[name] || name) + '.');
  }
  document.querySelectorAll('[data-go]').forEach(function (el) {
    el.addEventListener('click', function () { route(el.getAttribute('data-go')); });
    if (el.getAttribute('role') === 'button') el.addEventListener('keydown', function(e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); route(el.getAttribute('data-go')); } });
  });
  // Los iconos reflejan el estado real recibido de Android MediaSession.
  var mediaPlaying = null;
  function setMediaIcon(playing) {
    var use = $('#mediaPlayIcon');
    if (!use) return;
    use.setAttribute('href', playing ? '#pause' : '#play');
    use.setAttributeNS('http://www.w3.org/1999/xlink', 'href', playing ? '#pause' : '#play');
    $('#mediaToggle').setAttribute('aria-label', playing ? 'Pausar música' : 'Reproducir música');
  }
  window.rushMediaState = function (playing, title, app) {
    if (typeof playing === 'boolean') {
      mediaPlaying = playing;
      setMediaIcon(playing);
      $('#mediaTrack').textContent = (title ? title : app || 'Reproductor') + (playing ? ' · en reproducción' : ' · en pausa');
    } else {
      // Sin permiso MediaSession no afirmamos que la reproducción esté sincronizada.
      $('#mediaTrack').textContent = 'Activa sincronización en Ajustes';
    }
  };
  document.querySelectorAll('[data-media]').forEach(function (el) {
    el.addEventListener('click', function () {
      if (!android) { toast('Control multimedia disponible en el Android Box con reproductor activo.'); return; }
      if (el.getAttribute('data-media') === 'toggle') {
        // Feedback inmediato; MediaSession corrige el icono segun la reproduccion real.
        mediaPlaying = !mediaPlaying;
        setMediaIcon(mediaPlaying);
      }
      window.Rush.media(el.getAttribute('data-media'));
    });
  });
  function showRadioHelp() {
    openModal('Radio FM del Toyota', '<p>La radio FM es parte del sistema original Toyota. Esta aplicación no controla el sintonizador ni la cámara del vehículo.</p><p>Para utilizarla, pulsa <b>Salir</b> y vuelve a la pantalla original del automóvil.</p><button class="action" id="goOut">Volver al inicio Android</button>');
    $('#goOut').addEventListener('click', function() { closeModal(); route('exit'); });
  }
  $('#gpsButton').addEventListener('click', function () {
    if (android) window.Rush.askGps(); else toast('La velocidad real requiere GPS y permisos de Android.');
  });
  window.rushGpsStatus = function (status) {
    $('#gpsStatus').textContent = status;
    if (!status.toLowerCase().includes('señal') && status.indexOf('Conectado') < 0) $('#speed').textContent = '—';
  };
  window.rushGpsData = function (speed, heading) {
    if (speed === null || speed === undefined || !isFinite(speed)) $('#speed').textContent = '—';
    else $('#speed').textContent = String(Math.max(0, Math.round(speed)));
    $('#heading').textContent = 'Rumbo: ' + (heading === null || !isFinite(heading) ? '—' : String(Math.round(heading)) + '°');
    $('#gpsStatus').textContent = 'GPS conectado';
  };
  // Values are MANUAL. No invented oil-life/odometer readings or access to car CAN bus.
  var service;
  try { service = JSON.parse(localStorage.getItem('rush_service') || 'null'); } catch(e) { service = null; }
  function serviceUI() {
    if (!service || !Number.isFinite(service.current) || !Number.isFinite(service.next) || service.next <= 0) {
      $('#serviceInfo').textContent = 'Configurar kilometraje'; $('#serviceDetail').textContent = 'Registro manual · sin conexión OBD'; $('#serviceBar').style.width = '0%'; return;
    }
    var remaining = service.next - service.current;
    $('#serviceInfo').textContent = remaining > 0 ? remaining.toLocaleString('es-CL') + ' km restantes' : 'Revisión pendiente';
    $('#serviceDetail').textContent = 'Odómetro manual: ' + service.current.toLocaleString('es-CL') + ' km';
    var base = (service.last || Math.max(0, service.next - 10000));
    var progress = (service.current - base) / Math.max(1, service.next - base);
    $('#serviceBar').style.width = Math.round(Math.min(1,Math.max(0,progress))*100) + '%';
  }
  serviceUI();
  function serviceDialog() {
    openModal('Servicio de aceite · datos manuales', '<p>Esta pantalla <b>no lee el odómetro</b>. Introduce los kilómetros reales de tu Toyota para crear un recordatorio local.</p>'+
      '<label for="currentKm">Odómetro actual (km)</label><input id="currentKm" type="number" min="0" step="1" inputmode="numeric" value="' + (service && service.current || '') + '">'+
      '<label for="nextKm">Próximo cambio de aceite (km)</label><input id="nextKm" type="number" min="1" step="1" inputmode="numeric" value="' + (service && service.next || '') + '">'+
      '<button class="action" id="saveKm">Guardar</button><button class="action secondary" id="clearKm">Borrar registro</button><div class="note">Los datos se guardan solo en el Android Box.</div>');
    $('#saveKm').addEventListener('click', function() {
      var current = Number($('#currentKm').value), next = Number($('#nextKm').value);
      if ($('#currentKm').value === '' || $('#nextKm').value === '' || !Number.isInteger(current) || !Number.isInteger(next) || current < 0 || next <= 0) { toast('Introduce dos cantidades válidas de kilómetros.'); return; }
      service = {current:current, next:next, last:Math.max(0,next - 10000)};
      localStorage.setItem('rush_service',JSON.stringify(service)); serviceUI(); closeModal(); toast('Recordatorio manual guardado.');
    });
    $('#clearKm').addEventListener('click', function() { service=null;localStorage.removeItem('rush_service');serviceUI();closeModal(); });
  }
  function showPanel(which) {
    var obd = which === 'obd';
    $('#servicePanel').hidden = obd;
    $('#obdPanel').hidden = !obd;
    $('#serviceTab').classList.toggle('active', !obd);
    $('#obdTab').classList.toggle('active', obd);
    $('#serviceTab').setAttribute('aria-pressed', String(!obd));
    $('#obdTab').setAttribute('aria-pressed', String(obd));
  }
  $('#serviceTab').addEventListener('click', function(){ showPanel('service'); });
  $('#obdTab').addEventListener('click', function(){ showPanel('obd'); });
  $('#servicePanel').addEventListener('click', serviceDialog);
  $('#servicePanel').addEventListener('keydown', function(e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); serviceDialog(); } });

  window.rushObdStatus = function(message) {
    $('#obdConnectionStatus').textContent = message;
    var messageInDialog = $('#obdModalStatus');
    if(messageInDialog) messageInDialog.textContent = message;
  };
  window.rushObdData = function(speed, rpm, coolant) {
    $('#obdSpeed').textContent = speed >= 0 ? String(speed) : '—';
    $('#obdRpm').textContent = rpm >= 0 ? String(rpm) : '—';
    $('#obdTemp').textContent = coolant > -1000 ? String(coolant) : '—';
  };
  window.rushObdDevices = function(devices) {
    var list = $('#obdDeviceList');
    if (!list) return;
    list.textContent = '';
    if (!Array.isArray(devices) || devices.length === 0) {
      list.textContent = 'No hay dispositivos vinculados. Vincula un adaptador Bluetooth Classic ELM327 desde Ajustes de Android.';
      return;
    }
    devices.forEach(function(device) {
      var btn = document.createElement('button');
      btn.className = 'action secondary obd-device';
      btn.textContent = String(device.name || 'Dispositivo') + ' · ' + String(device.address || '');
      btn.addEventListener('click', function(){
        if (!android) {toast('OBD requiere Android y un adaptador ELM327.');return;}
        window.Rush.connectObd(String(device.address));
        closeModal();
        showPanel('obd');
      });
      list.appendChild(btn);
    });
  };
  function openObdDialog() {
    showPanel('obd');
    openModal('Conectar OBD-II · Bluetooth Classic',
      '<p>Vincula antes tu adaptador ELM327 con Android. Solo se leen datos del motor; no se modifica la ECU.</p>'+
      '<button class="action" id="scanObd">Buscar dispositivos vinculados</button>'+
      '<button class="action secondary" id="obdBluetoothSettings">Ajustes Bluetooth</button>'+
      '<button class="action secondary" id="disconnectObd">Desconectar</button>'+
      '<div id="obdModalStatus" class="note">Estado: sin conexión</div>'+
      '<div id="obdDeviceList" class="obd-device-list">Pulsa buscar para ver los dispositivos vinculados.</div>');
    $('#scanObd').addEventListener('click',function(){
      if(android)window.Rush.requestObdDevices();
      else toast('Requiere un adaptador ELM327 vinculado al Android Box.');
    });
    $('#obdBluetoothSettings').addEventListener('click',function(){if(android)window.Rush.bluetoothSettings();else toast('Abrir Ajustes Bluetooth en Android.');});
    $('#disconnectObd').addEventListener('click',function(){if(android)window.Rush.disconnectObd();closeModal();});
    if(android)window.Rush.requestObdDevices();
  }
  $('#openObdSettings').addEventListener('click',openObdDialog);
  $('#openLocalSettings').addEventListener('click', function () {
    openModal('Rush Launcher · Ajustes', '<p>Diseñado para Toyota Rush 2021 XLI en TBox Lite S (referencia 800 × 480).</p>'+
      '<button class="action" id="enableHome">Elegir Rush como inicio del TBox</button>'+
      '<button class="action" id="allowMedia">Sincronizar Spotify · dar permiso</button>'+
      '<button class="action secondary" id="openObd">Conectar OBD-II</button>'+
      '<button class="action secondary" id="openAndroidSettings">Ajustes de Android</button><button class="action secondary" id="openService">Recordatorio de aceite</button>'+
      '<button class="action secondary" id="leaveApp">Salir al inicio anterior</button>'+
      '<div class="note">Versión 1.3 · Waze, inicio seleccionable, control Spotify con autorización y OBD-II Bluetooth de solo lectura. No modifica firmware, sintonizador FM, controles del vehículo ni cámara de retroceso. El velocímetro solo se activa con datos GPS reales.</div>');
    $('#enableHome').addEventListener('click', function(){closeModal();route('enableHome');});
    $('#allowMedia').addEventListener('click',function(){closeModal();if(android)window.Rush.requestMediaAccess();else toast('Configúralo en el Android Box.');});
    $('#openObd').addEventListener('click',function(){openObdDialog();});
    $('#openAndroidSettings').addEventListener('click', function(){ closeModal();route('settings'); });
    $('#openService').addEventListener('click',serviceDialog);
    $('#leaveApp').addEventListener('click',function(){closeModal();route('exit');});
  });
  if (android) { try { window.Rush.ready(); } catch(e) { /* Safe fallback for testing */ } }
})();
