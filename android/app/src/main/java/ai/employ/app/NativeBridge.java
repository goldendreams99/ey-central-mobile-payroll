package ai.employ.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.io.File;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Executor;

import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;

public class NativeBridge {
    private static final String TAG = "NativeBridge";
    private static final String PREFS_NAME = "CapacitorStorage";
    private static final String CHANNEL_ID = "default";

    public static final int REQUEST_CAMERA = 2001;
    public static final int REQUEST_GALLERY = 2002;
    public static final int REQUEST_CAMERA_PERMISSION = 2003;
    public static final int REQUEST_MICROPHONE_PERMISSION = 2004;
    public static final int REQUEST_PICK_IMAGES = 2005;
    public static final int REQUEST_PICK_VIDEO = 2006;
    public static final int REQUEST_PICK_FILES = 2007; // files.pick (EY-CENTRAL 2026-10-07)
    private static final long FILES_DEFAULT_MAX_SIZE = 15L * 1024 * 1024;

    // Microphone permission state
    private String pendingMicrophoneRequestId;

    private final FragmentActivity activity;
    private final WebView webView;
    private final Handler mainHandler;
    private final FusedLocationProviderClient fusedLocationClient;
    private final SharedPreferences preferences;

    // Camera state
    private String pendingCameraRequestId;
    private Uri pendingCameraUri;
    private String pendingCameraResultType;
    private String pendingFilesRequestId;   // files.pick
    private long pendingFilesMaxSize = FILES_DEFAULT_MAX_SIZE;
    private int pendingCameraMaxWidth = 0;   // width/height/quality de getPhoto y pickImages (EY-CENTRAL 2026-10-07)
    private int pendingCameraMaxHeight = 0;
    private int pendingCameraQuality = 90;

