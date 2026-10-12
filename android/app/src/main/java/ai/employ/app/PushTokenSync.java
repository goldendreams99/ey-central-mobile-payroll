package ai.employ.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * EY-CENTRAL 2026-10-08 (push, "token rotado"): cuando FCM cambia el token del dispositivo, se lo
 * avisamos al backend EN EL ACTO con el endpoint que ya existe
 * (PATCH /api/push/devices/{deviceId}/fcmToken, autenticado con X-Session-Id). Antes
 * `onNewToken` solo lo logueaba y el backend seguía mandando al token viejo hasta que la web, por
 * casualidad, volvía a pedir `push.refreshToken`: la persona dejaba de recibir push sin ningún error.
 *
 * Si todavía no hay sesión (token rotado antes del login), el token queda pendiente en prefs y se
 * manda apenas la web guarda la sesión (`session.save` → syncPendingIfNeeded).
 */
final class PushTokenSync {
    private static final String TAG = "PushTokenSync";
    private static final String PREFS_NAME = "CapacitorStorage";              // = MainActivity.PREFS_NAME
    private static final String CACHED_API_URL_KEY = "cached_api_url";        // = MainActivity.CACHED_API_URL_KEY
    private static final String DEFAULT_API_URL = "https://ey-backend-fgqald7xpa-rj.a.run.app";
    static final String PREF_FCM_TOKEN = "fcmToken";
    static final String PREF_FCM_TOKEN_PENDING = "fcmTokenPendingSync";

    private PushTokenSync() {}

    /** Llamar desde MyFirebaseMessagingService.onNewToken. */
    static void onNewToken(Context ctx, String token) {
        if (token == null || token.isEmpty()) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_FCM_TOKEN, token).putBoolean(PREF_FCM_TOKEN_PENDING, true).apply();
        syncPendingIfNeeded(ctx);
    }

    /** Llamar después de guardar la sesión (session.save): manda el token pendiente si lo hay. */
    static void syncPendingIfNeeded(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_FCM_TOKEN_PENDING, false)) return;
        final String token = prefs.getString(PREF_FCM_TOKEN, null);
        final String deviceId = prefs.getString("device_id", null);
        final String sessionId = prefs.getString("sessionId", null);
        if (token == null || deviceId == null || deviceId.isEmpty() || sessionId == null || sessionId.isEmpty()) {
            Log.d(TAG, "Token pendiente, sin sesión todavía: se manda al guardar la sesión");
            return;
        }
        String base = prefs.getString(CACHED_API_URL_KEY, null);
        if (base == null || base.isEmpty()) base = DEFAULT_API_URL;
        final String endpoint = base.replaceAll("/+$", "") + "/api/push/devices/" + deviceId + "/fcmToken";

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("PATCH");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Session-Id", sessionId);
                byte[] body = ("{\"fcmToken\":\"" + token.replace("\"", "\\\"") + "\"}").getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    prefs.edit().putBoolean(PREF_FCM_TOKEN_PENDING, false).apply();
                    Log.d(TAG, "FCM token actualizado en el backend (" + code + ")");
                } else {
                    // 401 = sesión vencida: queda pendiente y se reintenta en el próximo session.save
                    Log.w(TAG, "No se pudo actualizar el FCM token en el backend: HTTP " + code);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error avisando el FCM token al backend: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
