# Rush Launcher 3.8.0 · Toyota Rush 2021 XLI

Launcher Android para **Carlinkit TBox Lite S** que se muestra en la radio original del Toyota mediante el sistema de conexión compatible. No reemplaza ni modifica el firmware C67 del vehículo.

## Secciones
- **Inicio:** vehículo, acceso a Waze, YouTube, Netflix, Spotify y AutoKit; controles multimedia básicos mediante teclas Android (sin acceso a notificaciones) y velocidad real mediante GPS/OBD cuando estén disponibles.
- **Tablero:** velocidad por GPS u OBD, RPM, temperatura del refrigerante leídas desde ELM327 Bluetooth Classic. Otros indicadores permanecen con "—" hasta disponer de soporte real; no se simulan valores.
- **Conexión:** lista de adaptadores Bluetooth Classic ya vinculados con Android; conexión ELM327 y desconexión. Wi-Fi y USB OBD no están implementados.
- **Garaje:** patente opcional, historial de mantenimiento manual y datos del Toyota Rush; almacenamiento local en el dispositivo.
- **Acerca de:** ajustes Android, activación como inicio opcional y regreso al launcher anterior.

## Instalación
El repositorio incluye `.github/workflows/build-apk.yml`. La pestaña Actions genera un artefacto `RushLauncher-APK-debug` que contiene `app-debug.apk`. Para probarlo en el TBox, instálalo como una aplicación normal; no lo establezcas como launcher predeterminado hasta verificar funcionamiento y salida al inicio original.

## Limitaciones de hardware
El mapa de Inicio es decorativo y **abre Waze**; no presenta navegación en vivo incrustada. Los datos OBD dependen de un adaptador ELM327 Bluetooth Classic vinculado, de los PIDs soportados por el Toyota y de que Android permita la conexión SPP. No se consulta odómetro real, presión de aceite, cámara, códigos DTC, fiscalización ni sensores de seguridad. La interfaz solo muestra lecturas verificadas o marcadores "—".

## Seguridad
Mantén funciones de cámara de retroceso, climatización y radio FM en el sistema original. Los accesos a aplicaciones de video son para uso con el automóvil estacionado. La aplicación no controla la ECU.

## Edición 2.0.1 · permisos mínimos
Se retiró el servicio de acceso a notificaciones y la sincronización avanzada de Spotify porque Play Protect puede bloquear instalaciones externas que solicitan permisos sensibles. Los controles básicos siguen usando teclas multimedia de Android, según el reproductor activo. Play Protect puede realizar otras verificaciones y esta revisión no garantiza autorización para instalar.

## Visor del Toyota Rush 360° (v2.1.0)
La pantalla **Inicio** y la sección **Garaje** incorporan un visor 3D ligero sin conexión a internet, creado mediante polígonos locales y Canvas 2D. La figura es **ilustrativa del Toyota Rush blanco**, no un modelo CAD exacto ni una fotografía del vehículo real. El usuario puede arrastrar con un dedo para girar el automóvil 360°, abrir una vista ampliada, pausar o reanudar el giro automático y restablecer la orientación. No requiere WebGL, bibliotecas externas ni hardware adicional y no modifica el firmware del automóvil.

## Versión 2.2.0 — imagen correcta y GPS al iniciar
Se eliminó la figura 3D ilustrativa y los controles 360°. Inicio y Garaje muestran la imagen local de un Toyota Rush 2021 XLI blanco. El GPS se solicita automáticamente al abrir Rush Launcher y cuando vuelve al primer plano. Es necesario conceder permiso de ubicación y habilitar la localización del TBox. Si el dispositivo no entrega velocidad válida, se muestra «—». Las RPM, la temperatura del motor y otros sensores **no** se obtienen del GPS: requieren un ELM327 Bluetooth Classic compatible, vinculado y conectado a la ECU. Los indicadores no compatibles siguen «—»; no se simulan lecturas de sensores. La radio original y la cámara trasera no se modifican.

