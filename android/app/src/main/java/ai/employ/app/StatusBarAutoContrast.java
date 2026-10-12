package ai.employ.app;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * EY-CENTRAL 2026-10-08 (pedido del usuario): la barra de estado es transparente (edge-to-edge) y
 * debajo se dibuja la webapp, que a veces es blanca (dashboard) y a veces oscura (login). El color
 * de los íconos (hora, batería) se fijaba UNA vez al arrancar y la configuración del plugin StatusBar
 * de Capacitor lo pisaba con íconos claros → sobre fondo blanco no se veían.
 *
 * Ahora la app MIRA el fondo real debajo de la barra (le pregunta a la página el color de fondo efectivo;
 * antes PixelCopy, que en el Pixel 6 devolvía siempre negro) y elige sola: fondo claro → íconos oscuros; fondo oscuro →
 * íconos claros. Con histéresis para no parpadear en fondos intermedios. Se evalúa al terminar de cargar
 * una página, al hacer scroll, y cada segundo mientras la app está en primer plano.
 */
final class StatusBarAutoContrast {
    private static final String TAG = "StatusBarAuto";
    private static final long PERIOD_MS = 1000;
    private static final int SAMPLE_W = 32, SAMPLE_H = 4;
    // Luminancia 0..1: por encima de HI el fondo es claro (íconos oscuros); por debajo de LO, oscuro.
    private static final float HI = 0.62f, LO = 0.45f;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean running = false;
    private boolean busy = false;
    private Boolean lightIcons = null; // null = todavía no decidido
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            sample();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    StatusBarAutoContrast(Activity activity) {
        this.activity = activity;
    }

    void attach(WebView webView) {
        this.webView = webView;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.setOnScrollChangeListener((v, x, y, ox, oy) -> requestSample());
        }
    }

    void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** Evaluar pronto (p. ej. al terminar de cargar una página). */
    void requestSample() {
        if (!running) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 80);
    }

    private int statusBarHeightPx() {
        View decor = activity.getWindow().getDecorView();
        WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor);
        int h = insets != null ? insets.getInsets(WindowInsetsCompat.Type.statusBars()).top : 0;
        if (h <= 0) {
            int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            h = id > 0 ? activity.getResources().getDimensionPixelSize(id) : (int) (24 * activity.getResources().getDisplayMetrics().density);
        }
        return h;
    }

    // EY-CENTRAL 2026-10-08 (usuario, Pixel 6: no se veían la hora ni la batería sobre fondo blanco): PixelCopy
    // devolvía SIEMPRE negro (lum 0,00) aunque la pantalla fuera blanca — en este equipo no captura el contenido
    // del WebView. Ahora se le pregunta a la página el color de fondo efectivo debajo de la barra (tres puntos,
    // subiendo por el árbol hasta el primer fondo opaco). Exacto, sin capturar píxeles.
    private static final String SAMPLE_JS_PREFIX =
        "(function(){try{var w=window.innerWidth,y=";
    private static final String SAMPLE_JS_SUFFIX =
        ";var pts=[[w*0.1,y],[w*0.5,y],[w*0.9,y]];var acc=[0,0,0],n=0;" +
        "for(var i=0;i<pts.length;i++){var el=document.elementFromPoint(pts[i][0],pts[i][1]);" +
        "while(el){var c=getComputedStyle(el).backgroundColor;" +
        "var m=c&&c.match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)/);" +
        "if(m&&(m[4]===undefined||parseFloat(m[4])>0.5)){acc[0]+=+m[1];acc[1]+=+m[2];acc[2]+=+m[3];n++;break;}" +
        "el=el.parentElement;}}" +
        "if(!n){return '248,249,255';}" +
        "return Math.round(acc[0]/n)+','+Math.round(acc[1]/n)+','+Math.round(acc[2]/n);}catch(e){return '';}})()";

    private void sample() {
        if (busy || webView == null || !webView.isAttachedToWindow()) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int yCss = Math.max(1, Math.round((statusBarHeightPx() / density) / 2f));
        busy = true;
        try {
            webView.evaluateJavascript(SAMPLE_JS_PREFIX + yCss + SAMPLE_JS_SUFFIX, value -> {
                busy = false;
                if (value == null) return;
                String v = value.replace("\"", "").trim();
                String[] p = v.split(",");
                if (p.length != 3) return;
                try {
                    int r = Integer.parseInt(p[0].trim()), g = Integer.parseInt(p[1].trim()), b = Integer.parseInt(p[2].trim());
                    float lum = (float) ((0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0);
                    Log.v(TAG, "sample rgb=" + v + " lum=" + String.format(java.util.Locale.US, "%.2f", lum));
                    apply(lum);
                } catch (NumberFormatException ignored) {}
            });
        } catch (Exception e) {
            busy = false;
            Log.w(TAG, "evaluateJavascript: " + e.getMessage());
        }
    }

    private void apply(float lum) {
        Boolean wantDarkIcons;
        if (lum >= HI) wantDarkIcons = Boolean.TRUE;          // fondo claro
        else if (lum <= LO) wantDarkIcons = Boolean.FALSE;    // fondo oscuro
        else wantDarkIcons = null;                             // zona gris: no cambiar
        if (wantDarkIcons == null) return;
        boolean darkIcons = wantDarkIcons;
        lightIcons = !darkIcons;
        // EY-CENTRAL 2026-10-11 (usuario, Pixel con la 392: en el Inicio a veces no se veían la hora ni la batería):
        // se comparaba contra la última decisión guardada, pero configureSystemBars() (al recuperar el foco y al
        // terminar el splash) volvía a poner íconos oscuros por su cuenta; como la decisión no había cambiado, nunca
        // se corregía y quedaban oscuros sobre fondo oscuro. Ahora se compara contra lo que la ventana tiene puesto.
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        if (c != null && c.isAppearanceLightStatusBars() != darkIcons) {
            c.setAppearanceLightStatusBars(darkIcons); // true = íconos oscuros (para fondo claro)
            Log.d(TAG, "lum=" + String.format(java.util.Locale.US, "%.2f", lum) + " → íconos " + (darkIcons ? "oscuros" : "claros"));
        }
    }

    /**
     * Color de íconos a usar al reconfigurar las barras: el último decidido según el fondo, o íconos oscuros si
     * todavía no se midió (el splash y la mayoría de las pantallas son claros).
     */
    boolean darkIconsForNow() {
        return lightIcons == null || !lightIcons;
    }
}
