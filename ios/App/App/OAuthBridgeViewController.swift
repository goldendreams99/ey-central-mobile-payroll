import UIKit
import Capacitor
import WebKit

class OAuthBridgeViewController: CAPBridgeViewController {

    private var oauthWebView: WKWebView?
    private var oauthNavController: UINavigationController?

    override func viewDidLoad() {
        super.viewDidLoad()

        webView?.navigationDelegate = self
        webView?.uiDelegate = self

        // EY-CENTRAL 2026-10-09: en Capacitor 8 `view === webView` (CAPBridgeViewController.loadView hace `view = webView`).
        // setupWebViewConstraints() anclaba el WebView A SI MISMO y le apagaba translatesAutoresizingMaskIntoConstraints:
        // el frame de la vista raiz quedaba a merced de constraints rotas (tiron del Inicio al cargar) y al volver de la
        // camara quedaba 0x0 (pantalla negra). La vista raiz la posiciona UIKit: borde a borde, debajo de la barra de
        // estado; la web reserva arriba con --status-bar-height (inyectado al inicio del documento, sin tiron).
        installHomeIndicatorChild() // EY-CENTRAL 2026-10-08: ocultar el home indicator vía hijo (Capacitor 8)
        installFirstPaintSignal() // EY-CENTRAL 2026-10-09: el splash se va en el primer cuadro de la webapp
        installTrackerBlocker() // EY-CENTRAL 2026-10-13: Apple 5.1.2, sin rastreo dentro de la app
    }

    // EY-CENTRAL 2026-10-13 (revisión de Apple 5.1.2 de la 116, orden del usuario): la webapp carga Microsoft Clarity, que
    // deja cookies de terceros. En vez de pedir App Tracking Transparency, la app NO rastrea: una lista de bloqueo de WebKit
    // corta Clarity y los píxeles de publicidad (Google, Meta, Bing, LinkedIn, TikTok) antes de que se pidan. La medición
    // propia (PostHog por e.employ.digital, Vercel) y el inicio de sesión (Clerk, accounts.google.com) no se tocan.
    private static let blockedTrackerPatterns = [
        "clarity\\.ms/",
        "bat\\.bing\\.com/",
        "c\\.bing\\.com/",
        "google-analytics\\.com/",
        "googletagmanager\\.com/",
        "doubleclick\\.net/",
        "googleadservices\\.com/",
        "googlesyndication\\.com/",
        "connect\\.facebook\\.net/",
        "facebook\\.com/tr",
        "px\\.ads\\.linkedin\\.com/",
        "snap\\.licdn\\.com/",
        "analytics\\.tiktok\\.com/",
    ]

    private func installTrackerBlocker() {
        let rules = Self.blockedTrackerPatterns.map { pattern in
            ["trigger": ["url-filter": "^https?://[^/]*" + pattern], "action": ["type": "block"]]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let json = String(data: data, encoding: .utf8) else { return }
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "ey-tracker-blocker", encodedContentRuleList: json) { [weak self] list, error in
            guard let list = list else {
                CAPLog.print("⚠️ [TrackerBlocker] No compiló: \(error?.localizedDescription ?? "?")")
                return
            }
            DispatchQueue.main.async {
                self?.webView?.configuration.userContentController.add(list)
            }
        }
    }

    // EY-CENTRAL 2026-10-09 (usuario: "¿por qué el splash dura tanto?"): WKWebView no avisa el primer pintado. Un script al
    // terminar de leer el HTML (atDocumentEnd: el contenido que manda el servidor ya está) espera dos cuadros de animación,
    // o sea que ya hay algo en pantalla, y avisa por el canal "eyFirstPaint". Solo cuenta la página principal de la
    // webapp (app. o dev.employ.digital); la página local de Capacitor y el modal OAuth no.
    private func installFirstPaintSignal() {
        guard let controller = webView?.configuration.userContentController else { return }
        controller.removeScriptMessageHandler(forName: "eyFirstPaint")
        controller.add(self, name: "eyFirstPaint")
        let js = "(function(){function s(){try{window.webkit.messageHandlers.eyFirstPaint.postMessage(location.host)}catch(e){}}"
            + "requestAnimationFrame(function(){requestAnimationFrame(s)})})();"
        controller.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
    }