## Versión 2.3 — Vistas giratorias Toyota Rush 2021
Inicio y Garaje ofrecen un visor por **ocho imágenes de referencia del Toyota Rush 2021 blanco** con desplazamiento táctil y giro automático. Se incluyen dentro de la APK, por lo que no requieren conexión a internet ni permisos extra. No son fotos del vehículo del propietario ni un escaneo 3D preciso del modelo XLI; la transición es por ángulos (45° por cuadro) y no debe confundirse con render 3D continuo. No muestra datos de velocidad ni RPM sin señales reales. Sin icono 360 en pantalla; arrastra directamente el auto para explorarlo.

## Cambios v2.4.0 — Giro más continuo y aplicaciones
- **Visor Toyota Rush:** la animación ahora dibuja con \`requestAnimationFrame\` y mezcla continuamente dos de las ocho vistas disponibles; mantiene arrastre táctil fluido, control por flechas y giro automático. **No crea geometría 3D real**: la transición entre ocho imágenes puede producir superposiciones visuales. Para rotación verdaderamente precisa se requiere una malla 3D adecuada del Toyota Rush 2021 o muchos más fotogramas coherentes.
- **Apps:** acceso directo que muestra todas las apps **que Android expone como lanzables (MAIN/LAUNCHER)**. Los programas y servicios del sistema sin icono de inicio no aparecen. Las entradas se pueden buscar y tocar para abrir, sin permiso \`QUERY_ALL_PACKAGES\`.
- **Waze** permanece únicamente en el panel de navegación y se eliminó de la barra de accesos. Los demás accesos mantienen su función.
- **GPS/OBD:** sin cambios; las RPM y sensores no disponibles siguen sin inventar valores.

## Cambios v2.5.0 · Imagen fija y Spotify
- Se retiró la animación del vehículo, incluido el código del visor. Inicio y Garaje muestran una imagen fija de `rush_hero.jpg` del Toyota Rush.
- En Inicio, el panel Spotify permite enviar las teclas multimedia estándar de Android: anterior, reproducir/pausar y siguiente, dirigidas al reproductor activo. Debes iniciar Spotify previamente; no se garantiza que Android dirija los controles a Spotify si hay otro reproductor activo.
- Se añadieron los datos de **canción, artista, álbum y estado** mediante los broadcasts de Spotify `com.spotify.music.metadatachanged` y `com.spotify.music.playbackstatechanged`. Estos eventos son opcionales y Spotify puede no emitirlos. Según la documentación de Spotify, el usuario debe activar **Device Broadcast Status** si la opción existe en su versión de la aplicación. Cuando los eventos no están disponibles, el launcher informa que la canción no está sincronizada y no inventa información.
- Esta edición **no solicita acceso a notificaciones ni permisos de control privilegiado**, para no reproducir el bloqueo de Play Protect que ocurrió en versiones anteriores.
- El botón Apps, Waze únicamente en el mapa y el tablero OBD/GPS se mantienen.

## Rush Launcher 3.0.0 — interfaz día y noche
Se reemplaza el menú de cinco tarjetas por una pantalla premium adaptada a 800×480 y tablet:
- **Día/noche:** automático según hora local de Android, o claro/oscuro manual desde Ajustes.
- **Toyota Rush 2021 XLI:** imagen fija local \`rush_hero.jpg\`; no hay giro 360 ni se utiliza una imagen de RAV4.
- **Mapa:** vista **ilustrativa**, no navegación en vivo. Abre Waze directamente, y el botón Mapas también abre Waze. No muestra rutas ficticias.
- **Spotify:** play/pausa, anterior/siguiente mediante teclas multimedia de Android. Canción, artista y estado únicamente si Spotify emite los broadcasts opcionales; no necesita acceso a notificaciones ni implica control exclusivo de Spotify.
- **Clima:** consultas reales Open-Meteo después de pulsar "Activar clima". Requiere ubicación y conexión a internet. La ubicación se comparte con Open-Meteo; el usuario puede desactivar la función. Nunca se simulan temperaturas.
- **Apps:** buscador y listado de apps Android que exponen actividad de inicio, con acceso a YouTube, Netflix y otras apps instaladas.
- **OBD2:** búsqueda de adaptadores Bluetooth Classic ELM327 ya vinculados; lecturas reales de velocidad, RPM y refrigerante según soporte ECU. Se abre desde el indicador OBD o Ajustes.
- **Radio FM:** mantiene un acceso explicativo; el sintonizador y la cámara pertenecen a la radio original Toyota y no se pueden controlar desde esta APK.
- **AutoKit y Bluetooth:** acceso nativo a la aplicación Android y a sus ajustes, cuando sean compatibles.
- El launcher original del TBox permanece como alternativa y no se instala por defecto. No modifica el firmware del automóvil.

### Privacidad, seguridad y limitaciones
Se usan permisos de localización, Bluetooth según versión y de internet (para la consulta voluntaria del clima). **Sin** servicio de acceso a notificaciones, sin superposiciones de accesibilidad, sin control de la ECU. Las aplicaciones de video deben usarse con el automóvil estacionado. El APK debug no cuenta con firma de publicación de Play Store y podría activar las advertencias habituales del dispositivo.

### Ajustes visuales v3.0.1
La imagen fija del Toyota Rush usa recorte SVG sobre fotografía local para integrarse con fondos diurnos y nocturnos sin un rectángulo oscuro. El mapa ilustrativo se atenúa de noche; no se muestran condiciones climáticas inventadas cuando el pronóstico está desactivado.

## Mapa real GPS · v3.1.0
- Sustituye el plano **ilustrativo** por cartografía interactiva real con teselas `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, atribución permanente **© OpenStreetMap contributors**.
- La posición del coche procede exclusivamente del GPS o proveedor de ubicación del Android Box, nunca de una ubicación inventada. Si no hay señal, se muestra un aviso y no se dibuja una ruta ni un marcador ficticio. Si Android dispone de una posición guardada de menos de 2 minutos y precisión razonable, la muestra al iniciar.
- **Funciones:** arrastre del mapa, zoom con + y –, gesto de pellizcar, marcador del vehículo y orientación de rumbo si existe, botón para recentrar y seguimiento automático mientras no se desplace manualmente el mapa. Para iniciar rutas y navegación guiada se abre **Waze** desde el botón inferior.
- Requiere **acceso a internet y permisos de localización** para los datos actuales. Enviar teselas desde la zona del mapa implica solicitudes de red a los servidores de OSM (ver su política de privacidad). No se ofrecen mapas sin conexión, descargas masivas ni precarga. Las imágenes usan caché normal del WebView y User-Agent identificable.
- No incorpora Google Maps SDK ni exige clave de Google. La visualización del mapa y el geoposicionamiento deben verificarse en el Carlinkit TBox Lite S real; compilar Android no prueba recepción satelital.

