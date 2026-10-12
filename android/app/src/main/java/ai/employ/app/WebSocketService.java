package ai.employ.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WebSocket service para recibir comandos del dashboard
 * Dashboard → Backend → WebSocket → Este servicio → NativeBridge
 */
public class WebSocketService extends WebSocketListener {
    private static final String TAG = "WebSocketService";
    private static final String PREFS_NAME = "CapacitorStorage";

    // URL del backend WebSocket (dinamica desde Firebase)
    private String wsUrl = null;
    // Command mapping: module.action -> bridge.action (replaces 185 lines of switch statements)
    private static final Map<String, String> CMD = new HashMap<>();
    static {
        CMD.put("geolocation.getCurrentPosition", "geolocation.getCurrentPosition");
        CMD.put("geolocation.watchPosition", "geolocation.watchPosition");
        CMD.put("geolocation.clearWatch", "geolocation.clearWatch");
        CMD.put("geolocation.checkPermissions", "geolocation.checkPermissions");
        CMD.put("geolocation.requestPermissions", "geolocation.requestPermissions");
        CMD.put("notifications.send", "localNotifications.schedule");
        CMD.put("notifications.cancel", "localNotifications.cancel");
        CMD.put("notifications.cancelAll", "localNotifications.cancelAll");
        CMD.put("notifications.checkPermissions", "localNotifications.checkPermissions");
        CMD.put("notifications.requestPermissions", "localNotifications.requestPermissions");
        CMD.put("notifications.getPending", "localNotifications.getPending");
        CMD.put("notifications.getDelivered", "localNotifications.getDelivered");
        CMD.put("notifications.removeDelivered", "localNotifications.removeDelivered");
        CMD.put("notifications.removeAllDelivered", "localNotifications.removeAllDelivered");
        CMD.put("notifications.createChannel", "localNotifications.createChannel");
        CMD.put("notifications.deleteChannel", "localNotifications.deleteChannel");
        CMD.put("notifications.listChannels", "localNotifications.listChannels");
        CMD.put("camera.getPhoto", "camera.getPhoto");
        CMD.put("camera.pickImages", "camera.pickImages");
        CMD.put("camera.pickVideo", "camera.pickVideo");
        CMD.put("camera.checkPermissions", "camera.checkPermissions");
        CMD.put("camera.requestPermissions", "camera.requestPermissions");
        CMD.put("biometrics.isAvailable", "biometrics.isAvailable");
        CMD.put("biometrics.verifyIdentity", "biometrics.verifyIdentity");
        CMD.put("biometrics.setCredentials", "biometrics.setCredentials");
        CMD.put("biometrics.getCredentials", "biometrics.getCredentials");
        CMD.put("biometrics.deleteCredentials", "biometrics.deleteCredentials");
        CMD.put("haptics.vibrate", "haptics.vibrate");
        CMD.put("haptics.impact", "haptics.impact");
        CMD.put("haptics.notification", "haptics.notification");
        CMD.put("store.requestReview", "store.requestReview");
        CMD.put("statusBar.setBackgroundColor", "statusBar.setBackgroundColor");
        CMD.put("statusBar.setStyle", "statusBar.setStyle");
        CMD.put("statusBar.show", "statusBar.show");
        CMD.put("statusBar.hide", "statusBar.hide");
        CMD.put("navigationBar.setBackgroundColor", "navigationBar.setBackgroundColor");
        CMD.put("navigationBar.setStyle", "navigationBar.setStyle");
        CMD.put("navigationBar.show", "navigationBar.show");
        CMD.put("navigationBar.hide", "navigationBar.hide");
        CMD.put("clipboard.write", "clipboard.write");
        CMD.put("clipboard.read", "clipboard.read");
        CMD.put("share.send", "share.share");
        CMD.put("share.shareFile", "share.shareFile");
        CMD.put("share.canShare", "share.canShare");
        CMD.put("browser.open", "browser.open");
        CMD.put("browser.close", "browser.close");
        CMD.put("device.getInfo", "device.getInfo");
        CMD.put("device.getId", "device.getId");
        CMD.put("device.getDeviceId", "device.getDeviceId");
        CMD.put("device.getBatteryInfo", "device.getBatteryInfo");
        CMD.put("network.getStatus", "network.getStatus");
        CMD.put("storage.get", "preferences.get");
        CMD.put("storage.set", "preferences.set");
        CMD.put("storage.remove", "preferences.remove");
        CMD.put("storage.clear", "preferences.clear");
        CMD.put("storage.keys", "preferences.keys");
        CMD.put("session.save", "session.save");
        CMD.put("session.clear", "session.clear");
        CMD.put("session.get", "session.get");
        CMD.put("session.enableBiometricLogin", "session.enableBiometricLogin");
        CMD.put("session.checkBiometricLogin", "session.checkBiometricLogin");
        CMD.put("push.refreshToken", "push.refreshToken");
        CMD.put("push.getToken", "push.getToken");
        CMD.put("push.checkPermissions", "push.checkPermissions");
        CMD.put("push.requestPermissions", "push.requestPermissions");
        CMD.put("localNotifications.schedule", "localNotifications.schedule");
        CMD.put("localNotifications.cancel", "localNotifications.cancel");
        CMD.put("localNotifications.checkPermissions", "localNotifications.checkPermissions");
        CMD.put("localNotifications.requestPermissions", "localNotifications.requestPermissions");
        CMD.put("cache.clear", "cache.clear");
        CMD.put("cache.clearCache", "cache.clearCache");
        CMD.put("cache.clearStorage", "cache.clearStorage");
        CMD.put("cache.clearPreferences", "cache.clearPreferences");
        CMD.put("cache.reload", "cache.reload");
        CMD.put("cache.hardReload", "cache.hardReload");
        CMD.put("cache.clearAndRestart", "cache.clearAndRestart");
        CMD.put("app.getInfo", "app.getInfo");
        CMD.put("app.minimize", "app.minimize");
        CMD.put("app.exit", "app.exit");
        CMD.put("keyboard.show", "keyboard.show");
        CMD.put("keyboard.hide", "keyboard.hide");
        CMD.put("splashScreen.show", "splashScreen.show");
        CMD.put("splashScreen.hide", "splashScreen.hide");
    }