    // WebSocket for sending responses back to dashboard
    private WebSocketService webSocketService;
    // EY-CENTRAL 2026-10-08: cada orden que llega por WebSocket se recuerda por su id (requestId → módulo.acción).
    // Antes había una sola marca compartida: dos órdenes casi juntas (una de la web, otra del servidor) se pisaban
    // y la respuesta salía por el canal equivocado; además TODA respuesta (también las de la web) se mandaba al
    // servidor, que la reenvía a los demás clientes conectados. Ahora: vino por WS → responde solo por WS.
    private final Map<String, String[]> wsCommands = new HashMap<>();
    // EY-CENTRAL 2026-10-08 (pedido de EY-01): último resultado de cámara/archivos. Si el sistema mata la web
    // mientras el selector está al frente y la página recarga, la web lo recupera con camera.getPendingResult
    // (vale 2 minutos, se borra al leerlo). Contrato en 04_BRIDGE.txt.
    private static final java.util.Set<String> RECOVERABLE_ACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
        "camera.getPhoto", "camera.takePicture", "camera.pickImages", "camera.pickVideo", "files.pick"));
    private static final long PENDING_RESULT_TTL_MS = 120_000L;
    private String recoverableRequestId;
    private String recoverableAction;
    private JSONObject pendingResult;
    private String currentModule;
    private String currentAction;

    public void setWebSocketService(WebSocketService service) {
        this.webSocketService = service;
    }

    public NativeBridge(Activity activity, WebView webView) {
        this.activity = (FragmentActivity) activity;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity);
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Default",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /** Orden que llega por WebSocket (la llama WebSocketService). Responde por WebSocket. */
    public void handleWebSocketCommand(String messageJson) {
        try {
            JSONObject message = new JSONObject(messageJson);
            String action = message.optString("action", "");
            String requestId = message.optString("requestId", "");
            String[] parts = action.split("\\.");
            if (!requestId.isEmpty()) {
                synchronized (wsCommands) {
                    wsCommands.put(requestId, new String[]{ parts.length >= 2 ? parts[0] : action, parts.length >= 2 ? parts[1] : "" });
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing WebSocket command", e);
            return;
        }
        handleBridgeMessage(messageJson);
    }

    // EY-CENTRAL 2026-10-09: la web avisa que ya dibuja fluido (script que inyecta MainActivity al hacerse visible);
    // recién ahí el splash se desvanece. Ver MainActivity.doSmoothTransition.
    @JavascriptInterface
    public void eySplashReady() {
        if (activity instanceof MainActivity) {
            final MainActivity main = (MainActivity) activity;
            mainHandler.post(main::onWebSplashReady);
        }
    }

    @JavascriptInterface
    public void handleBridgeMessage(String messageJson) {
        Log.d(TAG, "Received message: " + messageJson);

        try {
            JSONObject message = new JSONObject(messageJson);
            String action = message.optString("action", "");
            JSONObject payload = message.optJSONObject("payload");
            String requestId = message.optString("requestId", "");

            if (payload == null) {
                payload = new JSONObject();
            }

            executeAction(action, payload, requestId);

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing message", e);
        }
    }

    private void executeAction(String action, JSONObject payload, String requestId) {
        Log.d(TAG, "Executing action: " + action);
        if (RECOVERABLE_ACTIONS.contains(action)) { recoverableRequestId = requestId; recoverableAction = action; }

        // Store module and action for WebSocket response
        String[] parts = action.split("\\.");
        if (parts.length >= 2) {
            currentModule = parts[0];
            currentAction = parts[1];
        } else {
            currentModule = action;
            currentAction = "";
        }

        try {
            switch (action) {
                // ═══════════════════════════════════════════════════════════════
                // GEOLOCATION
                // ═══════════════════════════════════════════════════════════════
                case "geolocation.getCurrentPosition":
                    getCurrentPosition(payload, requestId);
                    break;

                case "geolocation.checkPermissions":
                    checkLocationPermissions(requestId);
                    break;

                case "geolocation.requestPermissions":
                    requestLocationPermissions(requestId);
                    break;

                case "geolocation.watchPosition":
                    watchPosition(payload, requestId);
                    break;

                case "geolocation.clearWatch":
                    clearWatch(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // LOCAL NOTIFICATIONS
                // ═══════════════════════════════════════════════════════════════
                case "notifications.send":
                case "localNotifications.schedule":
                    scheduleNotification(payload, requestId);
                    break;

                case "localNotifications.checkPermissions":
                    checkNotificationPermissions(requestId);
                    break;

                case "localNotifications.requestPermissions":
                    requestNotificationPermissions(requestId);
                    break;

                case "localNotifications.cancel":
                    cancelNotification(payload, requestId);
                    break;

                case "localNotifications.cancelAll":
                    cancelAllNotifications(requestId);
                    break;

                case "localNotifications.getPending":
                    getPendingNotifications(requestId);
                    break;

                case "localNotifications.getDelivered":
                    getDeliveredNotifications(requestId);
                    break;

                case "localNotifications.removeDelivered":
                    removeDeliveredNotification(payload, requestId);
                    break;

                case "localNotifications.removeAllDelivered":
                    removeAllDeliveredNotifications(requestId);
                    break;

                case "localNotifications.createChannel":
                    createNotificationChannel(payload, requestId);
                    break;

                case "localNotifications.deleteChannel":
                    deleteNotificationChannel(payload, requestId);
                    break;

                case "localNotifications.listChannels":
                    listNotificationChannels(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // CAMERA
                // ═══════════════════════════════════════════════════════════════
                case "camera.getPhoto":
                case "camera.takePicture":
                    getPhoto(payload, requestId);
                    break;

                case "camera.pickImages":
                    pickImages(payload, requestId);
                    break;

                case "camera.pickVideo":
                    pickVideo(requestId);
                    break;

                case "camera.checkPermissions":
                    checkCameraPermissions(requestId);
                    break;

                case "camera.requestPermissions":
                    requestCameraPermissions(requestId);
                    break;

                case "camera.getPendingResult":
                    getPendingResult(requestId);
                    break;

                case "share.getPendingFile": // EY-CENTRAL 2026-10-09: archivo compartido a Employ (gasto)
                    getPendingSharedFile(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // MICROPHONE
                // ═══════════════════════════════════════════════════════════════
                case "microphone.checkPermissions":
                    checkMicrophonePermissions(requestId);
                    break;

                case "microphone.requestPermissions":
                    requestMicrophonePermissions(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // BIOMETRICS
                // ═══════════════════════════════════════════════════════════════
                case "biometrics.isAvailable":
                    biometricsIsAvailable(requestId);
                    break;

                case "biometrics.verifyIdentity":
                    biometricsVerifyIdentity(payload, requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // HAPTICS
                // ═══════════════════════════════════════════════════════════════
                case "haptics.vibrate":
                case "haptics.impact":
                    vibrateImpact(payload, requestId);
                    break;

                case "haptics.notification":
                    vibrateNotification(payload, requestId);
                    break;

                case "haptics.selectionStart":
                case "haptics.selectionChanged":
                case "haptics.selectionEnd":
                    vibrateSelection(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // STORE (In-App Review)
                // ═══════════════════════════════════════════════════════════════
                case "store.requestReview":
                    requestStoreReview(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // STATUS BAR
                // ═══════════════════════════════════════════════════════════════
                case "statusBar.setBackgroundColor":
                    setStatusBarColor(payload, requestId);
                    break;

                case "statusBar.setStyle":
                    setStatusBarStyle(payload, requestId);
                    break;

                case "statusBar.show":
                    showStatusBar(requestId);
                    break;

                case "statusBar.hide":
                    hideStatusBar(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // NAVIGATION BAR
                // ═══════════════════════════════════════════════════════════════
                case "navigationBar.setBackgroundColor":
                    setNavigationBarColor(payload, requestId);
                    break;

                case "navigationBar.setStyle":
                    setNavigationBarStyle(payload, requestId);
                    break;

                case "navigationBar.show":
                    showNavigationBar(requestId);
                    break;

                case "navigationBar.hide":
                    hideNavigationBar(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // CLIPBOARD
                // ═══════════════════════════════════════════════════════════════
                case "clipboard.write":
                    clipboardWrite(payload, requestId);
                    break;

                case "clipboard.read":
                    clipboardRead(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // SHARE
                // ═══════════════════════════════════════════════════════════════
                case "share.share":
                    share(payload, requestId);
                    break;

                case "share.shareFile":
                    shareFile(payload, requestId);
                    break;

                case "share.canShare":
                    canShare(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // FILES (EY-CENTRAL 2026-10-07: selector de archivos genérico,
                // pedido de la web para Gastos y Mensajes; espejo de share.shareFile)
                // ═══════════════════════════════════════════════════════════════
                case "files.pick":
                    pickFiles(payload, requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // BROWSER
                // ═══════════════════════════════════════════════════════════════
                case "browser.open":
                    browserOpen(payload, requestId);
                    break;

                case "browser.close":
                    sendResponse(requestId, new JSONObject().put("success", true), null);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // DEVICE
                // ═══════════════════════════════════════════════════════════════
                case "device.getInfo":
                    getDeviceInfo(requestId);
                    break;

                case "device.getBatteryInfo":
                    getBatteryInfo(requestId);
                    break;

                case "device.getId":
                    getDeviceId(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // NETWORK
                // ═══════════════════════════════════════════════════════════════
                case "network.getStatus":
                    getNetworkStatus(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // PREFERENCES (Storage)
                // ═══════════════════════════════════════════════════════════════
                case "preferences.get":
                case "storage.get":
                    preferencesGet(payload, requestId);
                    break;

                case "preferences.set":
                case "storage.set":
                    preferencesSet(payload, requestId);
                    break;

                case "preferences.remove":
                case "storage.remove":
                    preferencesRemove(payload, requestId);
                    break;

                case "preferences.clear":
                case "storage.clear":
                    preferencesClear(requestId);
                    break;

                case "preferences.keys":
                case "storage.keys":
                    preferencesKeys(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // CACHE - Limpiar cache y storage remotamente
                // ═══════════════════════════════════════════════════════════════
                case "cache.clear":
                    cacheClearAll(payload, requestId);
                    break;

                case "cache.clearCache":
                    cacheClearHttpCache(requestId);
                    break;

                case "cache.clearStorage":
                    cacheClearStorage(requestId);
                    break;

                case "cache.clearPreferences":
                    cacheClearPreferences(requestId);
                    break;

                case "cache.reload":
                    cacheReload(requestId);
                    break;

                case "cache.hardReload":
                    cacheHardReload(requestId);
                    break;

                case "cache.clearAndRestart":
                    cacheClearAndRestart(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // SESSION (para guardar userId/companyId localmente después del login)
                // ═══════════════════════════════════════════════════════════════
                case "session.save":
                    sessionSave(payload, requestId);
                    break;

                case "session.get":
                    sessionGet(requestId);
                    break;

                case "session.enableBiometricLogin":
                    enableBiometricLogin(payload, requestId);
                    break;

                case "session.checkBiometricLogin":
                    checkBiometricLoginStatus(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // APP INFO
                // ═══════════════════════════════════════════════════════════════
                case "app.setIcon": // EY-CENTRAL 2026-10-08: ícono por organización
                    appSetIcon(payload, requestId);
                    break;

                case "app.getIcon":
                    appGetIcon(requestId);
                    break;

                case "app.getInfo":
                    getAppInfo(requestId);
                    break;

                case "app.minimize":
                    appMinimize(requestId);
                    break;

                case "app.exit":
                    appExit(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // KEYBOARD
                // ═══════════════════════════════════════════════════════════════
                case "keyboard.show":
                    keyboardShow(requestId);
                    break;

                case "keyboard.hide":
                    keyboardHide(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // SPLASH SCREEN
                // ═══════════════════════════════════════════════════════════════
                case "splashScreen.show":
                    splashScreenShow(requestId);
                    break;

                case "splashScreen.hide":
                    splashScreenHide(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // DEVICE NATIVE INFO
                // ═══════════════════════════════════════════════════════════════
                case "device.getNativeInfo":
                    getDeviceNativeInfo(requestId);
                    break;

                case "device.getDeviceId":
                    getStoredDeviceId(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // CONFIG (Firebase URLs)
                // ═══════════════════════════════════════════════════════════════
                case "config.getUrls":
                    getConfigUrls(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // AUTH / SESSION
                // ═══════════════════════════════════════════════════════════════
                case "auth.clearSession":
                case "session.clear":
                    clearSession(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // ONBOARDING - Sincronizar permisos a Firebase
                // ═══════════════════════════════════════════════════════════════
                case "onboarding.syncToFirebase":
                    syncOnboardingToFirebase(requestId);
                    break;

                case "onboarding.getPermissions":
                    getOnboardingPermissions(requestId);
                    break;

                // ═══════════════════════════════════════════════════════════════
                // PUSH NOTIFICATIONS (FCM)
                // ═══════════════════════════════════════════════════════════════
                case "push.refreshToken":
                case "push.getToken":
                    pushRefreshToken(requestId);
                    break;

                case "push.checkPermissions":
                    pushCheckPermissions(requestId);
                    break;

                case "push.requestPermissions":
                    pushRequestPermissions(requestId);
                    break;

                default:
                    sendResponse(requestId, null, "Acción no soportada: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing action: " + action, e);
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESPONSE HELPER
    // ═══════════════════════════════════════════════════════════════════════════

    private void sendResponse(String requestId, JSONObject result, String error) {
        // Resultado recuperable (cámara/archivos): guardarlo por si la web recarga antes de recibirlo
        if (requestId != null && requestId.equals(recoverableRequestId)) {
            if (error == null && result != null) {
                try {
                    pendingResult = new JSONObject()
                        .put("action", recoverableAction == null ? "" : recoverableAction)
                        .put("requestId", requestId)
                        .put("takenAt", System.currentTimeMillis())
                        .put("result", result);
                } catch (JSONException e) {
                    pendingResult = null;
                }
            }
            recoverableRequestId = null;
            recoverableAction = null;
        }
        // EY-CENTRAL 2026-10-08: si la orden vino por WebSocket, la respuesta vuelve SOLO por WebSocket, con el
        // módulo.acción de ESA orden (no de la última que pasó). Si vino de la web, vuelve solo a la web.
        String[] wsMeta = null;
        if (requestId != null && !requestId.isEmpty()) {
            synchronized (wsCommands) { wsMeta = wsCommands.remove(requestId); }
        }
        if (wsMeta != null) {
            if (webSocketService == null) {
                Log.w(TAG, "Cannot send WS response - webSocketService is null, requestId: " + requestId);
                return;
            }
            try {
                JSONObject wsResponse = new JSONObject();
                wsResponse.put("type", "command_response");
                wsResponse.put("commandId", requestId);
                wsResponse.put("module", wsMeta[0]);
                wsResponse.put("action", wsMeta[1]);
                wsResponse.put("success", error == null);
                wsResponse.put("result", result);
                if (error != null) {
                    wsResponse.put("error", error);
                }
                webSocketService.sendResponse(wsResponse);
                Log.d(TAG, "Response sent to WebSocket: " + wsMeta[0] + "." + wsMeta[1]);
            } catch (Exception e) {
                Log.e(TAG, "Error sending WebSocket response", e);
            }
            return;
        }
        try {
            JSONObject response = new JSONObject();
            response.put("type", "CAPACITOR_RESPONSE");
            response.put("requestId", requestId);
            response.put("result", result);
            response.put("error", error);

            // Llamar al callback si existe (para onboarding)
            String callbackScript = "if(window['__bridge_callback_" + requestId + "']){" +
                "window['__bridge_callback_" + requestId + "'](" + (result != null ? result.toString() : "null") + ");" +
                "}";

            // También enviar postMessage para compatibilidad
            String postMessageScript = "window.postMessage(" + response.toString() + ", '*');";

            mainHandler.post(() -> {
                webView.evaluateJavascript(callbackScript, null);
                webView.evaluateJavascript(postMessageScript, null);
            });


        } catch (JSONException e) {
            Log.e(TAG, "Error sending response", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CAMERA IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // EY-CENTRAL 2026-10-09 (orden del usuario): ARCHIVO COMPARTIDO A EMPLOY → GASTO ("camino simple").
    // El sistema entrega a MainActivity un ACTION_SEND con un content:// (PDF o imagen). Se lee entero acá
    // (tope 25 MB, como files.pick), se guarda 10 minutos y:
    //  - se avisa a la web con el evento NATIVE_EVENT share.received { target, fileName, mimeType, size, receivedAt }
    //    (si la página ya está; en frío el aviso se pierde y por eso...)
    //  - la web, al montar, llama share.getPendingFile → { target, fileName, mimeType, size, data(base64), receivedAt }
    //    o {} si no hay nada. Se borra al leerlo. Contrato en 04_BRIDGE.txt / 05_BRIDGE_API.txt.
    // Lado web (EY-01): abrir "gasto nuevo" con ese archivo como comprobante.
    // ═══════════════════════════════════════════════════════════════════════════
    private static final long PENDING_SHARE_TTL_MS = 600_000L;
    private static final long PENDING_SHARE_MAX_BYTES = 25L * 1024 * 1024;
    private volatile JSONObject pendingSharedFile;

    /** Llamado por MainActivity (onCreate/onNewIntent) con el Uri del ACTION_SEND. */
    public void receiveSharedFile(Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            try {
                JSONObject f = readPickedFile(uri, PENDING_SHARE_MAX_BYTES);
                f.put("target", "expense");
                f.put("receivedAt", System.currentTimeMillis());
                pendingSharedFile = f;
                JSONObject detail = new JSONObject()
                    .put("target", "expense")
                    .put("fileName", f.optString("fileName"))
                    .put("mimeType", f.optString("mimeType"))
                    .put("size", f.optLong("size"))
                    .put("receivedAt", f.optLong("receivedAt"));
                emitNativeEvent("share.received", detail);
                Log.d(TAG, "Shared file received: " + f.optString("fileName") + " (" + f.optLong("size") + " bytes)");
            } catch (Exception e) {
                Log.e(TAG, "Shared file could not be read: " + e.getMessage());
                pendingSharedFile = null;
            }
        }).start();
    }

    /** Archivo compartido aún no caducado (10 min), o {} si no hay. Se borra al leerlo. */
    private void getPendingSharedFile(String requestId) {
        JSONObject p = pendingSharedFile;
        pendingSharedFile = null;
        if (p != null && System.currentTimeMillis() - p.optLong("receivedAt", 0) <= PENDING_SHARE_TTL_MS) {
            sendResponse(requestId, p, null);
        } else {
            sendResponse(requestId, new JSONObject(), null);
        }
    }

    /** Evento nativo a la web: { type: 'NATIVE_EVENT', event, detail } por window.postMessage (mismo contrato que iOS). */
    private void emitNativeEvent(String event, JSONObject detail) {
        try {
            JSONObject payload = new JSONObject().put("type", "NATIVE_EVENT").put("event", event).put("detail", detail);
            String script = "window.postMessage(" + payload.toString() + ", '*');";
            mainHandler.post(() -> webView.evaluateJavascript(script, null));
        } catch (JSONException e) {
            Log.e(TAG, "emitNativeEvent " + event, e);
        }
    }

    /** Último resultado de cámara/archivos aún no caducado (2 min), o {} si no hay. Se borra al leerlo. */
    private void getPendingResult(String requestId) {
        JSONObject p = pendingResult;
        pendingResult = null;
        if (p != null && System.currentTimeMillis() - p.optLong("takenAt", 0) <= PENDING_RESULT_TTL_MS) {
            sendResponse(requestId, p, null);
        } else {
            sendResponse(requestId, new JSONObject(), null);
        }
    }

    private void getPhoto(JSONObject payload, String requestId) {
        String source = payload.optString("source", "Prompt");
        pendingCameraRequestId = requestId;
        pendingCameraResultType = payload.optString("resultType", "Base64");
        readPhotoSizeOptions(payload);

        mainHandler.post(() -> {
            // Check camera permission first
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            if (source.equals("Camera")) {
                launchCamera();
            } else if (source.equals("Photos")) {
                launchGallery();
            } else {
                // Prompt - show chooser
                showImageSourceChooser();
            }
        });
    }

    private void launchCamera() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, "Camera_" + System.currentTimeMillis());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

            pendingCameraUri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            activity.startActivityForResult(intent, REQUEST_CAMERA);
        } catch (Exception e) {
            sendResponse(pendingCameraRequestId, null, "Error launching camera: " + e.getMessage());
            pendingCameraRequestId = null;
        }
    }

    private void launchGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            activity.startActivityForResult(intent, REQUEST_GALLERY);
        } catch (Exception e) {
            sendResponse(pendingCameraRequestId, null, "Error launching gallery: " + e.getMessage());
            pendingCameraRequestId = null;
        }
    }

    private void showImageSourceChooser() {
        try {
            // Create camera intent
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, "Camera_" + System.currentTimeMillis());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            pendingCameraUri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);

            // Create gallery intent
            Intent galleryIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            galleryIntent.setType("image/*");

            // Create chooser
            Intent chooserIntent = Intent.createChooser(galleryIntent, "Seleccionar imagen");
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});

            activity.startActivityForResult(chooserIntent, REQUEST_CAMERA);
        } catch (Exception e) {
            sendResponse(pendingCameraRequestId, null, "Error showing chooser: " + e.getMessage());
            pendingCameraRequestId = null;
        }
    }

    public void handleCameraResult(int requestCode, int resultCode, Intent data) {
        if (pendingCameraRequestId == null) return;

        if (resultCode != Activity.RESULT_OK) {
            sendResponse(pendingCameraRequestId, null, "User cancelled");
            pendingCameraRequestId = null;
            return;
        }

        try {
            // Handle pick multiple images
            if (requestCode == REQUEST_PICK_IMAGES) {
                handlePickImagesResult(data);
                return;
            }

            // Handle pick video
            if (requestCode == REQUEST_PICK_VIDEO) {
                handlePickVideoResult(data);
                return;
            }

            Uri imageUri = null;

            if (requestCode == REQUEST_GALLERY && data != null && data.getData() != null) {
                imageUri = data.getData();
            } else if (requestCode == REQUEST_CAMERA) {
                // Check if data has URI (some devices)
                if (data != null && data.getData() != null) {
                    imageUri = data.getData();
                } else {
                    imageUri = pendingCameraUri;
                }
            }

            if (imageUri == null) {
                sendResponse(pendingCameraRequestId, null, "No image captured");
                pendingCameraRequestId = null;
                return;
            }

            // Process image
            processImage(imageUri);

        } catch (Exception e) {
            sendResponse(pendingCameraRequestId, null, "Error processing image: " + e.getMessage());
            pendingCameraRequestId = null;
        }
    }

    private void handlePickImagesResult(Intent data) {
        try {
            org.json.JSONArray photos = new org.json.JSONArray();

            if (data.getClipData() != null) {
                // Multiple images selected
                ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    JSONObject photo = processImageToJson(uri);
                    if (photo != null) {
                        photos.put(photo);
                    }
                }
            } else if (data.getData() != null) {
                // Single image selected
                Uri uri = data.getData();
                JSONObject photo = processImageToJson(uri);
                if (photo != null) {
                    photos.put(photo);
                }
            }

            JSONObject result = new JSONObject();
            result.put("photos", photos);
            sendResponse(pendingCameraRequestId, result, null);

        } catch (Exception e) {
            sendResponse(pendingCameraRequestId, null, "Error processing images: " + e.getMessage());
        } finally {
            pendingCameraRequestId = null;
        }
    }

    private void handlePickVideoResult(Intent data) {
        try {
            if (data.getData() != null) {
                Uri videoUri = data.getData();
                JSONObject result = new JSONObject();
                result.put("path", videoUri.toString());
                result.put("webPath", videoUri.toString());
                result.put("format", "video");
                sendResponse(pendingCameraRequestId, result, null);
            } else {
                sendResponse(pendingCameraRequestId, null, "No video selected");
            }
        } catch (Exception e) {
            sendResponse(pendingCameraRequestId, null, "Error processing video: " + e.getMessage());
        } finally {
            pendingCameraRequestId = null;
        }
    }

    private JSONObject processImageToJson(Uri uri) {
        try {
            Bitmap bitmap = decodeScaledBitmap(uri);

            if (bitmap == null) return null;

            // Fix rotation
            bitmap = fixImageRotation(uri, bitmap);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, pendingCameraQuality, baos);
            byte[] imageBytes = baos.toByteArray();
            String base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            JSONObject photo = new JSONObject();
            if ("Uri".equals(pendingCameraResultType)) {
                photo.put("path", uri.toString());
                photo.put("webPath", uri.toString());
            } else if ("DataUrl".equals(pendingCameraResultType)) {
                photo.put("dataUrl", "data:image/jpeg;base64," + base64String);
            } else {
                photo.put("base64String", base64String);
            }
            photo.put("format", "jpeg");
            return photo;
        } catch (Exception e) {
            Log.e(TAG, "Error processing image: " + e.getMessage());
            return null;
        }
    }

    private void processImage(Uri uri) {
        try {
            Bitmap bitmap = decodeScaledBitmap(uri);

            if (bitmap == null) {
                sendResponse(pendingCameraRequestId, null, "Could not decode image");
                pendingCameraRequestId = null;
                return;
            }

            // Fix rotation based on EXIF data
            bitmap = fixImageRotation(uri, bitmap);

            // Compress and convert to base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, pendingCameraQuality, baos);
            byte[] imageBytes = baos.toByteArray();
            String base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            JSONObject result = new JSONObject();

            if ("Uri".equals(pendingCameraResultType)) {
                result.put("path", uri.toString());
                result.put("webPath", uri.toString());
            } else if ("DataUrl".equals(pendingCameraResultType)) {
                result.put("dataUrl", "data:image/jpeg;base64," + base64String);
            } else {
                // Base64 (default)
                result.put("base64String", base64String);
            }

            result.put("format", "jpeg");

            sendResponse(pendingCameraRequestId, result, null);

        } catch (Exception e) {
            sendResponse(pendingCameraRequestId, null, "Error processing image: " + e.getMessage());
        } finally {
            pendingCameraRequestId = null;
        }
    }

    private Bitmap fixImageRotation(Uri uri, Bitmap bitmap) {
        try {
            InputStream inputStream = activity.getContentResolver().openInputStream(uri);
            if (inputStream == null) return bitmap;

            ExifInterface exif = new ExifInterface(inputStream);
            inputStream.close();

            int orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            );

            int rotationDegrees = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    rotationDegrees = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    rotationDegrees = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    rotationDegrees = 270;
                    break;
            }

            if (rotationDegrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationDegrees);
                Bitmap rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
                );
                bitmap.recycle();
                return rotatedBitmap;
            }

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error fixing image rotation: " + e.getMessage());
            return bitmap;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EY-CENTRAL 2026-10-07 (pedido 2 de EY-01: pantalla negra con fotos de 48 MP):
    // la web manda width/height/quality en camera.getPhoto y camera.pickImages, pero
    // Android decodificaba a resolución completa y comprimía siempre a 90. Ahora se
    // decodifica con inSampleSize (sin cargar la foto entera en memoria), se ajusta
    // al tope exacto y se comprime con el quality pedido. Sin width/height: igual que antes.
    // ═══════════════════════════════════════════════════════════════════════════

    /** Lee width/height/quality del payload hacia los campos pending (getPhoto y pickImages). */
    private void readPhotoSizeOptions(JSONObject payload) {
        pendingCameraMaxWidth = Math.max(0, payload.optInt("width", 0));
        pendingCameraMaxHeight = Math.max(0, payload.optInt("height", 0));
        int q = payload.optInt("quality", 90);
        pendingCameraQuality = Math.min(100, Math.max(1, q));
    }

    /** Decodifica la imagen ya reducida para que entre en pendingCameraMaxWidth × pendingCameraMaxHeight (0 = sin tope). */
    private Bitmap decodeScaledBitmap(Uri uri) throws Exception {
        int limitW = pendingCameraMaxWidth > 0 ? pendingCameraMaxWidth : Integer.MAX_VALUE;
        int limitH = pendingCameraMaxHeight > 0 ? pendingCameraMaxHeight : Integer.MAX_VALUE;
        BitmapFactory.Options opts = new BitmapFactory.Options();

        if (limitW != Integer.MAX_VALUE || limitH != Integer.MAX_VALUE) {
            // 1) Solo medir
            opts.inJustDecodeBounds = true;
            InputStream probe = activity.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(probe, null, opts);
            if (probe != null) probe.close();
            int w = opts.outWidth, h = opts.outHeight;
            // 2) Potencia de 2 más grande que deja la imagen por encima del tope (el ajuste fino va después)
            int sample = 1;
            if (w > 0 && h > 0) {
                while ((w / (sample * 2)) >= limitW || (h / (sample * 2)) >= limitH) {
                    sample *= 2;
                }
            }
            opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
        }

        InputStream inputStream = activity.getContentResolver().openInputStream(uri);
        Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, opts);
        if (inputStream != null) inputStream.close();
        if (bitmap == null) return null;

        // 3) Ajuste exacto al tope, manteniendo proporción
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        if (bw > limitW || bh > limitH) {
            float scale = Math.min((float) limitW / bw, (float) limitH / bh);
            int nw = Math.max(1, Math.round(bw * scale));
            int nh = Math.max(1, Math.round(bh * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        return bitmap;
    }

    private void checkCameraPermissions(String requestId) {
        try {
            String cameraStatus = "prompt";
            String photosStatus = "granted";

            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraStatus = "granted";
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                cameraStatus = "denied";
            }

            JSONObject result = new JSONObject();
            result.put("camera", cameraStatus);
            result.put("photos", photosStatus);
            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void requestCameraPermissions(String requestId) {
        mainHandler.post(() -> {
            ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CAMERA_PERMISSION);
        });

        try {
            JSONObject result = new JSONObject();
            result.put("camera", "prompt");
            result.put("photos", "granted");
            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void pickImages(JSONObject payload, String requestId) {
        pendingCameraRequestId = requestId;
        pendingCameraResultType = payload.optString("resultType", "Base64");
        readPhotoSizeOptions(payload);

        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                activity.startActivityForResult(Intent.createChooser(intent, "Seleccionar imágenes"), REQUEST_PICK_IMAGES);
            } catch (Exception e) {
                sendResponse(pendingCameraRequestId, null, "Error launching picker: " + e.getMessage());
                pendingCameraRequestId = null;
            }
        });
    }

    private void pickVideo(String requestId) {
        pendingCameraRequestId = requestId;
        pendingCameraResultType = "Uri";

        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*");
                activity.startActivityForResult(Intent.createChooser(intent, "Seleccionar video"), REQUEST_PICK_VIDEO);
            } catch (Exception e) {
                sendResponse(pendingCameraRequestId, null, "Error launching picker: " + e.getMessage());
                pendingCameraRequestId = null;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MICROPHONE IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void checkMicrophonePermissions(String requestId) {
        try {
            String micStatus = "prompt";

            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                micStatus = "granted";
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
                micStatus = "denied";
            }

            JSONObject result = new JSONObject();
            result.put("microphone", micStatus);
            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void requestMicrophonePermissions(String requestId) {
        // Guardar requestId para responder después de que el usuario responda
        pendingMicrophoneRequestId = requestId;

        mainHandler.post(() -> {
            ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_MICROPHONE_PERMISSION);
        });
        // NO respondemos aquí - esperamos a handleMicrophonePermissionResult
    }

    // Llamado desde MainActivity cuando el usuario responde al diálogo de permiso
    public void handleMicrophonePermissionResult(boolean granted) {
        if (pendingMicrophoneRequestId == null) return;

        try {
            JSONObject result = new JSONObject();
            result.put("microphone", granted ? "granted" : "denied");
            sendResponse(pendingMicrophoneRequestId, result, null);
        } catch (JSONException e) {
            sendResponse(pendingMicrophoneRequestId, null, e.getMessage());
        }
        pendingMicrophoneRequestId = null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BIOMETRICS IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void biometricsIsAvailable(String requestId) {
        try {
            BiometricManager biometricManager = BiometricManager.from(activity);
            int canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

            JSONObject result = new JSONObject();

            switch (canAuthenticate) {
                case BiometricManager.BIOMETRIC_SUCCESS:
                    result.put("isAvailable", true);
                    result.put("biometryType", getBiometryType());
                    break;
                case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                    result.put("isAvailable", false);
                    result.put("errorCode", "NO_HARDWARE");
                    break;
                case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                    result.put("isAvailable", false);
                    result.put("errorCode", "HW_UNAVAILABLE");
                    break;
                case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                    result.put("isAvailable", false);
                    result.put("errorCode", "NONE_ENROLLED");
                    break;
                default:
                    result.put("isAvailable", false);
                    result.put("errorCode", "UNKNOWN");
            }

            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private String getBiometryType() {
        // Android doesn't provide specific biometry type easily
        // Return generic "biometric"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return "fingerprint"; // Most common on Android
        }
        return "biometric";
    }

    private void biometricsVerifyIdentity(JSONObject payload, String requestId) {
        String title = payload.optString("title", "Autenticación");
        String subtitle = payload.optString("subtitle", "");
        String description = payload.optString("description", payload.optString("reason", "Verificar identidad"));

        // Obtener deviceId con mobile_ prefix (usando hardware ID)
        String deviceId = preferences.getString("device_id", null);
        if (deviceId == null || !deviceId.startsWith("mobile_")) {
            String hardwareId = getHardwareDeviceId();
            if (hardwareId != null) {
                deviceId = "mobile_" + hardwareId;
            } else {
                String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
                deviceId = "mobile_" + androidId;
            }
            preferences.edit().putString("device_id", deviceId).apply();
        }
        String userId = preferences.getString("userId", null);
        String companyId = preferences.getString("companyId", null);

        final String finalDeviceId = deviceId;
        final String finalUserId = userId;
        final String finalCompanyId = companyId;

        mainHandler.post(() -> {
            try {
                Executor executor = ContextCompat.getMainExecutor(activity);

                BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle.isEmpty() ? null : subtitle)
                    .setDescription(description)
                    .setNegativeButtonText("Cancelar")
                    .build();

                BiometricPrompt biometricPrompt = new BiometricPrompt(activity, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            try {
                                JSONObject response = new JSONObject();
                                response.put("verified", true);
                                response.put("deviceId", finalDeviceId);
                                if (finalUserId != null) {
                                    response.put("userId", finalUserId);
                                }
                                if (finalCompanyId != null) {
                                    response.put("companyId", finalCompanyId);
                                }
                                response.put("timestamp", System.currentTimeMillis());
                                sendResponse(requestId, response, null);
                            } catch (JSONException e) {
                                sendResponse(requestId, null, e.getMessage());
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                            sendResponse(requestId, null, errString.toString());
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // Don't send response yet, user can retry
                            Log.d(TAG, "Biometric authentication failed, user can retry");
                        }
                    });

                biometricPrompt.authenticate(promptInfo);

            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // GEOLOCATION IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void getCurrentPosition(JSONObject payload, String requestId) {
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendResponse(requestId, null, "Location permission not granted");
            return;
        }

        boolean highAccuracy = payload.optBoolean("enableHighAccuracy", true);
        int priority = highAccuracy ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;

        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();

        fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.getToken())
            .addOnSuccessListener(location -> {
                if (location != null) {
                    try {
                        JSONObject coords = new JSONObject();
                        coords.put("latitude", location.getLatitude());
                        coords.put("longitude", location.getLongitude());
                        coords.put("accuracy", location.getAccuracy());
                        coords.put("altitude", location.getAltitude());
                        coords.put("altitudeAccuracy", location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : null);
                        coords.put("speed", location.getSpeed());
                        coords.put("heading", location.getBearing());

                        JSONObject result = new JSONObject();
                        result.put("coords", coords);
                        result.put("timestamp", location.getTime());

                        sendResponse(requestId, result, null);
                    } catch (JSONException e) {
                        sendResponse(requestId, null, e.getMessage());
                    }
                } else {
                    sendResponse(requestId, null, "Could not get location");
                }
            })
            .addOnFailureListener(e -> {
                sendResponse(requestId, null, e.getMessage());
            });
    }

    private void checkLocationPermissions(String requestId) {
        try {
            String status = "prompt";
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                status = "granted";
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
                status = "denied";
            }

            JSONObject result = new JSONObject();
            result.put("location", status);
            result.put("coarseLocation", status);
            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void requestLocationPermissions(String requestId) {
        ActivityCompat.requestPermissions(activity,
            new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
            1001);

        try {
            JSONObject result = new JSONObject();
            result.put("location", "prompt");
            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NOTIFICATIONS IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void scheduleNotification(JSONObject payload, String requestId) {
        try {
            int id = payload.optInt("id", (int) (Math.random() * Integer.MAX_VALUE));
            String title = payload.optString("title", "Notificación");
            String body = payload.optString("body", payload.optString("message", ""));

            NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(payload.optBoolean("autoCancel", true));

            if (payload.has("largeBody")) {
                builder.setStyle(new NotificationCompat.BigTextStyle().bigText(payload.optString("largeBody")));
            }

            NotificationManagerCompat manager = NotificationManagerCompat.from(activity);
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                manager.notify(id, builder.build());
            }

            JSONObject result = new JSONObject();
            JSONObject notif = new JSONObject();
            notif.put("id", id);
            result.put("notifications", new org.json.JSONArray().put(notif));
            sendResponse(requestId, result, null);

        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void checkNotificationPermissions(String requestId) {
        try {
            String status = "granted";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    status = "denied";
                }
            }

            JSONObject result = new JSONObject();
            result.put("display", status);
            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void requestNotificationPermissions(String requestId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                1002);
        }

        try {
            JSONObject result = new JSONObject();
            result.put("display", "granted");
            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void cancelNotification(JSONObject payload, String requestId) {
        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(activity);

            if (payload.has("id")) {
                manager.cancel(payload.optInt("id"));
            } else if (payload.has("notifications")) {
                org.json.JSONArray notifications = payload.getJSONArray("notifications");
                for (int i = 0; i < notifications.length(); i++) {
                    JSONObject notif = notifications.getJSONObject(i);
                    manager.cancel(notif.optInt("id"));
                }
            }

            sendResponse(requestId, new JSONObject(), null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HAPTICS IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void vibrateImpact(JSONObject payload, String requestId) {
        try {
            Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                String style = payload.optString("style", payload.optString("type", "Medium"));
                int duration;
                int amplitude;

                switch (style) {
                    case "Light":
                        duration = 30;
                        amplitude = 50;
                        break;
                    case "Heavy":
                        duration = 50;
                        amplitude = 255;
                        break;
                    default: // Medium
                        duration = 40;
                        amplitude = 150;
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
                } else {
                    vibrator.vibrate(duration);
                }
            }
            sendResponse(requestId, new JSONObject(), null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void vibrateNotification(JSONObject payload, String requestId) {
        try {
            Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(100);
                }
            }
            sendResponse(requestId, new JSONObject(), null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void vibrateSelection(String requestId) {
        try {
            Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(10, 50));
                } else {
                    vibrator.vibrate(10);
                }
            }
            sendResponse(requestId, new JSONObject(), null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STORE (IN-APP REVIEW) IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void requestStoreReview(String requestId) {
        mainHandler.post(() -> {
            try {
                Log.d(TAG, "Requesting In-App Review");
                com.google.android.play.core.review.ReviewManager manager =
                    com.google.android.play.core.review.ReviewManagerFactory.create(activity);

                com.google.android.gms.tasks.Task<com.google.android.play.core.review.ReviewInfo> request =
                    manager.requestReviewFlow();

                request.addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        com.google.android.play.core.review.ReviewInfo reviewInfo = task.getResult();
                        com.google.android.gms.tasks.Task<Void> flow =
                            manager.launchReviewFlow(activity, reviewInfo);

                        flow.addOnCompleteListener(flowTask -> {
                            // Note: We can't know if the user actually reviewed or dismissed
                            // The API intentionally doesn't provide this information
                            Log.d(TAG, "In-App Review flow completed");
                            try {
                                JSONObject result = new JSONObject();
                                result.put("shown", true);
                                result.put("message", "Review dialog shown (result unknown by design)");
                                sendResponse(requestId, result, null);
                            } catch (JSONException e) {
                                sendResponse(requestId, null, e.getMessage());
                            }
                        });
                    } else {
                        Log.e(TAG, "Failed to request review flow");
                        try {
                            JSONObject result = new JSONObject();
                            result.put("shown", false);
                            result.put("message", "Failed to request review flow");
                            sendResponse(requestId, result, null);
                        } catch (JSONException e) {
                            sendResponse(requestId, null, e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error requesting review: " + e.getMessage());
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS BAR IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void setStatusBarColor(JSONObject payload, String requestId) {
        mainHandler.post(() -> {
            try {
                String color = payload.optString("color", "#000000");
                Window window = activity.getWindow();
                // Con edge-to-edge (targetSdk 36) la barra es transparente y el
                // contenido se dibuja POR DEBAJO, asi que setStatusBarColor esta
                // deprecada y no hace nada. Lo que se ve detras de la barra es el
                // fondo de la ventana: pintamos ese. Sin esto queda el
                // windowBackground del tema (negro en modo oscuro).
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    Color.parseColor(color)));
                Log.d(TAG, "StatusBar color aplicado al fondo de ventana: " + color);
                JSONObject result = new JSONObject();
                result.put("applied", true);
                sendResponse(requestId, result, null);
            } catch (Exception e) {
                Log.e(TAG, "Error setting status bar color: " + e.getMessage());
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void setStatusBarStyle(JSONObject payload, String requestId) {
        mainHandler.post(() -> {
            try {
                String style = payload.optString("style", "Dark");
                Window window = activity.getWindow();

                // Asegurar flags necesarios
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ usa WindowInsetsController
                    android.view.WindowInsetsController controller = window.getInsetsController();
                    if (controller != null) {
                        if (style.equals("Light")) {
                            // Iconos oscuros (para fondo claro)
                            controller.setSystemBarsAppearance(
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            );
                            Log.d(TAG, "StatusBar: Set LIGHT (dark icons) via WindowInsetsController");
                        } else {
                            // Iconos claros (para fondo oscuro)
                            controller.setSystemBarsAppearance(
                                0,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            );
                            Log.d(TAG, "StatusBar: Set DARK (light icons) via WindowInsetsController");
                        }
                    } else {
                        Log.w(TAG, "WindowInsetsController is null");
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Android 6-10 usa el método antiguo
                    android.view.View decorView = window.getDecorView();
                    int flags = decorView.getSystemUiVisibility();
                    if (style.equals("Light")) {
                        flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        Log.d(TAG, "StatusBar: Set LIGHT via SystemUiVisibility");
                    } else {
                        flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        Log.d(TAG, "StatusBar: Set DARK via SystemUiVisibility");
                    }
                    decorView.setSystemUiVisibility(flags);
                }
                Log.d(TAG, "StatusBar style set to: " + style);
                sendResponse(requestId, new JSONObject(), null);
            } catch (Exception e) {
                Log.e(TAG, "Error setting status bar style: " + e.getMessage());
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void showStatusBar(String requestId) {
        mainHandler.post(() -> {
            try {
                Window window = activity.getWindow();
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                sendResponse(requestId, new JSONObject(), null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void hideStatusBar(String requestId) {
        mainHandler.post(() -> {
            try {
                Window window = activity.getWindow();
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                sendResponse(requestId, new JSONObject(), null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NAVIGATION BAR IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void setNavigationBarColor(JSONObject payload, String requestId) {
        mainHandler.post(() -> {
            try {
                String color = payload.optString("color", "#000000");
                // Misma razon que setStatusBarColor: con edge-to-edge la barra de
                // navegacion es transparente y setNavigationBarColor esta deprecada
                // y sin efecto en targetSdk 36. Lo pinta la web.
                Log.d(TAG, "NavigationBar color ignorado (edge-to-edge, lo pinta la web): " + color);
                JSONObject result = new JSONObject();
                result.put("applied", false);
                result.put("reason", "edge-to-edge: el color lo pinta el contenido web");
                sendResponse(requestId, result, null);
            } catch (Exception e) {
                Log.e(TAG, "Error setting navigation bar color: " + e.getMessage());
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void setNavigationBarStyle(JSONObject payload, String requestId) {
        mainHandler.post(() -> {
            try {
                String style = payload.optString("style", "Dark");
                Window window = activity.getWindow();

                // Asegurar flags necesarios
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ usa WindowInsetsController
                    android.view.WindowInsetsController controller = window.getInsetsController();
                    if (controller != null) {
                        if (style.equals("Light")) {
                            // Iconos oscuros (para fondo claro)
                            controller.setSystemBarsAppearance(
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                            );
                            Log.d(TAG, "NavigationBar: Set LIGHT (dark icons) via WindowInsetsController");
                        } else {
                            // Iconos claros (para fondo oscuro)
                            controller.setSystemBarsAppearance(
                                0,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                            );
                            Log.d(TAG, "NavigationBar: Set DARK (light icons) via WindowInsetsController");
                        }
                    } else {
                        Log.w(TAG, "WindowInsetsController is null for NavigationBar");
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8-10
                    android.view.View decorView = window.getDecorView();
                    int flags = decorView.getSystemUiVisibility();
                    if (style.equals("Light")) {
                        flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        Log.d(TAG, "NavigationBar: Set LIGHT via SystemUiVisibility");
                    } else {
                        flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        Log.d(TAG, "NavigationBar: Set DARK via SystemUiVisibility");
                    }
                    decorView.setSystemUiVisibility(flags);
                } else {
                    Log.w(TAG, "NavigationBar style not supported on Android < 8.0");
                }
                Log.d(TAG, "=== NavigationBar style set to: " + style + " ===");
                sendResponse(requestId, new JSONObject(), null);
            } catch (Exception e) {
                Log.e(TAG, "Error setting navigation bar style: " + e.getMessage());
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void showNavigationBar(String requestId) {
        mainHandler.post(() -> {
            try {
                Window window = activity.getWindow();
                android.view.View decorView = window.getDecorView();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.view.WindowInsetsController controller = window.getInsetsController();
                    if (controller != null) {
                        controller.show(android.view.WindowInsets.Type.navigationBars());
                        Log.d(TAG, "NavigationBar: show() via WindowInsetsController");
                    }
                } else {
                    // Limpiar todos los flags de ocultamiento
                    int flags = decorView.getSystemUiVisibility();
                    flags &= ~(android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    decorView.setSystemUiVisibility(flags);
                    Log.d(TAG, "NavigationBar: show() via SystemUiVisibility");
                }
                Log.d(TAG, "=== NavigationBar shown ===");
                sendResponse(requestId, new JSONObject(), null);
            } catch (Exception e) {
                Log.e(TAG, "Error showing navigation bar: " + e.getMessage());
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void hideNavigationBar(String requestId) {
        mainHandler.post(() -> {
            try {
                Window window = activity.getWindow();
                android.view.View decorView = window.getDecorView();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.view.WindowInsetsController controller = window.getInsetsController();
                    if (controller != null) {
                        controller.hide(android.view.WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        );
                        Log.d(TAG, "NavigationBar: hide() via WindowInsetsController");
                    }
                } else {
                    int flags = decorView.getSystemUiVisibility();
                    flags |= android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                          | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                    decorView.setSystemUiVisibility(flags);
                    Log.d(TAG, "NavigationBar: hide() via SystemUiVisibility");
                }
                Log.d(TAG, "=== NavigationBar hidden ===");
                sendResponse(requestId, new JSONObject(), null);
            } catch (Exception e) {
                Log.e(TAG, "Error hiding navigation bar: " + e.getMessage());
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CLIPBOARD IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void clipboardWrite(JSONObject payload, String requestId) {
        mainHandler.post(() -> {
            try {
                String text = payload.optString("text", payload.optString("string", ""));
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("text", text);
                clipboard.setPrimaryClip(clip);
                sendResponse(requestId, new JSONObject(), null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void clipboardRead(String requestId) {
        mainHandler.post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                String text = "";
                if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence textSeq = clipboard.getPrimaryClip().getItemAt(0).getText();
                    if (textSeq != null) {
                        text = textSeq.toString();
                    }
                }
                JSONObject result = new JSONObject();
                result.put("type", "text/plain");
                result.put("value", text);
                sendResponse(requestId, result, null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARE IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void share(JSONObject payload, String requestId) {
        mainHandler.post(() -> {
            try {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");

                String title = payload.optString("title", "");
                String text = payload.optString("text", "");
                String url = payload.optString("url", "");

                String shareText = text;
                if (!url.isEmpty()) {
                    shareText = shareText.isEmpty() ? url : shareText + " " + url;
                }

                shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
                shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);

                activity.startActivity(Intent.createChooser(shareIntent, payload.optString("dialogTitle", "Compartir")));

                JSONObject result = new JSONObject();
                result.put("activityType", "");
                sendResponse(requestId, result, null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // APP ICON — app.setIcon / app.getIcon (EY-CENTRAL 2026-10-08, pedido del usuario)
    // Ícono alternativo por organización: el catálogo viaja en la app (activity-alias
    // ".icon_<nombre>" generados por zScripts/generate-app-icons.mjs; ".icon_default" es
    // el ícono de Employ). La web manda el nombre que eligió Soporte en el Manager.
    //   payload: { name: "default" | "<nombre del catálogo>" }
    //   result:  { name, changed: boolean }
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String ICON_ALIAS_PREFIX = ".icon_";

    /** Aliases de ícono declarados en el manifest (nombres cortos: "default", "prisma", …). */
    private java.util.List<String> appIconCatalog() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            android.content.pm.PackageInfo pi = activity.getPackageManager().getPackageInfo(
                activity.getPackageName(), PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (pi.activities != null) {
                for (android.content.pm.ActivityInfo ai : pi.activities) {
                    String full = ai.name; // ai.employ.app.icon_prisma
                    int k = full.lastIndexOf(ICON_ALIAS_PREFIX);
                    if (k >= 0 && ai.targetActivity != null) out.add(full.substring(k + ICON_ALIAS_PREFIX.length()));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "appIconCatalog: " + e.getMessage());
        }
        return out;
    }

    private void appSetIcon(JSONObject payload, String requestId) {
        String name = payload.optString("name", "default").trim().toLowerCase();
        if (name.isEmpty()) name = "default";
        java.util.List<String> catalog = appIconCatalog();
        if (!catalog.contains(name)) {
            sendResponse(requestId, null, "Unknown icon: " + name + " (catalog: " + catalog + ")");
            return;
        }
        String current = preferences.getString("app_icon", "default");
        if (current.equals(name)) {
            try {
                JSONObject r = new JSONObject(); r.put("name", name); r.put("changed", false);
                sendResponse(requestId, r, null);
            } catch (JSONException ignored) {}
            return;
        }
        try {
            PackageManager pm = activity.getPackageManager();
            String pkg = activity.getPackageName();
            // Primero habilitar el nuevo, después deshabilitar el resto: nunca quedar sin launcher.
            pm.setComponentEnabledSetting(new android.content.ComponentName(pkg, pkg + ICON_ALIAS_PREFIX + name),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (String other : catalog) {
                if (other.equals(name)) continue;
                pm.setComponentEnabledSetting(new android.content.ComponentName(pkg, pkg + ICON_ALIAS_PREFIX + other),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
            preferences.edit().putString("app_icon", name).apply();
            Log.d(TAG, "App icon → " + name);
            JSONObject r = new JSONObject(); r.put("name", name); r.put("changed", true);
            sendResponse(requestId, r, null);
        } catch (Exception e) {
            sendResponse(requestId, null, "Cannot change icon: " + e.getMessage());
        }
    }

    private void appGetIcon(String requestId) {
        try {
            JSONObject r = new JSONObject();
            r.put("name", preferences.getString("app_icon", "default"));
            r.put("catalog", new org.json.JSONArray(appIconCatalog()));
            sendResponse(requestId, r, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void shareFile(JSONObject payload, String requestId) {
        new Thread(() -> {
            try {
                String base64Data = payload.optString("data", "");
                String fileName = payload.optString("fileName", "document");
                String mimeType = payload.optString("mimeType", "application/octet-stream");

                // El nombre lo manda la web: nos quedamos solo con el nombre y
                // descartamos cualquier ruta, para que un "../.." no pueda
                // escribir fuera de la cache (path traversal).
                fileName = new File(fileName).getName();
                if (fileName.isEmpty()) fileName = "document";

                if (base64Data.isEmpty()) {
                    sendResponse(requestId, null, "No file data provided");
                    return;
                }

                byte[] fileBytes = Base64.decode(base64Data, Base64.NO_WRAP);

                File cacheDir = new File(activity.getCacheDir(), "shared_files");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                File tempFile = new File(cacheDir, fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
                fos.write(fileBytes);
                fos.close();

                Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    tempFile
                );

                mainHandler.post(() -> {
                    try {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType(mimeType);
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        String title = payload.optString("title", "");
                        if (!title.isEmpty()) {
                            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
                        }

                        activity.startActivity(Intent.createChooser(shareIntent,
                            payload.optString("dialogTitle", "Compartir")));

                        JSONObject result = new JSONObject();
                        result.put("success", true);
                        sendResponse(requestId, result, null);
                    } catch (Exception e) {
                        sendResponse(requestId, null, e.getMessage());
                    }
                });
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILES — files.pick (EY-CENTRAL, 2026-10-07)
    // Selector de archivos del sistema (ACTION_OPEN_DOCUMENT): PDF, documentos,
    // imágenes, lo que la web pida en `accept`. Devuelve cada archivo en base64,
    // espejo inverso de share.shareFile. Contrato en zDocumentation/04_BRIDGE.txt.
    //   payload: { accept?: string[] (mime o ".ext"), multiple?: boolean, maxSize?: number (bytes) }
    //   result:  { files: [{ data: base64, fileName, mimeType, size }] }
    //   error:   "User cancelled" | "File too large: <nombre> (<bytes> > <max>)" | otro
    // ═══════════════════════════════════════════════════════════════════════════

    private void pickFiles(JSONObject payload, String requestId) {
        if (pendingFilesRequestId != null) {
            sendResponse(requestId, null, "File picker already in progress");
            return;
        }
        pendingFilesRequestId = requestId;
        pendingFilesMaxSize = payload.optLong("maxSize", FILES_DEFAULT_MAX_SIZE);
        boolean multiple = payload.optBoolean("multiple", false);
        String[] mimeTypes = acceptToMimeTypes(payload.optJSONArray("accept"));

        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (mimeTypes.length > 0) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
                activity.startActivityForResult(intent, REQUEST_PICK_FILES);
            } catch (Exception e) {
                sendResponse(pendingFilesRequestId, null, "Error launching file picker: " + e.getMessage());
                pendingFilesRequestId = null;
            }
        });
    }

    /** "application/pdf" queda igual; ".pdf" o "pdf" se traduce con MimeTypeMap; lo desconocido se descarta. */
    private String[] acceptToMimeTypes(org.json.JSONArray accept) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (accept == null) return new String[0];
        for (int i = 0; i < accept.length(); i++) {
            String a = accept.optString(i, "").trim().toLowerCase();
            if (a.isEmpty()) continue;
            if (a.contains("/")) {
                out.add(a);
            } else {
                String ext = a.startsWith(".") ? a.substring(1) : a;
                String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null) out.add(mime);
            }
        }
        return out.toArray(new String[0]);
    }

    /** Llamado desde MainActivity.onActivityResult con REQUEST_PICK_FILES. */
    public void handleFilesResult(int resultCode, Intent data) {
        if (pendingFilesRequestId == null) return;
        final String requestId = pendingFilesRequestId;
        pendingFilesRequestId = null;

        if (resultCode != Activity.RESULT_OK || data == null) {
            sendResponse(requestId, null, "User cancelled");
            return;
        }

        final long maxSize = pendingFilesMaxSize;
        new Thread(() -> {
            try {
                java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (uris.isEmpty()) {
                    sendResponse(requestId, null, "No file selected");
                    return;
                }

                org.json.JSONArray files = new org.json.JSONArray();
                for (Uri uri : uris) {
                    files.put(readPickedFile(uri, maxSize));
                }
                JSONObject result = new JSONObject();
                result.put("files", files);
                sendResponse(requestId, result, null);
            } catch (FileTooLargeException e) {
                sendResponse(requestId, null, e.getMessage());
            } catch (Exception e) {
                sendResponse(requestId, null, "Error reading files: " + e.getMessage());
            }
        }).start();
    }

    private static class FileTooLargeException extends Exception {
        FileTooLargeException(String message) { super(message); }
    }

    /** Nombre, tamaño y mime desde el ContentResolver; contenido en base64 (NO_WRAP, igual que shareFile). */
    private JSONObject readPickedFile(Uri uri, long maxSize) throws Exception {
        String fileName = "file";
        long size = -1;
        android.database.Cursor c = activity.getContentResolver().query(uri, null, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    int n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    int s = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (n >= 0 && !c.isNull(n)) fileName = c.getString(n);
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
                }
            } finally {
                c.close();
            }
        }
        // Solo el nombre, nunca una ruta (misma defensa que shareFile).
        fileName = new File(fileName).getName();
        if (fileName.isEmpty()) fileName = "file";

        String mimeType = activity.getContentResolver().getType(uri);
        if (mimeType == null || mimeType.isEmpty()) {
            String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(fileName);
            mimeType = ext != null ? android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase()) : null;
            if (mimeType == null) mimeType = "application/octet-stream";
        }

        if (maxSize > 0 && size > maxSize) {
            throw new FileTooLargeException("File too large: " + fileName + " (" + size + " > " + maxSize + ")");
        }

        java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("Cannot open " + fileName);
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int read;
        long total = 0;
        try {
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (maxSize > 0 && total > maxSize) {
                    throw new FileTooLargeException("File too large: " + fileName + " (" + total + "+ > " + maxSize + ")");
                }
                buf.write(chunk, 0, read);
            }
        } finally {
            in.close();
        }
        byte[] bytes = buf.toByteArray();

        JSONObject f = new JSONObject();
        f.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        f.put("fileName", fileName);
        f.put("mimeType", mimeType);
        f.put("size", bytes.length);
        return f;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BROWSER IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void browserOpen(JSONObject payload, String requestId) {
        mainHandler.post(() -> {
            try {
                String url = payload.optString("url", "");
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                activity.startActivity(browserIntent);
                sendResponse(requestId, new JSONObject().put("success", true), null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEVICE IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void getDeviceInfo(String requestId) {
        try {
            JSONObject result = new JSONObject();
            result.put("platform", "android");
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("model", Build.MODEL);
            result.put("osVersion", Build.VERSION.RELEASE);
            result.put("androidSDKVersion", Build.VERSION.SDK_INT);
            result.put("isVirtual", isEmulator());
            result.put("name", Build.DEVICE);

            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void getBatteryInfo(String requestId) {
        try {
            BatteryManager batteryManager = (BatteryManager) activity.getSystemService(Context.BATTERY_SERVICE);
            int level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean isCharging = batteryManager.isCharging();

            JSONObject result = new JSONObject();
            result.put("batteryLevel", level / 100.0);
            result.put("isCharging", isCharging);

            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    /**
     * Obtiene un ID único del dispositivo basado en hardware (MediaDrm Widevine).
     * Este ID NO cambia con reinstalaciones ni con diferentes firmas de app.
     * Solo cambia con factory reset.
     */
    private String getHardwareDeviceId() {
        try {
            // Widevine UUID - estándar de la industria
            UUID WIDEVINE_UUID = new UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
            MediaDrm mediaDrm = new MediaDrm(WIDEVINE_UUID);
            byte[] deviceIdBytes = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            mediaDrm.close();

            // Convertir a hex string (16 caracteres para mantener formato similar)
            StringBuilder sb = new StringBuilder();
            for (byte b : deviceIdBytes) {
                sb.append(String.format("%02x", b));
            }
            // Tomar los primeros 16 caracteres para mantener consistencia con formato anterior
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

    private void getDeviceId(String requestId) {
        try {
            // Use stored deviceId with mobile_ prefix, or generate new one
            String deviceId = preferences.getString("device_id", null);
            if (deviceId == null || !deviceId.startsWith("mobile_")) {
                // Primero intentar obtener hardware ID (no cambia con reinstalaciones)
                String hardwareId = getHardwareDeviceId();
                if (hardwareId != null) {
                    deviceId = "mobile_" + hardwareId;
                } else {
                    // Fallback a ANDROID_ID si MediaDrm no está disponible
                    String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
                    deviceId = "mobile_" + androidId;
                }
                preferences.edit().putString("device_id", deviceId).apply();
            }

            JSONObject result = new JSONObject();
            result.put("identifier", deviceId);
            result.put("deviceId", deviceId);

            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.MANUFACTURER.contains("Genymotion");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NETWORK IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void getNetworkStatus(String requestId) {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork();

            JSONObject result = new JSONObject();

            if (network != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities != null) {
                    result.put("connected", true);
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        result.put("connectionType", "wifi");
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        result.put("connectionType", "cellular");
                    } else {
                        result.put("connectionType", "unknown");
                    }
                } else {
                    result.put("connected", false);
                    result.put("connectionType", "none");
                }
            } else {
                result.put("connected", false);
                result.put("connectionType", "none");
            }

            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PREFERENCES IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void preferencesGet(JSONObject payload, String requestId) {
        try {
            String key = payload.optString("key", "");
            String value = preferences.getString(key, null);

            JSONObject result = new JSONObject();
            result.put("value", value);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void preferencesSet(JSONObject payload, String requestId) {
        try {
            String key = payload.optString("key", "");
            String value = payload.optString("value", "");

            preferences.edit().putString(key, value).apply();

            sendResponse(requestId, new JSONObject(), null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void preferencesRemove(JSONObject payload, String requestId) {
        try {
            String key = payload.optString("key", "");
            preferences.edit().remove(key).apply();
            sendResponse(requestId, new JSONObject(), null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void preferencesClear(String requestId) {
        try {
            preferences.edit().clear().apply();
            sendResponse(requestId, new JSONObject(), null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SESSION - Guardar/obtener userId y companyId localmente
    // ═══════════════════════════════════════════════════════════════════════════════

    private void sessionSave(JSONObject payload, String requestId) {
        try {
            String userId = payload.optString("userId", null);
            String companyId = payload.optString("companyId", null);
            String sessionId = payload.optString("sessionId", null);

            SharedPreferences.Editor editor = preferences.edit();
            if (userId != null) {
                editor.putString("userId", userId);
            }
            if (companyId != null) {
                editor.putString("companyId", companyId);
            }
            if (sessionId != null) {
                editor.putString("sessionId", sessionId);
            }
            editor.apply();
            PushTokenSync.syncPendingIfNeeded(activity); // EY-CENTRAL 2026-10-08: token FCM pendiente, ahora con sesión

            Log.d(TAG, "Session saved - userId: " + userId + ", companyId: " + companyId);

            // IMPORTANTE: Re-registrar WebSocket con el nuevo userId/companyId
            // para que los comandos filtrados por usuario lleguen correctamente
            if (webSocketService != null) {
                webSocketService.reRegister();
                Log.d(TAG, "WebSocket re-registered with new session data");
            }

            // NOTA: NO pedimos permisos de notificaciones automáticamente aquí
            // La web-app decide cuándo pedir permisos usando push.requestPermissions

            JSONObject result = new JSONObject();
            result.put("saved", true);
            result.put("userId", userId);
            result.put("companyId", companyId);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void sessionGet(String requestId) {
        try {
            String userId = preferences.getString("userId", null);
            String companyId = preferences.getString("companyId", null);
            String sessionId = preferences.getString("sessionId", null);
            String deviceId = preferences.getString("device_id", null);
            String biometricEnabled = preferences.getString("biometric_login_enabled", null);
            String biometricVerified = preferences.getString("biometric_login_verified", null);

            JSONObject result = new JSONObject();
            result.put("userId", userId);
            result.put("companyId", companyId);
            result.put("sessionId", sessionId);
            result.put("deviceId", deviceId);
            result.put("hasSession", userId != null);
            result.put("biometricLoginEnabled", "true".equals(biometricEnabled));
            result.put("biometricLoginVerified", "true".equals(biometricVerified));

            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void enableBiometricLogin(JSONObject payload, String requestId) {
        try {
            boolean enable = payload.optBoolean("enable", true);

            preferences.edit()
                .putString("biometric_login_enabled", enable ? "true" : "false")
                .apply();

            Log.d(TAG, "Biometric login " + (enable ? "enabled" : "disabled"));

            JSONObject result = new JSONObject();
            result.put("enabled", enable);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void checkBiometricLoginStatus(String requestId) {
        try {
            String biometricEnabled = preferences.getString("biometric_login_enabled", null);
            String biometricVerified = preferences.getString("biometric_login_verified", null);
            long verifiedTimestamp = preferences.getLong("biometric_login_timestamp", 0);
            String userId = preferences.getString("userId", null);

            // Verificar si biometría está disponible
            BiometricManager biometricManager = BiometricManager.from(activity);
            int canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            boolean biometricAvailable = (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS);

            JSONObject result = new JSONObject();
            result.put("biometricAvailable", biometricAvailable);
            result.put("biometricLoginEnabled", "true".equals(biometricEnabled));
            result.put("biometricLoginVerified", "true".equals(biometricVerified));
            result.put("verifiedTimestamp", verifiedTimestamp);
            result.put("hasSession", userId != null);

            // Si fue verificado recientemente (últimos 5 minutos), considerarlo válido para auto-login
            boolean recentlyVerified = "true".equals(biometricVerified) &&
                (System.currentTimeMillis() - verifiedTimestamp) < 5 * 60 * 1000;
            result.put("canAutoLogin", recentlyVerified && userId != null);

            sendResponse(requestId, result, null);

            // Limpiar flag de verificación después de consultarlo
            if ("true".equals(biometricVerified)) {
                preferences.edit().remove("biometric_login_verified").apply();
            }

        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // APP INFO IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void getAppInfo(String requestId) {
        try {
            JSONObject result = new JSONObject();
            result.put("platform", "android");
            result.put("isNative", true);

            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEVICE NATIVE INFO IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void getDeviceNativeInfo(String requestId) {
        try {
            // Get or generate device ID with mobile_ prefix (usando hardware ID)
            String deviceId = preferences.getString("device_id", null);
            if (deviceId == null || !deviceId.startsWith("mobile_")) {
                String hardwareId = getHardwareDeviceId();
                if (hardwareId != null) {
                    deviceId = "mobile_" + hardwareId;
                } else {
                    String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
                    if (androidId == null || androidId.isEmpty()) {
                        androidId = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
                    }
                    deviceId = "mobile_" + androidId;
                }
                preferences.edit().putString("device_id", deviceId).apply();
            }

            // Screen info
            JSONObject screen = new JSONObject();
            screen.put("width", activity.getResources().getDisplayMetrics().widthPixels);
            screen.put("height", activity.getResources().getDisplayMetrics().heightPixels);
            screen.put("pixelRatio", activity.getResources().getDisplayMetrics().density);

            // Device info
            JSONObject device = new JSONObject();
            device.put("platform", "android");
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            device.put("osVersion", Build.VERSION.RELEASE);
            device.put("sdkVersion", Build.VERSION.SDK_INT);
            device.put("isVirtual", isEmulator());
            device.put("screen", screen);
            device.put("locale", java.util.Locale.getDefault().toLanguageTag());
            device.put("timezone", TimeZone.getDefault().getID());

            // Battery info
            JSONObject battery = new JSONObject();
            try {
                BatteryManager batteryManager = (BatteryManager) activity.getSystemService(Context.BATTERY_SERVICE);
                int level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                boolean isCharging = batteryManager.isCharging();
                battery.put("level", level);
                battery.put("isCharging", isCharging);
            } catch (Exception e) {
                battery.put("level", -1);
                battery.put("isCharging", false);
            }

            // Network info
            JSONObject network = new JSONObject();
            try {
                ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                    if (capabilities != null) {
                        network.put("connected", true);
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            network.put("connectionType", "wifi");
                        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            network.put("connectionType", "cellular");
                        } else {
                            network.put("connectionType", "other");
                        }
                    } else {
                        network.put("connected", false);
                        network.put("connectionType", "none");
                    }
                } else {
                    network.put("connected", false);
                    network.put("connectionType", "none");
                }
            } catch (Exception e) {
                network.put("connected", false);
                network.put("connectionType", "unknown");
            }

            // App info with real version
            JSONObject app = new JSONObject();
            try {
                String versionName = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
                int versionCode = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionCode;
                app.put("version", versionName);
                app.put("build", versionCode);
                app.put("capabilities", new org.json.JSONArray().put("files.pick").put("camera.getPendingResult").put("share.receive")); // EY-CENTRAL 2026-10-08: para nativeAppSupports; share.receive desde el 389
            } catch (Exception e) {
                app.put("version", "1.0.0");
                app.put("build", 1);
            }

            // Get FCM token asynchronously
            final String finalDeviceId = deviceId;
            final JSONObject finalDevice = device;
            final JSONObject finalApp = app;
            final JSONObject finalBattery = battery;
            final JSONObject finalNetwork = network;

            FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(fcmToken -> {
                    try {
                        JSONObject result = new JSONObject();
                        result.put("deviceId", finalDeviceId);
                        result.put("fcmToken", fcmToken);
                        result.put("device", finalDevice);
                        result.put("battery", finalBattery);
                        result.put("network", finalNetwork);
                        result.put("app", finalApp);
                        Log.d(TAG, "FCM Token obtained: " + (fcmToken != null ? fcmToken.substring(0, 20) + "..." : "null"));
                        sendResponse(requestId, result, null);
                    } catch (JSONException e) {
                        sendResponse(requestId, null, e.getMessage());
                    }
                })
                .addOnFailureListener(e -> {
                    try {
                        JSONObject result = new JSONObject();
                        result.put("deviceId", finalDeviceId);
                        result.put("fcmToken", null);
                        result.put("device", finalDevice);
                        result.put("battery", finalBattery);
                        result.put("network", finalNetwork);
                        result.put("app", finalApp);
                        Log.e(TAG, "Failed to get FCM token: " + e.getMessage());
                        sendResponse(requestId, result, null);
                    } catch (JSONException ex) {
                        sendResponse(requestId, null, ex.getMessage());
                    }
                });

        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void getStoredDeviceId(String requestId) {
        try {
            String deviceId = preferences.getString("device_id", null);

            JSONObject result = new JSONObject();
            result.put("deviceId", deviceId);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIG (Firebase URLs) IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void getConfigUrls(String requestId) {
        try {
            String webappUrl = preferences.getString("cached_webapp_url", null);
            String apiUrl = preferences.getString("cached_api_url", null);
            String wsUrl = preferences.getString("cached_ws_url", null);

            JSONObject result = new JSONObject();
            result.put("webapp", webappUrl);
            result.put("api", apiUrl);
            result.put("ws", wsUrl);

            Log.d(TAG, "[Config] Returning URLs - api: " + apiUrl);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTH / SESSION IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void clearSession(String requestId) {
        mainHandler.post(() -> {
            try {
                Log.d(TAG, "Clearing all session data (cookies, cache, storage)...");

                // 1. Borrar TODAS las cookies
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.removeAllCookies(success -> {
                    Log.d(TAG, "Cookies removed: " + success);
                });
                cookieManager.removeSessionCookies(success -> {
                    Log.d(TAG, "Session cookies removed: " + success);
                });
                cookieManager.flush();

                // 2. Borrar cache del WebView
                webView.clearCache(true);

                // 3. Borrar historial
                webView.clearHistory();

                // 4. Borrar form data
                webView.clearFormData();

                // 5. Borrar localStorage y sessionStorage via JavaScript
                webView.evaluateJavascript(
                    "try { localStorage.clear(); sessionStorage.clear(); } catch(e) { console.log('Storage clear error:', e); }",
                    null
                );

                // 6. Borrar userId/companyId/sessionId de SharedPreferences
                preferences.edit()
                    .remove("userId")
                    .remove("companyId")
                    .remove("sessionId")
                    .apply();

                Log.d(TAG, "All session data cleared successfully (including userId/companyId)");

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "Session cleared successfully");
                sendResponse(requestId, result, null);

            } catch (Exception e) {
                Log.e(TAG, "Error clearing session", e);
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ONBOARDING IMPLEMENTATION - Sincronizar permisos a Firebase
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String ONBOARDING_KEY = "permission_onboarding_completed";
    private static final String ONBOARDING_PERMISSIONS_KEY = "onboarding_permissions";

    private void syncOnboardingToFirebase(String requestId) {
        try {
            // 1. Obtener permisos de SharedPreferences
            String permissionsJson = preferences.getString(ONBOARDING_PERMISSIONS_KEY, "{}");

            // 2. Obtener deviceId con mobile_ prefix (usando hardware ID)
            String deviceId = preferences.getString("device_id", null);
            if (deviceId == null || !deviceId.startsWith("mobile_")) {
                String hardwareId = getHardwareDeviceId();
                if (hardwareId != null) {
                    deviceId = "mobile_" + hardwareId;
                } else {
                    String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
                    deviceId = "mobile_" + androidId;
                }
                preferences.edit().putString("device_id", deviceId).apply();
            }

            if (deviceId == null || deviceId.isEmpty()) {
                sendResponse(requestId, null, "No deviceId available");
                return;
            }

            // 3. Parsear permisos
            JSONObject permissionsObj = new JSONObject(permissionsJson);
            Map<String, Object> permissionsMap = new HashMap<>();
            java.util.Iterator<String> keys = permissionsObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                permissionsMap.put(key, permissionsObj.getString(key));
            }

            // 4. Preparar datos para Firestore (solo si hay permisos)
            Map<String, Object> data = new HashMap<>();
            if (!permissionsMap.isEmpty()) {
                data.put("permissions", permissionsMap);
                data.put("permissionsSyncedAt", new java.util.Date().toInstant().toString());
            }

            // 5. Escribir a Firestore con merge (solo si hay datos)
            if (data.isEmpty()) {
                Log.d(TAG, "No permissions to sync - skipping Firestore write");
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("skipped", true);
                result.put("reason", "No permissions to sync");
                sendResponse(requestId, result, null);
                return;
            }

            final String finalDeviceId = deviceId;
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.collection("devices").document(deviceId)
                .set(data, SetOptions.merge())
                .addOnSuccessListener(aVoid -> {
                    try {
                        Log.d(TAG, "Permissions synced to Firebase: " + finalDeviceId);
                        JSONObject result = new JSONObject();
                        result.put("success", true);
                        result.put("deviceId", finalDeviceId);
                        result.put("permissions", permissionsObj);
                        sendResponse(requestId, result, null);
                    } catch (JSONException e) {
                        sendResponse(requestId, null, e.getMessage());
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error syncing to Firebase", e);
                    sendResponse(requestId, null, e.getMessage());
                });

        } catch (Exception e) {
            Log.e(TAG, "Error in syncOnboardingToFirebase", e);
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void getOnboardingPermissions(String requestId) {
        try {
            String permissionsJson = preferences.getString(ONBOARDING_PERMISSIONS_KEY, "{}");
            String completed = preferences.getString(ONBOARDING_KEY, "false");

            JSONObject result = new JSONObject();
            result.put("completed", "true".equals(completed));
            result.put("permissions", new JSONObject(permissionsJson));

            sendResponse(requestId, result, null);

        } catch (Exception e) {
            Log.e(TAG, "Error in getOnboardingPermissions", e);
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUSH NOTIFICATIONS (FCM) IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void pushRefreshToken(String requestId) {
        Log.d(TAG, "=== pushRefreshToken START === requestId: " + requestId);

        // Capturar module/action porque son async y pueden cambiar
        final String savedModule = currentModule;
        final String savedAction = currentAction;

        try {
            // Verificar permisos (solo en Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int permStatus = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS);
                Log.d(TAG, "Push permission status: " + permStatus + " (GRANTED=" + PackageManager.PERMISSION_GRANTED + ")");

                if (permStatus != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Push permissions not granted - returning error");
                    JSONObject result = new JSONObject();
                    result.put("success", false);
                    result.put("error", "Push permissions not granted");
                    result.put("needsPermission", true);
                    sendResponse(requestId, result, null);
                    return;
                }
            }

            // Obtener deviceId con mobile_ prefix (usando hardware ID)
            String storedDeviceId = preferences.getString("device_id", null);
            if (storedDeviceId == null || !storedDeviceId.startsWith("mobile_")) {
                String hardwareId = getHardwareDeviceId();
                if (hardwareId != null) {
                    storedDeviceId = "mobile_" + hardwareId;
                } else {
                    String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
                    storedDeviceId = "mobile_" + androidId;
                }
                preferences.edit().putString("device_id", storedDeviceId).apply();
            }
            final String deviceId = storedDeviceId;
            Log.d(TAG, "DeviceId: " + deviceId);

            // Obtener token fresco de FCM
            Log.d(TAG, "Calling FirebaseMessaging.getInstance().getToken()...");
            FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(fcmToken -> {
                    Log.d(TAG, "=== FCM TOKEN SUCCESS === token: " + (fcmToken != null ? fcmToken.substring(0, Math.min(20, fcmToken.length())) + "..." : "null"));

                    // Restaurar module/action para el response
                    currentModule = savedModule;
                    currentAction = savedAction;

                    // Actualizar token en Firebase Firestore
                    if (deviceId != null && fcmToken != null) {
                        updateFcmTokenInFirestore(deviceId, fcmToken);
                    }

                    try {
                        JSONObject result = new JSONObject();
                        result.put("success", true);
                        result.put("token", fcmToken);
                        result.put("deviceId", deviceId);
                        Log.d(TAG, "Sending success response for push.refreshToken");
                        sendResponse(requestId, result, null);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON error in success callback", e);
                        sendResponse(requestId, null, e.getMessage());
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "=== FCM TOKEN FAILURE === error: " + e.getMessage(), e);

                    // Restaurar module/action para el response
                    currentModule = savedModule;
                    currentAction = savedAction;

                    try {
                        JSONObject result = new JSONObject();
                        result.put("success", false);
                        result.put("error", e.getMessage());
                        Log.d(TAG, "Sending failure response for push.refreshToken");
                        sendResponse(requestId, result, null);
                    } catch (JSONException ex) {
                        Log.e(TAG, "JSON error in failure callback", ex);
                        sendResponse(requestId, null, ex.getMessage());
                    }
                });

            Log.d(TAG, "FirebaseMessaging.getToken() called - waiting for callback...");

        } catch (Exception e) {
            Log.e(TAG, "=== pushRefreshToken EXCEPTION ===", e);
            try {
                JSONObject result = new JSONObject();
                result.put("success", false);
                result.put("error", "Exception: " + e.getMessage());
                sendResponse(requestId, result, null);
            } catch (JSONException ex) {
                sendResponse(requestId, null, e.getMessage());
            }
        }
    }

    private void updateFcmTokenInFirestore(String deviceId, String fcmToken) {
        Log.d(TAG, "updateFcmTokenInFirestore - deviceId: " + deviceId);
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("fcmToken", fcmToken);
            // Usar formato ISO sin toInstant() para compatibilidad con Android < 8
            data.put("fcmTokenUpdatedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(new java.util.Date()));

            FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.collection("devices").document(deviceId)
                .set(data, SetOptions.merge())
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "FCM token updated in Firestore for device: " + deviceId);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error updating FCM token in Firestore", e);
                });
        } catch (Exception e) {
            Log.e(TAG, "Error in updateFcmTokenInFirestore", e);
        }
    }

    private void pushCheckPermissions(String requestId) {
        try {
            String status = "granted";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                        status = "denied";
                    } else {
                        status = "prompt";
                    }
                }
            }

            JSONObject result = new JSONObject();
            result.put("receive", status);
            sendResponse(requestId, result, null);
        } catch (JSONException e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void pushRequestPermissions(String requestId) {
        Log.d(TAG, "=== pushRequestPermissions START ===");

        // Capturar module/action para el response async
        final String savedModule = currentModule;
        final String savedAction = currentAction;

        // Obtener deviceId con mobile_ prefix (usando hardware ID)
        String storedDeviceId = preferences.getString("device_id", null);
        if (storedDeviceId == null || !storedDeviceId.startsWith("mobile_")) {
            String hardwareId = getHardwareDeviceId();
            if (hardwareId != null) {
                storedDeviceId = "mobile_" + hardwareId;
            } else {
                String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
                storedDeviceId = "mobile_" + androidId;
            }
            preferences.edit().putString("device_id", storedDeviceId).apply();
        }
        final String deviceId = storedDeviceId;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requiere permiso explícito
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                // Ya tiene permisos, obtener token directamente
                Log.d(TAG, "Permissions already granted, getting token...");
                getTokenAndRespond(requestId, savedModule, savedAction, deviceId, "granted");
            } else {
                // Pedir permisos
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission...");
                pendingPushRequestId = requestId;
                pendingPushModule = savedModule;
                pendingPushAction = savedAction;
                ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_PUSH_PERMISSION);
            }
        } else {
            // Android < 13 no necesita permiso explícito, obtener token directo
            Log.d(TAG, "Android < 13, getting token directly...");
            getTokenAndRespond(requestId, savedModule, savedAction, deviceId, "granted");
        }
    }

    // Variables para manejar el callback de permisos
    private String pendingPushRequestId;
    private String pendingPushModule;
    private String pendingPushAction;
    private static final int REQUEST_PUSH_PERMISSION = 1003;

    // Llamado desde MainActivity cuando se recibe el resultado del permiso
    public void onPushPermissionResult(boolean granted) {
        Log.d(TAG, "onPushPermissionResult: granted=" + granted);
        if (pendingPushRequestId != null) {
            // Obtener deviceId con mobile_ prefix (usando hardware ID)
            String deviceId = preferences.getString("device_id", null);
            if (deviceId == null || !deviceId.startsWith("mobile_")) {
                String hardwareId = getHardwareDeviceId();
                if (hardwareId != null) {
                    deviceId = "mobile_" + hardwareId;
                } else {
                    String androidId = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ANDROID_ID);
                    deviceId = "mobile_" + androidId;
                }
                preferences.edit().putString("device_id", deviceId).apply();
            }
            if (granted) {
                getTokenAndRespond(pendingPushRequestId, pendingPushModule, pendingPushAction, deviceId, "granted");
            } else {
                // Permiso denegado
                currentModule = pendingPushModule;
                currentAction = pendingPushAction;
                try {
                    JSONObject result = new JSONObject();
                    result.put("receive", "denied");
                    result.put("token", null);
                    sendResponse(pendingPushRequestId, result, null);
                } catch (JSONException e) {
                    sendResponse(pendingPushRequestId, null, e.getMessage());
                }
            }
            pendingPushRequestId = null;
            pendingPushModule = null;
            pendingPushAction = null;
        }
    }

    private void getTokenAndRespond(String requestId, String module, String action, String deviceId, String permissionStatus) {
        Log.d(TAG, "getTokenAndRespond - FORCING token creation...");

        // 1. Habilitar auto-init (necesario para Android 13+)
        FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        Log.d(TAG, "Auto-init enabled");

        // 2. Suscribir a topic dummy para forzar la generación del token
        FirebaseMessaging.getInstance().subscribeToTopic("employ_all")
            .addOnCompleteListener(subscribeTask -> {
                Log.d(TAG, "Subscribed to topic: " + subscribeTask.isSuccessful());

                // 3. Ahora obtener el token
                FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(fcmToken -> {
                        Log.d(TAG, "FCM token obtained: " + (fcmToken != null ? fcmToken.substring(0, 20) + "..." : "null"));

                        // Guardar en Firebase
                        if (deviceId != null && fcmToken != null) {
                            updateFcmTokenInFirestore(deviceId, fcmToken);
                        }

                        currentModule = module;
                        currentAction = action;

                        try {
                            JSONObject result = new JSONObject();
                            result.put("receive", permissionStatus);
                            result.put("token", fcmToken);
                            result.put("deviceId", deviceId);
                            sendResponse(requestId, result, null);
                        } catch (JSONException e) {
                            sendResponse(requestId, null, e.getMessage());
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "getToken failed, trying deleteToken + getToken...");

                        // 4. Si falla, intentar delete + get
                        FirebaseMessaging.getInstance().deleteToken()
                            .addOnCompleteListener(deleteTask -> {
                                Log.d(TAG, "Token deleted: " + deleteTask.isSuccessful());

                                // Esperar un momento y reintentar
                                mainHandler.postDelayed(() -> {
                                    FirebaseMessaging.getInstance().getToken()
                                        .addOnSuccessListener(newToken -> {
                                            Log.d(TAG, "FCM token (retry): " + (newToken != null ? newToken.substring(0, 20) + "..." : "null"));

                                            if (deviceId != null && newToken != null) {
                                                updateFcmTokenInFirestore(deviceId, newToken);
                                            }

                                            currentModule = module;
                                            currentAction = action;

                                            try {
                                                JSONObject result = new JSONObject();
                                                result.put("receive", permissionStatus);
                                                result.put("token", newToken);
                                                result.put("deviceId", deviceId);
                                                sendResponse(requestId, result, null);
                                            } catch (JSONException ex) {
                                                sendResponse(requestId, null, ex.getMessage());
                                            }
                                        })
                                        .addOnFailureListener(e2 -> {
                                            Log.e(TAG, "All attempts failed: " + e2.getMessage());
                                            currentModule = module;
                                            currentAction = action;

                                            try {
                                                JSONObject result = new JSONObject();
                                                result.put("receive", permissionStatus);
                                                result.put("token", null);
                                                result.put("error", "Token generation failed: " + e2.getMessage());
                                                sendResponse(requestId, result, null);
                                            } catch (JSONException ex) {
                                                sendResponse(requestId, null, ex.getMessage());
                                            }
                                        });
                                }, 1000);
                            });
                    });
            });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE MANAGEMENT - Limpiar cache y storage remotamente
    // ═══════════════════════════════════════════════════════════════════════════

    private void cacheClearAll(JSONObject payload, String requestId) {
        mainHandler.post(() -> {
            try {
                Log.d(TAG, "=== CACHE CLEAR ALL ===");
                boolean reload = true;
                if (payload != null && payload.has("reload")) {
                    reload = payload.getBoolean("reload");
                }

                // 1. Limpiar cookies
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.removeAllCookies(null);
                cookieManager.removeSessionCookies(null);
                cookieManager.flush();

                // 2. Limpiar cache del WebView
                webView.clearCache(true);
                webView.clearHistory();
                webView.clearFormData();

                // 3. Limpiar localStorage y sessionStorage
                webView.evaluateJavascript(
                    "try { localStorage.clear(); sessionStorage.clear(); console.log('[Cache] Storage cleared'); } catch(e) {}",
                    null
                );

                // 4. Limpiar SharedPreferences (excepto device_id)
                String deviceId = preferences.getString("device_id", null);
                preferences.edit().clear().apply();
                if (deviceId != null) {
                    preferences.edit().putString("device_id", deviceId).apply();
                }

                Log.d(TAG, "All cache cleared successfully, reload=" + reload);

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "All cache cleared");
                sendResponse(requestId, result, null);

                // Reload si se solicita
                if (reload) {
                    mainHandler.postDelayed(() -> {
                        webView.reload();
                    }, 300);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error clearing cache", e);
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void cacheClearStorage(String requestId) {
        mainHandler.post(() -> {
            try {
                Log.d(TAG, "=== CACHE CLEAR STORAGE ===");

                // Limpiar localStorage y sessionStorage
                webView.evaluateJavascript(
                    "try { localStorage.clear(); sessionStorage.clear(); console.log('[Cache] Storage cleared'); } catch(e) {}",
                    null
                );

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "Web storage cleared");
                sendResponse(requestId, result, null);

            } catch (Exception e) {
                Log.e(TAG, "Error clearing storage", e);
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void cacheClearPreferences(String requestId) {
        mainHandler.post(() -> {
            try {
                Log.d(TAG, "=== CACHE CLEAR PREFERENCES ===");

                // Guardar device_id antes de limpiar
                String deviceId = preferences.getString("device_id", null);

                // Limpiar SharedPreferences
                preferences.edit().clear().apply();

                // Restaurar device_id
                if (deviceId != null) {
                    preferences.edit().putString("device_id", deviceId).apply();
                }

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "Preferences cleared");
                sendResponse(requestId, result, null);

            } catch (Exception e) {
                Log.e(TAG, "Error clearing preferences", e);
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void cacheReload(String requestId) {
        mainHandler.post(() -> {
            try {
                Log.d(TAG, "=== CACHE RELOAD ===");

                webView.reload();

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "App reloaded");
                sendResponse(requestId, result, null);

            } catch (Exception e) {
                Log.e(TAG, "Error reloading", e);
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void cacheHardReload(String requestId) {
        mainHandler.post(() -> {
            try {
                Log.d(TAG, "=== CACHE HARD RELOAD ===");

                // Limpiar cache primero
                webView.clearCache(true);

                // Luego reload
                webView.reload();

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "Hard reload complete");
                sendResponse(requestId, result, null);

            } catch (Exception e) {
                Log.e(TAG, "Error hard reloading", e);
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NOTIFICATION METHODS - Adicionales
    // ═══════════════════════════════════════════════════════════════════════════

    private void cancelAllNotifications(String requestId) {
        try {
            NotificationManagerCompat.from(activity).cancelAll();
            JSONObject result = new JSONObject();
            result.put("success", true);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void getPendingNotifications(String requestId) {
        try {
            // Android no tiene API directa para esto, retornamos array vacío
            JSONObject result = new JSONObject();
            result.put("notifications", new org.json.JSONArray());
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void getDeliveredNotifications(String requestId) {
        try {
            JSONObject result = new JSONObject();
            result.put("notifications", new org.json.JSONArray());
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void removeDeliveredNotification(JSONObject payload, String requestId) {
        try {
            int id = payload.optInt("id", 0);
            NotificationManagerCompat.from(activity).cancel(id);
            JSONObject result = new JSONObject();
            result.put("success", true);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void removeAllDeliveredNotifications(String requestId) {
        try {
            NotificationManagerCompat.from(activity).cancelAll();
            JSONObject result = new JSONObject();
            result.put("success", true);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void createNotificationChannel(JSONObject payload, String requestId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String id = payload.optString("id", "default");
                String name = payload.optString("name", "Default");
                String description = payload.optString("description", "");
                int importance = payload.optInt("importance", NotificationManager.IMPORTANCE_DEFAULT);

                NotificationChannel channel = new NotificationChannel(id, name, importance);
                channel.setDescription(description);

                NotificationManager manager = activity.getSystemService(NotificationManager.class);
                manager.createNotificationChannel(channel);
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void deleteNotificationChannel(JSONObject payload, String requestId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String id = payload.optString("id", "");
                NotificationManager manager = activity.getSystemService(NotificationManager.class);
                manager.deleteNotificationChannel(id);
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void listNotificationChannels(String requestId) {
        try {
            org.json.JSONArray channels = new org.json.JSONArray();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager manager = activity.getSystemService(NotificationManager.class);
                for (NotificationChannel channel : manager.getNotificationChannels()) {
                    JSONObject ch = new JSONObject();
                    ch.put("id", channel.getId());
                    ch.put("name", channel.getName());
                    ch.put("importance", channel.getImportance());
                    channels.put(ch);
                }
            }
            JSONObject result = new JSONObject();
            result.put("channels", channels);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GEOLOCATION METHODS - Adicionales
    // ═══════════════════════════════════════════════════════════════════════════

    private void watchPosition(JSONObject payload, String requestId) {
        // Por ahora, watchPosition simplemente obtiene la posición actual
        // Una implementación completa requeriría callbacks continuos
        getCurrentPosition(payload, requestId);
    }

    private void clearWatch(String requestId) {
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PREFERENCES METHODS - Adicionales
    // ═══════════════════════════════════════════════════════════════════════════

    private void preferencesKeys(String requestId) {
        try {
            org.json.JSONArray keys = new org.json.JSONArray();
            for (String key : preferences.getAll().keySet()) {
                keys.put(key);
            }
            JSONObject result = new JSONObject();
            result.put("keys", keys);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARE METHODS - Adicionales
    // ═══════════════════════════════════════════════════════════════════════════

    private void canShare(String requestId) {
        try {
            JSONObject result = new JSONObject();
            result.put("value", true); // Android siempre puede compartir
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE METHODS - Adicionales
    // ═══════════════════════════════════════════════════════════════════════════

    private void cacheClearHttpCache(String requestId) {
        mainHandler.post(() -> {
            try {
                webView.clearCache(true);
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "HTTP cache cleared");
                sendResponse(requestId, result, null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void cacheClearAndRestart(String requestId) {
        mainHandler.post(() -> {
            try {
                Log.d(TAG, "=== CACHE CLEAR AND RESTART ===");

                // 1. Limpiar cookies
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.removeAllCookies(null);
                cookieManager.removeSessionCookies(null);
                cookieManager.flush();

                // 2. Limpiar cache del WebView
                webView.clearCache(true);
                webView.clearHistory();
                webView.clearFormData();

                // 3. Limpiar localStorage y sessionStorage
                webView.evaluateJavascript(
                    "try { localStorage.clear(); sessionStorage.clear(); } catch(e) {}",
                    null
                );

                // 4. Limpiar SharedPreferences (excepto device_id)
                String deviceId = preferences.getString("device_id", null);
                preferences.edit().clear().apply();
                if (deviceId != null) {
                    preferences.edit().putString("device_id", deviceId).apply();
                }

                Log.d(TAG, "Cache cleared, restarting app...");

                // Enviar respuesta antes de reiniciar
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "Cache cleared, restarting...");
                sendResponse(requestId, result, null);

                // 5. Reiniciar la app completamente después de un pequeño delay
                mainHandler.postDelayed(() -> {
                    android.content.Intent intent = activity.getPackageManager()
                        .getLaunchIntentForPackage(activity.getPackageName());
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        activity.startActivity(intent);
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }, 500);

            } catch (Exception e) {
                Log.e(TAG, "Error clearing cache and restarting", e);
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // APP METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private void appMinimize(String requestId) {
        mainHandler.post(() -> {
            try {
                activity.moveTaskToBack(true);
                JSONObject result = new JSONObject();
                result.put("success", true);
                sendResponse(requestId, result, null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void appExit(String requestId) {
        mainHandler.post(() -> {
            try {
                JSONObject result = new JSONObject();
                result.put("success", true);
                sendResponse(requestId, result, null);
                // Dar tiempo para enviar respuesta
                mainHandler.postDelayed(() -> {
                    activity.finishAffinity();
                }, 100);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KEYBOARD METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private void keyboardShow(String requestId) {
        mainHandler.post(() -> {
            try {
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                JSONObject result = new JSONObject();
                result.put("success", true);
                sendResponse(requestId, result, null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    private void keyboardHide(String requestId) {
        mainHandler.post(() -> {
            try {
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                JSONObject result = new JSONObject();
                result.put("success", true);
                sendResponse(requestId, result, null);
            } catch (Exception e) {
                sendResponse(requestId, null, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SPLASH SCREEN METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private void splashScreenShow(String requestId) {
        try {
            // El splash nativo de Android no se puede volver a mostrar
            // después de ocultarse, esto es solo para compatibilidad
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Splash screen cannot be shown again on Android");
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }

    private void splashScreenHide(String requestId) {
        try {
            // El splash ya se oculta automáticamente
            JSONObject result = new JSONObject();
            result.put("success", true);
            sendResponse(requestId, result, null);
        } catch (Exception e) {
            sendResponse(requestId, null, e.getMessage());
        }
    }
}