## Cambios v3.2.0: navegación sin Waze y música realmente integrada
### Mapas
- Se conserva el mapa en vivo OpenStreetMap de Inicio con GPS del TBox.
- **Navegar** y el botón inferior **Mapas** abren un cuadro para escribir un destino.
- Se abre Google Maps instalado si está disponible; en caso contrario se abre la dirección web de Google Maps en un navegador Android. Para ver el mapa general busca Maps/Maps Go, Organic Maps, OsmAnd o HERE WeGo. Navegación guiada no se ejecuta dentro de Rush Launcher, sino en una app/navegador compatible.
- La navegación no depende de Waze, que algunas versiones de Carlinkit TBox Lite S no admiten.
### Música
- Se mantiene el mecanismo sin permisos de broadcasts de Spotify como respaldo.
- La vía principal para mostrar **canción, artista, álbum, estado, duración y posición** es ahora \`MediaSessionManager\` de Android; solo si el usuario autoriza **Acceso a notificaciones** para Rush Launcher en Ajustes. Se prefiere Spotify; si no está, otro reproductor Android con sesión multimedia.
- El servicio \`PlaybackAccessService\` implementa \`NotificationListenerService\` pero **no inspecciona, registra, almacena ni comparte notificaciones**; el permiso se emplea exclusivamente para consultar \`MediaSessionManager.getActiveSessions()\`. Puedes revocarlo desde Ajustes. Esta autorización puede generar advertencias de seguridad, especialmente con APK instaladas manualmente: no se recomienda desactivar Play Protect.
- Play/pausa, siguiente, anterior y desplazamiento en el tiempo usan \`MediaController.TransportControls\`, con respaldo de teclas multimedia Android cuando no se concede acceso.
- **Spotify debe estar instalado y reproduciendo música en el propio TBox**, no únicamente en el teléfono dentro de CarPlay/Android Auto. Si está ausente, el botón intenta abrir Spotify Web en un navegador, donde el control externo puede no funcionar.
- No hay API privada, automatización de pantalla, permisos de accesibilidad, controles del auto ni DRM modificado.
### Pruebas
- El workflow de GitHub compila APK debug y valida las conexiones UI/nativas.
- Verificar en el TBox el permiso de sesión multimedia, que el reproductor está visible, que el botón de mapa externo encuentra navegador compatible y que la reproducción real muestra información. Compilar no certifica funcionamiento en el hardware.


## Versión 3.3.0 — íconos originales de las aplicaciones
- El launcher obtiene los **íconos originales** de las aplicaciones que Android declara como lanzables usando \`PackageManager.queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)\` y \`ResolveInfo.loadIcon()\`. No descarga logos desde internet ni reproduce imitaciones.
- El menú **Apps** lista nombre e ícono real de cada aplicación visible y permite buscarla y abrirla; no enumera servicios ocultos ni necesita \`QUERY_ALL_PACKAGES\`. Los logos se convierten en PNG local de 64×64 y se muestran como imágenes \`data:\` dentro del WebView. Se usa caché y un hilo auxiliar para minimizar carga al abrir el menú.
- La barra de accesos **Mapas, Spotify y AutoKit** selecciona automáticamente el logo de la aplicación realmente instalada. AutoKit reconoce las variantes Carlinkit/manstep/ZLink disponibles, y Mapas reconoce proveedores compatibles. Si falta el programa, mantiene el pictograma genérico: no muestra un logo de un producto no instalado.
- Los accesos **Apps, Bluetooth y Radio FM original Toyota** mantienen íconos neutrales del sistema, ya que no representan necesariamente aplicaciones Android. Radio FM no controla el sintonizador original desde el TBox.
- El panel de música muestra el logo real del reproductor Android que esté activo si este ofrece una actividad de lanzamiento visible. Cuando no es posible leer el ícono, se usa el pictograma genérico. No se descargan carátulas de terceros.
- Los íconos se actualizan al reabrir el launcher y al acceder a Apps; los paquetes visibles varían según el firmware del TBox. Este cambio no modifica el firmware ni agrega permisos sensibles.
- El mapa GPS real, el menú de rutas, las funciones OBD, los controles musicales y el modo claro/oscuro de 3.2 se conservan.

**Verificación de hardware pendiente:** compilar la APK no confirma que todos los fabricantes expongan su icono al PackageManager. Algunos TBox restringen ciertas apps del sistema; en ese caso el botón muestra el símbolo genérico.

## Rush Launcher 3.3.1 — corrección de bloqueo de Play Protect
En un Carlinkit TBox Lite S, la versión 3.3 fue bloqueada con el aviso «Aplicación bloqueada para proteger tu dispositivo». Google identifica como riesgo elevado algunas APK descargadas directamente con servicios de lectura de notificaciones. Se corrigió el origen probable:
- Eliminados PlaybackAccessService, su declaración en AndroidManifest.xml y toda dependencia de MediaSessionManager que requería acceso sensible.
- Esta APK no declara servicios NotificationListener ni permisos de accesibilidad, SMS, lectura de notificaciones o control multimedia privilegiado.
- Spotify conserva comandos AudioManager.dispatchMediaKeyEvent (anterior, siguiente, play/pausa), que dependen de la compatibilidad del reproductor activo.
- Nombre de canción, artista y estado se muestran SOLO si Spotify envía broadcasts opcionales desde el mismo Android Box; no se solicitan datos sensibles para forzarlos.
- Íconos auténticos de aplicaciones Android por PackageManager, mapa GPS real, rutas web, clima voluntario, modo día/noche y OBD no se alteran.
- El workflow inspecciona el manifiesto final del APK generado para impedir que reaparezcan servicios sensibles.
- Play Protect puede seguir advirtiendo por otros motivos (APK debug, reputación, firmas o política del dispositivo). No se aconseja deshabilitar ni eludir Play Protect. La compilación no demuestra aprobación de Play Protect en un equipo físico.
- Si el paquete se instaló previamente con otra clave de firma, Android puede exigir desinstalar la versión antigua (se perderían datos locales). No forzar instalaciones bloqueadas.


## Rush Launcher 3.4.0 · Spotify Connect real sin permisos sensibles
Esta versión usa la API oficial de Spotify para mostrar canción, artista, álbum, duración, dispositivo y carátula, y para realizar play/pausa, anterior, siguiente y seek. Incluye un navegador de autorización OAuth 2.0 Authorization Code + PKCE, sin contraseña ni client secret en el APK. El token de actualización se cifra en Android Keystore AES-256-GCM y se guarda en preferencias privadas; el access token se mantiene únicamente en memoria. No usa servicios de notificaciones, accesibilidad, lectura de mensajes ni medios privilegiados.

### Configuración única en el TBox (vehículo estacionado)
1. La aplicación que autoriza el uso de Spotify debe estar registrada en https://developer.spotify.com/dashboard bajo tu cuenta de desarrollador.
2. Copia su **Client ID** (nunca el Client Secret).
3. En su configuración, añade **exactamente** la URI de retorno \`rushlauncherxli://spotify/callback\`.
4. Si tu aplicación está en modo desarrollo, agrega tu usuario de Spotify a la lista de usuarios permitidos (máximo según límites de Spotify) y asegúrate de que la cuenta del desarrollador tenga Spotify Premium.
5. En Rush Launcher: Música (ⓘ) > escribir Client ID > Guardar > Conectar Spotify. Se abre el navegador para autorizar y retorna a Rush Launcher.
6. Abre Spotify en el Android Box o usa Spotify Connect y comienza a escuchar música. El panel de Inicio se actualiza automáticamente con intervalos de 16 segundos, y el botón «Actualizar canción» fuerza una consulta respetando los límites.
7. Al desconectar se revoca el acceso local (se borra el token cifrado). Para revocarlo en Spotify también, visita la página de aplicaciones conectadas de tu cuenta.

### Límites
- Solo funciona con **autorización real**, cuenta y API habilitadas y conexión a internet. Las operaciones de playback requieren **Spotify Premium** y un dispositivo Spotify Connect activo. No inventa metadatos ni garantiza datos en sistemas Spotify muy antiguos.
- Si no configuras Spotify for Developers, los botones multimedia Android de versiones anteriores siguen siendo el respaldo y el título/artista solo se mostrarán si Spotify los difunde opcionalmente.
- No se pueden asegurar las decisiones de Google Play Protect desde el APK: se revisa el manifiesto para que no incluya los servicios sensibles que provocaron el riesgo anterior; la prueba final la realiza el dispositivo.
- No existe bypass de Play Protect, root, accesibilidad, overlays ni permisos invasivos. La seguridad del sistema permanece activada.
- Estado y errores se muestran dentro del panel Spotify. La aplicación maneja 401, 403, 404 y 429; las cuotas y las reglas de Spotify pueden variar.

## Rush Launcher 3.5.0 — Spotify sin dependencia de Spotify Developers
- La pantalla Música prioriza iniciar la **aplicación Spotify instalada** en Android. El dock y el botón Abrir no requieren Client ID, Spotify Developers ni acceso a notificaciones.
- Nuevo botón **Abrir mi playlist**: intenta iniciar `spotify:playlist:6jaGMO7U1463pu2nSX2V6v` en la app oficial, y usa `https://open.spotify.com/playlist/6jaGMO7U1463pu2nSX2V6v` desde un navegador como alternativa.
- Nuevo **reproductor web opcional** con Spotify Embed. Solo se carga al pulsar el botón. La reproducción íntegra depende del soporte DRM/EME y autenticación del WebView, y puede limitarse a previews; si falla, utilizar la app nativa.
- Los botones de reproducción, pausa y saltos usan `AudioManager.dispatchMediaKeyEvent` cuando no hay Client ID autorizado. Android dirige estos eventos al reproductor multimedia activo: si se está reproduciendo otra app, puede recibirlos esa app.
- Spotify documenta `com.spotify.music.metadatachanged` y `com.spotify.music.playbackstatechanged` con datos opcionales de tema, artista, álbum, duración, reproducción y posición. Rush Launcher los muestra si se difunden en el TBox. La opción `Device Broadcast Status` debe habilitarse en Spotify si la versión instalada la ofrece; no se puede activar desde Rush Launcher ni garantizar su existencia.
- La duración/posición se presenta como **aproximada** cuando procede de broadcasts; la barra no permite adelantar mediante broadcasts porque esa API no admite seek. El control de posición avanzado sigue disponible solo con OAuth Web API autorizado.
- La interfaz avanzada por Spotify API permanece en una sección desplegable **opcional**, útil cuando Spotify permita crear el Client ID.
- El mapa real GPS, los iconos originales de las apps, la selección de temas y las demás funciones existentes no cambian.
- El archivo APK se comprueba automáticamente para impedir que se incorporen permisos de lectura de notificaciones o accesibilidad. Esto **no asegura** la aprobación de Google Play Protect, cuya decisión se verifica al instalar en el hardware.

