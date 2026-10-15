import UIKit
import Capacitor
import WebKit
import Network
import UserNotifications
import FirebaseCore
import FirebaseFirestore
import FirebaseMessaging
import WatchConnectivity

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?
    var splashView: UIView?
    var splashStartTime: Date?
    var observation: NSKeyValueObservation?
    var urlObservation: NSKeyValueObservation?
    var splashHidden = false
    // EY-CENTRAL 2026-10-09 (usuario: "¿por qué el splash dura tanto?"): antes 2 s de mínimo (espera de adorno) y el
    // splash se iba recién con la carga COMPLETA de la página. Ahora: sin mínimo, y se va en el primer cuadro pintado
    // de la webapp (hideSplashOnFirstPaint, avisado por OAuthBridgeViewController). La carga completa queda de respaldo.
    let minimumSplashDuration: TimeInterval = 0

    // EY-CENTRAL 2026-10-09: vista propia cuando la webapp no carga (ver showOfflineView)
    var offlineView: UIView?
    var offlineTitleLabel: UILabel?
    var offlineBodyLabel: UILabel?
    var offlineFailedURL: URL?
    var offlinePathMonitor: NWPathMonitor?

    // Native Bridge
    var nativeBridge: NativeBridge?

    // Cache keys for URLs from Firebase
    let cachedWebappUrlKey = "cached_webapp_url"
    let cachedApiUrlKey = "cached_api_url"
    let cachedWsUrlKey = "cached_ws_url"

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Configure Firebase (GoogleService-Info.plist must be in bundle)
        FirebaseApp.configure()

        // Configure FCM
        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self

        // CRITICAL: Register for remote notifications to get APNs token
        // Without this, FCM token will be nil!
        UIApplication.shared.registerForRemoteNotifications()

        splashStartTime = Date()

        // EY-CENTRAL 2026-10-15 (mismo arreglo que Employ 2.9.5 del 2026-10-13, captura zRef/zError/55): con el teclado
        // abierto el WebView se achica y lo que queda detrás (esquinas redondeadas del teclado) es la ventana, que era
        // negra. Blanco como la página, también con el iPhone en modo oscuro (la webapp es solo clara).
        self.window?.backgroundColor = .white

        // Mostrar splash inmediatamente
        self.showSplash()

        // Cargar webapp inmediatamente (sin delays)
        self.setupNativeBridge()
        self.loadWebappDirect()

        return true
    }

    // APNS Token received
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let tokenString = deviceToken.map { String(format: "%02.2hhx", $0) }.joined()
        print("[APNs] Device token received: \(String(tokenString.prefix(20)))...")
        Messaging.messaging().apnsToken = deviceToken
        print("[APNs] Token passed to Firebase Messaging")
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("[APNs] Failed to register for remote notifications: \(error.localizedDescription)")
        // This is expected in Simulator - push notifications only work on physical devices
        #if targetEnvironment(simulator)
        print("[APNs] Running in Simulator - Push notifications are not available")
        #endif
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NATIVE BRIDGE SETUP
    // ═══════════════════════════════════════════════════════════════════════════

    private func setupNativeBridge() {
        guard let window = self.window,
              let rootVC = window.rootViewController,
              let webView = findWebView(in: rootVC.view) else {
            // Reintentar
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                self.setupNativeBridge()
            }
            return
        }

        // Create native bridge
        let bridge = NativeBridge(webView: webView, viewController: rootVC)
        nativeBridge = bridge

        // Register message handler safely
        webView.configuration.userContentController.add(bridge, name: "iOSBridge")

        print("[NativeBridge] Registered message handler")

        // Connect WebSocket service for remote commands
        WebSocketService.shared.nativeBridge = bridge
        WebSocketService.shared.connect()
        // EY-CENTRAL 2026-10-08: al crearse el bridge de Capacitor, su NotificationRouter se queda con el
        // delegate de notificaciones y willPresent/didReceive de AppDelegate dejan de correr (tocar una
        // push no llevaba a ningún lado). Se vuelve a tomar el delegate acá, DESPUÉS del bridge.
        UNUserNotificationCenter.current().delegate = self
        print("[WebSocket] Service started")

        // Initialize Watch Connectivity to sync data with Apple Watch
        _ = WatchConnectivityService.shared
        print("[WatchConn] Service initialized")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODO DIRECTO: Carga la webapp sin pasar por React/Capacitor
    // ═══════════════════════════════════════════════════════════════════════════

    private func loadWebappDirect() {
        let defaults = UserDefaults.standard
        let cachedUrl = defaults.string(forKey: cachedWebappUrlKey)
        let cachedWsUrl = defaults.string(forKey: cachedWsUrlKey)

        if let cached = cachedUrl, !cached.isEmpty {
            // Hay cache → usar inmediatamente
            print("[DIRECT MODE] Using cached URL: \(cached)")

            // Configure WebSocket with cached URL
            if let wsUrl = cachedWsUrl {
                WebSocketService.shared.configure(wsUrl: wsUrl)
            }

            loadUrlInWebView(startURL(forCached: cached))
            // Actualizar cache en background
            updateUrlFromFirebase()
        } else {
            // No hay cache → cargar desde Firebase
            print("[DIRECT MODE] No cache, loading from Firebase...")
            loadUrlFromFirebase()
        }
    }

    private func loadUrlInWebView(_ urlString: String) {
        guard let window = self.window,
              let rootVC = window.rootViewController,
              let webView = self.findWebView(in: rootVC.view) else {
            // Reintentar si el WebView no esta listo
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                self.loadUrlInWebView(urlString)
            }
            return
        }

        // ***--- EY-CENTRAL 2026-10-09 (video 09:25 del usuario, build 388: Inicio 59 px arriba y tirón 0,3 s después):
        // acá estamos dentro de didFinishLaunching, la ventana todavía no avisó sus insets (0) y el script de inicio
        // de documento con la safe area se instalaba recién después, con la página ya pedida al servidor. Se espera
        // a que la ventana tenga insets (unos pocos ciclos; tope 1 s) y se instala el script ANTES de cargar la URL:
        // así el primer documento ya nace con --status-bar-height. ---***
        if window.safeAreaInsets.top <= 0 && safeAreaWaitTries < 20 {
            safeAreaWaitTries += 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                self.loadUrlInWebView(urlString)
            }
            return
        }
        (rootVC as? OAuthBridgeViewController)?.installDocumentStartScript()

        if let url = URL(string: urlString) {
            print("[DIRECT MODE] Loading webapp now: \(urlString) (safe top \(Int(window.safeAreaInsets.top)) px tras \(safeAreaWaitTries) esperas)")
            webView.load(URLRequest(url: url))
            self.observeWebViewLoading(webView)
        }
    }
    private var safeAreaWaitTries = 0

    private func loadUrlFromFirebase() {
        let db = Firestore.firestore()

        // 1. Obtener platforms/app-mobile-payroll para saber la versión actual
        db.collection("platforms").document("app-mobile-payroll").getDocument { [weak self] document, error in
            guard let self = self else { return }

            if let error = error {
                print("[DIRECT MODE] Firebase error: \(error.localizedDescription)")
                return
            }

            guard let data = document?.data(),
                  let currentVersion = data["current"] as? String else {
                print("[DIRECT MODE] No current version in platform doc")
                return
            }

            print("[DIRECT MODE] Current version: \(currentVersion)")

            // 2. Obtener platforms/app-mobile-payroll/versions/{version} para las URLs
            db.collection("platforms").document("app-mobile-payroll")
                .collection("versions").document(currentVersion)
                .getDocument { [weak self] versionDoc, versionError in
                    guard let self = self else { return }

                    if let versionError = versionError {
                        print("[DIRECT MODE] Firebase version error: \(versionError.localizedDescription)")
                        return
                    }

                    guard let versionData = versionDoc?.data(),
                          let urls = versionData["urls"] as? [String: Any],
                          let webappUrl = urls["webapp"] as? String else {
                        print("[DIRECT MODE] No urls in version doc")
                        return
                    }

                    // Cache all URLs
                    let apiUrl = urls["api"] as? String
                    let wsUrl = urls["ws"] as? String

                    self.saveUrlsToCache(webapp: webappUrl, api: apiUrl, ws: wsUrl)
                    print("[DIRECT MODE] Got URLs from Firebase v\(currentVersion) - webapp: \(webappUrl)")

                    // Configure WebSocket with dynamic URL
                    if let wsUrl = wsUrl {
                        WebSocketService.shared.configure(wsUrl: wsUrl)
                    }

                    self.loadUrlInWebView(webappUrl)
                }
        }
    }

    private func updateUrlFromFirebase() {
        let db = Firestore.firestore()

        // 1. Obtener versión actual
        db.collection("platforms").document("app-mobile-payroll").getDocument { [weak self] document, error in
            guard let self = self,
                  let data = document?.data(),
                  let currentVersion = data["current"] as? String else { return }

            // 2. Obtener URLs de la versión
            db.collection("platforms").document("app-mobile-payroll")
                .collection("versions").document(currentVersion)
                .getDocument { [weak self] versionDoc, _ in
                    guard let self = self,
                          let versionData = versionDoc?.data(),
                          let urls = versionData["urls"] as? [String: Any],
                          let webappUrl = urls["webapp"] as? String else { return }

                    let apiUrl = urls["api"] as? String
                    let wsUrl = urls["ws"] as? String

                    self.saveUrlsToCache(webapp: webappUrl, api: apiUrl, ws: wsUrl)
                    print("[DIRECT MODE] Background update - cached URLs from v\(currentVersion)")

                    // Update WebSocket URL if changed
                    if let wsUrl = wsUrl {
                        WebSocketService.shared.configure(wsUrl: wsUrl)
                    }
                }
        }
    }

    private func saveUrlsToCache(webapp: String, api: String?, ws: String?) {
        let defaults = UserDefaults.standard
        defaults.set(webapp, forKey: cachedWebappUrlKey)
        if let api = api {
            defaults.set(api, forKey: cachedApiUrlKey)
        }
        if let ws = ws {
            defaults.set(ws, forKey: cachedWsUrlKey)
        }
    }

    private func observeWebViewLoading(_ webView: WKWebView) {
        observation = webView.observe(\.isLoading, options: [.new]) { [weak self] webView, change in
            guard let self = self,
                  let isLoading = change.newValue,
                  !isLoading,
                  let url = webView.url?.absoluteString else { return }

            print("[Splash] Page loaded: \(url)")

            if self.isExternalUrl(url) {
                print("[Splash] External URL detected - hiding splash")
                self.onWebViewLoaded()
            }
        }
    }

    private func showSplash() {
        guard let window = self.window else { return }

        let storyboard = UIStoryboard(name: "LaunchScreen", bundle: nil)
        if let splashVC = storyboard.instantiateInitialViewController() {
            splashView = splashVC.view
            splashView?.frame = window.bounds
            splashView?.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            window.addSubview(splashView!)

            // Start logo pulse animation
            self.startLogoPulseAnimation()

            // ***--- EY-CENTRAL 2026-10-09 (usuario: "al abrir veo todo blanco, no aparece la e"; video 09:25, build
            // 388): la "e" del launch screen del sistema se desvanecía en 0,3 s y quedaba blanco 1,4 s hasta
            // "Verificando acceso". Este splash se agrega a la ventana DENTRO de didFinishLaunching, pero la vista del
            // controlador raíz (el WebView, blanco por systemBackground) la instala UIKit DESPUÉS, al hacer visible la
            // ventana del storyboard: queda ENCIMA y tapa el splash. Se lo vuelve a traer al frente cuando la ventana
            // ya es visible (próximo ciclo y al activarse la app), mientras siga en pantalla. ---***
            DispatchQueue.main.async { [weak self] in self?.bringSplashToFront() }
        }
    }

    // MARK: - Arrancar en el Inicio de la última empresa
    // ***--- EY-CENTRAL 2026-10-09 (orden del usuario: "si ya tenemos la info en caché, ¿por qué se muestra
    // Redirigiendo...?"): la app siempre abría la portada (/) y la web, en CADA apertura, esperaba la sesión, sincronizaba
    // el usuario, calculaba la empresa y mostraba "Acceso confirmado / Redirigiendo..." (~4,5 s en el video 09:25) para
    // terminar siempre en el mismo Inicio. Ahora la app recuerda el Inicio de la última empresa visitada
    // (https://<host>/organization/<id>, solo el Inicio, no la pantalla interna) y arranca ahí. No se saltea ninguna
    // validación: el middleware y el servidor del Inicio revisan sesión y acceso igual (sin sesión → /sign-in; redirecciones
    // del Inicio, como portal u onboarding, las hace su page.tsx). Se olvida al pasar por /sign-in, /sign-up, /sign-out o
    // /sso (otro usuario puede entrar) y si el host de la webapp cambia (DEV/PRD). Un enlace o una push mandan sobre esto.
    let lastOrgUrlKey = "last_org_home_url"
    static let platformHosts: Set<String> = ["app.employ.digital", "dev.employ.digital"]

    /// Guarda el Inicio de la organización de esta URL, o lo olvida si la URL es de inicio/cierre de sesión.
    func rememberOrgURL(_ url: URL?) {
        // EY-CENTRAL 2026-10-09 (log del Pixel): la web manda a los usuarios de prueba de app. a dev.employ.digital
        // (isUserTestUrl): se aceptan los dos hosts de la plataforma, no solo el cacheado.
        guard let url = url, let host = url.host?.lowercased(), Self.platformHosts.contains(host) else { return }
        let path = url.path
        let defaults = UserDefaults.standard
        if ["/sign-in", "/sign-up", "/sign-out", "/sso"].contains(where: { path.hasPrefix($0) }) {
            if defaults.string(forKey: lastOrgUrlKey) != nil {
                defaults.removeObject(forKey: lastOrgUrlKey)
                print("[StartURL] Forgotten (auth page)")
            }
            return
        }
        let parts = path.split(separator: "/")
        guard parts.count >= 2, parts[0] == "organization", !parts[1].isEmpty else { return }
        let home = "\(url.scheme ?? "https")://\(host)/organization/\(parts[1])"
        if defaults.string(forKey: lastOrgUrlKey) != home {
            defaults.set(home, forKey: lastOrgUrlKey)
            print("[StartURL] Remembered \(home)")
        }
    }

    /// URL de arranque: el Inicio de la última empresa si es del mismo host que la webapp cacheada; si no, la cacheada.
    private func startURL(forCached cached: String) -> String {
        guard let last = UserDefaults.standard.string(forKey: lastOrgUrlKey),
              let lastHost = URL(string: last)?.host?.lowercased(),
              Self.platformHosts.contains(lastHost) else { return cached }
        print("[StartURL] Starting at last organization home: \(last)")
        return last
    }

    /// Splash por encima de todo mientras exista (la vista raíz se instala después de didFinishLaunching).
    private func bringSplashToFront() {
        guard let window = self.window, let splash = splashView, splash.superview === window else { return }
        window.bringSubviewToFront(splash)
        if let offline = offlineView, offline.superview === window { window.bringSubviewToFront(offline) }
    }

    /// Start pulse animation on the splash logo (breathe effect like Android)
    private func startLogoPulseAnimation() {
        guard let splash = splashView else { return }

        // Find the ImageView in the splash (it's the logo)
        guard let logoImageView = findImageView(in: splash) else {
            print("[Splash] Could not find logo ImageView")
            return
        }

        animateLogoPulse(logoImageView)
    }

    private func findImageView(in view: UIView) -> UIImageView? {
        if let imageView = view as? UIImageView {
            return imageView
        }
        for subview in view.subviews {
            if let imageView = findImageView(in: subview) {
                return imageView
            }
        }
        return nil
    }

    /// Animate logo with pulse/breathe effect (scale 1.0 → 1.08, alpha 1.0 → 0.85)
    private func animateLogoPulse(_ logoView: UIImageView) {
        UIView.animate(
            withDuration: 1.25,
            delay: 0,
            options: [.curveEaseInOut],
            animations: {
                logoView.transform = CGAffineTransform(scaleX: 1.08, y: 1.08)
                logoView.alpha = 0.85
            }
        ) { _ in
            UIView.animate(
                withDuration: 1.25,
                delay: 0,
                options: [.curveEaseInOut],
                animations: {
                    logoView.transform = .identity
                    logoView.alpha = 1.0
                }
            ) { [weak self] _ in
                // Repeat if splash is still visible
                if self?.splashView != nil {
                    self?.animateLogoPulse(logoView)
                }
            }
        }
    }

    private func observeWebView() {
        // Buscar el webview y observar cuando termine de cargar
        findWebViewAndObserve()
    }

    private func findWebViewAndObserve() {
        guard let window = self.window,
              let rootVC = window.rootViewController,
              let webView = findWebView(in: rootVC.view) else {
            // Reintentar
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                self.findWebViewAndObserve()
            }
            return
        }

        // Observe loading state to detect when pages finish loading
        observation = webView.observe(\.isLoading, options: [.new]) { [weak self] webView, change in
            guard let self = self,
                  let isLoading = change.newValue,
                  !isLoading,
                  let url = webView.url?.absoluteString else { return }

            print("[Splash] Page loaded: \(url)")

            // Only hide splash when external webapp is visible (not localhost)
            if self.isExternalUrl(url) {
                print("[Splash] External URL detected - hiding splash")
                self.onWebViewLoaded()
            }
        }
    }

    /// Check if URL is external (not localhost/capacitor)
    private func isExternalUrl(_ url: String) -> Bool {
        return !url.isEmpty &&
               !url.hasPrefix("https://localhost") &&
               !url.hasPrefix("capacitor://") &&
               !url.hasPrefix("file://") &&
               !url.contains("accounts.google.com") &&
               !url.contains("clerk.")
    }

    private func findWebView(in view: UIView) -> WKWebView? {
        if let webView = view as? WKWebView {
            return webView
        }
        for subview in view.subviews {
            if let webView = findWebView(in: subview) {
                return webView
            }
        }
        return nil
    }

    private func onWebViewLoaded() {
        guard !splashHidden else { return }
        splashHidden = true
        observation?.invalidate()
        observation = nil

        // Inject bridge script
        injectBridgeScript()

        // Inject safe area CSS variables
        injectSafeAreaCSS()

        // Observe URL changes to re-inject CSS on navigation
        observeURLChanges()

        let elapsed = Date().timeIntervalSince(splashStartTime ?? Date())
        let remaining = max(0, minimumSplashDuration - elapsed)

        DispatchQueue.main.asyncAfter(deadline: .now() + remaining) {
            self.hideSplash()
        }
    }

    /// Inject bridge script to intercept window.parent.postMessage
    func injectBridgeScript() { // EY-CENTRAL 2026-10-09: tambien la llama OAuthBridgeViewController en cada didFinish (reload)
        guard let window = self.window,
              let rootVC = window.rootViewController,
              let webView = findWebView(in: rootVC.view) else {
            return
        }

        // EY-CENTRAL 2026-10-07 (pedido 3 de EY-01): el saludo del puente lleva la versión de la app.
        let appVersion = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? ""
        let appBuild = (Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? ""

        let script = """
        (function() {
            if (window.__CAPACITOR_BRIDGE_INJECTED__) return;
            window.__CAPACITOR_BRIDGE_INJECTED__ = true;
            console.log('[Bridge] Injecting native bridge...');

            // Guardar referencia al postMessage original
            var originalPostMessage = window.postMessage.bind(window);

            // Crear objeto parent falso que intercepta postMessage
            var parentProxy = {
                postMessage: function(data, origin) {
                    if (data && data.type === 'CAPACITOR_BRIDGE') {
                        console.log('[Bridge] Intercepted:', data.action);
                        try {
                            if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.iOSBridge) {
                                window.webkit.messageHandlers.iOSBridge.postMessage(data);
                            }
                        } catch(e) {
                            console.error('[Bridge] Error:', e);
                        }
                    } else {
                        originalPostMessage(data, origin);
                    }
                }
            };

            // Sobrescribir window.parent
            try {
                Object.defineProperty(window, 'parent', {
                    get: function() { return parentProxy; },
                    configurable: true
                });
                console.log('[Bridge] window.parent intercepted successfully');
            } catch(e) {
                console.error('[Bridge] Could not override window.parent:', e);
                window.parent = parentProxy;
            }

            // Notificar que el bridge está listo
            setTimeout(function() {
                window.postMessage({
                    type: 'CAPACITOR_BRIDGE_READY',
                    platform: 'ios',
                    appVersion: '\(appVersion)',
                    appBuild: '\(appBuild)',
                    isNative: true
                }, '*');
                console.log('[Bridge] Ready notification sent');
            }, 100);

            console.log('[Bridge] Native bridge injected successfully');
        })();
        """

        webView.evaluateJavaScript(script) { _, error in
            if let error = error {
                print("[iOSBridge] Error injecting bridge script: \(error)")
            } else {
                print("[iOSBridge] Bridge script injected successfully")
            }
        }
    }

    /// Inject CSS variables with safe area insets for the webapp to use
    func injectSafeAreaCSS() { // EY-CENTRAL 2026-10-09: tambien la llama OAuthBridgeViewController en cada didFinish (reload)
        guard let window = self.window,
              let rootVC = window.rootViewController,
              let webView = findWebView(in: rootVC.view) else {
            return
        }

        // Get safe area insets from the window
        let insets: UIEdgeInsets
        if #available(iOS 11.0, *) {
            insets = window.safeAreaInsets
        } else {
            insets = UIEdgeInsets(top: UIApplication.shared.statusBarFrame.height, left: 0, bottom: 0, right: 0)
        }

        // EY-CENTRAL 2026-10-09: el WebView es la vista raiz (Capacitor 8, `view = webView`), va de borde a borde: arriba
        // va el inset real. Esta inyeccion tardia es solo refuerzo: el valor ya llega al inicio del documento
        // (OAuthBridgeViewController.installDocumentStartScript), asi no hay tiron.
        let topInset = Int(insets.top)
        let bottomInset = Int(insets.bottom)

        let css = """
        :root {
            --status-bar-height: \(topInset)px;
            --nav-bar-height: \(bottomInset)px;
            --safe-area-inset-top: \(topInset)px;
            --safe-area-inset-bottom: \(bottomInset)px;
        }
        """

        // EY-CENTRAL 2026-10-09: además del <style> (una sola vez), SIEMPRE se fijan las variables inline en <html>,
        // por si la web las perdió (hidratación, recarga): es idempotente y barato.
        let script = """
        (function() {
            var r = document.documentElement;
            if (r) {
                r.style.setProperty('--status-bar-height', '\(topInset)px');
                r.style.setProperty('--safe-area-inset-top', '\(topInset)px');
                r.style.setProperty('--nav-bar-height', '\(bottomInset)px');
                r.style.setProperty('--safe-area-inset-bottom', '\(bottomInset)px');
            }
            if (document.getElementById('__SAFE_AREA_CSS__') || !document.head) return;
            var style = document.createElement('style');
            style.id = '__SAFE_AREA_CSS__';
            style.textContent = '\(css.replacingOccurrences(of: "\n", with: " "))';
            document.head.appendChild(style);
            console.log('[SafeArea] Injected CSS variables - top: \(topInset)px, bottom: \(bottomInset)px');
        })();
        """

        webView.evaluateJavaScript(script) { _, error in
            if let error = error {
                print("[SafeArea] Error injecting CSS: \(error)")
            } else {
                print("[SafeArea] CSS variables injected - top: \(topInset)px, bottom: \(bottomInset)px")
            }
        }
    }

    /// Observe URL changes to re-inject CSS on page navigation
    private func observeURLChanges() {
        guard let window = self.window,
              let rootVC = window.rootViewController,
              let webView = findWebView(in: rootVC.view) else {
            return
        }

        rememberOrgURL(webView.url) // EY-CENTRAL 2026-10-09: la URL con la que terminó la primera carga
        urlObservation = webView.observe(\.url, options: [.new]) { [weak self] wv, _ in
            self?.rememberOrgURL(wv.url) // EY-CENTRAL 2026-10-09: abrir la próxima vez directo en el Inicio de esta empresa
            // EY-CENTRAL 2026-10-09: la safe area va YA (en una navegación del router la página sigue viva y pinta en
            // el mismo cuadro); medio segundo después se repite junto con el bridge, por si fue una carga completa.
            self?.injectSafeAreaCSS()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self?.injectBridgeScript()
                self?.injectSafeAreaCSS()
            }
        }
    }

    // MARK: - Vista propia "Sin conexión" / "Employ no responde"
    // ***--- EY-CENTRAL 2026-10-09 (pedido del usuario): la webapp no cargó (sin red, DNS, timeout o 5xx en la
    // página principal). Antes nadie atendía el fallo: quedaba el splash para siempre o la pantalla en blanco.
    // Ahora: vista nativa con el logo del splash, un texto y "Reintentar"; tapa el splash si todavía está y
    // reintenta sola cuando vuelve la red. Se quita en didCommit de la webapp (la carga volvió a andar).
    // La dispara OAuthBridgeViewController (didFailProvisionalNavigation / didFail / respuesta 5xx). ---***
    func showOfflineView(failedURL: URL?, serverError: Bool) {
        if let u = failedURL { offlineFailedURL = u }
        let title = serverError ? "Employ no responde" : "Sin conexión"
        let body = serverError
            ? "Hubo un problema al cargar Employ. Intenta de nuevo en unos segundos."
            : "No se pudo cargar Employ. Revisa tu conexión e intenta de nuevo."
        if offlineView != nil {
            offlineTitleLabel?.text = title
            offlineBodyLabel?.text = body
            return
        }
        guard let window = self.window else { return }

        let view = UIView(frame: window.bounds)
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.backgroundColor = .systemBackground

        let logo = UIImageView(image: UIImage(named: "Splash"))
        logo.contentMode = .scaleAspectFit
        logo.translatesAutoresizingMaskIntoConstraints = false

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 20, weight: .semibold)
        titleLabel.textColor = .label
        titleLabel.textAlignment = .center

        let bodyLabel = UILabel()
        bodyLabel.text = body
        bodyLabel.font = .systemFont(ofSize: 15)
        bodyLabel.textColor = .secondaryLabel
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        let retry = UIButton(type: .system)
        retry.setTitle("Reintentar", for: .normal)
        retry.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        retry.setTitleColor(.white, for: .normal)
        retry.backgroundColor = .systemBlue
        retry.layer.cornerRadius = 12
        retry.contentEdgeInsets = UIEdgeInsets(top: 12, left: 28, bottom: 12, right: 28)
        retry.addTarget(self, action: #selector(retryOfflineLoad), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [logo, titleLabel, bodyLabel, retry])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 8
        stack.setCustomSpacing(24, after: logo)
        stack.setCustomSpacing(24, after: bodyLabel)
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            logo.widthAnchor.constraint(equalToConstant: 64),
            logo.heightAnchor.constraint(equalToConstant: 64),
            stack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
        ])

        window.addSubview(view)
        window.bringSubviewToFront(view)
        offlineView = view
        offlineTitleLabel = titleLabel
        offlineBodyLabel = bodyLabel

        // El splash (si sigue) se va: la espera terminó, aunque mal.
        if let splash = splashView {
            splash.removeFromSuperview()
            splashView = nil
            splashHidden = true
            observation?.invalidate()
            observation = nil
        }
        startWatchingNetworkForRetry()
        print("[Offline] Shown (\(serverError ? "server error" : "no connection")) for \(failedURL?.absoluteString ?? "-")")
    }

    func hideOfflineView() {
        stopWatchingNetwork()
        guard let view = offlineView else { return }
        offlineView = nil
        offlineTitleLabel = nil
        offlineBodyLabel = nil
        UIView.animate(withDuration: 0.2, animations: { view.alpha = 0 }) { _ in
            view.removeFromSuperview()
        }
        print("[Offline] Hidden - webapp visible")
    }

    @objc func retryOfflineLoad() {
        guard let window = self.window,
              let rootVC = window.rootViewController,
              let webView = findWebView(in: rootVC.view) else { return }
        let url = offlineFailedURL ?? webappBaseURL()
        print("[Offline] Retrying: \(url.absoluteString)")
        webView.load(URLRequest(url: url))
    }

    // Cuando vuelve la red, reintenta sola (el usuario no tiene que tocar nada).
    private func startWatchingNetworkForRetry() {
        if offlinePathMonitor != nil { return }
        let monitor = NWPathMonitor()
        var wasSatisfied = false
        monitor.pathUpdateHandler = { [weak self] path in
            let ok = path.status == .satisfied
            if ok && !wasSatisfied {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                    guard let self = self, self.offlineView != nil else { return }
                    self.retryOfflineLoad()
                }
            }
            wasSatisfied = ok
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
        offlinePathMonitor = monitor
    }

    private func stopWatchingNetwork() {
        offlinePathMonitor?.cancel()
        offlinePathMonitor = nil
    }

    /// La webapp pintó su primer cuadro: el splash se va ya (sin esperar la carga completa).
    func hideSplashOnFirstPaint() {
        guard splashView != nil else { return }
        print("[Splash] Web first paint - hiding splash")
        hideSplash()
    }

    private func hideSplash() {
        guard let splash = splashView else { return }
        UIView.animate(withDuration: 0.2, animations: {
            splash.alpha = 0
        }) { _ in
            splash.removeFromSuperview()
            self.splashView = nil
        }
    }

    func applicationWillResignActive(_ application: UIApplication) {}
    func applicationDidEnterBackground(_ application: UIApplication) {
        // EY-CENTRAL 2026-10-09: la URL actual del WebView al ir a segundo plano es confiable (la web llega al Inicio con
        // history.replaceState): se recuerda también acá para arrancar la próxima vez en este Inicio.
        if let window = self.window, let rootVC = window.rootViewController, let wv = findWebView(in: rootVC.view) {
            rememberOrgURL(wv.url)
        }
    }
    func applicationWillEnterForeground(_ application: UIApplication) {}

    func applicationDidBecomeActive(_ application: UIApplication) {
        bringSplashToFront() // EY-CENTRAL 2026-10-09: la ventana ya es visible; el splash no puede quedar debajo

        // Clear badge count when app becomes active
        UIApplication.shared.applicationIconBadgeNumber = 0

        // Also clear delivered notifications from notification center
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()

        // For iOS 16+, also reset badge using the new API
        if #available(iOS 16.0, *) {
            UNUserNotificationCenter.current().setBadgeCount(0) { error in
                if let error = error {
                    print("[Badge] Error clearing badge: \(error)")
                }
            }
        }
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Cleanup observers to prevent memory leaks
        observation?.invalidate()
        observation = nil
        urlObservation?.invalidate()
        urlObservation = nil

        // Disconnect WebSocket
        WebSocketService.shared.disconnect()
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        // EY-CENTRAL 2026-10-09 (orden del usuario): archivo compartido a Employ (PDF/imagen, CFBundleDocumentTypes)
        // → el puente lo guarda y la web lo toma como comprobante de un gasto nuevo.
        if url.isFileURL {
            print("[Share] File received: \(url.lastPathComponent)")
            self.nativeBridge?.receiveSharedFile(url)
            return true
        }
        // Custom URL scheme (ai.employ.app://...) → deep link (PUNTO 4)
        if let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            let route = comps.path.isEmpty ? ("/" + (comps.host ?? "")) : comps.path
            if route != "/" {
                self.nativeBridge?.emitDeeplink(route)
            }
        }
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        // Universal Link (https://app.employ.digital/...) → la app, no Safari.
        // EY-CENTRAL 2026-10-07 (orden del usuario): antes se mandaba solo el path al web
        // (perdía el subdominio de la organización y la query). Ahora:
        //  - si el WebView ya está en ese mismo host → ruta (path + query) al web, que navega sin recargar;
        //  - si no (otra organización, o la app recién abre) → se carga la URL completa en el WebView.
        // Requiere la capability Associated Domains en el App ID + AASA en el dominio (lado web).
        if userActivity.activityType == NSUserActivityTypeBrowsingWeb,
           let url = userActivity.webpageURL,
           let host = url.host?.lowercased(),
           host == "app.employ.digital" || host == "dev.employ.digital" { // sin subdominios de org (decisión del usuario 2026-10-07)
            let currentHost = self.currentWebViewHost()?.lowercased()
            if let currentHost = currentHost, currentHost == host, !url.path.isEmpty {
                var route = url.path
                if let q = url.query, !q.isEmpty { route += "?" + q }
                self.nativeBridge?.emitDeeplink(route)
            } else {
                print("[DeepLink] Loading platform link in WebView: \(url.absoluteString)")
                self.loadUrlInWebView(url.absoluteString)
            }
        }
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }

    /// Host de la página que muestra el WebView ahora (nil si todavía no cargó nada).
    private func currentWebViewHost() -> String? {
        guard let window = self.window,
              let rootVC = window.rootViewController,
              let webView = self.findWebView(in: rootVC.view) else { return nil }
        return webView.url?.host
    }
}

