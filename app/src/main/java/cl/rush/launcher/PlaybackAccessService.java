package cl.rush.launcher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Servicio de autorización opcional para acceder a MediaSessionManager.
 * No lee ni almacena texto, contenido ni datos de notificaciones.
 * Android únicamente permite consultar sesiones multimedia de otras apps
 * si el usuario ha habilitado este servicio explícitamente en Ajustes.
 */
public class PlaybackAccessService extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification notification) {
        // No se inspeccionan ni guardan notificaciones.
    }
    @Override public void onNotificationRemoved(StatusBarNotification notification) {
        // No se inspeccionan ni guardan notificaciones.
    }
}
