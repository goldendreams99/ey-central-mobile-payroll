package ai.employ.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.animation.AlphaAnimation;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.net.ConnectivityManager;
import android.net.Network;
import android.widget.Button;
import android.widget.TextView;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.splashscreen.SplashScreen;
import com.getcapacitor.BridgeActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.concurrent.Executor;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "CapacitorStorage";
    private static final String CACHED_WEBAPP_URL_KEY = "cached_webapp_url";
    private static final String CACHED_API_URL_KEY = "cached_api_url";
    private static final String CACHED_WS_URL_KEY = "cached_ws_url";

    private boolean hasCachedUrl = false; // Para saber si debemos esperar la webapp
    private NativeBridge nativeBridge;
    private WebSocketService webSocketService;
    private PermissionRequest pendingPermissionRequest;
    private static final int REQUEST_MICROPHONE = 1001;

    // Biometric login state
    private boolean biometricLoginRequired = false;
    private boolean biometricLoginSuccess = false;
    private boolean splashKeepOnScreen = true;

    // EY-CENTRAL 2026-10-09 (usuario: "¿por qué el splash dura tanto?"): antes el logo quedaba 2 s como mínimo aunque la
    // web ya estuviera lista (espera de adorno). Ahora se va apenas la webapp pinta (onPageCommitVisible).
    private static final long MINIMUM_SPLASH_DURATION = 0;
    private long splashStartTime;
    private boolean webappReady = false;

    // Custom splash overlay
    private View splashOverlay;

    // EY-CENTRAL 2026-10-09: vista propia cuando la webapp no carga (ver offline_overlay.xml)
    private View offlineOverlay;
    private String offlineFailedUrl;
    private boolean offlineErrorThisLoad = false;
    private ConnectivityManager.NetworkCallback offlineNetCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Registrar tiempo de inicio
        splashStartTime = System.currentTimeMillis();

        // Fondo blanco desde el inicio
        getWindow().setBackgroundDrawableResource(android.R.color.white);

        // Splash nativo - se mantiene hasta que la webapp esté lista
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        splashScreen.setKeepOnScreenCondition(() -> splashKeepOnScreen);

        // Animacion de salida del splash - solo en Android 12+ (S)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener(splashScreenView -> {
                // OJO: este bloque corre DESPUES, cuando el splash se va, no
                // cuando se registra el listener. Un try/catch alrededor del
                // setOnExitAnimationListener no lo protege: cualquier excepcion
                // aca adentro mata la app en el arranque (NPE en produccion,
                // 753 usuarios en la 2.8.0/380). La animacion es decorativa, asi
                // que ante cualquier fallo se descarta el efecto y se quita el
                // splash igual.
                try {
                    Log.d(TAG, "Splash: " + "system splash exit start");
                    View splashView = splashScreenView.getView();
                    View iconView = splashScreenView.getIconView();

                    if (splashView == null) {
                        splashScreenView.remove();
                        return;
                    }

                    // Fade out suave
                    android.animation.ObjectAnimator fadeOut = android.animation.ObjectAnimator.ofFloat(
                        splashView, "alpha", 1f, 0f
                    );
                    fadeOut.setDuration(400);
                    fadeOut.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));

                    if (iconView != null) {
                        android.animation.ObjectAnimator iconFade = android.animation.ObjectAnimator.ofFloat(
                            iconView, "alpha", 1f, 0f
                        );
                        iconFade.setDuration(250);
                        iconFade.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
                        iconFade.start();
                    }

                    fadeOut.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            try {
                                Log.d(TAG, "Splash: " + "system splash removed");
                                splashScreenView.remove();
                            } catch (Throwable t) {
                                Log.w(TAG, "Splash remove failed: " + t.getMessage());
                            }
                        }
                    });
                    fadeOut.start();
                } catch (Throwable t) {
                    // Sin animacion, pero la app abre.
                    Log.w(TAG, "Splash exit animation failed: " + t, t);
                    try {
                        splashScreenView.remove();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        // En Android < 12, el splash se oculta automaticamente sin animacion personalizada

        super.onCreate(savedInstanceState);

        // Agregar overlay propio INMEDIATAMENTE
        addSplashOverlayImmediate();

        // Esperar a que el overlay esté dibujado antes de ocultar el splash nativo
        if (splashOverlay != null) {
            splashOverlay.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        splashOverlay.getViewTreeObserver().removeOnPreDrawListener(this);
                        // Ahora sí el overlay está visible, ocultar splash nativo
                        Log.d(TAG, "Splash: " + "overlay predraw -> release system splash");
                        splashKeepOnScreen = false;
                        return true;
                    }
                }
            );
        } else {
            splashKeepOnScreen = false;
        }

        // Configurar barras del sistema (navbar visible + transparente)
        configureSystemBars();

        // Verificar si hay URL cacheada y cargarla directamente
        loadCachedWebappUrl();

        // Configurar el bridge nativo después de que Capacitor inicialice
        setupNativeBridge();

        // ═══════════════════════════════════════════════════════════════════════════
        // MODO DIRECTO: Cargar webapp sin pasar por React/Capacitor
        // Esto salta el paso de React que solo servía para cargar config de Firebase
        // ═══════════════════════════════════════════════════════════════════════════
        captureInitialDeepLink(getIntent()); // EY-CENTRAL 2026-10-07: enlace en frío
        captureSharedFile(getIntent()); // EY-CENTRAL 2026-10-09: archivo compartido en frío
        loadWebappDirect();

        // configureSystemBars() se llama en onWindowFocusChanged() cuando la ventana está lista

        // Verificar si hay sesión guardada y biometría disponible
        checkBiometricLogin();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Reaplicar configuración de barras cuando la ventana tiene foco
            configureSystemBars();
        }
    }

    /**
     * Agrega overlay propio INMEDIATAMENTE para cubrir cualquier transición
     * Este overlay se queda hasta que el WebView tenga contenido visible
     * El logo aparece con animación "desde el fondo"
     */
    private void addSplashOverlayImmediate() {
        splashOverlay = LayoutInflater.from(this).inflate(R.layout.splash_overlay, null);
        ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
        decorView.addView(splashOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        splashOverlay.bringToFront();

        // Iniciar animación pulse del logo
        startLogoPulseAnimation();

        Log.d(TAG, "Splash: " + "overlay added");
    }

    /**
     * Inicia la animación pulse del logo (estilo breathe)
     * Scale: 1.0 -> 1.08 -> 1.0 con duración de 2.5 segundos
     */
    private void startLogoPulseAnimation() {
        if (splashOverlay == null) return;

        ImageView logoView = splashOverlay.findViewById(R.id.splash_logo);
        if (logoView == null) return;

        // Animación de scale (pulse/breathe)
        android.animation.ObjectAnimator scaleX = android.animation.ObjectAnimator.ofFloat(
            logoView, "scaleX", 1f, 1.08f, 1f
        );
        android.animation.ObjectAnimator scaleY = android.animation.ObjectAnimator.ofFloat(
            logoView, "scaleY", 1f, 1.08f, 1f
        );

        // Animación de alpha sutil
        android.animation.ObjectAnimator alpha = android.animation.ObjectAnimator.ofFloat(
            logoView, "alpha", 1f, 0.85f, 1f
        );

        // Combinar animaciones
        android.animation.AnimatorSet pulseAnimator = new android.animation.AnimatorSet();
        pulseAnimator.playTogether(scaleX, scaleY, alpha);
        pulseAnimator.setDuration(2500); // 2.5 segundos como en el prototipo
        pulseAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());

        // Loop infinito
        pulseAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (splashOverlay != null) {
                    pulseAnimator.start();
                }
            }
        });

        pulseAnimator.start();
        Log.d(TAG, "Logo pulse animation started");
    }

    /**
     * Splash animado simple:
     * 1. Fondo blanco 300ms
     * 2. Logo aparece (viene del fondo) 400ms
     * 3. Logo visible 1000ms
     * 4. Transición al webview
     */
    private void showAnimatedSplash() {
        splashOverlay = LayoutInflater.from(this).inflate(R.layout.splash_overlay, null);
        ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
        decorView.addView(splashOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ImageView logoView = splashOverlay.findViewById(R.id.splash_logo);

        // Logo invisible al inicio
        logoView.setAlpha(0f);
        logoView.setScaleX(0.5f);
        logoView.setScaleY(0.5f);

        // Logo aparece inmediatamente con animación
        logoView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .start();
    }

    /**
     * Oculta el splash overlay con animación elegante
     */
    private void hideAnimatedSplash() {
        if (splashOverlay != null && splashOverlay.getParent() != null) {
            ImageView logoView = splashOverlay.findViewById(R.id.splash_logo);

            // El logo hace un sutil scale up mientras se desvanece
            if (logoView != null) {
                logoView.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .alpha(0f)
                    .setDuration(400)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
            }

            // El fondo se desvanece suavemente
            splashOverlay.animate()
                .alpha(0f)
                .setDuration(450)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    ViewGroup parent = (ViewGroup) splashOverlay.getParent();
                    if (parent != null) {
                        parent.removeView(splashOverlay);
                    }
                    splashOverlay = null;
                    Log.d(TAG, "Animated splash overlay hidden");
                })
                .start();
        }
    }
    // System bar heights in device pixels (for CSS injection)
    private int statusBarHeight = 0;
    private int navBarHeight = 0;

    /**
     * Calculate system bar heights from device resources.
     */
    private void calculateSystemBarHeights() {
        int statusId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (statusId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(statusId);
        }

        int navId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (navId > 0) {
            navBarHeight = getResources().getDimensionPixelSize(navId);
        }

        Log.d(TAG, "System bar heights - status: " + statusBarHeight + "px, nav: " + navBarHeight + "px");
    }

    /**
     * Inject CSS variables with safe area insets.
     * The webapp uses these variables to apply padding where needed.
     * For external auth pages (Google, Microsoft), we inject direct padding.
     */
    // EY-CENTRAL 2026-10-08 (pedido de EY-01, orden del usuario "jamás tirón"): las variables de safe area se fijan
    // al INICIO de cada documento (antes del primer pintado), no en onPageFinished como la inyección de abajo,
    // que queda de refuerzo. En el mismo script, un stub de window.Capacitor: Capacitor evalúa
    // window.Capacitor.triggerEvent(...) (resume, etc.) en nuestra página remota, donde su bridge JS no está,
    // y tiraba "Cannot read properties of undefined (reading 'triggerEvent')" en cada vuelta a primer plano.
    private void installDocumentStartScript(WebView webView) {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w("MainActivity", "DOCUMENT_START_SCRIPT not supported by this WebView");
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int top = Math.round(statusBarHeight / density);
        String js = "(function(){var r=document.documentElement;if(r){" +
            "r.style.setProperty('--status-bar-height','" + top + "px');" +
            "r.style.setProperty('--safe-area-inset-top','" + top + "px');" +
            "r.style.setProperty('--nav-bar-height','0px');" +
            "r.style.setProperty('--safe-area-inset-bottom','0px');}" +
            "if(!window.Capacitor){window.Capacitor={triggerEvent:function(){}};}})();";
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, js, java.util.Collections.singleton("*"));
        Log.d("MainActivity", "Document-start script installed - top: " + top + "px");
    }

    private void injectSafeAreaCSS(WebView webView) {
        // Get current URL to determine injection strategy
        String currentUrl = webView.getUrl();

        // Convert device pixels to CSS pixels
        float density = getResources().getDisplayMetrics().density;
        int statusBarCss = Math.round(statusBarHeight / density);
        // Nav bar is hidden (swipe to show), so we pass 0 to avoid unnecessary bottom padding
        int navBarCss = 0;

        // Check if this is an external auth page
        boolean isExternalAuth = isExternalAuthUrl(currentUrl);

        String css;
        String logMessage;

        if (isExternalAuth) {
            // External auth pages (Google, Microsoft, Clerk) - inject direct padding
            // These pages don't use our CSS variables, so we force padding on html
            css =
                "html { " +
                "  padding-top: " + statusBarCss + "px !important; " +
                "  padding-bottom: " + navBarCss + "px !important; " +
                "  box-sizing: border-box !important; " +
                "} " +
                "body { " +
                "  min-height: calc(100vh - " + statusBarCss + "px - " + navBarCss + "px) !important; " +
                "}";
            logMessage = "[SafeArea] Injected DIRECT padding for external auth - top: " + statusBarCss + "px, bottom: " + navBarCss + "px";
        } else {
            // Our webapp - inject CSS variables (webapp applies them once)
            css =
                ":root { " +
                "  --status-bar-height: " + statusBarCss + "px; " +
                "  --nav-bar-height: " + navBarCss + "px; " +
                "  --safe-area-inset-top: " + statusBarCss + "px; " +
                "  --safe-area-inset-bottom: " + navBarCss + "px; " +
                "}";
            logMessage = "[SafeArea] Injected CSS variables - top: " + statusBarCss + "px, bottom: " + navBarCss + "px";
        }

        String script = "(function() {" +
            "  if (document.getElementById('__SAFE_AREA_CSS__')) return;" +
            "  var style = document.createElement('style');" +
            "  style.id = '__SAFE_AREA_CSS__';" +
            "  style.textContent = '" + css.replace("'", "\\'") + "';" +
            "  document.head.appendChild(style);" +
            "  console.log('" + logMessage + "');" +
            "})();";

        webView.evaluateJavascript(script, null);
        Log.d(TAG, isExternalAuth ?
            "Injected direct safe area padding for external auth - top: " + statusBarCss + "px, bottom: " + navBarCss + "px" :
            "Injected safe area CSS variables - top: " + statusBarCss + "px, bottom: " + navBarCss + "px");
    }

    /**
     * Check if URL is an external authentication page (Google, Microsoft, Clerk).
     * These pages don't use our CSS variables, so we need to inject direct padding.
     */
    private boolean isExternalAuthUrl(String url) {
        if (url == null) return false;
        return url.contains("accounts.google.com") ||
               url.contains("login.microsoftonline.com") ||
               url.contains("login.live.com") ||
               url.contains("clerk.accounts") ||
               url.contains("clerk.") && url.contains("/sign-in") ||
               url.contains("clerk.") && url.contains("/sign-up");
    }

    /**
     * Configure edge-to-edge display with transparent system bars.
     * The webapp will handle the safe area padding via CSS variables.
     */
    private void configureSystemBars() {
        android.view.Window window = getWindow();

        // Edge-to-edge. EdgeToEdge.enable() es la API vigente: en Android 15+
        // ya deja las barras transparentes por su cuenta, y en versiones
        // anteriores hace el equivalente. Reemplaza a setStatusBarColor /
        // setNavigationBarColor, deprecadas y sin efecto con targetSdk 36.
        androidx.activity.EdgeToEdge.enable(this);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);

        // Disable the white scrim overlay on nav bar (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }

        // Get the insets controller
        androidx.core.view.WindowInsetsControllerCompat insetsController =
            androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView());

        if (insetsController != null) {
            // Íconos según el fondo ya medido (EY-CENTRAL 2026-10-11: antes siempre oscuros, y en el Inicio con
            // fondo oscuro arriba la hora y la batería desaparecían cada vez que la ventana recuperaba el foco).
            insetsController.setAppearanceLightStatusBars(statusBarAuto == null || statusBarAuto.darkIconsForNow());

            // HIDE navigation bar - appears with swipe from bottom
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars());

            // IMMERSIVE_STICKY: navbar appears temporarily on swipe, then auto-hides
            insetsController.setSystemBarsBehavior(
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }

        Log.d(TAG, "Edge-to-edge configured - navbar hidden (swipe to show)");
    }


    private void loadCachedWebappUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String cachedUrl = prefs.getString(CACHED_WEBAPP_URL_KEY, null);

        if (cachedUrl != null && !cachedUrl.isEmpty()) {
            hasCachedUrl = true;
            Log.d(TAG, "Found cached webapp URL: " + cachedUrl);
            // React cargará, actualizará el cache desde Firebase, y hará redirect
            // El splash nativo se mantiene hasta que la webapp externa cargue
            // Esto permite que React actualice el cache para la próxima vez
        } else {
            hasCachedUrl = false;
            Log.d(TAG, "No cached URL found - React will fetch from Firebase");
        }
    }

    /**
     * MODO DIRECTO: Carga la webapp sin pasar por React/Capacitor
     * 1. Si hay URL cacheada → usarla (inicio rápido)
     * 2. Si no → cargar desde Firebase
     */
    private void loadWebappDirect() {
        // Pequeño delay para asegurar que el WebView esté listo
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!BuildConfig.EY_LOCAL_URL.isEmpty()) { // EY-CENTRAL 2026-10-16: compilación de prueba → servidor local, sin Firebase ni caché
                Log.d(TAG, "DIRECT MODE: local build, starting at " + BuildConfig.EY_LOCAL_URL);
                loadUrlInWebView(BuildConfig.EY_LOCAL_URL);
                return;
            }
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String cachedUrl = prefs.getString(CACHED_WEBAPP_URL_KEY, null);
            if (initialDeepLinkUrl != null) {
                // EY-CENTRAL 2026-10-07: la app se abrió desde un enlace → arrancar directo ahí
                String link = initialDeepLinkUrl;
                initialDeepLinkUrl = null;
                Log.d(TAG, "DIRECT MODE: Starting at deep link: " + link);
                loadUrlInWebView(link);
                if (cachedUrl != null && !cachedUrl.isEmpty()) updateUrlFromFirebase();
                return;
            }

            if (cachedUrl != null && !cachedUrl.isEmpty()) {
                // Hay cache → usar inmediatamente
                Log.d(TAG, "DIRECT MODE: Using cached URL: " + cachedUrl);
                loadUrlInWebView(startUrlForCached(cachedUrl)); // EY-CENTRAL 2026-10-09: Inicio de la última empresa
                // Actualizar cache en background para la próxima vez
                updateUrlFromFirebase();
            } else {
                // No hay cache → cargar desde Firebase
                Log.d(TAG, "DIRECT MODE: No cache, loading from Firebase...");
                loadUrlFromFirebase();
            }
        }, 100);
    }

    // ***--- EY-CENTRAL 2026-10-09 (orden del usuario: "si ya tenemos la info en caché, ¿por qué se muestra
    // Redirigiendo...?"): la app siempre abría la portada (/) y la web, en CADA apertura, esperaba la sesión, sincronizaba
    // el usuario, calculaba la empresa y mostraba "Acceso confirmado / Redirigiendo..." para terminar siempre en el mismo
    // Inicio. Ahora la app recuerda el Inicio de la última empresa visitada (https://<host>/organization/<id>, solo el
    // Inicio) y arranca ahí. No se saltea ninguna validación: middleware y servidor del Inicio revisan sesión y acceso igual.
    // Se olvida al pasar por /sign-in, /sign-up, /sign-out o /sso (otro usuario puede entrar). Hosts: app. y dev.employ.digital
    // (la web manda a los usuarios de prueba a DEV). Un enlace o una push mandan sobre esto (initialDeepLinkUrl se atiende antes). Igual que iOS. ---***
    private static final String LAST_ORG_URL_KEY = "last_org_home_url";

    private void rememberOrgUrl(String urlString) {
        if (urlString == null) return;
        try {
            Uri u = Uri.parse(urlString);
            String host = u.getHost();
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            // EY-CENTRAL 2026-10-09 (log del Pixel): la app abre app.employ.digital y la web manda a los usuarios de
            // prueba a dev.employ.digital (isUserTestUrl). Se aceptan los dos hosts de la plataforma, no solo el cacheado.
            if (!isPlatformHost(host)) return;
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.startsWith("/sign-in") || path.startsWith("/sign-up") || path.startsWith("/sign-out") || path.startsWith("/sso")) {
                if (prefs.contains(LAST_ORG_URL_KEY)) {
                    prefs.edit().remove(LAST_ORG_URL_KEY).apply();
                    Log.i(TAG, "StartURL: forgotten (auth page)");
                }
                return;
            }
            java.util.List<String> segs = u.getPathSegments();
            if (segs.size() < 2 || !"organization".equals(segs.get(0)) || segs.get(1).isEmpty()) return;
            String scheme = u.getScheme() == null ? "https" : u.getScheme();
            String home = scheme + "://" + host.toLowerCase() + "/organization/" + segs.get(1);
            if (!home.equals(prefs.getString(LAST_ORG_URL_KEY, null))) {
                prefs.edit().putString(LAST_ORG_URL_KEY, home).apply();
                Log.i(TAG, "StartURL: remembered " + home);
            }
        } catch (Exception e) {
            Log.w(TAG, "rememberOrgUrl: " + e.getMessage());
        }
    }

    private static boolean isPlatformHost(String host) {
        return host != null && (host.equalsIgnoreCase("app.employ.digital") || host.equalsIgnoreCase("dev.employ.digital"));
    }

    private String startUrlForCached(String cachedUrl) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String last = prefs.getString(LAST_ORG_URL_KEY, null);
        if (last == null) { Log.i(TAG, "StartURL: none remembered, starting at " + cachedUrl); return cachedUrl; }
        if (!isPlatformHost(Uri.parse(last).getHost())) return cachedUrl;
        Log.i(TAG, "StartURL: starting at last organization home: " + last);
        return last;
    }

    /**
     * Carga la URL en el WebView
     */
    private void loadUrlInWebView(String url) {
        WebView webView = getBridge().getWebView();
        Log.d(TAG, "DIRECT MODE: Loading webapp now: " + url);
        webView.loadUrl(url);
    }

    /**
     * Carga la URL desde Firebase (bloqueante - para cuando no hay cache)
     */
    private void loadUrlFromFirebase() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // 1. Obtener platforms/app-mobile-payroll para saber la versión actual
        db.collection("platforms").document("app-mobile-payroll")
            .get()
            .addOnSuccessListener(platformDoc -> {
                if (platformDoc.exists()) {
                    String currentVersion = platformDoc.getString("current");
                    Log.d(TAG, "DIRECT MODE: Firebase current version: " + currentVersion);

                    if (currentVersion != null) {
                        // 2. Obtener la config de esa versión
                        db.collection("platforms").document("app-mobile-payroll")
                            .collection("versions").document(currentVersion)
                            .get()
                            .addOnSuccessListener(versionDoc -> {
                                if (versionDoc.exists()) {
                                    // Obtener urls.webapp, urls.api, urls.ws
                                    Object urlsObj = versionDoc.get("urls");
                                    if (urlsObj instanceof java.util.Map) {
                                        @SuppressWarnings("unchecked")
                                        java.util.Map<String, Object> urls = (java.util.Map<String, Object>) urlsObj;
                                        String webappUrl = (String) urls.get("webapp");
                                        String apiUrl = (String) urls.get("api");
                                        String wsUrl = (String) urls.get("ws");

                                        if (webappUrl != null && !webappUrl.isEmpty()) {
                                            Log.d(TAG, "DIRECT MODE: Got URLs from Firebase - webapp: " + webappUrl + ", api: " + apiUrl);
                                            // Guardar todas las URLs en cache
                                            saveUrlsToCache(webappUrl, apiUrl, wsUrl);
                                            // Configurar WebSocket
                                            if (wsUrl != null && webSocketService != null) {
                                                webSocketService.configure(wsUrl);
                                            }
                                            // Cargar webapp
                                            loadUrlInWebView(webappUrl);
                                            return;
                                        }
                                    }
                                }
                                Log.e(TAG, "DIRECT MODE: Version doc missing or no urls.webapp");
                                loadFallbackUrl();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "DIRECT MODE: Failed to load version: " + e.getMessage());
                                loadFallbackUrl();
                            });
                    } else {
                        Log.e(TAG, "DIRECT MODE: No current version in platform doc");
                        loadFallbackUrl();
                    }
                } else {
                    Log.e(TAG, "DIRECT MODE: Platform doc doesn't exist");
                    loadFallbackUrl();
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "DIRECT MODE: Firebase failed: " + e.getMessage());
                loadFallbackUrl();
            });
    }

    /**
     * Actualiza la URL desde Firebase en background (para la próxima vez)
     */
    private void updateUrlFromFirebase() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("platforms").document("app-mobile-payroll")
            .get()
            .addOnSuccessListener(platformDoc -> {
                if (platformDoc.exists()) {
                    String currentVersion = platformDoc.getString("current");
                    if (currentVersion != null) {
                        db.collection("platforms").document("app-mobile-payroll")
                            .collection("versions").document(currentVersion)
                            .get()
                            .addOnSuccessListener(versionDoc -> {
                                if (versionDoc.exists()) {
                                    Object urlsObj = versionDoc.get("urls");
                                    if (urlsObj instanceof java.util.Map) {
                                        @SuppressWarnings("unchecked")
                                        java.util.Map<String, Object> urls = (java.util.Map<String, Object>) urlsObj;
                                        String webappUrl = (String) urls.get("webapp");
                                        String apiUrl = (String) urls.get("api");
                                        String wsUrl = (String) urls.get("ws");
                                        if (webappUrl != null && !webappUrl.isEmpty()) {
                                            saveUrlsToCache(webappUrl, apiUrl, wsUrl);
                                            Log.d(TAG, "DIRECT MODE: Background update - cached URLs");
                                            // Update WebSocket URL if changed
                                            if (wsUrl != null && webSocketService != null) {
                                                webSocketService.configure(wsUrl);
                                            }
                                        }
                                    }
                                }
                            });
                    }
                }
            });
    }

    /**
     * Guarda la URL en cache (solo webapp - legacy)
     */
    private void saveUrlToCache(String url) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(CACHED_WEBAPP_URL_KEY, url).apply();
    }

    /**
     * Guarda todas las URLs en cache
     */
    private void saveUrlsToCache(String webappUrl, String apiUrl, String wsUrl) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(CACHED_WEBAPP_URL_KEY, webappUrl);
        if (apiUrl != null) editor.putString(CACHED_API_URL_KEY, apiUrl);
        if (wsUrl != null) editor.putString(CACHED_WS_URL_KEY, wsUrl);
        editor.apply();
        Log.d(TAG, "Cached URLs - webapp: " + webappUrl + ", api: " + apiUrl + ", ws: " + wsUrl);
    }

    /**
     * Carga URL de fallback si todo falla
     */
    private void loadFallbackUrl() {
        String fallbackUrl = "https://app.employ.digital/mobile";
        Log.d(TAG, "DIRECT MODE: Using fallback URL: " + fallbackUrl);
        loadUrlInWebView(fallbackUrl);
    }

    // Ocultar splash respetando el tiempo mínimo
    private void hideSplashWithMinDuration() {
        long elapsed = System.currentTimeMillis() - splashStartTime;
        long remaining = MINIMUM_SPLASH_DURATION - elapsed;

        if (remaining > 0) {
            Log.d(TAG, "Waiting " + remaining + "ms before hiding splash");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                doSmoothTransition();
            }, remaining);
        } else {
            doSmoothTransition();
        }
    }

    // Ocultar splash overlay con fade suave
    // ***--- EY-CENTRAL 2026-10-09 (usuario: "el Inicio aparece de golpe"; medido en el Pixel 6, 5 aperturas en frío:
    // 2 de golpe y 3 con fundido). En Android el WebView compone EN SINCRONÍA con la app: si la web está ocupada armando
    // su pantalla, la app no puede dibujar cuadros y el fundido se saltea. Medir los cuadros de la APP no alcanzaba (a
    // veces daban fluidos y el fundido igual se perdía). Ahora se le pregunta a la WEB: un script mide sus cuadros
    // (requestAnimationFrame) y cuando dibuja 4 seguidos de menos de 25 ms llama AndroidBridge.eySplashReady(); ahí
    // arranca el fundido. Tope 2,6 s por si la web nunca avisa. ---***
    private boolean splashFadeRequested = false;

    private void doSmoothTransition() {
        if (splashOverlay == null || splashOverlay.getParent() == null) { configureSystemBars(); return; }
        Log.d(TAG, "Splash: " + "doSmoothTransition requested=" + splashFadeRequested);
        if (splashFadeRequested) return;
        splashFadeRequested = true;
        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null) {
            // Medido (5 aperturas, registros): la web dibuja fluido apenas se muestra (foto guardada, liviana) y medio
            // segundo después arranca su trabajo pesado (hidratación): si el fundido empieza ahí, pierde los cuadros.
            // Por eso: esperar la carga completa (load), un momento libre (requestIdleCallback) y 8 cuadros fluidos
            // seguidos. Tope 2,4 s.
            String js = "(function(){var t0=performance.now(),sent=0;function done(){if(sent)return;sent=1;"
                + "try{window.AndroidBridge.eySplashReady()}catch(e){}}"
                + "function smooth(){var n=0,last=0;function f(t){if(last&&t-last<22)n++;else n=0;last=t;"
                + "if(n>=8||performance.now()-t0>2200){done();return}requestAnimationFrame(f)}requestAnimationFrame(f)}"
                + "function idle(){if(window.requestIdleCallback)requestIdleCallback(smooth,{timeout:700});else setTimeout(smooth,100)}"
                + "if(document.readyState==='complete')idle();else window.addEventListener('load',idle,{once:true});"
                + "setTimeout(done,2400)})();";
            wv.evaluateJavascript(js, null);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::onWebSplashReady, 2600);
    }

    /** La web dibuja fluido (o venció el tope): arranca el fundido del splash, una sola vez. */
    public void onWebSplashReady() {
        Log.d(TAG, "Splash: " + "onWebSplashReady overlay=" + (splashOverlay != null) + " tag=" + (splashOverlay != null ? splashOverlay.getTag() : null));
        if (splashOverlay == null || splashOverlay.getParent() == null || splashOverlay.getTag() != null) return;
        splashOverlay.setTag("fading");
        startSplashFade();
    }

    private void startSplashFade() {
        if (splashOverlay != null && splashOverlay.getParent() != null) {
            // Tiempo y cuadros reales del fundido (Log.d: solo en builds de depuración).
            final long diagT0 = android.os.SystemClock.uptimeMillis();
            final int[] diagFrames = {0};
            Log.d(TAG, "Splash: " + "fade start alpha=" + splashOverlay.getAlpha());
            splashOverlay.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withLayer() // capa de hardware: el fundido no redibuja el logo en cada cuadro
                .setUpdateListener(a -> diagFrames[0]++)
                .withEndAction(() -> {
                    Log.d(TAG, "Splash: " + "fade end after " + (android.os.SystemClock.uptimeMillis() - diagT0) + " ms, frames=" + diagFrames[0]);
                    ViewGroup parent = (ViewGroup) splashOverlay.getParent();
                    if (parent != null) {
                        parent.removeView(splashOverlay);
                    }
                    splashOverlay = null;
                    Log.d(TAG, "Splash: " + "overlay removed");
                })
                .start();
        }
        configureSystemBars();
    }

    // ***--- EY-CENTRAL 2026-10-09 (pedido del usuario): la webapp no cargó (sin red, DNS, timeout o 5xx en la
    // página principal). Antes quedaba la página de error del sistema ("Sin conexión / No se pudo cargar la
    // página"), que delata que esto es una web. Ahora: vista nativa con el logo, un texto y "Reintentar"; tapa
    // el splash si todavía está, y reintenta sola cuando vuelve la red. Se quita en onPageCommitVisible de la
    // webapp (la carga volvió a andar). ---***
    private void showOfflineOverlay(String failedUrl, boolean serverError) {
        offlineFailedUrl = failedUrl;
        runOnUiThread(() -> {
            if (offlineOverlay != null && offlineOverlay.getParent() != null) {
                TextView title = offlineOverlay.findViewById(R.id.offline_title);
                TextView body = offlineOverlay.findViewById(R.id.offline_body);
                title.setText(serverError ? R.string.server_error_title : R.string.offline_title);
                body.setText(serverError ? R.string.server_error_body : R.string.offline_body);
                return;
            }
            offlineOverlay = LayoutInflater.from(this).inflate(R.layout.offline_overlay, null);
            TextView title = offlineOverlay.findViewById(R.id.offline_title);
            TextView body = offlineOverlay.findViewById(R.id.offline_body);
            title.setText(serverError ? R.string.server_error_title : R.string.offline_title);
            body.setText(serverError ? R.string.server_error_body : R.string.offline_body);
            Button retry = offlineOverlay.findViewById(R.id.offline_retry);
            retry.setOnClickListener(v -> retryOfflineLoad());
            ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
            decorView.addView(offlineOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            offlineOverlay.bringToFront();
            // El splash (si sigue) se va: la espera terminó, aunque mal.
            if (splashOverlay != null && splashOverlay.getParent() != null) {
                ((ViewGroup) splashOverlay.getParent()).removeView(splashOverlay);
                splashOverlay = null;
            }
            watchNetworkForRetry();
            Log.d(TAG, "Offline overlay shown (" + (serverError ? "server error" : "no connection") + ") for " + failedUrl);
        });
    }

    private void hideOfflineOverlay() {
        runOnUiThread(() -> {
            stopWatchingNetwork();
            if (offlineOverlay == null) return;
            final View v = offlineOverlay;
            offlineOverlay = null;
            v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                ViewGroup parent = (ViewGroup) v.getParent();
                if (parent != null) parent.removeView(v);
            }).start();
            Log.d(TAG, "Offline overlay hidden - webapp visible");
        });
    }

    private void retryOfflineLoad() {
        WebView webView = getBridge().getWebView();
        if (webView == null) return;
        String url = offlineFailedUrl;
        if (url == null || url.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            url = prefs.getString(CACHED_WEBAPP_URL_KEY, "https://app.employ.digital");
        }
        Log.d(TAG, "Retrying webapp load: " + url);
        webView.loadUrl(url);
    }

    // Cuando vuelve la red, reintenta sola (el usuario no tiene que tocar nada).
    private void watchNetworkForRetry() {
        if (offlineNetCallback != null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        offlineNetCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (offlineOverlay != null) retryOfflineLoad();
                }, 800);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(offlineNetCallback);
        } catch (Exception e) {
            Log.w(TAG, "Network callback not registered: " + e.getMessage());
            offlineNetCallback = null;
        }
    }

    private void stopWatchingNetwork() {
        if (offlineNetCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        try {
            if (cm != null) cm.unregisterNetworkCallback(offlineNetCallback);
        } catch (Exception ignored) {}
        offlineNetCallback = null;
    }

    // Setear fondo blanco en todas las vistas recursivamente
    private void setAllBackgroundsWhite(View view) {
        view.setBackgroundColor(android.graphics.Color.WHITE);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setAllBackgroundsWhite(group.getChildAt(i));
            }
        }
    }

    private void checkBiometricLogin() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String userId = prefs.getString("userId", null);
        String biometricEnabled = prefs.getString("biometric_login_enabled", null);

        Log.d(TAG, "checkBiometricLogin - userId: " + userId + ", biometricEnabled: " + biometricEnabled);

        // Si hay sesión guardada y biometría está habilitada
        if (userId != null && "true".equals(biometricEnabled)) {
            // Verificar si biometría está disponible en el dispositivo
            BiometricManager biometricManager = BiometricManager.from(this);
            int canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

            if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                Log.d(TAG, "Biometric login required - showing prompt");
                biometricLoginRequired = true;
                showBiometricPrompt();
                return;
            } else {
                Log.d(TAG, "Biometrics not available: " + canAuthenticate);
            }
        }

        // No hay sesión o biometría no disponible - esperar a que webapp cargue
        Log.d(TAG, "No biometric login required - waiting for webapp to load");
    }

    private void showBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Iniciar sesión")
            .setSubtitle("Usa tu huella o rostro para acceder")
            .setNegativeButtonText("Usar contraseña")
            .build();

        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    Log.d(TAG, "Biometric authentication succeeded");
                    biometricLoginSuccess = true;

                    // Guardar flag para que la web-app sepa que ya está autenticado
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    prefs.edit()
                        .putString("biometric_login_verified", "true")
                        .putLong("biometric_login_timestamp", System.currentTimeMillis())
                        .apply();

                    // Notificar a la web-app vía JavaScript
                    notifyWebAppBiometricSuccess();

                    // Cerrar splash respetando tiempo mínimo
                    hideSplashWithMinDuration();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    Log.d(TAG, "Biometric authentication error: " + errorCode + " - " + errString);

                    // Si el usuario cancela o elige "Usar contraseña", limpiar sesión
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        clearSessionForLogin();
                    }

                    // Cerrar splash y continuar (mostrará login)
                    hideSplashWithMinDuration();
                }

                @Override
                public void onAuthenticationFailed() {
                    Log.d(TAG, "Biometric authentication failed - user can retry");
                    // No hacer nada, el usuario puede reintentar
                }
            });

        biometricPrompt.authenticate(promptInfo);
    }

    private void clearSessionForLogin() {
        Log.d(TAG, "Clearing session for login");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .remove("biometric_login_verified")
            .remove("biometric_login_timestamp")
            .apply();
    }

    private void notifyWebAppBiometricSuccess() {
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            // Obtener datos de sesión para enviar al webapp
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String userId = prefs.getString("userId", "");
            String companyId = prefs.getString("companyId", "");
            String sessionId = prefs.getString("sessionId", "");
            String deviceId = prefs.getString("device_id", "");

            webView.post(() -> {
                String script = "(function() {" +
                    "  var biometricData = {" +
                    "    type: 'BIOMETRIC_LOGIN_SUCCESS'," +
                    "    userId: '" + userId + "'," +
                    "    companyId: '" + companyId + "'," +
                    "    sessionId: '" + sessionId + "'," +
                    "    deviceId: '" + deviceId + "'," +
                    "    timestamp: " + System.currentTimeMillis() +
                    "  };" +
                    "  console.log('[BiometricLogin] Notifying webapp:', biometricData);" +
                    "  window.__BIOMETRIC_LOGIN_DATA__ = biometricData;" +
                    "  window.postMessage(biometricData, '*');" +
                    "  window.dispatchEvent(new CustomEvent('biometricLoginSuccess', { detail: biometricData }));" +
                    "})();";
                webView.evaluateJavascript(script, null);
                Log.d(TAG, "Notified web app of biometric success with session data");
            });
        }
    }

    /**
     * EY-CENTRAL 2026-10-16 (pedido de EY-PAYROLL; reclamo del usuario en producción; revisión de Zeus y mediciones de
     * EY-PAYROLL y de la sonda propia en el Pixel): EL TECLADO ARRASTRA LA CAJA CUADRO A CUADRO, COMO GEMINI, SIN
     * REARMAR LA PÁGINA.
     *
     * Qué pasaba: al despacharse los insets finales (INICIO de la animación del teclado) DOS cosas descontaban el teclado a
     * la vez: el SystemBars de Capacitor ponía el alto del IME como relleno del padre del WebView, y el propio WebView
     * (Chromium) recibía el inset del IME intacto y lo descontaba por su cuenta. Medido: un cuadro con la página a 173 px
     * (866 − 2×346), después 519, de golpe, con banda mientras el teclado subía. Y la primera versión de este método
     * (achicar el WebView por cuadro) re-maqueta la página entera ~15 veces en 250 ms (Zeus): descartada.
     *
     * Qué hace ahora (contrato con la web, igual en iOS):
     * - UN solo escritor de los insets: este método reemplaza el listener de Capacitor en el padre del WebView. Pone el
     *   relleno inferior (= alto del IME cuando está visible, 0 si no) y le pasa al WebView los insets SIN el IME, así
     *   Chromium no vuelve a descontarlo. Las barras del sistema pasan como antes (la web las maneja con sus variables).
     * - El alto de la página cambia UNA sola vez por apertura o cierre, cuando la página es MÁS GRANDE: al abrir, al
     *   TERMINAR la animación (onEnd); al cerrar, al EMPEZAR (onStart). Mientras el teclado se mueve el layout no cambia:
     *   el teclado tapa la página y la web mueve la caja con `transform`.
     * - En cada cuadro (onProgress) le pasa a la web cuánto tapa el teclado y el layout todavía no descuenta, en px CSS:
     *   la variable CSS `--ey-keyboard-height` en <html> (0 en reposo) y el evento `ey:keyboard` fase "progress". El valor
     *   sale del inset del IME del propio WindowInsetsCompat del cuadro (interpolado por el sistema).
     * - `ey:keyboard` fase "start" (height final, duration ms, open) al empezar y "end" (open, height) al terminar, cuando
     *   el layout ya descontó el teclado y la variable volvió a 0.
     * El callback va en la vista raíz porque el plugin Keyboard de Capacitor pone ahí el suyo con DISPATCH_MODE_STOP (los
     * hijos no reciben nada): lo reemplaza; sus eventos keyboardWillShow/DidShow no los usa la web. Antes de Android 11 no
     * hay animación de insets: el relleno se aplica en el despacho, como siempre.
     */
    private void installKeyboardFollow(WebView webView) {
        final View root = getWindow().getDecorView();
        if (!(webView.getParent() instanceof View)) return;
        final View parent = (View) webView.getParent();
        parent.setBackgroundColor(android.graphics.Color.WHITE); // ningún cuadro muestra negro
        final float density = getResources().getDisplayMetrics().density;
        final int[] finalBottom = {parent.getPaddingBottom()}; // lo que el despacho de insets pide
        final int[] startBottom = {0};
        final int[] targetBottom = {0}; // destino real de ESTA animación (al tomar el control, el sistema vuelve a avisar el estado viejo)
        final boolean[] imeRunning = {false};
        final int[] lastSent = {-1};
        final int[] holdUntilTaller = {-1}; // al cerrar: alto del WebView antes de agrandarlo; la variable espera a que crezca
        // EY-CENTRAL 2026-10-16 (pedido de EY-PAYROLL, 14:50; reclamo del usuario "se traba al llegar abajo"): la curva del
        // teclado del sistema hace el 80 % del recorrido en 90 ms y arrastra una cola de 170 ms en la franja de abajo. Como
        // Gemini, la app CONDUCE la animación del IME: apenas el sistema la arranca, pide el control
        // (controlWindowInsetsAnimation), la cancela y la lleva ella con una curva pareja de IME_DRIVE_MS. Los callbacks de
        // abajo reciben la animación conducida igual que la del sistema (onPrepare/onStart/onProgress/onEnd), así que la web
        // sigue recibiendo progress por cuadro. Si el sistema niega el control, queda la animación del sistema.
        final boolean[] driving = {false};         // la app lleva la animación (pedida o en curso)
        final boolean[] takeoverPending = {false}; // pedimos el control: el fin de la del sistema y el arranque de la nuestra no cuentan
        final boolean[] controlDenied = {false};   // el sistema negó el control: no insistir

        // Único escritor de los insets del padre (reemplaza al de SystemBars de Capacitor).
        ViewCompat.setOnApplyWindowInsetsListener(parent, (v, insets) -> {
            boolean keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            finalBottom[0] = keyboardVisible ? imeBottom : 0;
            if (!imeRunning[0]) setPaddingBottom(v, finalBottom[0]); // sin animación (teclado físico, cambios sin animar)
            return new WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE) // el WebView no vuelve a descontar el teclado
                .build();
        });

        ViewCompat.setWindowInsetsAnimationCallback(root,
            new androidx.core.view.WindowInsetsAnimationCompat.Callback(
                androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {

                private boolean isIme(androidx.core.view.WindowInsetsAnimationCompat animation) {
                    return (animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0;
                }

                private void sendCovered(int imeBottomPx) {
                    int coveredCss = Math.max(0, Math.round((imeBottomPx - parent.getPaddingBottom()) / density));
                    if (coveredCss == lastSent[0]) return;
                    lastSent[0] = coveredCss;
                    webView.evaluateJavascript("document.documentElement.style.setProperty('--ey-keyboard-height','"
                        + coveredCss + "px');window.dispatchEvent(new CustomEvent('ey:keyboard',{detail:{phase:'progress',height:"
                        + coveredCss + "}}))", null);
                }

                @Override
                public void onPrepare(@NonNull androidx.core.view.WindowInsetsAnimationCompat animation) {
                    if (!isIme(animation)) return;
                    if (takeoverPending[0]) return; // arranca la animación conducida: el estado sigue el de la del sistema
                    imeRunning[0] = true;
                    startBottom[0] = parent.getPaddingBottom();
                }

                @NonNull
                @Override
                public androidx.core.view.WindowInsetsAnimationCompat.BoundsCompat onStart(
                        @NonNull androidx.core.view.WindowInsetsAnimationCompat animation,
                        @NonNull androidx.core.view.WindowInsetsAnimationCompat.BoundsCompat bounds) {
                    if (!isIme(animation)) return bounds;
                    if (takeoverPending[0]) { takeoverPending[0] = false; return bounds; } // arranque de la conducida: ya está todo hecho
                    targetBottom[0] = finalBottom[0];
                    boolean opening = targetBottom[0] > startBottom[0];
                    holdUntilTaller[0] = -1;
                    if (!opening) {
                        // Cerrar: la página grande desde el primer cuadro. La variable se retiene hasta que la página ya
                        // creció (medido: el JS llegaba un cuadro antes que el nuevo tamaño y la caja saltaba un cuadro).
                        holdUntilTaller[0] = webView.getHeight();
                        setPaddingBottom(parent, targetBottom[0]);
                    }
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
                    boolean open = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
                    int height = insets != null ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom : 0;
                    lastSent[0] = -1;
                    notifyWebKeyboard(webView, "start", open, Math.round(height / density), animation.getDurationMillis());
                    if (IME_DRIVE_ENABLED && !driving[0] && !controlDenied[0] && Build.VERSION.SDK_INT >= 30) {
                        driving[0] = true;
                        takeoverPending[0] = true;
                        final boolean target = opening;
                        root.post(() -> driveImeAnimation(root, target, () -> { driving[0] = false; },
                            () -> { driving[0] = false; takeoverPending[0] = false; controlDenied[0] = true; }));
                    }
                    return bounds;
                }

                @NonNull
                @Override
                public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets,
                        @NonNull java.util.List<androidx.core.view.WindowInsetsAnimationCompat> running) {
                    if (!imeRunning[0]) return insets;
                    if (holdUntilTaller[0] >= 0) {
                        if (webView.getHeight() > holdUntilTaller[0]) holdUntilTaller[0] = -1; // creció: desde el próximo cuadro
                        return insets;
                    }
                    sendCovered(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                    return insets;
                }

                @Override
                public void onEnd(@NonNull androidx.core.view.WindowInsetsAnimationCompat animation) {
                    if (!isIme(animation)) return;
                    if (takeoverPending[0]) return; // es la del sistema, cancelada por la nuestra: la conducida sigue
                    imeRunning[0] = false;
                    // Con la animación conducida, los insets de la raíz todavía no reflejan el final: se usa lo que pidió el despacho.
                    boolean open = targetBottom[0] > 0;
                    int height = targetBottom[0];
                    // El único achique de la página: el layout descuenta el teclado y la variable vuelve a 0.
                    lastSent[0] = -1;
                    webView.evaluateJavascript("document.documentElement.style.setProperty('--ey-keyboard-height','0px')", null);
                    setPaddingBottom(parent, targetBottom[0]);
                    notifyWebKeyboard(webView, "end", open, Math.round(height / density), 0);
                }
            });
        ViewCompat.requestApplyInsets(parent);
        Log.d(TAG, "Keyboard follow installed (single insets writer, per-frame height to the web)");
    }

    private static final long IME_DRIVE_MS = 280;
    // EY-CENTRAL 2026-10-16, 16:00: APAGADO. Con la conducción puesta el usuario vio en el Pixel que "se mueve todo"; la
    // versión sin conducción (animación del sistema, contrato por cuadro) pasó la medición y el circuito completo de
    // EY-PAYROLL. Se vuelve a encender solo después de medirla sin nadie usando el teléfono.
    private static final boolean IME_DRIVE_ENABLED = false;

    /**
     * Conduce la animación del IME (abrir si shown, cerrar si no) con una curva pareja (Material "emphasized decelerate"),
     * en vez de la del sistema (80 % en 90 ms + cola de 170 ms). Toma el control de la animación en curso: el sistema
     * cancela la suya y despacha la nuestra a los mismos callbacks. onDone cuando terminó; onDenied si el sistema no
     * dio el control (queda la del sistema).
     */
    private void driveImeAnimation(View root, boolean shown, Runnable onDone, Runnable onDenied) {
        androidx.core.view.WindowInsetsControllerCompat wic = androidx.core.view.WindowCompat.getInsetsController(getWindow(), root);
        // Abrir: arranque decidido y frenado suave. Cerrar: curva simétrica, sin cola al llegar abajo (medido: con la de abrir,
        // el cierre hacía el 80 % en 90 ms y arrastraba los últimos 70 px durante 170 ms: el "se traba" del usuario).
        final android.view.animation.Interpolator curve = shown
            ? new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            : new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
        wic.controlWindowInsetsAnimation(WindowInsetsCompat.Type.ime(), IME_DRIVE_MS, curve, null,
            new androidx.core.view.WindowInsetsAnimationControlListenerCompat() {
                @Override
                public void onReady(@NonNull androidx.core.view.WindowInsetsAnimationControllerCompat controller, int types) {
                    // Al tomar el control, el sistema ya tiene el IME en su estado FINAL (medido: animaba 346 → 346). Se parte
                    // del estado inicial real y se llega al final, siempre.
                    final int hidden = controller.getHiddenStateInsets().bottom;
                    final int shownB = controller.getShownStateInsets().bottom;
                    final int from = shown ? hidden : shownB;
                    final int to = shown ? shownB : hidden;
                    controller.setInsetsAndAlpha(Insets.of(0, 0, 0, from), 1f, 0f);
                    android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    va.setDuration(IME_DRIVE_MS);
                    va.setInterpolator(curve);
                    va.addUpdateListener(a -> {
                        if (!controller.isReady()) { a.cancel(); return; }
                        float f = (float) a.getAnimatedValue();
                        controller.setInsetsAndAlpha(Insets.of(0, 0, 0, Math.round(from + (to - from) * f)), 1f, f);
                    });
                    va.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator animation) {
                            if (controller.isReady()) controller.finish(shown);
                        }
                    });
                    va.start();
                }
                @Override public void onFinished(@NonNull androidx.core.view.WindowInsetsAnimationControllerCompat controller) { onDone.run(); }
                @Override public void onCancelled(androidx.core.view.WindowInsetsAnimationControllerCompat controller) {
                    if (controller == null) { Log.w(TAG, "IME drive: control denied, keeping system animation"); onDenied.run(); }
                    else onDone.run();
                }
            });
    }

    private static void setPaddingBottom(View v, int bottom) {
        if (v.getPaddingBottom() == bottom) return;
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
    }

    /** `ey:keyboard` para la web: fase, abierto o no, alto en px CSS y duración en ms (0 al terminar). */
    private static void notifyWebKeyboard(WebView webView, String phase, boolean open, int heightCss, long durationMs) {
        String js = "window.dispatchEvent(new CustomEvent('ey:keyboard',{detail:{phase:'" + phase + "',open:" + open
            + ",height:" + heightCss + ",duration:" + durationMs + "}}))";
        webView.evaluateJavascript(js, null);
    }

    private void setupNativeBridge() {
        WebView webView = getBridge().getWebView();

        // EY-CENTRAL 2026-10-08: íconos de la barra de estado según el fondo real (StatusBarAutoContrast)
        statusBarAuto = new StatusBarAutoContrast(this);
        statusBarAuto.attach(webView);

        // Calcular alturas de las barras del sistema
        calculateSystemBarHeights();

        // White background to avoid black flash during load
        getWindow().getDecorView().setBackgroundColor(android.graphics.Color.WHITE);
        webView.setBackgroundColor(android.graphics.Color.WHITE);

        // Habilitar cookies (necesario para OAuth de Clerk)
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Crear el bridge nativo
        nativeBridge = new NativeBridge(this, webView);
        if (pendingSharedUri != null) { // EY-CENTRAL 2026-10-09: archivo compartido que llegó en frío
            nativeBridge.receiveSharedFile(pendingSharedUri);
            pendingSharedUri = null;
        }

        // Agregar JavascriptInterface para comunicación JS -> Java
        webView.addJavascriptInterface(nativeBridge, "AndroidBridge");
        installDocumentStartScript(webView); // EY-CENTRAL 2026-10-08: safe area + stub de Capacitor antes del primer pintado
        installKeyboardFollow(webView); // EY-CENTRAL 2026-10-16: el teclado arrastra la página cuadro a cuadro
        if (!BuildConfig.EY_LOCAL_URL.isEmpty()) WebView.setWebContentsDebuggingEnabled(true); // EY-CENTRAL 2026-10-16: solo la compilación de prueba (chrome://inspect)

        // Iniciar WebSocket para recibir comandos del dashboard
        webSocketService = new WebSocketService(this, nativeBridge);
        nativeBridge.setWebSocketService(webSocketService); // Para enviar respuestas de vuelta al dashboard
        webSocketService.connect();
        Log.d("MainActivity", "WebSocket service started");

        // NO pedir permiso aquí - el onboarding lo maneja con mejor UX

        // Cambiar User-Agent para que Google no detecte WebView
        String originalUserAgent = webView.getSettings().getUserAgentString();
        // Quitar "wv" que indica WebView
        String newUserAgent = originalUserAgent
            .replace("; wv)", ")")
            .replace("Version/4.0 ", "");
        webView.getSettings().setUserAgentString(newUserAgent);
        Log.d("MainActivity", "User-Agent modified: " + newUserAgent);

        // DESACTIVAR popups - el iframe hará redirect directo
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);

        // CACHE: Habilitar cache para mejorar rendimiento en datos móviles
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        Log.d(TAG, "WebView cache enabled (LOAD_DEFAULT)");

        // Obtener el cliente original de Capacitor
        final WebViewClient originalClient = getBridge().getWebViewClient();

        // Crear wrapper que extiende el comportamiento original
        webView.setWebViewClient(new WebViewClient() {

            // CRÍTICO: Este método es el que sirve archivos locales en Capacitor
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Delegar SIEMPRE al cliente original para que Capacitor sirva los archivos
                if (originalClient != null) {
                    return originalClient.shouldInterceptRequest(view, request);
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d("MainActivity", "shouldOverrideUrlLoading: " + url);

                // Manejar URLs especiales sin abrir diálogos de navegador
                if (url.equals("about:blank") ||
                    url.startsWith("about:") ||
                    url.startsWith("javascript:")) {
                    Log.d("MainActivity", "Allowing internal URL: " + url);
                    return false; // Dejar que WebView lo maneje
                }

                // Permitir navegación a localhost y dominios de Employ
                if (url.startsWith("https://localhost") ||
                    (!BuildConfig.EY_LOCAL_URL.isEmpty() && url.startsWith("http://localhost")) || // EY-CENTRAL 2026-10-16: prueba local
                    url.contains("employ.digital") ||
                    url.contains("accounts.google.com") ||
                    url.contains("clerk.")) {
                    Log.d("MainActivity", "Allowing navigation to: " + url);
                    return false; // Cargar en WebView
                }

                // Para todo lo demás, delegar al cliente original de Capacitor
                if (originalClient != null) {
                    return originalClient.shouldOverrideUrlLoading(view, request);
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d("MainActivity", "onPageStarted: " + url);
                offlineErrorThisLoad = false; // EY-CENTRAL 2026-10-09: carga nueva, sin error todavía
                if (originalClient != null) {
                    originalClient.onPageStarted(view, url, favicon);
                }

                // Inyectar CSS temprano para páginas externas de auth (evita flash en Google)
                if (isExternalAuthUrl(url)) {
                    injectSafeAreaCSS(view);
                }
            }

            // EY-CENTRAL 2026-10-09: cada cambio de URL, también los del router de la web (pushState), para recordar el
            // Inicio de la última empresa y arrancar ahí la próxima vez.
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (originalClient != null) {
                    originalClient.doUpdateVisitedHistory(view, url, isReload);
                } else {
                    super.doUpdateVisitedHistory(view, url, isReload);
                }
                rememberOrgUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d("MainActivity", "onPageFinished: " + url);
                if (originalClient != null) {
                    originalClient.onPageFinished(view, url);
                }

                // Inyectar CSS de safe area en TODAS las páginas
                injectSafeAreaCSS(view);
                if (statusBarAuto != null) statusBarAuto.requestSample(); // EY-CENTRAL 2026-10-08

                // Inyectar script para convertir window.open() en redirect
                injectWindowOpenOverride(view);

                // Inyectar bridge en páginas externas (no localhost, no OAuth)
                if (isExternalUrl(url) && !isOAuthUrl(url)) {
                    Log.d("MainActivity", "Webapp loaded: " + url);
                    injectBridgeScript(view);
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                Log.d("MainActivity", "onPageCommitVisible: " + url);

                // Solo ocultar splash cuando la webapp EXTERNA tenga contenido visible
                // NUNCA ocultar en localhost - siempre esperar a la webapp externa
                if (isExternalUrl(url) && !isOAuthUrl(url)) {
                    Log.d(TAG, "Splash: " + "commitVisible " + url);
                    hideSplashWithMinDuration();
                    // EY-CENTRAL 2026-10-09: la webapp volvió a cargar. Si esta carga falló (la página de error del
                    // sistema o un 5xx también "se hacen visibles"), la vista propia se queda.
                    if (!offlineErrorThisLoad) hideOfflineOverlay();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e("MainActivity", "WebView error: " + errorCode + " - " + description + " at " + failingUrl);
                if (originalClient != null) {
                    originalClient.onReceivedError(view, errorCode, description, failingUrl);
                }
            }

            // EY-CENTRAL 2026-10-09 (pedido del usuario): si la PÁGINA PRINCIPAL de la webapp no carga (sin red,
            // DNS, timeout), vista propia en vez de la página de error del sistema. Los recursos secundarios
            // (imágenes, fuentes) no cuentan: de esos se ocupa la web.
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (originalClient != null) {
                    originalClient.onReceivedError(view, request, error);
                } else {
                    super.onReceivedError(view, request, error);
                }
                if (request.isForMainFrame()) {
                    String url = request.getUrl().toString();
                    Log.e("MainActivity", "Main-frame load error " + error.getErrorCode() + " at " + url);
                    if (isExternalUrl(url) && !isOAuthUrl(url)) {
                        offlineErrorThisLoad = true;
                        showOfflineOverlay(url, false);
                    }
                }
            }

            // Servidor caído (5xx) en la página principal: misma vista, otro texto.
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (originalClient != null) {
                    originalClient.onReceivedHttpError(view, request, errorResponse);
                } else {
                    super.onReceivedHttpError(view, request, errorResponse);
                }
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 500) {
                    String url = request.getUrl().toString();
                    Log.e("MainActivity", "Main-frame HTTP " + errorResponse.getStatusCode() + " at " + url);
                    if (isExternalUrl(url) && !isOAuthUrl(url)) {
                        offlineErrorThisLoad = true;
                        showOfflineOverlay(url, true);
                    }
                }
            }
        });

        // WebChromeClient para manejar permisos y popups
        webView.setWebChromeClient(new WebChromeClient() {
            // EY-CENTRAL 2026-10-08: consola de la web al logcat (Log.d; en release proguard lo quita)
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d("WebConsole", m.messageLevel() + " " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                String[] resources = request.getResources();
                Log.d("MainActivity", "WebChromeClient onPermissionRequest: " + java.util.Arrays.toString(resources));

                // Verificar si pide audio
                boolean needsAudio = false;
                for (String resource : resources) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        needsAudio = true;
                        break;
                    }
                }

                if (needsAudio) {
                    // Verificar permiso de Android
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        // Guardar request pendiente y pedir permiso de Android
                        pendingPermissionRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.RECORD_AUDIO},
                                REQUEST_MICROPHONE);
                    } else {
                        // Ya tiene permiso, otorgar al WebView
                        request.grant(resources);
                    }
                } else {
                    // No necesita audio, otorgar directamente
                    request.grant(resources);
                }
            }

            // Sin popup - window.open() no hace nada cuando setSupportMultipleWindows=false
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == REQUEST_MICROPHONE) {
            // Manejar permiso de WebChromeClient
            if (granted) {
                if (pendingPermissionRequest != null) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                    pendingPermissionRequest = null;
                }
            } else {
                if (pendingPermissionRequest != null) {
                    pendingPermissionRequest.deny();
                    pendingPermissionRequest = null;
                }
            }
        }

        // Manejar permiso de NativeBridge (para onboarding)
        if (requestCode == NativeBridge.REQUEST_MICROPHONE_PERMISSION) {
            if (nativeBridge != null) {
                nativeBridge.handleMicrophonePermissionResult(granted);
            }
        }

        // Manejar permiso de push notifications
        if (requestCode == 1003) { // REQUEST_PUSH_PERMISSION
            Log.d(TAG, "Push permission result: granted=" + granted + ", nativeBridge=" + (nativeBridge != null));
            if (nativeBridge != null) {
                // Llamar inmediatamente - el token se fuerza con deleteToken + getToken
                nativeBridge.onPushPermissionResult(granted);
            }
        }
    }

    private void injectWindowOpenOverride(WebView webView) {
        // Sobrescribir window.open() para que haga redirect en lugar de popup
        // Clerk usa popup para OAuth pero necesitamos redirect para mantener la sesión
        String script = "(function() {" +
            "  if (window.__WINDOW_OPEN_OVERRIDE__) return;" +
            "  window.__WINDOW_OPEN_OVERRIDE__ = true;" +
            "  var originalOpen = window.open;" +
            "  window.open = function(url, target, features) {" +
            "    console.log('[WindowOpenOverride] Intercepted window.open:', url);" +
            "    if (url && (url.includes('accounts.google.com') || url.includes('clerk'))) {" +
            "      console.log('[WindowOpenOverride] Redirecting instead of popup');" +
            "      window.location.href = url;" +
            "      return null;" +
            "    }" +
            "    return originalOpen.call(window, url, target, features);" +
            "  };" +
            "  console.log('[WindowOpenOverride] window.open override installed');" +
            "})();";
        webView.evaluateJavascript(script, null);
    }

    private boolean isOAuthUrl(String url) {
        // NO interceptar OAuth - dejar que todo pase en el WebView
        // El User-Agent ya está modificado para que Google no detecte WebView
        // Clerk está en modo redirect, así que no necesitamos Custom Tabs
        return false;
    }

    private void openInCustomTabs(String url) {
        try {
            CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
            CustomTabsIntent customTabsIntent = builder.build();
            customTabsIntent.launchUrl(this, Uri.parse(url));
        } catch (Exception e) {
            // Fallback: abrir en navegador externo
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        }
    }

    private boolean isExternalUrl(String url) {
        // URLs locales de Capacitor (app React)
        if (url == null) return false;
        return !url.startsWith("https://localhost") &&
               !url.startsWith("capacitor://") &&
               !url.startsWith("file://");
    }

    private void injectBridgeScript(WebView webView) {
        String bridgeScript = getBridgeScript();
        webView.evaluateJavascript(bridgeScript, null);
    }

    private String getBridgeScript() {
        // EY-CENTRAL 2026-10-07 (pedido 3 de EY-01): el saludo del puente lleva la versión de la app.
        String appVersionName = "";
        String appBuildCode = "";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersionName = pi.versionName != null ? pi.versionName : "";
            appBuildCode = String.valueOf(pi.versionCode);
        } catch (Exception ignored) {}
        // Obtener datos de sesión biométrica si existe
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String biometricVerified = prefs.getString("biometric_login_verified", "");
        String userId = prefs.getString("userId", "");
        String companyId = prefs.getString("companyId", "");
        String sessionId = prefs.getString("sessionId", "");
        String deviceId = prefs.getString("device_id", "");
        long timestamp = prefs.getLong("biometric_login_timestamp", 0);

        // Verificar si es login biométrico reciente (últimos 5 minutos)
        boolean isBiometricLogin = "true".equals(biometricVerified) &&
            !userId.isEmpty() &&
            (System.currentTimeMillis() - timestamp) < 5 * 60 * 1000;

        String biometricLoginScript = "";
        if (isBiometricLogin) {
            biometricLoginScript =
                "  window.__BIOMETRIC_LOGIN_DATA__ = {" +
                "    type: 'BIOMETRIC_LOGIN_SUCCESS'," +
                "    userId: '" + userId + "'," +
                "    companyId: '" + companyId + "'," +
                "    sessionId: '" + sessionId + "'," +
                "    deviceId: '" + deviceId + "'," +
                "    timestamp: " + timestamp +
                "  };" +
                "  console.log('[Bridge] Biometric login data available:', window.__BIOMETRIC_LOGIN_DATA__);" +
                "  setTimeout(function() {" +
                "    window.postMessage(window.__BIOMETRIC_LOGIN_DATA__, '*');" +
                "    window.dispatchEvent(new CustomEvent('biometricLoginSuccess', { detail: window.__BIOMETRIC_LOGIN_DATA__ }));" +
                "  }, 500);";
        }

        return "(function() {" +
            "  if (window.__CAPACITOR_BRIDGE_INJECTED__) return;" +
            "  window.__CAPACITOR_BRIDGE_INJECTED__ = true;" +
            "  console.log('[Bridge] Injecting native bridge...');" +

            // Inyectar datos de login biométrico si existe
            biometricLoginScript +

            // Guardar referencia al postMessage original
            "  var originalPostMessage = window.postMessage.bind(window);" +

            // Crear objeto parent falso que intercepta postMessage
            "  var parentProxy = {" +
            "    postMessage: function(data, origin) {" +
            "      if (data && data.type === 'CAPACITOR_BRIDGE') {" +
            "        console.log('[Bridge] Intercepted:', data.action);" +
            "        try {" +
            "          window.AndroidBridge.handleBridgeMessage(JSON.stringify(data));" +
            "        } catch(e) {" +
            "          console.error('[Bridge] Error:', e);" +
            "        }" +
            "      } else {" +
            "        originalPostMessage(data, origin);" +
            "      }" +
            "    }" +
            "  };" +

            // Sobrescribir window.parent
            "  try {" +
            "    Object.defineProperty(window, 'parent', {" +
            "      get: function() { return parentProxy; }," +
            "      configurable: true" +
            "    });" +
            "    console.log('[Bridge] window.parent intercepted successfully');" +
            "  } catch(e) {" +
            "    console.error('[Bridge] Could not override window.parent:', e);" +
            "    window.parent = parentProxy;" +
            "  }" +

            // Notificar que el bridge está listo
            "  setTimeout(function() {" +
            "    window.postMessage({" +
            "      type: 'CAPACITOR_BRIDGE_READY'," +
            "      platform: 'android'," +
            "      appVersion: '" + appVersionName + "'," +
            "      appBuild: '" + appBuildCode + "'," +
            "      isNative: true" +
            "    }, '*');" +
            "    console.log('[Bridge] Ready notification sent');" +
            "  }, 100);" +

            "  console.log('[Bridge] Native bridge injected successfully');" +
            "})();";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Manejar resultados de cámara y galería
        if (requestCode == NativeBridge.REQUEST_CAMERA ||
            requestCode == NativeBridge.REQUEST_GALLERY ||
            requestCode == NativeBridge.REQUEST_PICK_IMAGES ||
            requestCode == NativeBridge.REQUEST_PICK_VIDEO) {
            if (nativeBridge != null) {
                nativeBridge.handleCameraResult(requestCode, resultCode, data);
            }
        }

        // files.pick (EY-CENTRAL 2026-10-07): selector de archivos genérico
        if (requestCode == NativeBridge.REQUEST_PICK_FILES) {
            if (nativeBridge != null) {
                nativeBridge.handleFilesResult(resultCode, data);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (captureSharedFile(intent)) return; // EY-CENTRAL 2026-10-09: archivo compartido a Employ
        handleDeepLink(intent);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EY-CENTRAL 2026-10-09 (orden del usuario, "camino simple"): ARCHIVO COMPARTIDO A EMPLOY → GASTO.
    // ACTION_SEND con EXTRA_STREAM (PDF o imagen). En frío el puente todavía no existe cuando llega el intent:
    // se guarda el Uri y se entrega apenas se crea el puente (ver creación de NativeBridge).
    // ═══════════════════════════════════════════════════════════════════════════
    private Uri pendingSharedUri = null;

    private boolean captureSharedFile(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return false;
        Log.d("MainActivity", "Shared file intent: " + uri + " (" + intent.getType() + ")");
        if (nativeBridge != null) {
            nativeBridge.receiveSharedFile(uri);
        } else {
            pendingSharedUri = uri;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EY-CENTRAL 2026-10-07 (orden del usuario): un enlace a la plataforma tocado en un
    // mail, WhatsApp o donde sea abre la APP, no el navegador (Android App Links).
    // Antes solo se atendían los callbacks de OAuth (/auth); cualquier otro enlace de
    // employ.digital se ignoraba (y en frío ni se miraba el intent). Ahora:
    //  - en frío: la app arranca directo en la URL del enlace (en vez de la cacheada);
    //  - abierta: carga la URL en el WebView.
    // Dominios: app.employ.digital y dev.employ.digital (la app mobile NO usa subdominios de organización).
    // Requiere assetlinks.json en el dominio (lado web) para que Android verifique.
    // ═══════════════════════════════════════════════════════════════════════════

    /** URL https de la plataforma que venía en el intent de arranque (cold start con enlace). */
    private String initialDeepLinkUrl = null;

    private static boolean isEmployWebLink(Uri data) {
        if (data == null) return false;
        if (!"https".equalsIgnoreCase(data.getScheme())) return false;
        String host = data.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        // sin subdominios de org (decisión del usuario 2026-10-07). EY-CENTRAL 2026-10-16: también los de payroll, que el
        // manifiesto ya declaraba (pedido de EY-PAYROLL: un enlace a payroll-dev en frío se ignoraba).
        return host.equals("app.employ.digital") || host.equals("dev.employ.digital")
            || host.equals("payroll.employ.digital") || host.equals("payroll-dev.employ.digital");
    }

    /** Se llama en onCreate ANTES de loadWebappDirect: guarda la URL del enlace, si la hay. */
    private void captureInitialDeepLink(Intent intent) {
        // EY-CENTRAL 2026-10-08: push dibujada por el sistema (app en segundo plano) → el toque lanza la app
        // con los `data` del push como extras (url/route), sin ACTION_VIEW.
        String fromPush = PushLinks.targetFromExtras(this, intent);
        if (fromPush != null) {
            initialDeepLinkUrl = fromPush;
            Log.d("MainActivity", "Cold start from push: " + fromPush);
            return;
        }
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (isEmployWebLink(data) && !isOAuthCallback(data.toString())) {
            initialDeepLinkUrl = data.toString();
            Log.d("MainActivity", "Cold start with deep link: " + initialDeepLinkUrl);
        }
    }

    private static boolean isOAuthCallback(String url) {
        return url.contains("/auth") || url.contains("callback");
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri data = intent.getData();

        // EY-CENTRAL 2026-10-08: push dibujada por el sistema con la app abierta → extras url/route.
        if (data == null) {
            String fromPush = PushLinks.targetFromExtras(this, intent);
            if (fromPush != null) {
                WebView wv = getBridge().getWebView();
                if (wv != null) {
                    Log.d("MainActivity", "Loading push target in WebView: " + fromPush);
                    wv.loadUrl(fromPush);
                }
                return;
            }
        }

        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            String url = data.toString();
            Log.d("MainActivity", "Deep link received: " + url);

            WebView webView = getBridge().getWebView();
            if (webView == null) return;

            // 1) Callback de OAuth (comportamiento original)
            if (isOAuthCallback(url)) {
                String webUrl = url;
                if (url.startsWith("com.employ.app://")) {
                    // Convertir scheme custom a HTTPS
                    webUrl = url.replace("com.employ.app://", "https://dev.employ.digital/");
                }
                Log.d("MainActivity", "Loading URL in WebView: " + webUrl);
                webView.loadUrl(webUrl);
                return;
            }

            // 2) Cualquier enlace https de la plataforma (EY-CENTRAL 2026-10-07): se carga tal cual,
            //    con su subdominio de organización y su query.
            if (isEmployWebLink(data)) {
                Log.d("MainActivity", "Loading platform link in WebView: " + url);
                webView.loadUrl(url);
            }
        }
    }

    private StatusBarAutoContrast statusBarAuto; // EY-CENTRAL 2026-10-08

    @Override
    public void onResume() { // BridgeActivity lo declara public
        super.onResume();
        if (statusBarAuto != null) statusBarAuto.start();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (statusBarAuto != null) statusBarAuto.stop();
        // EY-CENTRAL 2026-10-08 (usuario, Xiaomi A1 perdía la sesión al cerrar la app): las cookies del WebView
        // (sesión de Clerk) se persisten en disco en diferido; si el sistema mata la app antes, se pierden.
        // Forzar el guardado al pasar a segundo plano, como recomienda Android.
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        // EY-CENTRAL 2026-10-09 (usuario: "cada vez me muestra Verificando acceso"): la web llega al Inicio con
        // history.replaceState y doUpdateVisitedHistory no siempre lo reporta. Al pasar a segundo plano la URL actual del
        // WebView es confiable: se recuerda ahí también.
        try {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) rememberOrgUrl(wv.getUrl());
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        // Desconectar WebSocket al cerrar la app
        if (webSocketService != null) {
            webSocketService.disconnect();
            Log.d("MainActivity", "WebSocket service disconnected");
        }
        super.onDestroy();
    }
}