### Prueba en Carlinkit TBox Lite S
1. Con el auto detenido, abrir Spotify desde la barra del launcher. Iniciar música en Spotify y volver a Rush Launcher.
2. Comprobar si play/pausa y siguiente/anterior controlan realmente Spotify. Si no, confirmar que Spotify esté siendo el reproductor Android activo.
3. Para título/artista, buscar en Spotify > Ajustes > Device Broadcast Status. Si no aparece, esta compatibilidad no está disponible en esa versión; no se mostrarán datos inventados.
4. Probar **Abrir mi playlist**. Si la instalación de Spotify no admite el intent, verificar el navegador y Spotify Web.
5. Probar Embed solo como alternativa. Si la radio no dispone de DRM compatible, utilizar Spotify nativo.

## Rush Launcher 3.6.0 — carátulas originales de Spotify sin Client ID
- Spotify envía `id` en el broadcast oficial `com.spotify.music.metadatachanged`, además de `track`, `artist` y `album`. Solo se consulta portada cuando cambia ese identificador.
- Se consulta **Spotify oEmbed** para la URL `https://open.spotify.com/track/{id}` (o episodio), con `GET https://open.spotify.com/oembed?url=...`. La respuesta oficial incluye `thumbnail_url` cuando está disponible. No se requieren Spotify Developers, OAuth, Client ID, permisos de notificaciones ni cambios en la cuenta.
- Se limita la URL recibida a CDNs de imágenes de Spotify (i.scdn.co, mosaic.scdn.co, image-cdn-*.spotifycdn.com), siempre por HTTPS, y se dibuja en el panel Música. Si falta portada, hay error HTTP o internet, se muestra el ícono original sin inventar imágenes.
- Consultas fuera del hilo principal; timeout de conexión y lectura de 7 segundos; caché local de 96 pistas durante la sesión y verificación del ID antes de mostrar una imagen tardía. No se almacenan contraseñas ni se comparte ubicación.
- Se conservan mapa GPS, Spotify nativo, barra de controles, progreso aproximado, playlist y menú de Apps.
- El servidor Spotify oEmbed recibe la URL pública de la pista para responder; el servicio necesita internet. Algunas pistas locales, episodios o contenidos privados pueden carecer de portada. El cambio está validado por compilación, pero la carga real debe comprobarse en el Carlinkit TBox Lite S.
- No se modifican los permisos del manifiesto ni se anula Google Play Protect.

