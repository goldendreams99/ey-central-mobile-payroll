package ai.employ.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class MyFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "FCMService";
    private static final String DEFAULT_CHANNEL_ID = "push_notifications";
    private static final String HIGH_PRIORITY_CHANNEL_ID = "high_priority";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.d(TAG, "Push received from: " + remoteMessage.getFrom());

        String title = "Notificación";
        String body = "";
        String imageUrl = null;
        String iconVariant = "auto"; // 'auto', 'light', 'dark'
        String iconColor = null;
        String channelId = DEFAULT_CHANNEL_ID;
        String sound = "default";
        String url = null;   // EY-CENTRAL 2026-10-08: a dónde lleva la push (absoluta)
        String route = null; // o relativa
        String threadId = null; // EY-CENTRAL 2026-10-08: agrupación (setGroup)
        String tag = null;      // reemplaza la notificación visible con el mismo tag

        // Get notification payload
        RemoteMessage.Notification notification = remoteMessage.getNotification();
        if (notification != null) {
            title = notification.getTitle() != null ? notification.getTitle() : title;
            body = notification.getBody() != null ? notification.getBody() : body;

            // Get image URL
            if (notification.getImageUrl() != null) {
                imageUrl = notification.getImageUrl().toString();
            }

            // Get color
            if (notification.getColor() != null) {
                iconColor = notification.getColor();
            }

            // Get channel
            if (notification.getChannelId() != null) {
                channelId = notification.getChannelId();
            }

            // Get sound
            if (notification.getSound() != null) {
                sound = notification.getSound();
            }

            Log.d(TAG, "Notification - Title: " + title + ", Body: " + body +
                       ", Image: " + imageUrl + ", Color: " + iconColor +
                       ", Channel: " + channelId + ", Sound: " + sound);
        }

        // Get data payload (can override notification values)
        Map<String, String> data = remoteMessage.getData();
        if (data.size() > 0) {
            Log.d(TAG, "Data payload: " + data);
            if (data.containsKey("title")) title = data.get("title");
            if (data.containsKey("body")) body = data.get("body");
            if (data.containsKey("imageUrl")) imageUrl = data.get("imageUrl");
            if (data.containsKey("iconVariant")) iconVariant = data.get("iconVariant");
            if (data.containsKey("iconColor")) iconColor = data.get("iconColor");
            if (data.containsKey("channelId")) channelId = data.get("channelId");
            if (data.containsKey("sound")) sound = data.get("sound");
            if (data.containsKey("url")) url = data.get("url");       // EY-CENTRAL 2026-10-08: destino de la push
            if (data.containsKey("route")) route = data.get("route");
            if (data.containsKey("threadId")) threadId = data.get("threadId");
            if (data.containsKey("tag")) tag = data.get("tag");
        }

        // Procesar iconVariant para determinar el color correcto
        iconColor = resolveIconColor(iconVariant, iconColor);

        // Show notification with all options
        showNotification(title, body, imageUrl, iconColor, channelId, sound, url, route, threadId, tag);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "New FCM token: " + token.substring(0, 20) + "...");
        // EY-CENTRAL 2026-10-08: avisar al backend en el acto (antes solo se logueaba y el backend
        // seguía mandando al token viejo: la persona dejaba de recibir push sin error visible).
        PushTokenSync.onNewToken(getApplicationContext(), token);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;

            // Canal por defecto
            NotificationChannel defaultChannel = new NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "Push Notifications",
                NotificationManager.IMPORTANCE_HIGH
            );
            defaultChannel.setDescription("Notificaciones push de la aplicación");
            defaultChannel.enableVibration(true);
            defaultChannel.enableLights(true);
            defaultChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null);
            manager.createNotificationChannel(defaultChannel);

            // Canal alta prioridad
            NotificationChannel highPriorityChannel = new NotificationChannel(
                HIGH_PRIORITY_CHANNEL_ID,
                "Alta Prioridad",
                NotificationManager.IMPORTANCE_MAX
            );
            highPriorityChannel.setDescription("Notificaciones urgentes");
            highPriorityChannel.enableVibration(true);
            highPriorityChannel.enableLights(true);
            highPriorityChannel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null);
            manager.createNotificationChannel(highPriorityChannel);

            Log.d(TAG, "Notification channels created");
        }
    }

    /**
     * Determina el color del ícono basado en iconVariant y el modo del sistema
     * @param iconVariant 'auto', 'light', 'dark', 'custom'
     * @param providedColor Color proporcionado explícitamente (solo se usa con 'custom')
     * @return Color a usar en la notificación
     *
     * LIGHT = Ícono blanco (E blanca, para fondos oscuros)
     * DARK = Ícono negro (E negra, para fondos claros)
     * AUTO = Detecta tema del sistema y usa LIGHT o DARK
     */
    private String resolveIconColor(String iconVariant, String providedColor) {
        // LIGHT: ícono blanco (para fondos oscuros / dark mode)
        final String ICON_LIGHT = "#FFFFFF";
        // DARK: ícono negro (para fondos claros / light mode)
        final String ICON_DARK = "#000000";

        Log.d(TAG, "resolveIconColor - iconVariant: " + iconVariant + ", providedColor: " + providedColor);

        // CUSTOM: usar el color proporcionado
        if ("custom".equals(iconVariant)) {
            if (providedColor != null && !providedColor.isEmpty()) {
                Log.d(TAG, "Using CUSTOM color: " + providedColor);
                return providedColor;
            }
            Log.d(TAG, "Custom without color, falling back to auto");
        }

        // LIGHT: ícono blanco (E blanca)
        if ("light".equals(iconVariant)) {
            Log.d(TAG, "Using LIGHT icon (white): " + ICON_LIGHT);
            return ICON_LIGHT;
        }

        // DARK: ícono negro (E negra)
        if ("dark".equals(iconVariant)) {
            Log.d(TAG, "Using DARK icon (black): " + ICON_DARK);
            return ICON_DARK;
        }

        // AUTO: detectar modo del sistema
        // - Si el usuario tiene dark mode → fondo oscuro → usar ícono LIGHT (blanco)
        // - Si el usuario tiene light mode → fondo claro → usar ícono DARK (negro)
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDarkMode = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
        String autoColor = isDarkMode ? ICON_LIGHT : ICON_DARK;
        Log.d(TAG, "Using AUTO - userDarkMode: " + isDarkMode + ", icon: " + (isDarkMode ? "LIGHT (white)" : "DARK (black)"));

        return autoColor;
    }

    private void showNotification(String title, String body, String imageUrl,
                                   String iconColor, String channelId, String sound,
                                   String url, String route, String threadId, String tag) {
        Log.d(TAG, "Showing notification: " + title + " on channel: " + channelId);

        // EY-CENTRAL 2026-10-08 ("la push lleva al lugar"): antes TODAS las notificaciones compartían un
        // PendingIntent (requestCode 0, ONE_SHOT) sin destino: tocar la primera consumía el intent y las
        // demás de la bandeja quedaban muertas, y ninguna llevaba a ningún lado. Ahora: id único por
        // notificación, UPDATE_CURRENT, y la URL del payload (url/route) como data del intent → el mismo
        // camino que un App Link (MainActivity.handleDeepLink / captureInitialDeepLink).
        int notificationId = (int) (System.currentTimeMillis() & 0x7fffffff);
        String target = PushLinks.resolveTarget(this, url, route);
        Intent intent;
        if (target != null) {
            intent = PushLinks.openIntent(this, target);
        } else {
            intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Validar canal existe, si no usar default
        if (channelId == null || channelId.isEmpty()) {
            channelId = DEFAULT_CHANNEL_ID;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent);

        // Aplicar color del ícono
        if (iconColor != null && !iconColor.isEmpty()) {
            try {
                int color = Color.parseColor(iconColor);
                builder.setColor(color);
                builder.setColorized(true); // Forzar colorización en Android 8+
                Log.d(TAG, "Color aplicado: " + iconColor + " -> " + color);
            } catch (Exception e) {
                Log.e(TAG, "Invalid color: " + iconColor);
            }
        }

        // Aplicar sonido
        if (sound != null && !sound.equals("none")) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_LIGHTS);
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_LIGHTS);
        }

        // Texto expandido
        if (body != null && body.length() > 50) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        }

        // Cargar imagen si existe
        if (imageUrl != null && !imageUrl.isEmpty()) {
            Bitmap bitmap = getBitmapFromUrl(imageUrl);
            if (bitmap != null) {
                builder.setLargeIcon(bitmap);
                builder.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon((Bitmap) null));
            }
        }

        // EY-CENTRAL 2026-10-08: agrupar por hilo (conversación / módulo) en vez de apilar sueltas
        if (threadId != null && !threadId.isEmpty()) builder.setGroup(threadId);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            if (tag != null && !tag.isEmpty()) {
                manager.notify(tag, notificationId, builder.build()); // mismo tag = reemplaza la visible
            } else {
                manager.notify(notificationId, builder.build());
            }
            Log.d(TAG, "Notification shown with ID: " + notificationId);
        }
    }

    private Bitmap getBitmapFromUrl(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();
            InputStream input = connection.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (Exception e) {
            Log.e(TAG, "Error loading image: " + e.getMessage());
            return null;
        }
    }
}