    // EY-CENTRAL 2026-10-08 (pedido del usuario): contraste automático de la barra de estado.
    // En Capacitor 8 el WKWebView ES la vista raíz y va debajo de la barra de estado (EY-CENTRAL 2026-10-09):
    // lo que se ve detrás de la hora y la batería es la franja superior de la webapp. La app mira
    // el color real de esa franja (snapshot de 8 pt reducido a 32×1) y elige el texto: claro →
    // oscuro, oscuro → claro. Histéresis para no parpadear. Cada segundo en primer plano, al hacer
    // scroll y al terminar de cargar. El plugin StatusBar de Capacitor ya no fija el estilo.
    var autoStatusBarStyle: UIStatusBarStyle = .default
    private var autoStatusBarTimer: Timer?
    private var autoStatusBarObserving = false

    override var preferredStatusBarStyle: UIStatusBarStyle { autoStatusBarStyle }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        startStatusBarAutoContrast()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        stopStatusBarAutoContrast()
    }

    func startStatusBarAutoContrast() {
        stopStatusBarAutoContrast()
        let t = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in self?.sampleStatusBarBackground() }
        t.tolerance = 0.3
        autoStatusBarTimer = t
        if !autoStatusBarObserving, let sv = webView?.scrollView {
            sv.addObserver(self, forKeyPath: "contentOffset", options: [.new], context: nil)
            autoStatusBarObserving = true
        }
        sampleStatusBarBackground()
    }

    func stopStatusBarAutoContrast() {
        autoStatusBarTimer?.invalidate()
        autoStatusBarTimer = nil
        if autoStatusBarObserving, let sv = webView?.scrollView {
            sv.removeObserver(self, forKeyPath: "contentOffset")
            autoStatusBarObserving = false
        }
    }

    override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if keyPath == "contentOffset" { sampleStatusBarBackground(); return }
        super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
    }

    func sampleStatusBarBackground() {
        guard let webView = self.webView, view.window != nil, webView.bounds.width > 0 else { return }
        let config = WKSnapshotConfiguration()
        config.rect = CGRect(x: 0, y: 0, width: webView.bounds.width, height: 8)
        config.snapshotWidth = 32
        config.afterScreenUpdates = false
        webView.takeSnapshot(with: config) { [weak self] image, _ in
            guard let self = self, let cg = image?.cgImage else { return }
            let (r, g, b) = Self.averageColor(cg)
            let lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            let wantDarkText: Bool? = lum >= 0.62 ? true : (lum <= 0.45 ? false : nil)
            let color = UIColor(red: CGFloat(r), green: CGFloat(g), blue: CGFloat(b), alpha: 1)
            UIView.animate(withDuration: 0.2) { self.view.backgroundColor = color }
            guard let darkText = wantDarkText else { return }
            let style: UIStatusBarStyle = darkText ? .darkContent : .lightContent
            if self.autoStatusBarStyle != style {
                self.autoStatusBarStyle = style
                UIView.animate(withDuration: 0.2) { self.setNeedsStatusBarAppearanceUpdate() }
            }
        }
    }

    /// Promedio RGB (0..1) de la imagen reducida a 32×1.
    private static func averageColor(_ cg: CGImage) -> (Double, Double, Double) {
        let w = 32, h = 1
        var px = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return (1, 1, 1) }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var r = 0.0, g = 0.0, b = 0.0
        for i in stride(from: 0, to: px.count, by: 4) {
            r += Double(px[i]); g += Double(px[i + 1]); b += Double(px[i + 2])
        }
        let n = Double(w * h) * 255.0
        return (r / n, g / n, b / n)
    }

    // MARK: - Home Indicator Auto-Hide (like Android navbar)
    // EY-CENTRAL 2026-10-08: Capacitor 8 sobreescribe `prefersHomeIndicatorAutoHidden` en una extensión
    // pública (no open) de CAPBridgeViewController, así que ya no se puede sobreescribir acá (el build de
    // Codemagic fallaba: "overriding non-open property outside of its defining module"). UIKit permite
    // delegar la decisión en un view controller hijo: un hijo vacío que pide ocultar el indicador.
    private final class HomeIndicatorHiddenController: UIViewController {
        override var prefersHomeIndicatorAutoHidden: Bool { true }
    }
    private let homeIndicatorChild = HomeIndicatorHiddenController()

    private func installHomeIndicatorChild() {
        guard homeIndicatorChild.parent == nil else { return }
        addChild(homeIndicatorChild)
        homeIndicatorChild.view.frame = .zero
        homeIndicatorChild.view.isHidden = true
        view.addSubview(homeIndicatorChild.view)
        homeIndicatorChild.didMove(toParent: self)
        setNeedsUpdateOfHomeIndicatorAutoHidden()
    }

    override var childForHomeIndicatorAutoHidden: UIViewController? {
        homeIndicatorChild.parent == nil ? nil : homeIndicatorChild
    }

    // EY-CENTRAL 2026-10-08 (pedido de EY-01, orden del usuario "jamás tirón"): las variables de safe area se fijan al
    // INICIO de cada documento (WKUserScript .atDocumentStart), antes del primer pintado; la inyección tardía de
    // AppDelegate queda de refuerzo. En el mismo script, un stub de window.Capacitor: Capacitor evalúa
    // window.Capacitor.triggerEvent(...) en nuestra página remota, donde su bridge JS no está, y tiraba un TypeError.
    // WKUserContentController no permite quitar un script individual: si los insets cambian (rotación) se agrega
    // otro; el último gana y los anteriores son inofensivos.
    private var documentStartInsets: UIEdgeInsets?

    // EY-CENTRAL 2026-10-09: URL de la página principal que respondió 5xx en esta carga; didCommit la consume.
    var mainFrameServerErrorURL: URL?

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        installDocumentStartScript()
    }

    // EY-CENTRAL 2026-10-09 (video 09:25 del usuario, build 388: el Inicio seguía pintándose 59 px arriba y bajando
    // 0,3 s después): la carga de la webapp arranca en didFinishLaunching, ANTES de que la vista esté en la ventana y
    // de que iOS avise los insets, así que este script se instalaba con la página ya en camino. Ahora el AppDelegate
    // lo llama antes de cargar la URL (con los insets de la ventana) y además se reinyecta en didCommit. Interno.
    func installDocumentStartScript() {
        guard let webView = webView else { return }
        let appWindow = (UIApplication.shared.delegate as? AppDelegate)?.window
        let insets = view.window?.safeAreaInsets ?? appWindow?.safeAreaInsets ?? view.safeAreaInsets
        guard insets.top > 0 || insets.bottom > 0, insets != documentStartInsets else { return }
        documentStartInsets = insets
        // El WebView es la vista raiz (Capacitor 8): va de borde a borde, debajo de la barra de estado y hasta abajo.
        // La web reserva arriba y abajo con estas variables; al ir al inicio del documento no hay tiron.
        let top = Int(insets.top), bottom = Int(insets.bottom)
        let js = "(function(){var r=document.documentElement;if(r){"
            + "r.style.setProperty('--status-bar-height','\(top)px');"
            + "r.style.setProperty('--safe-area-inset-top','\(top)px');"
            + "r.style.setProperty('--nav-bar-height','\(bottom)px');"
            + "r.style.setProperty('--safe-area-inset-bottom','\(bottom)px');}"
            + "if(!window.Capacitor){window.Capacitor={triggerEvent:function(){}};}})();"
        webView.configuration.userContentController.addUserScript(
            WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        webView.evaluateJavaScript(js, completionHandler: nil) // y en la página ya cargada, ahora
        print("[SafeArea] Document-start script installed - top: \(top) bottom: \(bottom)")
    }

    // Defer system gestures on bottom edge so app menu has priority
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        return .bottom
    }

    /// SIN USO desde 2026-10-09 (EY-CENTRAL): en Capacitor 8 `view === webView`; anclar la vista raiz a si misma rompia
    /// el layout (tiron al cargar, 0x0 al volver de la camara). Se conserva solo como referencia historica.
    private func setupWebViewConstraints() {
        guard let webView = self.webView, webView !== view else { return }

        // Desactivar autoresizing mask
        webView.translatesAutoresizingMaskIntoConstraints = false

        // Desactivar constraints existentes que afecten al webview
        for constraint in view.constraints {
            if (constraint.firstItem as? UIView) == webView || (constraint.secondItem as? UIView) == webView {
                constraint.isActive = false
            }
        }

        // Nuevos constraints: top respeta safe area, resto al borde
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    private func isOAuthURL(_ url: URL) -> Bool {
        let host = url.host?.lowercased() ?? ""
        // Interceptar Google y Microsoft OAuth
        return host == "accounts.google.com" ||
               host == "login.microsoftonline.com" ||
               host == "login.live.com"
    }

    private func isAppDomain(_ url: URL) -> Bool {
        let host = url.host?.lowercased() ?? ""
        return host.contains("employ.digital") || host.contains("employ.app")
    }

    private func openOAuthModal(url: URL) {
        print("[OAuth] Opening modal WebView for: \(url.absoluteString)")

        // Crear WKWebView que comparta configuración con el principal
        // Esto hace que compartan cookies y localStorage
        let config = WKWebViewConfiguration()
        config.processPool = webView?.configuration.processPool ?? WKProcessPool()
        config.websiteDataStore = webView?.configuration.websiteDataStore ?? .default()

        let oauthWV = WKWebView(frame: .zero, configuration: config)
        oauthWV.navigationDelegate = self
        oauthWV.translatesAutoresizingMaskIntoConstraints = false

        // User-Agent de Safari para que Google no bloquee
        oauthWV.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

        // Crear ViewController para el modal
        let modalVC = UIViewController()
        modalVC.view.backgroundColor = .systemBackground
        modalVC.view.addSubview(oauthWV)

        NSLayoutConstraint.activate([
            oauthWV.topAnchor.constraint(equalTo: modalVC.view.safeAreaLayoutGuide.topAnchor),
            oauthWV.bottomAnchor.constraint(equalTo: modalVC.view.bottomAnchor),
            oauthWV.leadingAnchor.constraint(equalTo: modalVC.view.leadingAnchor),
            oauthWV.trailingAnchor.constraint(equalTo: modalVC.view.trailingAnchor)
        ])

        // Botón para cerrar
        modalVC.navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(closeOAuthModal)
        )
        modalVC.title = "Iniciar Sesión"

        let navController = UINavigationController(rootViewController: modalVC)
        navController.modalPresentationStyle = .pageSheet

        self.oauthWebView = oauthWV
        self.oauthNavController = navController

        // Cargar URL de OAuth
        oauthWV.load(URLRequest(url: url))

        // Presentar modal
        present(navController, animated: true)
    }

    @objc private func closeOAuthModal() {
        oauthNavController?.dismiss(animated: true) { [weak self] in
            self?.oauthWebView = nil
            self?.oauthNavController = nil
            // Recargar WebView principal para reflejar sesión
            self?.webView?.reload()
        }
    }

    private func closeOAuthModalWithURL(_ returnURL: URL) {
        oauthNavController?.dismiss(animated: true) { [weak self] in
            self?.oauthWebView = nil
            self?.oauthNavController = nil
            // Cargar la URL de retorno en el WebView principal (no reload)
            print("[OAuth] Loading return URL in main WebView: \(returnURL.absoluteString)")
            self?.webView?.load(URLRequest(url: returnURL))
        }
    }
}