## Rush Launcher 3.7.0 — Apps agrupadas y ordenadas
- En el cajón **Apps** se muestran tres secciones en este orden: **Streaming y video**, **Música y audio** y **Otras aplicaciones**. No se fuerza a instalar ninguna aplicación; solo aparecen las que Android expone como lanzables en el TBox.
- Incluye reconocimiento de plataformas como YouTube, Netflix, Prime Video, Paramount+, Disney+, Max, Pluto TV, Crunchyroll, Plex, VLC, Kodi e IPTV; Spotify, YouTube Music, Deezer, Tidal y otros reproductores se ubican en Música y audio.
- La categorización utiliza preferentemente el nombre del paquete Android y secundariamente la etiqueta visible. Las apps no reconocidas se colocan en Otras aplicaciones, sin adivinar su función.
- Se mantiene el orden alfabético dentro de cada sección, la búsqueda global, los iconos oficiales obtenidos del dispositivo y el acceso a las aplicaciones.
- En dispositivos pequeños las secciones comparten un contenedor desplazable; los títulos identifican los grupos y solo se muestran secciones con resultados.
- No se añaden permisos, no se modifica Spotify ni el mapa GPS, ni se altera la pantalla principal. La compilación incluye pruebas deterministas de clasificación.

## Rush Launcher 3.7.1 — Xuper al inicio
- Dentro de Streaming y video, todas las aplicaciones instaladas cuyo nombre o paquete las identifica como Xuper (incluyendo Xuper TV y Xuper Play) aparecen primero, ordenadas alfabéticamente entre sí.
- Después quedan YouTube, Netflix, Paramount+ y las demás aplicaciones de vídeo por orden alfabético; luego se muestran Música y audio y Otras aplicaciones.
- No se instala ni modifica Xuper. Se conservan los íconos obtenidos desde Android, el filtro, Spotify, las carátulas y el mapa GPS.

