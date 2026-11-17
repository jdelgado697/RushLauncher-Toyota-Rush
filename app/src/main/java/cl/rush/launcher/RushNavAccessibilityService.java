package cl.rush.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Controles externos opt-in. Muestra Inicio / Atrás / Apps sobre aplicaciones
 * de terceros SOLO si el usuario activa el servicio en Accesibilidad.
 * No inspecciona la jerarquía de vistas, no lee textos ni transmite eventos.
 * Android puede restringir la superposición según el firmware.
 */
public final class RushNavAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private LinearLayout controls;
    private boolean visible;

    private int dp(float value) {
        return (int)(getResources().getDisplayMetrics().density * value + .5f);
    }

    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(13));
        drawable.setStroke(dp(1), Color.rgb(72, 158, 231));
        return drawable;
    }

    private TextView button(String text, String description, View.OnClickListener action) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(17);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackground(background(Color.rgb(12, 43, 90)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(52));
        params.bottomMargin = dp(4);
        view.setLayoutParams(params);
        view.setOnClickListener(action);
        return view;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    private void goToRush(boolean showApps) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("rush_open_apps", showApps);
            startActivity(intent);
            hideControls();
        } catch (Exception ignored) {
            // El firmware puede impedir que el servicio abra actividades.
        }
    }

    private void showControls() {
        if (visible || windowManager == null) return;
        if (controls == null) {
            controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.VERTICAL);
            controls.setPadding(dp(3), dp(3), dp(3), dp(3));
            controls.setBackground(background(Color.argb(220, 6, 20, 49)));
            controls.addView(button("⌂", "Volver a Rush Launcher", v -> goToRush(false)));
            controls.addView(button("↶", "Atrás en la aplicación actual",
                    v -> performGlobalAction(GLOBAL_ACTION_BACK)));
            controls.addView(button("▦", "Abrir aplicaciones en Rush Launcher",
                    v -> goToRush(true)));
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(60), dp(183), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        p.x = dp(2);
        try {
            windowManager.addView(controls, p);
            visible = true;
        } catch (Exception ignored) { visible = false; }
    }

    private void hideControls() {
        if (!visible || windowManager == null || controls == null) return;
        try { windowManager.removeView(controls); } catch (Exception ignored) { }
        visible = false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null ||
                event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String activePackage = pkg.toString();
        if (activePackage.equals(getPackageName())) {
            hideControls();
        } else if (!activePackage.equals("com.android.systemui") &&
                   !activePackage.equals("android")) {
            showControls();
        }
    }

    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        hideControls();
        super.onDestroy();
    }
}
