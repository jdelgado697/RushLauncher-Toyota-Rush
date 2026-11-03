# Rush Launcher 2.0 · Toyota Rush 2021 XLI

Launcher Android para **Carlinkit TBox Lite S** que se muestra en la radio original del Toyota mediante el sistema de conexión compatible. No reemplaza ni modifica el firmware C67 del vehículo.

## Secciones
- **Inicio:** vehículo, acceso a Waze, YouTube, Netflix, Spotify y AutoKit; reproductor asociado a MediaSession (con autorización) y velocidad real mediante GPS/OBD cuando estén disponibles.
- **Tablero:** velocidad por GPS u OBD, RPM, temperatura del refrigerante leídas desde ELM327 Bluetooth Classic. Otros indicadores permanecen con "—" hasta disponer de soporte real; no se simulan valores.
- **Conexión:** lista de adaptadores Bluetooth Classic ya vinculados con Android; conexión ELM327 y desconexión. Wi-Fi y USB OBD no están implementados.
- **Garaje:** patente opcional, historial de mantenimiento manual y datos del Toyota Rush; almacenamiento local en el dispositivo.
- **Acerca de:** ajustes Android, permisos multimedia, activación como inicio opcional y regreso al launcher anterior.

## Instalación
El repositorio incluye `.github/workflows/build-apk.yml`. La pestaña Actions genera un artefacto `RushLauncher-APK-debug` que contiene `app-debug.apk`. Para probarlo en el TBox, instálalo como una aplicación normal; no lo establezcas como launcher predeterminado hasta verificar funcionamiento y salida al inicio original.

## Limitaciones de hardware
El mapa de Inicio es decorativo y **abre Waze**; no presenta navegación en vivo incrustada. Los datos OBD dependen de un adaptador ELM327 Bluetooth Classic vinculado, de los PIDs soportados por el Toyota y de que Android permita la conexión SPP. No se consulta odómetro real, presión de aceite, cámara, códigos DTC, fiscalización ni sensores de seguridad. La interfaz solo muestra lecturas verificadas o marcadores "—".

## Seguridad
Mantén funciones de cámara de retroceso, climatización y radio FM en el sistema original. Los accesos a aplicaciones de video son para uso con el automóvil estacionado. La aplicación no controla la ECU.