## Rush Launcher 3.8.0 — mapa ligero, vehículo gris completo y HOME opcional
- **Mapa más ligero para TBox Lite S:** vista inicial zoom 14, hasta 12 teselas visibles y caché en pantalla de 20 teselas cargadas, sin precarga de zonas. El seguimiento mueve el centro cuando cambian al menos 32 m y transcurren 2,5 segundos, mientras la ubicación real continúa actualizándose. Evita recomputaciones cuando el GPS oscila y evita solicitar continuamente las mismas teselas. El WebView respeta HTTP cache y mantiene User-Agent identificable, el texto obligatorio «© OpenStreetMap contributors» y su API oficial. Sigue requiriendo internet; la velocidad real depende del proveedor de mapas, cobertura móvil y hardware.
- **Imagen nueva:** el asset local `rush_gray.webp` contiene un Toyota Rush 2021 XLI gris en vista completa, generado como representación ilustrativa y no como fotografía certificada del vehículo. Se reemplazó el SVG con máscara que recortaba el auto. La imagen no necesita internet.
- **Inicio predeterminado:** en Ajustes se muestra «Elegir Rush Launcher como inicio». Activa el alias `HomeAlias` y abre `Settings.ACTION_HOME_SETTINGS`; si el firmware lo permite, el usuario debe escoger Rush Launcher manualmente. Al pulsar Atrás mientras Rush es HOME, no se deshabilita por accidente. El botón «Restaurar inicio original del TBox» deshabilita HomeAlias y regresa a Home Android. No fuerza cambios de firmware ni afecta cámara, radio o la unidad de Toyota.
- **Límites:** los firmwares de Carlinkit pueden restringir el rol HOME. Si los ajustes de Inicio no ofrecen Rush, es una limitación del TBox; no utilizar root, permisos de accesibilidad ni desactivar Play Protect.
- **Seguridad:** la APK no añade permisos invasivos. Las funciones de Spotify, carátulas, aplicaciones ordenadas (Xuper primero) y datos OBD permanecen. Descarga en repositorio Actions; instalar con vehículo detenido y con Play Protect activado.