    private final Context context;
    private final NativeBridge nativeBridge;
    private final Handler mainHandler;
    private final SharedPreferences preferences;

    private OkHttpClient client;
    private WebSocket webSocket;
    private boolean isConnected = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long RECONNECT_DELAY_MS = 5000;

    public WebSocketService(Context context, NativeBridge nativeBridge) {
        this.context = context;
        this.nativeBridge = nativeBridge;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        this.client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Configure WebSocket URL (call before connect)
     */
    public void configure(String url) {
        String oldUrl = this.wsUrl;
        this.wsUrl = url;
        Log.d(TAG, "URL configured: " + url);

        // If URL changed and we're connected, reconnect
        if (isConnected && oldUrl != null && !oldUrl.equals(url)) {
            disconnect();
            connect();
        } else if (!isConnected && url != null) {
            // If not connected, connect now that we have a URL
            connect();
        }
    }

    public void connect() {
        if (wsUrl == null || wsUrl.isEmpty()) {
            Log.d(TAG, "No URL configured - cannot connect");
            return;
        }

        if (isConnected) {
            Log.d(TAG, "Already connected");
            return;
        }

        Log.d(TAG, "Connecting to WebSocket: " + wsUrl);

        Request request = new Request.Builder()
            .url(wsUrl)
            .build();

        webSocket = client.newWebSocket(request, this);
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "App closing");
            webSocket = null;
        }
        isConnected = false;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        Log.d(TAG, "WebSocket connected");
        isConnected = true;
        reconnectAttempts = 0;

        // Registrar dispositivo
        registerDevice();
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        Log.d(TAG, "WebSocket message received: " + text);

        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type", "");

