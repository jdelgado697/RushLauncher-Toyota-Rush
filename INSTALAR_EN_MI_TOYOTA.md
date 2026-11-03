# Instalación rápida · Toyota Rush 2021 XLI

**Tienes dos dispositivos distintos:**

- Radio Toyota original: firmware `C67_V2.7.1.0_T`. **No la modificaremos.**
- Carlinkit TBox Lite S: compilación `X6-RK3528-10` y pantalla configurada en `800 × 480`. **Aquí se instala Rush Launcher**.

## Pasos rápidos
1. Construir `app-debug.apk` desde el proyecto utilizando GitHub Actions (instrucciones completas en `README.md`).
2. Copiar `app-debug.apk` al Android Box.
3. Instalar y abrir Rush Launcher. Autorizar ubicación si quieres velocidad GPS real.
4. Probar accesos Spotify, Maps, AutoKit y salir. Mantener el launcher original mientras pruebas.
5. Si deseas intentar reemplazar el inicio del Box, entrar en Ajustes de Rush Launcher > Probar como pantalla de inicio. Es opcional y puede estar restringido por el firmware.
6. Si algo falla, tocar **Salir** para deshabilitar esa opción y regresar al inicio anterior.

Si AutoKit no abre, el firmware podría identificar la aplicación con otro nombre o restringir su apertura desde otras apps. Podemos adaptar la búsqueda con el nombre de paquete exacto del equipo.

**Importante:** los datos de aceite son manuales y el velocímetro requiere GPS real. No se obtienen datos del automóvil por conectar un Android Box.
