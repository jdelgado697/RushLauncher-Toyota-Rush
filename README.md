# Rush Launcher 2.2.0 · Toyota Rush 2021 XLI

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