            switch (type) {
                case "command":
                    handleCommand(message);
                    break;
                case "registered":
                    Log.d(TAG, "Device registered with backend");
                    break;
                case "ping":
                    // Responder pong
                    sendMessage(new JSONObject().put("type", "pong"));
                    break;
                default:
                    Log.d(TAG, "Unknown message type: " + type);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing message", e);
        }
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        Log.d(TAG, "WebSocket closing: " + code + " - " + reason);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        Log.d(TAG, "WebSocket closed: " + code + " - " + reason);
        isConnected = false;
        scheduleReconnect();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        Log.e(TAG, "WebSocket error: " + t.getMessage());
        isConnected = false;
        scheduleReconnect();
    }

    /**
     * Obtiene un ID único del dispositivo basado en hardware (MediaDrm Widevine).
     * Este ID NO cambia con reinstalaciones ni con diferentes firmas de app.
     */
    private String getHardwareDeviceId() {
        try {
            UUID WIDEVINE_UUID = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm mediaDrm = new MediaDrm(WIDEVINE_UUID);
            byte[] deviceIdBytes = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            mediaDrm.close();

            StringBuilder sb = new StringBuilder();
            for (byte b : deviceIdBytes) {
                sb.append(String.format("%02x", b));
            }
            String hardwareId = sb.toString();
            if (hardwareId.length() > 16) {
                hardwareId = hardwareId.substring(0, 16);
            }
            return hardwareId;
        } catch (UnsupportedSchemeException e) {
            Log.w(TAG, "MediaDrm not supported, falling back to ANDROID_ID");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Error getting hardware ID: " + e.getMessage());
            return null;
        }
    }

    private void registerDevice() {
        try {
            // IMPORTANTE: Usar deviceId con prefix mobile_ para que coincida con Firestore
            String deviceId = preferences.getString("device_id", null);
            if (deviceId == null || !deviceId.startsWith("mobile_")) {
                // Primero intentar hardware ID (no cambia con reinstalaciones)
                String hardwareId = getHardwareDeviceId();
                if (hardwareId != null) {
                    deviceId = "mobile_" + hardwareId;
                } else {
                    // Fallback a ANDROID_ID
                    String androidId = android.provider.Settings.Secure.getString(
                        context.getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID
                    );
                    deviceId = "mobile_" + androidId;
                }
                // Guardar para que sea consistente con NativeBridge y Firestore
                preferences.edit().putString("device_id", deviceId).apply();
            }

            // Obtener userId y companyId de la sesion guardada (keys en camelCase)
            String userId = preferences.getString("userId", null);
            String companyId = preferences.getString("companyId", null);
            // EY-CENTRAL 2026-10-08 (seguridad): la credencial de la sesión (POST /api/sessions/login, la guarda session.save)
            String sessionId = preferences.getString("sessionId", null);

            JSONObject register = new JSONObject();
            register.put("type", "register");
            register.put("deviceId", deviceId);
            register.put("platform", "android");
            register.put("userId", userId);
            register.put("companyId", companyId);
            register.put("sessionId", sessionId);

            sendMessage(register);
            Log.d(TAG, "Device registration sent: " + deviceId + " (userId: " + userId + ", companyId: " + companyId + ")");
        } catch (JSONException e) {
            Log.e(TAG, "Error creating register message", e);
        }
    }

    /**
     * Re-registra el dispositivo con el WebSocket (ej: después de login)
     * Llamar cuando cambie el userId/companyId
     */
    public void reRegister() {
        if (isConnected) {
            registerDevice();
        }
    }

    private void sendMessage(JSONObject message) {
        if (webSocket != null && isConnected) {
            webSocket.send(message.toString());
        }
    }

    /**
     * Send a response back to the backend/dashboard
     * Called from NativeBridge after executing a command
     */
    public void sendResponse(JSONObject response) {
        Log.d(TAG, "sendResponse called - webSocket: " + (webSocket != null ? "OK" : "NULL") + ", isConnected: " + isConnected);
        if (webSocket != null && isConnected) {
            String msg = response.toString();
            Log.d(TAG, "Sending response via WebSocket: " + msg);
            boolean sent = webSocket.send(msg);
            Log.d(TAG, "WebSocket send result: " + sent);
        } else {
            Log.w(TAG, "Cannot send response - WebSocket not connected (webSocket=" + (webSocket != null) + ", isConnected=" + isConnected + ")");
        }
    }

    private void handleCommand(JSONObject message) {
        try {
            String module = message.optString("module", "");
            String action = message.optString("action", "");
            JSONObject params = message.optJSONObject("params");
            String commandId = message.optString("commandId", "");

            if (params == null) {
                params = new JSONObject();
            }

            Log.d(TAG, "handleCommand - module: " + module + ", action: " + action + ", commandId: " + commandId);

            // Mapear module.action a la acción del bridge
            String bridgeAction = CMD.get(module + "." + action);

            if (bridgeAction != null) {
                // Crear mensaje para el bridge
                JSONObject bridgeMessage = new JSONObject();
                bridgeMessage.put("action", bridgeAction);
                bridgeMessage.put("payload", params);
                bridgeMessage.put("requestId", commandId);

                // Ejecutar en el bridge
                mainHandler.post(() -> {
                    nativeBridge.handleWebSocketCommand(bridgeMessage.toString()); // EY-CENTRAL 2026-10-08: responde por WS, por id
                });
            } else {
                Log.w(TAG, "No bridge mapping for: " + module + "." + action);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error handling command", e);
        }
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached");
            return;
        }

        reconnectAttempts++;
        long delay = RECONNECT_DELAY_MS * reconnectAttempts;

        Log.d(TAG, "Scheduling reconnect attempt " + reconnectAttempts + " in " + delay + "ms");

        mainHandler.postDelayed(this::connect, delay);
    }

    public boolean isConnected() {
        return isConnected;
    }
}
