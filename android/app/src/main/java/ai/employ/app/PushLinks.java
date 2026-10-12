package ai.employ.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * EY-CENTRAL 2026-10-08 ("la push lleva al lugar"): resuelve a qué URL de la plataforma lleva una
 * notificación a partir de su payload `data` (`url` absoluta o `route` relativa), y arma el intent
 * que abre MainActivity en esa URL. Lo usan MyFirebaseMessagingService (notificación propia, app
 * abierta o mensaje data-only) y MainActivity (notificación dibujada por el sistema, app en segundo
 * plano: el toque lanza la app con los `data` como extras del intent).
 *
 * La app mobile NO usa subdominios de organización (decisión del usuario 2026-10-07): si la web manda
 * `https://<org>.employ.digital/...`, se conserva path + query y el host pasa a ser el de la webapp
 * cacheada (app.employ.digital o dev.employ.digital).
 */
final class PushLinks {
    static final String EXTRA_URL = "url";
    static final String EXTRA_ROUTE = "route";

    private static final String PREFS_NAME = "CapacitorStorage";          // = MainActivity.PREFS_NAME
    private static final String CACHED_WEBAPP_URL_KEY = "cached_webapp_url"; // = MainActivity.CACHED_WEBAPP_URL_KEY
    private static final String DEFAULT_BASE = "https://app.employ.digital";

    private PushLinks() {}

    /** Host base de la webapp que la app abre (la cacheada de Firebase, o app.employ.digital). */
    static Uri baseUri(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String cached = prefs.getString(CACHED_WEBAPP_URL_KEY, null);
            if (cached != null && !cached.isEmpty()) {
                Uri u = Uri.parse(cached);
                if (u.getHost() != null) return u;
            }
        } catch (Exception ignored) {}
        return Uri.parse(DEFAULT_BASE);
    }

    private static boolean isPlatformHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        return host.equals("employ.digital") || host.endsWith(".employ.digital");
    }

    /**
     * URL destino a partir de `url` (absoluta) y/o `route` (relativa, "/organization/x/messages?room=1").
     * Devuelve null si no hay nada navegable.
     */
    static String resolveTarget(Context ctx, String url, String route) {
        Uri base = baseUri(ctx);
        String baseHost = base.getHost();

        if (url != null && !url.trim().isEmpty()) {
            Uri u = Uri.parse(url.trim());
            if ("https".equalsIgnoreCase(u.getScheme()) && isPlatformHost(u.getHost())) {
                String host = u.getHost().toLowerCase();
                if (host.equals("app.employ.digital") || host.equals("dev.employ.digital") || host.equals(baseHost)) {
                    return u.toString();
                }
                // <org>.employ.digital → mismo path y query sobre el host de la app
                return u.buildUpon().scheme("https").authority(base.getAuthority()).build().toString();
            }
            // URL de otro dominio: no se abre dentro de la app
            return null;
        }

        if (route != null && !route.trim().isEmpty()) {
            String r = route.trim();
            if (!r.startsWith("/")) r = "/" + r;
            return "https://" + base.getAuthority() + r;
        }
        return null;
    }

    /** Intent explícito a MainActivity con la URL como data (ACTION_VIEW): mismo camino que un App Link. */
    static Intent openIntent(Context ctx, String targetUrl) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl), ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    /** Para intents que trae el sistema (push dibujada por el SO): los `data` del push vienen como extras. */
    static String targetFromExtras(Context ctx, Intent intent) {
        if (intent == null || intent.getExtras() == null) return null;
        String url = intent.getStringExtra(EXTRA_URL);
        String route = intent.getStringExtra(EXTRA_ROUTE);
        return resolveTarget(ctx, url, route);
    }
}