// MARK: - WKNavigationDelegate
extension OAuthBridgeViewController: WKNavigationDelegate {

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }

        // Si es el WebView principal y es OAuth, abrir en modal
        if webView == self.webView && isOAuthURL(url) {
            print("[OAuth] Intercepted in main WebView, opening modal")
            decisionHandler(.cancel)
            openOAuthModal(url: url)
            return
        }

        // Si es el modal OAuth y navega de vuelta a nuestra app, cerrar modal
        if webView == oauthWebView && isAppDomain(url) && !isOAuthURL(url) {
            print("[OAuth] Detected return to app domain, closing modal")
            decisionHandler(.cancel)
            closeOAuthModalWithURL(url)
            return
        }

        decisionHandler(.allow)
    }

    // EY-CENTRAL 2026-10-08 (usuario, iPhone 15 Pro Max: pantalla negra al volver de "Usar foto"): al tomar el
    // delegate de navegación, este VC dejó sin efecto el de Capacitor, que recargaba la web cuando iOS mata el
    // proceso del WKWebView por memoria (pasa con la cámara al frente en teléfonos con fotos muy grandes).
    // Sin recarga la web quedaba en negro para siempre. Recargar es lo mínimo; la foto tomada se pierde.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        print("[Navigation] WebContent process terminated, reloading")
        webView.reload()
    }

    // ***--- EY-CENTRAL 2026-10-09 (pedido del usuario): la PÁGINA PRINCIPAL de la webapp no cargó (sin red, DNS,
    // timeout, SSL o 5xx). Al tomar el delegado de navegación nadie atendía el fallo: quedaba el splash para
    // siempre o la pantalla en blanco. Ahora el AppDelegate muestra una vista nativa con "Reintentar"
    // (showOfflineView) y la quita cuando una carga vuelve a comprometerse (didCommit). Se ignoran las
    // navegaciones canceladas (-999, p. ej. una carga pisada por otra) y las "interrumpidas" de WebKit (102,
    // descargas o redirecciones que el propio WebKit resuelve). El modal OAuth no entra acá. ---***
    private func handleMainLoadFailure(_ webView: WKWebView, _ error: Error) {
        guard webView == self.webView else { return }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { return }
        if ns.domain == "WebKitErrorDomain" && (ns.code == 102 || ns.code == 204) { return }
        let failed = (ns.userInfo[NSURLErrorFailingURLErrorKey] as? URL) ?? webView.url
        if let f = failed, f.host?.lowercased() == "localhost" || isOAuthURL(f) { return }
        print("[Navigation] Main load failed (\(ns.domain) \(ns.code)): \(ns.localizedDescription) at \(failed?.absoluteString ?? "-")")
        (UIApplication.shared.delegate as? AppDelegate)?.showOfflineView(failedURL: failed, serverError: false)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handleMainLoadFailure(webView, error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handleMainLoadFailure(webView, error)
    }

    // Una carga se comprometió: si fue un 5xx de la página principal, se muestra (o se mantiene) la vista con el
    // texto de servidor; si no, la webapp volvió a cargar y la vista se quita.
    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        guard webView == self.webView, let app = UIApplication.shared.delegate as? AppDelegate else { return }
        // EY-CENTRAL 2026-10-09: safe area en cuanto el documento se compromete (antes del primer pintado), por si el
        // script de inicio no llegó a esta navegación. Idempotente.
        installDocumentStartScript()
        app.injectSafeAreaCSS()
        if let url = mainFrameServerErrorURL {
            mainFrameServerErrorURL = nil
            app.showOfflineView(failedURL: url, serverError: true)
        } else {
            app.hideOfflineView()
        }
    }

    // Servidor caído (5xx) en la página principal: misma vista, otro texto. La respuesta se deja pasar igual
    // (si Vercel manda una página de error propia, queda debajo; la vista nativa la tapa).
    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if webView == self.webView, navigationResponse.isForMainFrame,
           let http = navigationResponse.response as? HTTPURLResponse, http.statusCode >= 500,
           let url = http.url, url.host?.lowercased() != "localhost" {
            print("[Navigation] Main frame HTTP \(http.statusCode) at \(url.absoluteString)")
            mainFrameServerErrorURL = url
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let urlString = webView.url?.absoluteString ?? "unknown"
        print("[Navigation] Loaded: \(urlString)")

        // EY-CENTRAL 2026-10-09 (pedido de EY-01): tras un reload (p. ej. iOS mato el proceso web) la URL no cambia y
        // el AppDelegate no reinyectaba ni el bridge ni la safe area: la web quedaba "desnuda" (sin camara, push,
        // icono). Reinyectar en CADA carga del documento principal; las dos inyecciones son idempotentes.
        if webView == self.webView, let app = UIApplication.shared.delegate as? AppDelegate {
            app.injectBridgeScript()
            app.injectSafeAreaCSS()
            app.rememberOrgURL(webView.url) // EY-CENTRAL 2026-10-09: arrancar la próxima vez en este Inicio
        }

        // Si el modal OAuth cargó nuestra app, cerrar automáticamente
        if webView == oauthWebView, let url = webView.url, isAppDomain(url) && !isOAuthURL(url) {
            print("[OAuth] OAuth complete, closing modal")
            closeOAuthModalWithURL(url)
        }
    }
}

// MARK: - Primer cuadro pintado (EY-CENTRAL 2026-10-09)
extension OAuthBridgeViewController: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "eyFirstPaint", message.frameInfo.isMainFrame,
              let host = (message.body as? String)?.lowercased(),
              AppDelegate.platformHosts.contains(host) else { return }
        (UIApplication.shared.delegate as? AppDelegate)?.hideSplashOnFirstPaint()
    }
}

// MARK: - WKUIDelegate
extension OAuthBridgeViewController: WKUIDelegate {

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard let url = navigationAction.request.url else { return nil }

        if isOAuthURL(url) {
            openOAuthModal(url: url)
            return nil
        }

        webView.load(URLRequest(url: url))
        return nil
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancelar", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }
}