## Parche 4.15.1-NavIntegrado (base 4.15.0-GPS-Directo)

Esta edición **parte de la rama rush-launcher-415-gps-watchdog**, sin
descartar la interfaz OBD, Spotify Premium, clima, launcher HOME ni los
controles de regreso. Conserva también los archivos del mapa web 2D 4.15.

En Android, el botón «Abrir navegación» abre una Activity nativa **dentro del
mismo APK** con MapLibre 13.7.1 y servicios públicos Photon/OSRM para
buscar y calcular rutas. Permite regreso inmediato con «Inicio» o Atrás.
El mapa WebView 4.15 sigue disponible solo en la previsualización HTML
como respaldo, y no se inicia en Android por defecto.

**Importante:** no se integra la app Organic Maps. El mapa vectorial precisa
conexión y, al igual que la versión anterior, las búsquedas y rutas necesitan
internet; no equivale a Waze ni garantiza tráfico/reporte de incidentes.
Los servicios de rutas públicos son de prueba. La brújula y la actualización
de ubicación dependen de señal real del TBox.

**Identidad APK:** mantiene applicationId cl.rush.launcher.obd415, con
versionCode 42 (superior a 4.15: 41). Si las firmas no coinciden, Android
puede impedir actualizar; respalda los ajustes antes de desinstalar.