// MARK: - MessagingDelegate
extension AppDelegate: MessagingDelegate {
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        print("[FCM] Token received: \(fcmToken ?? "nil")")
        if let token = fcmToken {
            PushTokenSync.onNewToken(token) // EY-CENTRAL 2026-10-08: guarda y avisa al backend
        }
    }
}

// MARK: - UNUserNotificationCenterDelegate
extension AppDelegate: UNUserNotificationCenterDelegate {
    // Show notification when app is in foreground
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if #available(iOS 14.0, *) {
            completionHandler([.banner, .list, .sound, .badge]) // EY-CENTRAL 2026-10-08: también queda en el centro de notificaciones
        } else {
            completionHandler([.alert, .sound, .badge])
        }
    }

    // Handle notification tap
    // EY-CENTRAL 2026-10-08 ("la push lleva al lugar"): el payload trae `url` (absoluta, la manda la web)
    // o `route` (relativa). La app no usa subdominios de organización: si la URL viene con
    // <org>.employ.digital se conserva path + query sobre el host de la webapp cacheada. Si el WebView
    // ya está en ese host → ruta al web (navega sin recargar); si no → se carga la URL completa.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        print("[FCM] Notification tapped: \(userInfo)")

        if let target = pushTarget(from: userInfo) {
            let currentHost = self.currentWebViewHost()?.lowercased()
            if let currentHost = currentHost, currentHost == target.host?.lowercased() {
                var route = target.path.isEmpty ? "/" : target.path
                if let q = target.query, !q.isEmpty { route += "?" + q }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
                    self?.nativeBridge?.emitDeeplink(route)
                }
            } else {
                print("[FCM] Loading push target in WebView: \(target.absoluteString)")
                self.loadUrlInWebView(target.absoluteString)
            }
        } else if let route = AppDelegate.deeplinkRoute(from: userInfo) {
            // Ruta relativa (`route`/`deeplink`/`path` u `orgId`+`section`): al web, que navega.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
                self?.nativeBridge?.emitDeeplink(route)
            }
        }
        completionHandler()
    }

    /// Host de la webapp que la app abre (la cacheada de Firebase, o app.employ.digital).
    private func webappBaseURL() -> URL {
        if let cached = UserDefaults.standard.string(forKey: cachedWebappUrlKey), let u = URL(string: cached), u.host != nil {
            return u
        }
        return URL(string: "https://app.employ.digital")!
    }

    /// URL destino de una push a partir de `url` absoluta (normalizando el host) — nil si no hay `url` navegable.
    private func pushTarget(from userInfo: [AnyHashable: Any]) -> URL? {
        guard let raw = userInfo["url"] as? String, !raw.isEmpty,
              let u = URL(string: raw.trimmingCharacters(in: .whitespaces)),
              u.scheme?.lowercased() == "https",
              let host = u.host?.lowercased(),
              host == "employ.digital" || host.hasSuffix(".employ.digital") else { return nil }
        let base = webappBaseURL()
        if host == "app.employ.digital" || host == "dev.employ.digital" || host == base.host?.lowercased() {
            return u
        }
        // <org>.employ.digital → mismo path y query sobre el host de la app
        var comps = URLComponents(url: u, resolvingAgainstBaseURL: false)
        comps?.host = base.host
        comps?.port = base.port
        return comps?.url
    }

    /// Extrae una ruta de deep link del payload del push. Acepta una ruta directa
    /// (`route`/`deeplink`/`path`) o la construye desde `orgId` (+ `section` opcional).
    static func deeplinkRoute(from userInfo: [AnyHashable: Any]) -> String? {
        for key in ["route", "deeplink", "path"] {
            if let value = userInfo[key] as? String, !value.isEmpty {
                return value.hasPrefix("/") ? value : "/\(value)"
            }
        }
        if let orgId = userInfo["orgId"] as? String, !orgId.isEmpty {
            let section = (userInfo["section"] as? String) ?? "messages"
            return "/organization/\(orgId)/\(section)"
        }
        return nil
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// EY-CENTRAL 2026-10-08 (push, "token rotado"): cuando FCM cambia el token, se lo avisamos al
// backend EN EL ACTO con PATCH /api/push/devices/{deviceId}/fcmToken (X-Session-Id). Antes solo
// se guardaba en UserDefaults y el backend seguía mandando al token viejo hasta que la web
// volvía a pedir `push.refreshToken`. Sin sesión todavía → queda pendiente y se manda en
// `session.save`. Vive en AppDelegate.swift (y no en un archivo nuevo) para no depender de que
// el .pbxproj lo incluya.
// ═══════════════════════════════════════════════════════════════════════════
enum PushTokenSync {
    static let defaultApiUrl = "https://ey-backend-fgqald7xpa-rj.a.run.app"
    static let pendingKey = "fcmTokenPendingSync"

    static func onNewToken(_ token: String) {
        guard !token.isEmpty else { return }
        let d = UserDefaults.standard
        d.set(token, forKey: "fcmToken")
        d.set(true, forKey: pendingKey)
        syncPendingIfNeeded()
    }

    static func syncPendingIfNeeded() {
        let d = UserDefaults.standard
        guard d.bool(forKey: pendingKey),
              let token = d.string(forKey: "fcmToken"), !token.isEmpty,
              let deviceId = d.string(forKey: "device_id"), !deviceId.isEmpty,
              let sessionId = d.string(forKey: "sessionId"), !sessionId.isEmpty else {
            print("[PushTokenSync] Token pendiente, sin sesión todavía: se manda al guardar la sesión")
            return
        }
        var base = d.string(forKey: "cached_api_url") ?? defaultApiUrl
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: "\(base)/api/push/devices/\(deviceId)/fcmToken") else { return }

        var req = URLRequest(url: url)
        req.httpMethod = "PATCH"
        req.timeoutInterval = 10
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(sessionId, forHTTPHeaderField: "X-Session-Id")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["fcmToken": token])

        URLSession.shared.dataTask(with: req) { _, response, error in
            if let error = error {
                print("[PushTokenSync] Error avisando el token: \(error.localizedDescription)")
                return
            }
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            if (200..<300).contains(code) {
                d.set(false, forKey: pendingKey)
                print("[PushTokenSync] FCM token actualizado en el backend (\(code))")
            } else {
                // 401 = sesión vencida: queda pendiente y se reintenta en el próximo session.save
                print("[PushTokenSync] No se pudo actualizar el FCM token: HTTP \(code)")
            }
        }.resume()
    }
}
