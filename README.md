# Rush Launcher — Toyota Rush 2021 XLI (TBox Lite S)

**Proyecto Android para probar una pantalla de inicio personalizada en un Carlinkit TBox Lite S / RK3528.** La radio Toyota con firmware C67 NO se modifica. Esta es una app instalable en el Android Box, no un firmware ni un archivo que se copie en los ajustes de la radio Toyota.

## Contenido
- `app/src/main/assets/index.html`, `styles.css`, `app.js`: interfaz horizontal azul neón, diseñada para referencia 800 × 480.
- `app/src/main/assets/rush_hero.jpg`: ilustración conceptual de un Toyota Rush blanco, editable.
- `app/src/main/java/cl/rush/launcher/MainActivity.java`: integración Android con GPS real, comandos multimedia, intentos de abrir Spotify, Google Maps y AutoKit, salida al inicio original.
- `.github/workflows/build-apk.yml`: compila automáticamente el APK de depuración con Gradle 8.9, Android Gradle Plugin 8.7.3 y Android SDK 35.
- `app/src/main/AndroidManifest.xml`: permisos y actividades de Android.

## Qué funciona
- Menú de inicio táctil, reloj y fecha reales.
- Acceso a Spotify, Google Maps, AutoKit y Ajustes (si están instalados y Android permite abrirlos).
- Comandos multimedia (play/pausa/anterior/siguiente) enviados al reproductor activo. El resultado depende del reproductor.
- Velocímetro **solo cuando el proveedor GPS de Android entrega velocidad real**. Si no hay ubicación o permiso, se muestra `—`.
- Recordatorio manual de cambio de aceite: introduce los valores reales de tu odómetro, guardados localmente en el Box.
- Botón «Salir» para volver a la pantalla de inicio original del Android Box.
- Posibilidad opcional de solicitar ser pantalla de inicio: **está desactivada por defecto**. Úsala solo después de probar la aplicación.

## Lo que NO realiza
- No modifica la radio Toyota C67, su firmware, CarPlay, la cámara de retroceso ni controles del volante.
- No obtiene automáticamente el odómetro ni datos de mantenimiento de la ECU (sin adaptador y protocolo OBD).
- No controla la radio FM del vehículo: el botón explica cómo regresar al sistema Toyota.
- No muestra mapas dentro del panel: abre Google Maps si está instalado.
- No garantiza que AutoKit abra en todos los firmwares: la app busca su nombre visible, que puede variar.

## Construir un APK sin tener Android Studio — GitHub Actions
1. Descomprime el ZIP en un computador. **GitHub no descomprime automáticamente un ZIP cuando lo subes como archivo**.
2. Crea un repositorio nuevo en https://github.com/new (recomendado: privado). Elige rama `main`.
3. Sube **el contenido** de esta carpeta, incluyendo la carpeta oculta `.github`, a la raíz del repositorio. Con `git`:
   ```bash
   git init
   git branch -M main
   git add -A
   git commit -m "Rush Launcher inicial"
   git remote add origin https://github.com/TU_USUARIO/NOMBRE_REPOSITORIO.git
   git push -u origin main
   ```
   Puedes usar GitHub Desktop si no deseas utilizar comandos.
4. En el repositorio, entra en la pestaña **Actions** y selecciona «Compilar APK Toyota Rush». Se ejecuta con cada push y también mediante «Run workflow».
5. Cuando termine en verde, abre la ejecución y descarga «RushLauncher-APK-debug» en **Artifacts**.
6. Descomprime ese archivo de Actions: dentro estará `app-debug.apk`.
7. Si la compilación falla, comparte el registro del paso «Compilar APK de prueba» para corregirlo.

## Construirlo con Android Studio
- Abre esta carpeta como proyecto Gradle en Android Studio.
- Usa JDK 17, Android Gradle Plugin 8.7.3, Gradle 8.9, compileSdk 35.
- Este paquete usa la instalación de Gradle del entorno (la compilación automática la configura por sí misma); no incluye el archivo binario `gradle-wrapper.jar`.
- Con Gradle 8.9 instalado ejecuta `gradle :app:assembleDebug` o utiliza el menú **Build > Build APK(s)** en Android Studio.
- El APK se generará en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalación en el TBox Lite S
1. **Estaciona el auto**. Conserva el sistema Toyota sin cambios.
2. Copia el APK ya compilado a una memoria USB que el Box pueda leer (o transfiérelo por un método compatible).
3. Abre un gestor de archivos en el Android Box y selecciona `app-debug.apk`.
4. Si Android lo solicita, autoriza instalar apps desconocidas **solo para ese gestor de archivos**. Verifica que el APK provenga de tu propia compilación y revoca el permiso después.
5. Abre «Rush Launcher» desde la lista de aplicaciones. Al principio no lo definas como inicio predeterminado.
6. Prueba Spotify, Maps, AutoKit, volumen, radio del Toyota y cámara de retroceso; solo después considera «Ajustes > Probar como pantalla de inicio».
7. Para regresar, toca **Salir** (arriba a la derecha). Esta acción deshabilita la candidatura de Rush Launcher como HOME y cierra la app.

## Editar el diseño
Puedes editar `app/src/main/assets/styles.css` para colores y tamaños, `index.html` para tarjetas y `app.js` para interacciones. La imagen de vehículo está en `rush_hero.jpg` y puede reemplazarse por una foto propia del Rush. El proyecto usa Android WebView local, sin bibliotecas JavaScript externas ni servidores.

## Seguridad y limitaciones
- El proyecto es un prototipo funcional: las APIs o paquetes propios del fabricante del Box pueden variar.
- No uses apps/video ni interactúes con la pantalla mientras conduces.
- No instales firmware ni uses menús de actualización para este proyecto.
- Se solicitará ubicación solo para velocidad GPS real; el hardware podría no disponer de GPS autónomo.
- No se recopilan ni transmiten datos personales a un servidor desde esta app. La pantalla de servicio se guarda localmente en WebView.
- El APK de depuración es para pruebas, no una aplicación publicada en Play Store.

## Validaciones realizadas al entregar
- Sintaxis JavaScript comprobada.
- AndroidManifest y XML de recursos analizados correctamente.
- Vista en navegador 800 × 480 con pruebas de reloj, configuración de aceite, cuadro de ajustes y ayuda de radio.
- **APK no compilado en el entorno de generación:** no hay Android SDK/Gradle instalados y el acceso a los repositorios de descarga está bloqueado. GitHub Actions es la vía configurada para compilarlo.