## Rush Launcher 4.15.2 · conducción y mapa de Inicio
Se mantiene la base 4.15.1, con todo el tablero OBD, Spotify, clima, CarPlay y Apps.
El Inicio muestra un mapa 2D raster ligero de OSM desde el primer instante.
Si no ha llegado señal GPS usa Loncoche como **mapa de referencia**, sin afirmar
que allí esté el vehículo. El GPS se solicita al iniciar el launcher y
el mapa se actualiza a coordenadas reales cuando llegan lecturas de Android.

Rush Navigator tiene mapa nativo con TextureView para controles visibles,
predicción de lugares chilenos al escribir (Photon), pulsación larga para
seleccionar destino, botón explícito «Calcular ruta», respaldo de rutas
FOSSGIS y cámara inclinada/rumbo arriba al abrir. Servicios de mapas,
búsqueda y rutas necesitan red y son de demostración, sin SLA.
Configuración de destinos exclusivamente con vehículo detenido.


## Rush Launcher 4.15.3 — solución al error de búsqueda

Basada en 4.15.2 y conservando el mapa nativo MapLibre, Inicio, modo conducción,
tablero OBD, Spotify y aplicaciones.

**Búsqueda por prioridad**:
1. Autocompletado al teclear mediante Photon, con consulta mínima (q, limit,
   lat, lon y zoom), eliminando los parámetros de idioma/país que podrían
   provocar rechazo en determinados despliegues; filtrado local a Chile.
   Pausa de 25 segundos si el proveedor lanza excepción.
2. Si Photon falla o no encuentra lugares, Geocoder Android (solo cuando
   el servicio del sistema está disponible en el Carlinkit).
3. **Solo al tocar Buscar o pulsar Enter:** búsqueda puntual por Nominatim,
   geográficamente restringida a Chile, con limitación temporal y caché local
   de la sesión. No se utiliza Nominatim para autocompletar: lo prohíbe
   su política pública.
4. Los errores de HTTP, DNS, TLS y espera se distinguen en pantalla. Si
   ningún proveedor responde, se puede elegir destino con pulsación larga
   en el mapa. Se eliminó el mensaje «Buscando...» que quedaba visible
   después de un fallo de servicio.

No hay garantías de disponibilidad de servidores públicos; para
navegación fiable en producción debe contratarse o desplegarse un servicio
de geocodificación propio. Siempre preparar el destino con vehículo detenido.


## 4.15.4: inicio funcional y conexión automática

Base: 4.15.3, con mapa nativo de navegación y todos los accesos anteriores.

- **Clima**: primera instalación de la actualización activa clima en Inicio.
  Usa el GPS del TBox con el permiso de ubicación Android y se actualiza
  periódicamente. El usuario puede apagarlo desde el mismo widget.
- **OBD-II**: conexión Bluetooth Classic automática solo con adaptador
  ELM327 emparejado. Recuerda la última MAC seleccionada. En ausencia de
  preferencia, detecta UN ÚNICO dispositivo claramente identificado como
  OBD/ELM/VGate/VLink/Veepeak/iCar; si hay varios, pide escoger uno en el
  menú de conexión. No se empareja automáticamente ni escribe datos ECU.
  Ante fallo, reintenta cuando el launcher está abierto, en intervalos
  moderados (45 segundos). Desconectar desactiva la opción automática
  hasta reactivarla o conectar manualmente.
- **Mapa Inicio**: ya es un mapa interactivo. Arrastra con el dedo
  para explorar, usa +/- para zoom y «Seguir» para recuperar modo conducción.
  Arranca en seguimiento rumbo arriba cuando Android entrega dirección y
  velocidad. Sin lectura GPS muestra mapa de **referencia**, nunca afirma
  que la ubicación sea la del vehículo. El modo navegación completo
  MapLibre sigue disponible desde «Abrir mapa completo».
- **Límites**: el widget Inicio usa mapa raster 2D ligero; el modo 3D
  con rutas sigue en la pantalla nativa. Las teselas OSM y el pronóstico
  requieren internet; no se prometen incidencias viales ni navegación offline.
  Nunca manipular la pantalla mientras se conduce.
