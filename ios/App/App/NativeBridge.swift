import Foundation
import WebKit
import UIKit
import CoreLocation
import LocalAuthentication
import AVFoundation
import Photos
import PhotosUI
import UniformTypeIdentifiers // files.pick (iOS 14+, uso guardado con #available)
import UserNotifications
import StoreKit
import FirebaseMessaging
import FirebaseFirestore

// Capacitor plugins
import Capacitor

class NativeBridge: NSObject, WKScriptMessageHandler, CLLocationManagerDelegate {

    private weak var webView: WKWebView?
    private weak var viewController: UIViewController?
    private let locationManager = CLLocationManager()
    private var pendingLocationRequestId: String?
    private var pendingCameraRequestId: String?
    private var pendingCameraQuality: CGFloat = 0.9
    private var pendingCameraMaxDimension: CGFloat = 1920   // width/height de la web (EY-CENTRAL 2026-10-07)
    private var pendingCameraResultType: String = "Base64"
    var pendingFilesRequestId: String?            // files.pick (EY-CENTRAL 2026-10-07)
    var pendingFilesMaxSize: Int = 15 * 1024 * 1024
    private var watchPositionId: String?
    // Ruta de deep link pendiente (push tocada en cold-start, antes de que el web tenga listener)
    private var pendingDeeplinkRoute: String?
    private var isWatchingPosition = false

    // Storage
    private let defaults = UserDefaults.standard
    private let prefsPrefix = "CapacitorStorage."

    // Keychain key for persistent device ID
    private let keychainDeviceIdKey = "ai.employ.app.deviceId"

    // WebSocket command tracking
    // EY-CENTRAL 2026-10-08: por id de orden (requestId -> modulo.accion). Antes era una sola marca compartida y dos
    // ordenes casi juntas (una de la web, otra del servidor) se pisaban: la respuesta salia por el canal equivocado.
    private var wsCommands: [String: (module: String, action: String)] = [:]
    // EY-CENTRAL 2026-10-08 (pedido de EY-01): ultimo resultado de camara/archivos. Si iOS mata el proceso web
    // mientras el selector esta al frente (pasa con la camara en iPhones de 48 MP), la pagina recarga y la web lo
    // recupera con camera.getPendingResult (vale 2 minutos, se borra al leerlo). Contrato en 04_BRIDGE.txt.
    private static let recoverableActions: Set<String> = ["camera.getPhoto", "camera.takePicture", "camera.pickImages", "camera.pickVideo", "files.pick"]
    private static let pendingResultTtlMs: Double = 120_000
    private var recoverableRequestId: String?
    private var recoverableAction: String?
    private var pendingResult: [String: Any]?

    // Simulator detection (Swift-compatible)
    private var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    init(webView: WKWebView, viewController: UIViewController) {
        self.webView = webView
        self.viewController = viewController
        super.init()
        locationManager.delegate = self

        // Observa el teclado nativo para reportar altura real + duración a la webapp (PUNTO 3)
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleKeyboardWillShow(_:)),
            name: UIResponder.keyboardWillShowNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleKeyboardWillHide(_:)),
            name: UIResponder.keyboardWillHideNotification, object: nil)
        // EY-CENTRAL 2026-10-16: el teclado arrastra la página cuadro a cuadro (ver followKeyboard)
        NotificationCenter.default.addObserver(
            self, selector: #selector(followKeyboard(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Native Event Emitter (eventos nativos → webapp)

    /// Emite un evento nativo a la webapp vía window.postMessage.
    /// La webapp lo consume escuchando { type: 'NATIVE_EVENT', event, detail }.
    private func emitNativeEvent(_ event: String, detail: [String: Any]) {
        let payload: [String: Any] = ["type": "NATIVE_EVENT", "event": event, "detail": detail]
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload),
              let jsonString = String(data: jsonData, encoding: .utf8) else { return }
        let script = "window.postMessage(\(jsonString), '*');"
        DispatchQueue.main.async {
            self.webView?.evaluateJavaScript(script, completionHandler: nil)
        }
    }

    // MARK: - Deep Links (PUNTO 4)

    /// Recibe una ruta de deep link (p.ej. "/organization/abc/messages") desde AppDelegate
    /// al tocar una push o abrir un universal link. Emite el evento al web y guarda la ruta
    /// por si el listener del web aún no está montado (cold-start).
    func emitDeeplink(_ route: String) {
        guard !route.isEmpty else { return }
        pendingDeeplinkRoute = route
        emitNativeEvent("deeplink", detail: ["route": route])
    }

    // MARK: - Keyboard Notifications (PUNTO 3: altura real del teclado)

    @objc private func handleKeyboardWillShow(_ notification: Notification) {
        guard let info = notification.userInfo,
              let frame = (info[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
        let duration = (info[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        emitNativeEvent("keyboardChange", detail: [
            "height": frame.height,
            "duration": Int(duration * 1000),
            "visible": true
        ])
    }

    @objc private func handleKeyboardWillHide(_ notification: Notification) {
        let duration = (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        emitNativeEvent("keyboardChange", detail: [
            "height": 0,
            "duration": Int(duration * 1000),
            "visible": false
        ])
    }

    // MARK: - Teclado cuadro a cuadro (EY-CENTRAL 2026-10-16; pedido de EY-PAYROLL, revisión de Zeus)
    //
    // En Capacitor 8 la vista raíz ES el WKWebView. El plugin Keyboard de Capacitor (modo "native") le cambiaba el frame de
    // golpe: al abrir, 0,2 s DESPUÉS de que el teclado terminara de subir; al cerrar, al instante. La primera versión de
    // esto (mañana del 16/10) cambiaba el frame en cada cuadro: eso obliga a la página a re-maquetarse entera ~15 veces en
    // 250 ms, lo más caro que hay (Zeus). Ahora, mismo contrato que Android:
    // - el frame del WebView cambia UNA sola vez: al abrir, al TERMINAR la animación; al cerrar, al EMPEZAR (la página grande
    //   mientras el teclado se mueve; el teclado la tapa y la web mueve la caja con `transform`);
    // - en cada cuadro (CADisplayLink sobre una vista invisible animada con la duración y la curva REALES del teclado) le pasa
    //   a la web cuánto tapa el teclado y el layout todavía no descuenta: `--ey-keyboard-height` en <html> (px) y
    //   `ey:keyboard` fase "progress";
    // - `ey:keyboard` fase "start" (height final, duration) al empezar y "end" al terminar, con la variable en 0.
    // El plugin no toca el frame: `Keyboard.resize = "none"` en capacitor.config.json.
    private var keyboardTracker: UIView?
    private var keyboardLink: CADisplayLink?
    private var keyboardTargetHeight: CGFloat = 0
    private var keyboardLastSent: Int = -1

    @objc private func followKeyboard(_ notification: Notification) {
        guard let webView = webView, let window = webView.window, let info = notification.userInfo,
              let end = (info[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
        let duration = (info[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        let curve = (info[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.uintValue ?? 7
        let endInWindow = window.convert(end, from: nil)
        var overlap = max(0, window.bounds.maxY - endInWindow.minY)
        if overlap < 100 { overlap = 0 } // solo la barra de sugerencias (teclado físico): la página no se achica
        let fullHeight = window.bounds.height - webView.frame.minY
        let target = fullHeight - overlap
        if keyboardLink == nil && abs(webView.frame.height - target) < 0.5 { return }
        if keyboardLink != nil && abs(keyboardTargetHeight - target) < 0.5 { return }
        let opening = target < webView.frame.height
        keyboardTargetHeight = target
        keyboardLastSent = -1
        emitKeyboardPhase("start", open: overlap > 0, height: overlap, duration: duration)
        if !opening { applyWebViewHeight(target) } // al cerrar, la página grande desde el primer cuadro

        let tracker: UIView
        if let existing = keyboardTracker {
            tracker = existing
        } else {
            tracker = UIView(frame: .zero)
            tracker.isHidden = true
            tracker.isUserInteractionEnabled = false
            webView.addSubview(tracker)
            keyboardTracker = tracker
        }
        // La vista invisible mide "cuánto tapa el teclado": va de lo tapado ahora a `overlap` con la curva real.
        let coveredNow = tracker.layer.presentation()?.bounds.height ?? (opening ? 0 : fullHeight - webView.frame.height)
        tracker.layer.removeAllAnimations()
        tracker.frame = CGRect(x: 0, y: 0, width: 1, height: coveredNow)

        keyboardLink?.invalidate()
        let link = CADisplayLink(target: self, selector: #selector(keyboardTick))
        link.add(to: .main, forMode: .common)
        keyboardLink = link

        let options = UIView.AnimationOptions(rawValue: curve << 16).union(.beginFromCurrentState)
        UIView.animate(withDuration: duration, delay: 0, options: options, animations: {
            tracker.frame = CGRect(x: 0, y: 0, width: 1, height: overlap)
        }, completion: { [weak self] _ in
            guard let self = self, self.keyboardLink === link else { return }
            self.finishKeyboardFollow(open: overlap > 0, height: overlap)
        })
    }

    @objc private func keyboardTick() {
        guard let webView = webView, let window = webView.window, let tracker = keyboardTracker else { return }
        let covered = tracker.layer.presentation()?.bounds.height ?? 0
        let accounted = (window.bounds.height - webView.frame.minY) - webView.frame.height // lo que el layout ya descuenta
        sendCovered(max(0, covered - accounted))
    }

    private func sendCovered(_ covered: CGFloat) {
        let px = Int(covered.rounded())
        if px == keyboardLastSent { return }
        keyboardLastSent = px
        let script = "document.documentElement.style.setProperty('--ey-keyboard-height','\(px)px');window.dispatchEvent(new CustomEvent('ey:keyboard',{detail:{phase:'progress',height:\(px)}}))"
        webView?.evaluateJavaScript(script, completionHandler: nil)
    }

    private func applyWebViewHeight(_ height: CGFloat) {
        guard let webView = webView else { return }
        var frame = webView.frame
        if abs(frame.height - height) < 0.5 { return }
        frame.size.height = height
        webView.frame = frame
        webView.scrollView.contentInset = .zero
        webView.scrollView.scrollIndicatorInsets = .zero
    }

    private func finishKeyboardFollow(open: Bool, height: CGFloat) {
        keyboardLink?.invalidate()
        keyboardLink = nil
        keyboardLastSent = -1
        // El único achique: el layout descuenta el teclado y la variable vuelve a 0.
        webView?.evaluateJavaScript("document.documentElement.style.setProperty('--ey-keyboard-height','0px')", completionHandler: nil)
        applyWebViewHeight(keyboardTargetHeight)
        emitKeyboardPhase("end", open: open, height: height, duration: 0)
    }

    private func emitKeyboardPhase(_ phase: String, open: Bool, height: CGFloat, duration: Double) {
        let script = "window.dispatchEvent(new CustomEvent('ey:keyboard',{detail:{phase:'\(phase)',open:\(open),height:\(Int(height.rounded())),duration:\(Int(duration * 1000))}}))"
        // EY-CENTRAL 2026-10-16 (revisión de Zeus): sincrónico, por el mismo camino que los `progress`. La notificación del
        // teclado ya llega en el hilo principal; con el async el `start` del cierre podía llegar a la página DESPUÉS del
        // cambio de tamaño y hasta después del primer `progress`, y la caja caía un cuadro al fondo.
        webView?.evaluateJavaScript(script, completionHandler: nil)
    }

    // MARK: - Keychain Helpers (para deviceId persistente entre reinstalaciones)

    /// Guarda un valor en Keychain (persiste entre reinstalaciones)
    private func saveToKeychain(key: String, value: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }

        // Primero intentar borrar si existe
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        // Agregar nuevo valor
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]

        let status = SecItemAdd(addQuery as CFDictionary, nil)
        return status == errSecSuccess
    }

    /// Lee un valor del Keychain
    private func readFromKeychain(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess,
              let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else {
            return nil
        }
        return value
    }

    /// Obtiene el deviceId persistente. Usa Keychain para que no cambie con reinstalaciones.
    private func getPersistentDeviceId() -> String {
        // 1. Primero buscar en Keychain (persiste entre reinstalaciones)
        if let keychainId = readFromKeychain(key: keychainDeviceIdKey) {
            // También sincronizar con UserDefaults para consistencia
            defaults.set(keychainId, forKey: "device_id")
            return keychainId
        }

        // 2. Si no está en Keychain, buscar en UserDefaults (migración de versiones anteriores)
        if let storedId = defaults.string(forKey: "device_id"), storedId.hasPrefix("mobile_") {
            // Migrar a Keychain
            _ = saveToKeychain(key: keychainDeviceIdKey, value: storedId)
            return storedId
        }

        // 3. Generar nuevo ID y guardarlo en Keychain
        let vendorId = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        let newDeviceId = "mobile_\(vendorId)"

        // Guardar en Keychain (persistente) y UserDefaults (para acceso rápido)
        _ = saveToKeychain(key: keychainDeviceIdKey, value: newDeviceId)
        defaults.set(newDeviceId, forKey: "device_id")

        return newDeviceId
    }

    // MARK: - WebSocket Command Handler

    /// Handle commands received from WebSocketService
    func handleWebSocketCommand(_ message: [String: Any]) {
        guard let action = message["action"] as? String,
              let requestId = message["requestId"] as? String else {
            print("[NativeBridge] Invalid WebSocket command format")
            return
        }

        let payload = message["payload"] as? [String: Any] ?? [:]

        // Remember this order came by WebSocket: its response goes back by WebSocket, with ITS module.action
        let parts = action.split(separator: ".")
        wsCommands[requestId] = parts.count >= 2 ? (String(parts[0]), String(parts[1])) : (action, "")

        print("[NativeBridge] WebSocket command: \(action)")
        executeAction(action: action, payload: payload, requestId: requestId)
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let action = body["action"] as? String,
              let requestId = body["requestId"] as? String else {
            print("[NativeBridge] Invalid message format")
            return
        }

        let payload = body["payload"] as? [String: Any] ?? [:]
        print("[NativeBridge] Received: \(action)")

        executeAction(action: action, payload: payload, requestId: requestId)
    }

    // MARK: - Execute Action

    private func executeAction(action: String, payload: [String: Any], requestId: String) {
        if Self.recoverableActions.contains(action) { recoverableRequestId = requestId; recoverableAction = action }
        switch action {

        // ═══════════════════════════════════════════════════════════════════════════
        // GEOLOCATION
        // ═══════════════════════════════════════════════════════════════════════════

        case "geolocation.getCurrentPosition":
            getCurrentPosition(payload: payload, requestId: requestId)

        case "geolocation.checkPermissions":
            checkLocationPermissions(requestId: requestId)

        case "geolocation.requestPermissions":
            requestLocationPermissions(requestId: requestId)

        case "geolocation.watchPosition":
            watchPosition(payload: payload, requestId: requestId)

        case "geolocation.clearWatch":
            clearWatch(payload: payload, requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // LOCAL NOTIFICATIONS
        // ═══════════════════════════════════════════════════════════════════════════

        case "notifications.send", "localNotifications.schedule":
            scheduleNotification(payload: payload, requestId: requestId)

        case "localNotifications.checkPermissions":
            checkNotificationPermissions(requestId: requestId)

        case "localNotifications.requestPermissions":
            requestNotificationPermissions(requestId: requestId)

        case "localNotifications.cancel":
            cancelNotification(payload: payload, requestId: requestId)

        case "localNotifications.cancelAll":
            cancelAllNotifications(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // CAMERA
        // ═══════════════════════════════════════════════════════════════════════════

        case "camera.getPhoto", "camera.takePicture":
            getPhoto(payload: payload, requestId: requestId)

        case "camera.pickImages":
            pickImages(payload: payload, requestId: requestId)

        case "camera.pickVideo":
            pickVideo(payload: payload, requestId: requestId)

        case "camera.checkPermissions":
            checkCameraPermissions(requestId: requestId)

        case "camera.requestPermissions":
            requestCameraPermissions(requestId: requestId)

        case "camera.getPendingResult":
            getPendingResult(requestId: requestId)

        case "share.getPendingFile": // EY-CENTRAL 2026-10-09: archivo compartido a Employ (gasto)
            getPendingSharedFile(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // MICROPHONE
        // ═══════════════════════════════════════════════════════════════════════════

        case "microphone.checkPermissions":
            checkMicrophonePermissions(requestId: requestId)

        case "microphone.requestPermissions":
            requestMicrophonePermissions(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // BIOMETRICS
        // ═══════════════════════════════════════════════════════════════════════════

        case "biometrics.isAvailable":
            checkBiometricsAvailable(requestId: requestId)

        case "biometrics.verifyIdentity":
            verifyIdentity(payload: payload, requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // HAPTICS
        // ═══════════════════════════════════════════════════════════════════════════

        case "haptics.vibrate", "haptics.impact":
            hapticImpact(payload: payload, requestId: requestId)

        case "haptics.notification":
            hapticNotification(payload: payload, requestId: requestId)

        case "haptics.selectionStart", "haptics.selectionChanged", "haptics.selectionEnd":
            hapticSelection(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // STORE (In-App Review)
        // ═══════════════════════════════════════════════════════════════════════════

        case "store.requestReview":
            requestStoreReview(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // STATUS BAR
        // ═══════════════════════════════════════════════════════════════════════════

        case "statusBar.setStyle":
            setStatusBarStyle(payload: payload, requestId: requestId)

        case "statusBar.show", "statusBar.hide":
            sendSuccess(requestId: requestId, result: ["success": true])

        // ═══════════════════════════════════════════════════════════════════════════
        // CLIPBOARD
        // ═══════════════════════════════════════════════════════════════════════════

        case "clipboard.write":
            clipboardWrite(payload: payload, requestId: requestId)

        case "clipboard.read":
            clipboardRead(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // SHARE
        // ═══════════════════════════════════════════════════════════════════════════

        case "share.share":
            share(payload: payload, requestId: requestId)

        case "share.shareFile":
            shareFile(payload: payload, requestId: requestId)

        case "share.canShare":
            sendSuccess(requestId: requestId, result: ["value": true])

        // FILES (EY-CENTRAL 2026-10-07: selector de archivos genérico; espejo de share.shareFile)
        case "files.pick":
            pickFiles(payload: payload, requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // BROWSER
        // ═══════════════════════════════════════════════════════════════════════════

        case "browser.open":
            openBrowser(payload: payload, requestId: requestId)

        case "browser.close":
            sendSuccess(requestId: requestId, result: ["success": true])

        // ═══════════════════════════════════════════════════════════════════════════
        // DEVICE
        // ═══════════════════════════════════════════════════════════════════════════

        case "device.getInfo":
            getDeviceInfo(requestId: requestId)

        case "device.getBatteryInfo":
            getBatteryInfo(requestId: requestId)

        case "device.getId", "device.getDeviceId":
            getDeviceId(requestId: requestId)

        case "device.getNativeInfo":
            getNativeInfo(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // CONFIG (Firebase URLs)
        // ═══════════════════════════════════════════════════════════════════════════

        case "config.getUrls":
            getConfigUrls(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // NETWORK
        // ═══════════════════════════════════════════════════════════════════════════

        case "network.getStatus":
            getNetworkStatus(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // PREFERENCES / STORAGE
        // ═══════════════════════════════════════════════════════════════════════════

        case "preferences.get", "storage.get":
            storageGet(payload: payload, requestId: requestId)

        case "preferences.set", "storage.set":
            storageSet(payload: payload, requestId: requestId)

        case "preferences.remove", "storage.remove":
            storageRemove(payload: payload, requestId: requestId)

        case "preferences.clear", "storage.clear":
            storageClear(requestId: requestId)

        case "preferences.keys", "storage.keys":
            storageKeys(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // CACHE
        // ═══════════════════════════════════════════════════════════════════════════

        case "cache.clear":
            cacheClearAll(payload: payload, requestId: requestId)

        case "cache.clearCache":
            cacheClearHttpCache(requestId: requestId)

        case "cache.clearStorage":
            cacheClearStorage(requestId: requestId)

        case "cache.clearPreferences":
            cacheClearPreferences(requestId: requestId)

        case "cache.reload":
            cacheReload(requestId: requestId)

        case "cache.hardReload":
            cacheHardReload(requestId: requestId)

        case "cache.clearAndRestart":
            cacheClearAndRestart(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // SESSION
        // ═══════════════════════════════════════════════════════════════════════════

        case "session.save":
            sessionSave(payload: payload, requestId: requestId)

        case "session.get":
            sessionGet(requestId: requestId)

        case "session.enableBiometricLogin":
            enableBiometricLogin(payload: payload, requestId: requestId)

        case "session.checkBiometricLogin":
            checkBiometricLoginStatus(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // APP
        // ═══════════════════════════════════════════════════════════════════════════

        case "app.setIcon": // EY-CENTRAL 2026-10-08: ícono por organización
            appSetIcon(payload: payload, requestId: requestId)

        case "app.getIcon":
            appGetIcon(requestId: requestId)

        case "app.getInfo":
            getAppInfo(requestId: requestId)

        case "app.minimize":
            appMinimize(requestId: requestId)

        case "app.exit":
            appExit(requestId: requestId)

        case "app.getPendingDeeplink":
            // El web llama esto al montar para recuperar un deep link de cold-start
            let route = pendingDeeplinkRoute
            pendingDeeplinkRoute = nil
            sendSuccess(requestId: requestId, result: ["route": route as Any])

        // ═══════════════════════════════════════════════════════════════════════════
        // KEYBOARD
        // ═══════════════════════════════════════════════════════════════════════════

        case "keyboard.show":
            keyboardShow(requestId: requestId)

        case "keyboard.hide":
            keyboardHide(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // AUTH / SESSION
        // ═══════════════════════════════════════════════════════════════════════════

        case "auth.clearSession", "session.clear":
            clearSession(requestId: requestId)

        // ═══════════════════════════════════════════════════════════════════════════
        // PUSH NOTIFICATIONS
        // ═══════════════════════════════════════════════════════════════════════════

        case "push.checkPermissions":
            checkPushPermissions(requestId: requestId)

        case "push.requestPermissions":
            requestPushPermissions(requestId: requestId)

        case "push.getToken", "push.refreshToken":
            getPushToken(requestId: requestId)

        default:
            print("[NativeBridge] Unknown action: \(action)")
            sendError(requestId: requestId, error: "Unknown action: \(action)")
        }
    }

    // MARK: - Response Helpers

    private func sendSuccess(requestId: String, result: [String: Any]) {
        sendResponse(requestId: requestId, result: result, error: nil)
    }

    private func sendError(requestId: String, error: String) {
        sendResponse(requestId: requestId, result: nil, error: error)
    }

    private func sendResponse(requestId: String, result: [String: Any]?, error: String?) {
        // Resultado recuperable (camara/archivos): guardarlo por si la web recarga antes de recibirlo
        if requestId == recoverableRequestId {
            if error == nil, let result = result {
                pendingResult = ["action": recoverableAction ?? "", "requestId": requestId, "takenAt": Date().timeIntervalSince1970 * 1000, "result": result]
            }
            recoverableRequestId = nil
            recoverableAction = nil
        }
        // Order that came by WebSocket -> response by WebSocket only, with that order's module.action
        if let meta = wsCommands.removeValue(forKey: requestId) {
            var wsResponse: [String: Any] = [
                "type": "command_response",
                "commandId": requestId,
                "module": meta.module,
                "action": meta.action,
                "success": error == nil
            ]
            if let result = result {
                wsResponse["result"] = result
            }
            if let error = error {
                wsResponse["error"] = error
            }

            WebSocketService.shared.sendResponse(wsResponse)
            print("[NativeBridge] Response sent to WebSocket: \(meta.module).\(meta.action)")
            return
        }

        // Regular WebView response
        var response: [String: Any] = [
            "type": "CAPACITOR_RESPONSE",
            "requestId": requestId
        ]

        if let result = result {
            response["result"] = result
        }
        if let error = error {
            response["error"] = error
        }

        guard let jsonData = try? JSONSerialization.data(withJSONObject: response),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            return
        }

        let script = "window.postMessage(\(jsonString), '*');"

        DispatchQueue.main.async {
            self.webView?.evaluateJavaScript(script, completionHandler: nil)
        }
    }

    // MARK: - GEOLOCATION

    private func getCurrentPosition(payload: [String: Any], requestId: String) {
        pendingLocationRequestId = requestId

        let highAccuracy = payload["enableHighAccuracy"] as? Bool ?? true
        locationManager.desiredAccuracy = highAccuracy ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
        locationManager.requestWhenInUseAuthorization()
        locationManager.requestLocation()
    }

    private func checkLocationPermissions(requestId: String) {
        let status: String
        switch CLLocationManager.authorizationStatus() {
        case .authorizedWhenInUse, .authorizedAlways:
            status = "granted"
        case .denied, .restricted:
            status = "denied"
        case .notDetermined:
            status = "prompt"
        @unknown default:
            status = "prompt"
        }
        sendSuccess(requestId: requestId, result: ["location": status, "coarseLocation": status])
    }

    private func requestLocationPermissions(requestId: String) {
        locationManager.requestWhenInUseAuthorization()
        // Return immediately, actual permission will be checked on next call
        checkLocationPermissions(requestId: requestId)
    }

    // CLLocationManagerDelegate
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, let requestId = pendingLocationRequestId else { return }
        pendingLocationRequestId = nil

        let result: [String: Any] = [
            "coords": [
                "latitude": location.coordinate.latitude,
                "longitude": location.coordinate.longitude,
                "accuracy": location.horizontalAccuracy,
                "altitude": location.altitude,
                "altitudeAccuracy": location.verticalAccuracy,
                "heading": location.course,
                "speed": location.speed
            ],
            "timestamp": Int(location.timestamp.timeIntervalSince1970 * 1000)
        ]
        sendSuccess(requestId: requestId, result: result)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let requestId = pendingLocationRequestId {
            pendingLocationRequestId = nil
            sendError(requestId: requestId, error: error.localizedDescription)
        }
        if isWatchingPosition, let watchId = watchPositionId {
            sendError(requestId: watchId, error: error.localizedDescription)
        }
    }

    private func watchPosition(payload: [String: Any], requestId: String) {
        watchPositionId = requestId
        isWatchingPosition = true

        let highAccuracy = payload["enableHighAccuracy"] as? Bool ?? true
        locationManager.desiredAccuracy = highAccuracy ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
        locationManager.distanceFilter = payload["distanceFilter"] as? Double ?? 10
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()

        // Send initial success to confirm watch started
        sendSuccess(requestId: requestId, result: ["watching": true, "watchId": requestId])
    }

    private func clearWatch(payload: [String: Any], requestId: String) {
        isWatchingPosition = false
        watchPositionId = nil
        locationManager.stopUpdatingLocation()
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    // MARK: - NOTIFICATIONS

    private func scheduleNotification(payload: [String: Any], requestId: String) {
        let content = UNMutableNotificationContent()
        content.title = payload["title"] as? String ?? ""
        content.body = payload["body"] as? String ?? ""
        content.sound = .default

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let id = payload["id"] as? Int ?? Int.random(in: 1...999999)
        let request = UNNotificationRequest(identifier: String(id), content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                self.sendError(requestId: requestId, error: error.localizedDescription)
            } else {
                self.sendSuccess(requestId: requestId, result: ["id": id])
            }
        }
    }

    private func checkNotificationPermissions(requestId: String) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let status: String
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                status = "granted"
            case .denied:
                status = "denied"
            case .notDetermined:
                status = "prompt"
            @unknown default:
                status = "prompt"
            }
            self.sendSuccess(requestId: requestId, result: ["display": status])
        }
    }

    private func requestNotificationPermissions(requestId: String) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            self.sendSuccess(requestId: requestId, result: ["display": granted ? "granted" : "denied"])
        }
    }

    private func cancelNotification(payload: [String: Any], requestId: String) {
        if let id = payload["id"] as? Int {
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [String(id)])
        }
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    private func cancelAllNotifications(requestId: String) {
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    // MARK: - CAMERA

    /// Normaliza el `quality` (0-100 del web) a compressionQuality 0.0-1.0; default 0.9.
    private static func clampQuality(_ value: Any?) -> CGFloat {
        // EY-CENTRAL 2026-10-07: acepta entero (70) o decimal (0.7); antes un decimal caía al default.
        guard let n = value as? NSNumber else { return 0.9 }
        let d = n.doubleValue
        let pct = d <= 1.0 ? d * 100.0 : d
        return CGFloat(min(100.0, max(1.0, pct))) / 100.0
    }

    /// Reduce la imagen para que su lado mayor no supere `maxDimension`, evitando
    /// base64 enormes que pueden trabar el WKWebView (PUNTO 2).
    private func downscaleImage(_ image: UIImage, maxDimension: CGFloat = 1920) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxDimension else { return image }
        let scale = maxDimension / longest
        let newSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: newSize)) }
    }

    // EY-CENTRAL 2026-10-07 (pedido 2 de EY-01): la web manda width/height; antes se ignoraban
    // y el lado mayor quedaba fijo en 1920. Ahora el tope es el mayor de width/height si viene
    // (la proporción se mantiene), y 1920 si no viene nada.
    private static func maxDimension(from payload: [String: Any]) -> CGFloat {
        let w = (payload["width"] as? NSNumber)?.doubleValue ?? 0
        let h = (payload["height"] as? NSNumber)?.doubleValue ?? 0
        let m = max(w, h)
        return m > 0 ? CGFloat(m) : 1920
    }

    /// Un solo base64 por foto (antes iba duplicado en base64String y dataUrl: el doble de memoria
    /// en el WKWebView). dataUrl solo si la web pidió resultType "DataUrl".
    private func photoResult(base64: String) -> [String: Any] {
        if pendingCameraResultType == "DataUrl" {
            return ["dataUrl": "data:image/jpeg;base64,\(base64)", "format": "jpeg"]
        }
        return ["base64String": base64, "format": "jpeg"]
    }

    // MARK: - EY-CENTRAL 2026-10-09 (orden del usuario): ARCHIVO COMPARTIDO A EMPLOY → GASTO ("camino simple")
    // iOS entrega a AppDelegate un file:// (PDF o imagen, "Abrir en Employ" / "Copiar a Employ" / Archivos). Se lee
    // entero aca (tope 25 MB, como files.pick), se guarda 10 minutos y:
    //  - se avisa a la web con NATIVE_EVENT share.received { target, fileName, mimeType, size, receivedAt } (si la
    //    pagina ya esta; en frio el aviso se pierde y por eso...)
    //  - la web, al montar, llama share.getPendingFile → { target, fileName, mimeType, size, data(base64), receivedAt }
    //    o {} si no hay nada. Se borra al leerlo. Contrato en 04_BRIDGE.txt / 05_BRIDGE_API.txt.
    // Lado web (EY-01): abrir "gasto nuevo" con ese archivo como comprobante.
    private static let pendingShareTtlMs: Double = 600_000
    private static let pendingShareMaxBytes = 25 * 1024 * 1024
    private var pendingSharedFile: [String: Any]?

    /// Llamado por AppDelegate con el file:// recibido.
    func receiveSharedFile(_ url: URL) {
        DispatchQueue.global(qos: .userInitiated).async {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                print("[Share] Cannot read \(url.lastPathComponent)")
                return
            }
            guard data.count <= Self.pendingShareMaxBytes else {
                print("[Share] File too large: \(url.lastPathComponent) (\(data.count))")
                return
            }
            let fileName = url.lastPathComponent.isEmpty ? "file" : url.lastPathComponent
            let receivedAt = Date().timeIntervalSince1970 * 1000
            let mime = Self.mimeType(for: url)
            let file: [String: Any] = [
                "target": "expense",
                "fileName": fileName,
                "mimeType": mime,
                "size": data.count,
                "data": data.base64EncodedString(),
                "receivedAt": receivedAt
            ]
            // Copia del sistema en Inbox/: se borra, ya esta en memoria.
            try? FileManager.default.removeItem(at: url)
            DispatchQueue.main.async { // pendingSharedFile se lee en el hilo principal (share.getPendingFile)
                self.pendingSharedFile = file
                self.emitNativeEvent("share.received", detail: [
                    "target": "expense", "fileName": fileName, "mimeType": mime, "size": data.count, "receivedAt": receivedAt
                ])
                print("[Share] Shared file received: \(fileName) (\(data.count) bytes)")
            }
        }
    }

    /// Archivo compartido aun no caducado (10 min), o {} si no hay. Se borra al leerlo.
    private func getPendingSharedFile(requestId: String) {
        defer { pendingSharedFile = nil }
        if let p = pendingSharedFile, let receivedAt = p["receivedAt"] as? Double,
           Date().timeIntervalSince1970 * 1000 - receivedAt <= Self.pendingShareTtlMs {
            sendSuccess(requestId: requestId, result: p)
        } else {
            sendSuccess(requestId: requestId, result: [:])
        }
    }

    /// Ultimo resultado de camara/archivos aun no caducado (2 min), o {} si no hay. Se borra al leerlo.
    private func getPendingResult(requestId: String) {
        defer { pendingResult = nil }
        if let p = pendingResult, let takenAt = p["takenAt"] as? Double,
           Date().timeIntervalSince1970 * 1000 - takenAt <= Self.pendingResultTtlMs {
            sendSuccess(requestId: requestId, result: p)
        } else {
            sendSuccess(requestId: requestId, result: [:])
        }
    }

    private func getPhoto(payload: [String: Any], requestId: String) {
        if pendingCameraRequestId != nil {
            sendError(requestId: requestId, error: "Camera/picker already in progress")
            return
        }
        pendingCameraRequestId = requestId
        pendingCameraQuality = Self.clampQuality(payload["quality"])
        pendingCameraMaxDimension = Self.maxDimension(from: payload)
        pendingCameraResultType = (payload["resultType"] as? String) ?? "Base64"

        DispatchQueue.main.async {
            let picker = UIImagePickerController()
            picker.delegate = self

            let source = payload["source"] as? String ?? "Prompt"
            if source == "Camera" && UIImagePickerController.isSourceTypeAvailable(.camera) {
                picker.sourceType = .camera
            } else {
                picker.sourceType = .photoLibrary
            }

            picker.allowsEditing = payload["allowEditing"] as? Bool ?? false

            self.viewController?.present(picker, animated: true)
        }
    }

    private func checkCameraPermissions(requestId: String) {
        let cameraStatus: String
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            cameraStatus = "granted"
        case .denied, .restricted:
            cameraStatus = "denied"
        case .notDetermined:
            cameraStatus = "prompt"
        @unknown default:
            cameraStatus = "prompt"
        }

        let photosStatus: String
        switch PHPhotoLibrary.authorizationStatus() {
        case .authorized, .limited:
            photosStatus = "granted"
        case .denied, .restricted:
            photosStatus = "denied"
        case .notDetermined:
            photosStatus = "prompt"
        @unknown default:
            photosStatus = "prompt"
        }

        sendSuccess(requestId: requestId, result: ["camera": cameraStatus, "photos": photosStatus])
    }

    private func requestCameraPermissions(requestId: String) {
        AVCaptureDevice.requestAccess(for: .video) { _ in
            self.checkCameraPermissions(requestId: requestId)
        }
    }

    private func pickImages(payload: [String: Any], requestId: String) {
        if pendingCameraRequestId != nil {
            sendError(requestId: requestId, error: "Camera/picker already in progress")
            return
        }
        pendingCameraRequestId = requestId
        pendingCameraQuality = Self.clampQuality(payload["quality"])
        pendingCameraMaxDimension = Self.maxDimension(from: payload)
        pendingCameraResultType = (payload["resultType"] as? String) ?? "Base64"

        DispatchQueue.main.async {
            if #available(iOS 14.0, *) {
                var config = PHPickerConfiguration()
                config.selectionLimit = payload["limit"] as? Int ?? 0 // 0 = unlimited
                config.filter = .images

                let picker = PHPickerViewController(configuration: config)
                picker.delegate = self
                self.viewController?.present(picker, animated: true)
            } else {
                // Fallback to single image picker for iOS < 14
                self.getPhoto(payload: payload, requestId: requestId)
            }
        }
    }

    private func pickVideo(payload: [String: Any], requestId: String) {
        if pendingCameraRequestId != nil {
            sendError(requestId: requestId, error: "Camera/picker already in progress")
            return
        }
        pendingCameraRequestId = requestId

        DispatchQueue.main.async {
            let picker = UIImagePickerController()
            picker.delegate = self
            picker.sourceType = .photoLibrary
            picker.mediaTypes = ["public.movie"]
            picker.videoQuality = .typeHigh

            self.viewController?.present(picker, animated: true)
        }
    }

    // MARK: - MICROPHONE

    private func checkMicrophonePermissions(requestId: String) {
        let status: String
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            status = "granted"
        case .denied:
            status = "denied"
        case .undetermined:
            status = "prompt"
        @unknown default:
            status = "prompt"
        }
        sendSuccess(requestId: requestId, result: ["microphone": status])
    }

    private func requestMicrophonePermissions(requestId: String) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            self.sendSuccess(requestId: requestId, result: ["microphone": granted ? "granted" : "denied"])
        }
    }

    // MARK: - BIOMETRICS

    private func checkBiometricsAvailable(requestId: String) {
        let context = LAContext()
        var error: NSError?
        let available = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)

        var biometryType = "none"
        if #available(iOS 11.0, *) {
            switch context.biometryType {
            case .faceID:
                biometryType = "face"
            case .touchID:
                biometryType = "fingerprint"
            default:
                biometryType = "none"
            }
        }

        sendSuccess(requestId: requestId, result: [
            "isAvailable": available,
            "biometryType": biometryType,
            "errorCode": error?.code ?? 0
        ])
    }

    private func verifyIdentity(payload: [String: Any], requestId: String) {
        let context = LAContext()
        let reason = payload["reason"] as? String ?? "Authenticate"

        // .deviceOwnerAuthentication permite fallback a passcode del dispositivo si
        // Face ID/Touch ID falla o está bloqueado (coincide con el subtítulo del web).
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, error in
            if success {
                // Get stored session data (like Android does)
                let userId = self.defaults.string(forKey: "userId")
                let companyId = self.defaults.string(forKey: "companyId")

                // Get deviceId with mobile_ prefix (usando Keychain para persistencia)
                let deviceId = self.getPersistentDeviceId()

                self.sendSuccess(requestId: requestId, result: [
                    "verified": true,
                    "userId": userId as Any,
                    "companyId": companyId as Any,
                    "deviceId": deviceId as Any,
                    "timestamp": Int(Date().timeIntervalSince1970 * 1000)
                ])
            } else {
                self.sendError(requestId: requestId, error: error?.localizedDescription ?? "Authentication failed")
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // APP ICON — app.setIcon / app.getIcon (EY-CENTRAL 2026-10-08, pedido del usuario)
    // Ícono alternativo por organización. El catálogo son los appiconset "icon-<nombre>" del
    // Assets.xcassets (generados por zScripts/generate-app-icons.mjs; el target los incluye
    // todos). "default" = AppIcon. iOS muestra un aviso del sistema al cambiar; no se evita.
    //   payload: { name: "default" | "<nombre>" }   result: { name, changed }
    // ═══════════════════════════════════════════════════════════════════════════

    private func appSetIcon(payload: [String: Any], requestId: String) {
        var name = ((payload["name"] as? String) ?? "default").trimmingCharacters(in: .whitespaces).lowercased()
        if name.isEmpty { name = "default" }
        guard UIApplication.shared.supportsAlternateIcons else {
            sendError(requestId: requestId, error: "Alternate icons not supported")
            return
        }
        let target: String? = name == "default" ? nil : "icon-\(name)"
        let current = UIApplication.shared.alternateIconName
        if current == target {
            sendSuccess(requestId: requestId, result: ["name": name, "changed": false])
            return
        }
        DispatchQueue.main.async {
            UIApplication.shared.setAlternateIconName(target) { error in
                if let error = error {
                    self.sendError(requestId: requestId, error: "Cannot change icon: \(error.localizedDescription)")
                } else {
                    self.defaults.set(name, forKey: "app_icon")
                    self.sendSuccess(requestId: requestId, result: ["name": name, "changed": true])
                }
            }
        }
    }

    private func appGetIcon(requestId: String) {
        let current = UIApplication.shared.alternateIconName.map { $0.replacingOccurrences(of: "icon-", with: "") } ?? "default"
        // Catálogo: los appiconset incluidos en el bundle (CFBundleIcons / CFBundleAlternateIcons)
        var catalog = ["default"]
        if let icons = Bundle.main.object(forInfoDictionaryKey: "CFBundleIcons") as? [String: Any],
           let alternates = icons["CFBundleAlternateIcons"] as? [String: Any] {
            catalog += alternates.keys.map { $0.replacingOccurrences(of: "icon-", with: "") }.sorted()
        }
        sendSuccess(requestId: requestId, result: ["name": current, "catalog": catalog])
    }

    // MARK: - HAPTICS

    private func hapticImpact(payload: [String: Any], requestId: String) {
        let style = payload["style"] as? String ?? "Medium"
        let impactStyle: UIImpactFeedbackGenerator.FeedbackStyle

        switch style.lowercased() {
        case "light":
            impactStyle = .light
        case "heavy":
            impactStyle = .heavy
        default:
            impactStyle = .medium
        }

        let generator = UIImpactFeedbackGenerator(style: impactStyle)
        generator.impactOccurred()
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    private func hapticNotification(payload: [String: Any], requestId: String) {
        let type = payload["type"] as? String ?? "Success"
        let notificationType: UINotificationFeedbackGenerator.FeedbackType

        switch type.lowercased() {
        case "warning":
            notificationType = .warning
        case "error":
            notificationType = .error
        default:
            notificationType = .success
        }

        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(notificationType)
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    private func hapticSelection(requestId: String) {
        let generator = UISelectionFeedbackGenerator()
        generator.selectionChanged()
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    // MARK: - STORE (In-App Review)

    private func requestStoreReview(requestId: String) {
        DispatchQueue.main.async {
            if #available(iOS 14.0, *) {
                // iOS 14+ uses the scene-based API
                if let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene {
                    SKStoreReviewController.requestReview(in: scene)
                    print("[Store] Requested In-App Review (iOS 14+)")
                    self.sendSuccess(requestId: requestId, result: [
                        "shown": true,
                        "message": "Review dialog requested (result unknown by design)"
                    ])
                } else {
                    print("[Store] No active window scene found")
                    self.sendSuccess(requestId: requestId, result: [
                        "shown": false,
                        "message": "No active window scene found"
                    ])
                }
            } else {
                // iOS 10.3 - 13
                SKStoreReviewController.requestReview()
                print("[Store] Requested In-App Review (iOS < 14)")
                self.sendSuccess(requestId: requestId, result: [
                    "shown": true,
                    "message": "Review dialog requested (result unknown by design)"
                ])
            }
        }
    }

    // MARK: - STATUS BAR

    private func setStatusBarStyle(payload: [String: Any], requestId: String) {
        // iOS handles this automatically based on content
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    // MARK: - CLIPBOARD

    private func clipboardWrite(payload: [String: Any], requestId: String) {
        if let text = payload["string"] as? String ?? payload["text"] as? String {
            UIPasteboard.general.string = text
            sendSuccess(requestId: requestId, result: ["success": true])
        } else {
            sendError(requestId: requestId, error: "No text provided")
        }
    }

    private func clipboardRead(requestId: String) {
        let text = UIPasteboard.general.string ?? ""
        sendSuccess(requestId: requestId, result: ["value": text, "type": "text/plain"])
    }

    // MARK: - SHARE

    private func share(payload: [String: Any], requestId: String) {
        var items: [Any] = []

        if let text = payload["text"] as? String {
            items.append(text)
        }
        if let url = payload["url"] as? String, let shareUrl = URL(string: url) {
            items.append(shareUrl)
        }

        guard !items.isEmpty else {
            sendError(requestId: requestId, error: "Nothing to share")
            return
        }

        DispatchQueue.main.async {
            let activityVC = UIActivityViewController(activityItems: items, applicationActivities: nil)
            self.anchorPopover(activityVC)
            self.viewController?.present(activityVC, animated: true) {
                self.sendSuccess(requestId: requestId, result: ["success": true])
            }
        }
    }

    /// Comparte un archivo recibido como base64 (PUNTO 1: paridad con Android share.shareFile).
    /// Escribe el archivo a un dir temporal y lo entrega al share sheet del sistema vía su URL,
    /// evitando depender del Web Share API (poco fiable en WKWebView).
    private func shareFile(payload: [String: Any], requestId: String) {
        guard let base64 = payload["data"] as? String, !base64.isEmpty,
              let fileData = Data(base64Encoded: base64) else {
            sendError(requestId: requestId, error: "No file data provided")
            return
        }

        let rawName = (payload["fileName"] as? String) ?? "document"
        let safeName = rawName.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        let finalName = safeName.isEmpty ? "document" : safeName

        let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent("shared_files", isDirectory: true)
        let fileURL = tmpDir.appendingPathComponent(finalName)
        do {
            try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
            try fileData.write(to: fileURL)
        } catch {
            sendError(requestId: requestId, error: "Failed to write file: \(error.localizedDescription)")
            return
        }

        DispatchQueue.main.async {
            let activityVC = UIActivityViewController(activityItems: [fileURL], applicationActivities: nil)
            self.anchorPopover(activityVC)
            // El web no hace fallback si la promesa resuelve OK; resolvemos siempre que se mostró el sheet,
            // independientemente de si el usuario compartió o canceló.
            activityVC.completionWithItemsHandler = { _, completed, _, _ in
                self.sendSuccess(requestId: requestId, result: ["success": completed])
            }
            self.viewController?.present(activityVC, animated: true)
        }
    }

    /// Ancla el popover del share sheet (necesario en iPad para no crashear).
    private func anchorPopover(_ activityVC: UIActivityViewController) {
        guard let popover = activityVC.popoverPresentationController,
              let view = self.viewController?.view else { return }
        popover.sourceView = view
        popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0)
        popover.permittedArrowDirections = []
    }

    // MARK: - BROWSER

    private func openBrowser(payload: [String: Any], requestId: String) {
        guard let urlString = payload["url"] as? String, let url = URL(string: urlString) else {
            sendError(requestId: requestId, error: "Invalid URL")
            return
        }

        DispatchQueue.main.async {
            UIApplication.shared.open(url)
            self.sendSuccess(requestId: requestId, result: ["success": true])
        }
    }

    // MARK: - DEVICE

    private func getDeviceInfo(requestId: String) {
        let device = UIDevice.current
        let result: [String: Any] = [
            "platform": "ios",
            "manufacturer": "Apple",
            "model": device.model,
            "osVersion": device.systemVersion,
            "isVirtual": isSimulator,
            "name": device.name
        ]
        sendSuccess(requestId: requestId, result: result)
    }

    private func getBatteryInfo(requestId: String) {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let result: [String: Any] = [
            "batteryLevel": UIDevice.current.batteryLevel,
            "isCharging": UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        ]
        sendSuccess(requestId: requestId, result: result)
    }

    private func getDeviceId(requestId: String) {
        // Use stored deviceId with mobile_ prefix (usando Keychain para persistencia)
        let deviceId = getPersistentDeviceId()
        sendSuccess(requestId: requestId, result: ["identifier": deviceId, "deviceId": deviceId])
    }

    private func getNativeInfo(requestId: String) {
        let device = UIDevice.current
        let screen = UIScreen.main

        // Get or create deviceId with mobile_ prefix (usando Keychain para persistencia)
        let finalDeviceId = getPersistentDeviceId()

        // Always try to get FCM token (like Android does)
        // APNs token can be obtained without notification display permission
        // Notification permission is only needed to DISPLAY notifications, not to receive the token
        print("[FCM] Getting token...")
        fetchFCMTokenAndSendNativeInfo(requestId: requestId, device: device, screen: screen, deviceId: finalDeviceId)
    }

    private func fetchFCMTokenAndSendNativeInfo(requestId: String, device: UIDevice, screen: UIScreen, deviceId: String, retryCount: Int = 0) {
        // First check if we have a cached token
        if let cachedToken = defaults.string(forKey: "fcmToken"), !cachedToken.isEmpty {
            print("[FCM] Using cached token: \(String(cachedToken.prefix(20)))...")
            self.updateFcmTokenInFirestore(deviceId: deviceId, fcmToken: cachedToken)
            self.sendNativeInfoResponse(requestId: requestId, device: device, screen: screen, deviceId: deviceId, fcmToken: cachedToken)
            return
        }

        // Try to get token from Firebase
        Messaging.messaging().token { [weak self] fcmToken, error in
            guard let self = self else { return }

            if let error = error {
                print("[FCM] Error getting token: \(error.localizedDescription)")
            }

            if let token = fcmToken {
                print("[FCM] Token obtained: \(String(token.prefix(20)))...")
                self.defaults.set(token, forKey: "fcmToken")
                self.updateFcmTokenInFirestore(deviceId: deviceId, fcmToken: token)
                self.sendNativeInfoResponse(requestId: requestId, device: device, screen: screen, deviceId: deviceId, fcmToken: token)
            } else if retryCount < 3 {
                // Retry after a delay (APNs token might not be ready yet)
                print("[FCM] Token is nil, retry \(retryCount + 1)/3 in 1.5 seconds...")
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    self.fetchFCMTokenAndSendNativeInfo(requestId: requestId, device: device, screen: screen, deviceId: deviceId, retryCount: retryCount + 1)
                }
            } else {
                print("[FCM] Token is nil after 3 retries - APNs may not be configured")
                self.sendNativeInfoResponse(requestId: requestId, device: device, screen: screen, deviceId: deviceId, fcmToken: nil)
            }
        }
    }

    private func sendNativeInfoResponse(requestId: String, device: UIDevice, screen: UIScreen, deviceId: String, fcmToken: String?) {
        let result: [String: Any] = [
            "deviceId": deviceId,
            "fcmToken": fcmToken as Any,
            "device": [
                "platform": "ios",
                "manufacturer": "Apple",
                "model": device.model,
                "osVersion": device.systemVersion,
                "isVirtual": self.isSimulator,
                "screen": [
                    "width": Int(screen.bounds.width),
                    "height": Int(screen.bounds.height),
                    "pixelRatio": screen.scale
                ],
                "locale": Locale.current.identifier,
                "timezone": TimeZone.current.identifier
            ],
            "app": [
                "version": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0",
                "build": Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1",
                "capabilities": ["files.pick", "camera.getPendingResult", "share.receive"] // EY-CENTRAL 2026-10-08: para nativeAppSupports; share.receive desde el 389
            ]
        ]
        self.sendSuccess(requestId: requestId, result: result)
    }

    // MARK: - CONFIG (Firebase URLs)

    private func getConfigUrls(requestId: String) {
        let webappUrl = defaults.string(forKey: "cached_webapp_url")
        let apiUrl = defaults.string(forKey: "cached_api_url")
        let wsUrl = defaults.string(forKey: "cached_ws_url")

        let result: [String: Any] = [
            "webapp": webappUrl as Any,
            "api": apiUrl as Any,
            "ws": wsUrl as Any
        ]

        print("[Config] Returning URLs - api: \(apiUrl ?? "nil")")
        sendSuccess(requestId: requestId, result: result)
    }

    // MARK: - NETWORK

    private func getNetworkStatus(requestId: String) {
        // Simple check - for real implementation use Reachability
        let result: [String: Any] = [
            "connected": true,
            "connectionType": "wifi"
        ]
        sendSuccess(requestId: requestId, result: result)
    }

    // MARK: - STORAGE

    private func storageGet(payload: [String: Any], requestId: String) {
        guard let key = payload["key"] as? String else {
            sendError(requestId: requestId, error: "Missing key")
            return
        }

        let value = defaults.string(forKey: prefsPrefix + key)
        sendSuccess(requestId: requestId, result: ["value": value as Any])
    }

    private func storageSet(payload: [String: Any], requestId: String) {
        guard let key = payload["key"] as? String else {
            sendError(requestId: requestId, error: "Missing key")
            return
        }

        let value = payload["value"] as? String
        defaults.set(value, forKey: prefsPrefix + key)
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    private func storageRemove(payload: [String: Any], requestId: String) {
        guard let key = payload["key"] as? String else {
            sendError(requestId: requestId, error: "Missing key")
            return
        }

        defaults.removeObject(forKey: prefsPrefix + key)
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    private func storageClear(requestId: String) {
        let keys = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(prefsPrefix) }
        keys.forEach { defaults.removeObject(forKey: $0) }
        sendSuccess(requestId: requestId, result: ["success": true])
    }

    private func storageKeys(requestId: String) {
        let keys = defaults.dictionaryRepresentation().keys
            .filter { $0.hasPrefix(prefsPrefix) }
            .map { String($0.dropFirst(prefsPrefix.count)) }
        sendSuccess(requestId: requestId, result: ["keys": keys])
    }

    // MARK: - CACHE

    private func cacheClearAll(payload: [String: Any], requestId: String) {
        let reload = payload["reload"] as? Bool ?? true

        DispatchQueue.main.async {
            // 1. Clear cookies
            HTTPCookieStorage.shared.removeCookies(since: Date.distantPast)

            // 2. Clear WebView cache
            let dataTypes = WKWebsiteDataStore.allWebsiteDataTypes()
            WKWebsiteDataStore.default().removeData(ofTypes: dataTypes, modifiedSince: Date.distantPast) {
                print("[Cache] WebView data cleared")
            }

            // 3. Clear localStorage via JavaScript
            self.webView?.evaluateJavaScript(
                "try { localStorage.clear(); sessionStorage.clear(); } catch(e) {}",
                completionHandler: nil
            )

            // 4. Clear preferences (except device_id)
            let deviceId = self.defaults.string(forKey: "device_id")
            let keysToRemove = self.defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(self.prefsPrefix) }
            keysToRemove.forEach { self.defaults.removeObject(forKey: $0) }
            if let deviceId = deviceId {
                self.defaults.set(deviceId, forKey: "device_id")
            }

            print("[Cache] All cache cleared, reload=\(reload)")
            self.sendSuccess(requestId: requestId, result: ["success": true, "message": "All cache cleared"])

            if reload {
                self.webView?.reload()
            }
        }
    }

    private func cacheClearHttpCache(requestId: String) {
        DispatchQueue.main.async {
            let dataTypes: Set<String> = [WKWebsiteDataTypeDiskCache, WKWebsiteDataTypeMemoryCache]
            WKWebsiteDataStore.default().removeData(ofTypes: dataTypes, modifiedSince: Date.distantPast) {
                self.sendSuccess(requestId: requestId, result: ["success": true, "message": "HTTP cache cleared"])
            }
        }
    }

    private func cacheClearStorage(requestId: String) {
        DispatchQueue.main.async {
            self.webView?.evaluateJavaScript(
                "try { localStorage.clear(); sessionStorage.clear(); console.log('[Cache] Storage cleared'); } catch(e) {}",
                completionHandler: nil
            )
            self.sendSuccess(requestId: requestId, result: ["success": true, "message": "Web storage cleared"])
        }
    }

    private func cacheClearPreferences(requestId: String) {
        // Save device_id before clearing
        let deviceId = defaults.string(forKey: "device_id")

        // Clear preferences
        let keysToRemove = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(prefsPrefix) }
        keysToRemove.forEach { defaults.removeObject(forKey: $0) }

        // Restore device_id
        if let deviceId = deviceId {
            defaults.set(deviceId, forKey: "device_id")
        }

        sendSuccess(requestId: requestId, result: ["success": true, "message": "Preferences cleared"])
    }

    private func cacheReload(requestId: String) {
        DispatchQueue.main.async {
            self.webView?.reload()
            self.sendSuccess(requestId: requestId, result: ["success": true, "message": "App reloaded"])
        }
    }

    private func cacheHardReload(requestId: String) {
        DispatchQueue.main.async {
            // Clear cache first
            let dataTypes: Set<String> = [WKWebsiteDataTypeDiskCache, WKWebsiteDataTypeMemoryCache]
            WKWebsiteDataStore.default().removeData(ofTypes: dataTypes, modifiedSince: Date.distantPast) {
                self.webView?.reload()
                self.sendSuccess(requestId: requestId, result: ["success": true, "message": "Hard reload completed"])
            }
        }
    }

    private func cacheClearAndRestart(requestId: String) {
        DispatchQueue.main.async {
            // 1. Clear cookies
            HTTPCookieStorage.shared.removeCookies(since: Date.distantPast)

            // 2. Clear WebView data
            let dataTypes = WKWebsiteDataStore.allWebsiteDataTypes()
            WKWebsiteDataStore.default().removeData(ofTypes: dataTypes, modifiedSince: Date.distantPast) { }

            // 3. Clear localStorage
            self.webView?.evaluateJavaScript("try { localStorage.clear(); sessionStorage.clear(); } catch(e) {}", completionHandler: nil)

            // 4. Clear preferences (except device_id)
            let deviceId = self.defaults.string(forKey: "device_id")
            let keysToRemove = self.defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(self.prefsPrefix) }
            keysToRemove.forEach { self.defaults.removeObject(forKey: $0) }
            if let deviceId = deviceId {
                self.defaults.set(deviceId, forKey: "device_id")
            }

            self.sendSuccess(requestId: requestId, result: ["success": true, "message": "Cache cleared, restarting..."])

            // Reload after small delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                if let url = self.webView?.url {
                    self.webView?.load(URLRequest(url: url))
                } else {
                    self.webView?.reload()
                }
            }
        }
    }

    // MARK: - SESSION

    private func sessionSave(payload: [String: Any], requestId: String) {
        let userId = payload["userId"] as? String
        let companyId = payload["companyId"] as? String
        let sessionId = payload["sessionId"] as? String

        if let userId = userId {
            defaults.set(userId, forKey: "userId")
        }
        if let companyId = companyId {
            defaults.set(companyId, forKey: "companyId")
        }
        if let sessionId = sessionId {
            defaults.set(sessionId, forKey: "sessionId")
        }

        print("[Session] Saved - userId: \(userId ?? "nil"), companyId: \(companyId ?? "nil")")

        // Re-register WebSocket with new session data
        PushTokenSync.syncPendingIfNeeded() // EY-CENTRAL 2026-10-08: token FCM pendiente, ahora con sesión
        WebSocketService.shared.reRegister()
        print("[NativeBridge] WebSocket re-registered with new session data")

        // Send session data to Apple Watch
        WatchConnectivityService.shared.sendSessionData()
        print("[NativeBridge] Session data sent to Apple Watch")

        // Update Firestore with session data (required for push notifications!)
        updateDeviceSessionInFirestore()

        sendSuccess(requestId: requestId, result: [
            "saved": true,
            "userId": userId as Any,
            "companyId": companyId as Any
        ])
    }

    private func sessionGet(requestId: String) {
        let userId = defaults.string(forKey: "userId")
        let companyId = defaults.string(forKey: "companyId")
        let sessionId = defaults.string(forKey: "sessionId")

        // Get deviceId with mobile_ prefix (usando Keychain para persistencia)
        let deviceId = getPersistentDeviceId()

        let biometricEnabled = defaults.string(forKey: "biometric_login_enabled")
        let biometricVerified = defaults.string(forKey: "biometric_login_verified")

        sendSuccess(requestId: requestId, result: [
            "userId": userId as Any,
            "companyId": companyId as Any,
            "sessionId": sessionId as Any,
            "deviceId": deviceId as Any,
            "hasSession": userId != nil,
            "biometricLoginEnabled": biometricEnabled == "true",
            "biometricLoginVerified": biometricVerified == "true"
        ])
    }

    private func enableBiometricLogin(payload: [String: Any], requestId: String) {
        let enable = payload["enable"] as? Bool ?? true
        defaults.set(enable ? "true" : "false", forKey: "biometric_login_enabled")
        print("[Session] Biometric login \(enable ? "enabled" : "disabled")")
        sendSuccess(requestId: requestId, result: ["enabled": enable])
    }

    private func checkBiometricLoginStatus(requestId: String) {
        let enabled = defaults.string(forKey: "biometric_login_enabled") == "true"
        let verified = defaults.string(forKey: "biometric_login_verified") == "true"
        let userId = defaults.string(forKey: "userId")
        let companyId = defaults.string(forKey: "companyId")

        sendSuccess(requestId: requestId, result: [
            "enabled": enabled,
            "verified": verified,
            "hasSession": userId != nil,
            "userId": userId as Any,
            "companyId": companyId as Any
        ])
    }

    // MARK: - APP

    private func getAppInfo(requestId: String) {
        let info = Bundle.main.infoDictionary
        let result: [String: Any] = [
            "name": info?["CFBundleName"] as? String ?? "Employ",
            "id": info?["CFBundleIdentifier"] as? String ?? "",
            "build": info?["CFBundleVersion"] as? String ?? "1",
            "version": info?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        ]
        sendSuccess(requestId: requestId, result: result)
    }

    private func appMinimize(requestId: String) {
        // iOS doesn't support minimizing apps programmatically
        // This is for API compatibility with Android
        sendSuccess(requestId: requestId, result: ["success": true, "message": "Not supported on iOS"])
    }

    private func appExit(requestId: String) {
        // iOS doesn't allow programmatic app termination
        // This is for API compatibility with Android
        sendSuccess(requestId: requestId, result: ["success": true, "message": "Not supported on iOS"])
    }

    // MARK: - KEYBOARD

    private func keyboardShow(requestId: String) {
        DispatchQueue.main.async {
            // Find first responder or make webview first responder
            self.webView?.becomeFirstResponder()
            self.sendSuccess(requestId: requestId, result: ["success": true])
        }
    }

    private func keyboardHide(requestId: String) {
        DispatchQueue.main.async {
            self.webView?.resignFirstResponder()
            self.viewController?.view.endEditing(true)
            self.sendSuccess(requestId: requestId, result: ["success": true])
        }
    }

    // MARK: - AUTH

    private func clearSession(requestId: String) {
        // Clear session data from UserDefaults
        defaults.removeObject(forKey: "userId")
        defaults.removeObject(forKey: "companyId")
        defaults.removeObject(forKey: "sessionId")
        print("[Session] Session data cleared")

        // Notify Apple Watch of logout
        WatchConnectivityService.shared.notifyLogout()
        print("[NativeBridge] Logout sent to Apple Watch")

        // Clear cookies
        let dataStore = WKWebsiteDataStore.default()
        dataStore.fetchDataRecords(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes()) { records in
            dataStore.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), for: records) {
                self.sendSuccess(requestId: requestId, result: ["success": true, "message": "Session cleared"])
            }
        }
    }

    // MARK: - PUSH

    private func checkPushPermissions(requestId: String) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let status: String
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                status = "granted"
            case .denied:
                status = "denied"
            case .notDetermined:
                status = "prompt"
            @unknown default:
                status = "prompt"
            }
            self.sendSuccess(requestId: requestId, result: ["receive": status])
        }
    }

    private func requestPushPermissions(requestId: String) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            DispatchQueue.main.async {
                if granted {
                    print("[FCM] Permission granted, registering for remote notifications...")
                    // Register for remote notifications
                    UIApplication.shared.registerForRemoteNotifications()

                    // Get deviceId with mobile_ prefix (usando Keychain para persistencia)
                    let finalDeviceId = self.getPersistentDeviceId()

                    // Wait for APNs token, then get FCM token with retries
                    self.getFCMTokenWithRetry(requestId: requestId, deviceId: finalDeviceId, attempt: 1)
                } else {
                    print("[FCM] Permission denied by user")
                    self.sendSuccess(requestId: requestId, result: ["receive": "denied"])
                }
            }
        }
    }

    private func getFCMTokenWithRetry(requestId: String, deviceId: String, attempt: Int) {
        let maxAttempts = 5
        let delaySeconds: Double = attempt == 1 ? 3.0 : 2.0  // First attempt waits 3s, then 2s

        print("[FCM] Attempt \(attempt)/\(maxAttempts) - waiting \(delaySeconds)s for token...")

        DispatchQueue.main.asyncAfter(deadline: .now() + delaySeconds) { [weak self] in
            guard let self = self else { return }

            Messaging.messaging().token { [weak self] token, error in
                guard let self = self else { return }

                if let token = token {
                    print("[FCM] ✓ Token obtained on attempt \(attempt): \(String(token.prefix(20)))...")
                    self.defaults.set(token, forKey: "fcmToken")
                    self.updateFcmTokenInFirestore(deviceId: deviceId, fcmToken: token)

                    self.sendSuccess(requestId: requestId, result: [
                        "receive": "granted",
                        "token": token,
                        "fcmToken": token,
                        "deviceId": deviceId
                    ])
                } else if attempt < maxAttempts {
                    print("[FCM] Token nil on attempt \(attempt), retrying...")
                    self.getFCMTokenWithRetry(requestId: requestId, deviceId: deviceId, attempt: attempt + 1)
                } else {
                    print("[FCM] ✗ Failed to get token after \(maxAttempts) attempts")
                    // Still return granted but without token - webapp should show error
                    self.sendSuccess(requestId: requestId, result: [
                        "receive": "granted",
                        "token": NSNull(),
                        "fcmToken": NSNull(),
                        "deviceId": deviceId,
                        "error": "FCM token not available after \(maxAttempts) attempts. Check APNs configuration."
                    ])
                }
            }
        }
    }

    private func getPushToken(requestId: String) {
        // Get token directly from Firebase Messaging (like Android does)
        Messaging.messaging().token { [weak self] token, error in
            guard let self = self else { return }

            if let error = error {
                print("[FCM] Error getting token: \(error.localizedDescription)")
                self.sendError(requestId: requestId, error: error.localizedDescription)
                return
            }

            if let token = token {
                print("[FCM] Token obtained: \(String(token.prefix(20)))...")
                // Also save to UserDefaults for backup
                self.defaults.set(token, forKey: "fcmToken")

                // Get deviceId with mobile_ prefix (usando Keychain para persistencia)
                let deviceId = self.getPersistentDeviceId()
                self.updateFcmTokenInFirestore(deviceId: deviceId, fcmToken: token)

                self.sendSuccess(requestId: requestId, result: ["value": token])
            } else {
                self.sendError(requestId: requestId, error: "No token available")
            }
        }
    }

    // MARK: - Firestore Device Updates

    /// Update device session in Firestore (required for push notifications)
    private func updateDeviceSessionInFirestore() {
        // Get deviceId (usando Keychain para persistencia)
        let finalDeviceId = getPersistentDeviceId()

        let userId = defaults.string(forKey: "userId")
        let companyId = defaults.string(forKey: "companyId")
        let sessionId = defaults.string(forKey: "sessionId")

        let db = Firestore.firestore()
        let dateFormatter = ISO8601DateFormatter()
        dateFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let timestamp = dateFormatter.string(from: Date())

        var data: [String: Any] = [
            "platform": "ios",
            "deviceType": "mobile",
            "updatedAt": timestamp
        ]

        if let userId = userId {
            data["userId"] = userId
        }
        if let companyId = companyId {
            data["companyId"] = companyId
        }
        if let sessionId = sessionId {
            data["currentSessionId"] = sessionId
        }

        db.collection("devices").document(finalDeviceId).setData(data, merge: true) { error in
            if let error = error {
                print("[Session] Error updating device in Firestore: \(error.localizedDescription)")
            } else {
                print("[Session] Device updated in Firestore: \(finalDeviceId) (sessionId: \(sessionId ?? "nil"))")
            }
        }
    }

    private func updateFcmTokenInFirestore(deviceId: String, fcmToken: String) {
        let db = Firestore.firestore()

        let dateFormatter = ISO8601DateFormatter()
        dateFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let timestamp = dateFormatter.string(from: Date())

        // Get session data from UserDefaults
        let userId = defaults.string(forKey: "userId")
        let companyId = defaults.string(forKey: "companyId")
        let sessionId = defaults.string(forKey: "sessionId")

        var data: [String: Any] = [
            "fcmToken": fcmToken,
            "fcmTokenUpdatedAt": timestamp,
            "platform": "ios",
            "deviceType": "mobile",
            "updatedAt": timestamp
        ]

        // Add session data if available (required for push notifications!)
        if let userId = userId {
            data["userId"] = userId
        }
        if let companyId = companyId {
            data["companyId"] = companyId
        }
        if let sessionId = sessionId {
            data["currentSessionId"] = sessionId  // Required for push to work!
        }

        db.collection("devices").document(deviceId).setData(data, merge: true) { error in
            if let error = error {
                print("[FCM] Error saving token to Firestore: \(error.localizedDescription)")
            } else {
                print("[FCM] Token saved to Firestore for device: \(deviceId) (sessionId: \(sessionId ?? "nil"))")
            }
        }
    }
}

// MARK: - UIImagePickerControllerDelegate

extension NativeBridge: UIImagePickerControllerDelegate, UINavigationControllerDelegate {

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
        picker.dismiss(animated: true)

        guard let requestId = pendingCameraRequestId else { return }
        pendingCameraRequestId = nil

        if let rawImage = info[.editedImage] as? UIImage ?? info[.originalImage] as? UIImage {
            let image = downscaleImage(rawImage, maxDimension: pendingCameraMaxDimension)
            if let imageData = image.jpegData(compressionQuality: pendingCameraQuality) {
                let base64 = imageData.base64EncodedString()
                sendSuccess(requestId: requestId, result: photoResult(base64: base64))
                return
            }
        }

        sendError(requestId: requestId, error: "Failed to process image")
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)

        guard let requestId = pendingCameraRequestId else { return }
        pendingCameraRequestId = nil
        sendError(requestId: requestId, error: "User cancelled")
    }
}

// MARK: - PHPickerViewControllerDelegate (iOS 14+)

@available(iOS 14.0, *)
extension NativeBridge: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)

        guard let requestId = pendingCameraRequestId else { return }
        pendingCameraRequestId = nil

        if results.isEmpty {
            sendError(requestId: requestId, error: "User cancelled")
            return
        }

        var images: [[String: Any]] = []
        let group = DispatchGroup()

        for result in results {
            group.enter()
            result.itemProvider.loadObject(ofClass: UIImage.self) { [weak self] object, error in
                defer { group.leave() }

                if let rawImage = object as? UIImage,
                   let imageData = self?.downscaleImage(rawImage, maxDimension: self?.pendingCameraMaxDimension ?? 1920).jpegData(compressionQuality: self?.pendingCameraQuality ?? 0.9) {
                    let base64 = imageData.base64EncodedString()
                    if let r = self?.photoResult(base64: base64) { images.append(r) }
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            if images.isEmpty {
                self?.sendError(requestId: requestId, error: "Failed to process images")
            } else {
                self?.sendSuccess(requestId: requestId, result: ["images": images])
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// FILES — files.pick (EY-CENTRAL, 2026-10-07)
// Selector de documentos del sistema (UIDocumentPickerViewController): PDF,
// documentos, imágenes, lo que la web pida en `accept`. Devuelve cada archivo
// en base64, espejo inverso de share.shareFile. Contrato en zDocumentation/04_BRIDGE.txt.
//   payload: { accept?: [String] (mime o ".ext"), multiple?: Bool, maxSize?: Int (bytes) }
//   result:  { files: [{ data: base64, fileName, mimeType, size }] }
//   error:   "User cancelled" | "File too large: <nombre> (<bytes> > <max>)" | otro
// ═══════════════════════════════════════════════════════════════════════════
extension NativeBridge: UIDocumentPickerDelegate {

    static let filesDefaultMaxSize = 15 * 1024 * 1024

    func pickFiles(payload: [String: Any], requestId: String) {
        if pendingFilesRequestId != nil {
            sendError(requestId: requestId, error: "File picker already in progress")
            return
        }
        pendingFilesRequestId = requestId
        pendingFilesMaxSize = (payload["maxSize"] as? Int) ?? Self.filesDefaultMaxSize
        let multiple = (payload["multiple"] as? Bool) ?? false
        let accept = (payload["accept"] as? [String]) ?? []

        DispatchQueue.main.async {
            let picker: UIDocumentPickerViewController
            if #available(iOS 14.0, *) {
                let types = Self.acceptToUTTypes(accept)
                picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
            } else {
                let utis = Self.acceptToUTIStrings(accept)
                picker = UIDocumentPickerViewController(documentTypes: utis, in: .import)
            }
            picker.allowsMultipleSelection = multiple
            picker.delegate = self
            self.viewController?.present(picker, animated: true)
        }
    }

    /// "application/pdf" → UTType(mimeType:); "image/*" → .image; ".pdf"/"pdf" → UTType(filenameExtension:).
    /// Sin `accept` o sin nada reconocible: cualquier archivo (.item).
    @available(iOS 14.0, *)
    private static func acceptToUTTypes(_ accept: [String]) -> [UTType] {
        var out: [UTType] = []
        for raw in accept {
            let a = raw.trimmingCharacters(in: .whitespaces).lowercased()
            if a.isEmpty { continue }
            if a.hasSuffix("/*") {
                switch a {
                case "image/*": out.append(.image)
                case "video/*": out.append(.movie)
                case "audio/*": out.append(.audio)
                case "text/*": out.append(.text)
                default: out.append(.item)
                }
            } else if a.contains("/") {
                if let t = UTType(mimeType: a) { out.append(t) }
            } else {
                let ext = a.hasPrefix(".") ? String(a.dropFirst()) : a
                if let t = UTType(filenameExtension: ext) { out.append(t) }
            }
        }
        return out.isEmpty ? [.item] : out
    }

    /// Fallback iOS 13: UTIs clásicos para lo más común; el resto, público.
    private static func acceptToUTIStrings(_ accept: [String]) -> [String] {
        var out: [String] = []
        for raw in accept {
            let a = raw.trimmingCharacters(in: .whitespaces).lowercased()
            switch a {
            case "application/pdf", ".pdf", "pdf": out.append("com.adobe.pdf")
            case "image/*": out.append("public.image")
            case "image/jpeg", ".jpg", ".jpeg", "jpg", "jpeg": out.append("public.jpeg")
            case "image/png", ".png", "png": out.append("public.png")
            case "video/*": out.append("public.movie")
            case "text/plain", ".txt", "txt": out.append("public.plain-text")
            default: break
            }
        }
        return out.isEmpty ? ["public.item"] : out
    }

    private static func mimeType(for url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        if #available(iOS 14.0, *) {
            if let t = UTType(filenameExtension: ext), let mime = t.preferredMIMEType { return mime }
        }
        switch ext {
        case "pdf": return "application/pdf"
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "heic": return "image/heic"
        case "txt": return "text/plain"
        case "csv": return "text/csv"
        case "doc": return "application/msword"
        case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        case "xls": return "application/vnd.ms-excel"
        case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        default: return "application/octet-stream"
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let requestId = pendingFilesRequestId else { return }
        pendingFilesRequestId = nil
        let maxSize = pendingFilesMaxSize

        DispatchQueue.global(qos: .userInitiated).async {
            var files: [[String: Any]] = []
            for url in urls {
                // asCopy / .import: el archivo ya es nuestro, pero el scope no molesta.
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }

                let fileName = url.lastPathComponent
                if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
                   let size = attrs[.size] as? Int, maxSize > 0, size > maxSize {
                    self.sendError(requestId: requestId, error: "File too large: \(fileName) (\(size) > \(maxSize))")
                    return
                }
                guard let data = try? Data(contentsOf: url) else {
                    self.sendError(requestId: requestId, error: "Cannot read \(fileName)")
                    return
                }
                if maxSize > 0, data.count > maxSize {
                    self.sendError(requestId: requestId, error: "File too large: \(fileName) (\(data.count) > \(maxSize))")
                    return
                }
                files.append([
                    "data": data.base64EncodedString(),
                    "fileName": fileName,
                    "mimeType": Self.mimeType(for: url),
                    "size": data.count
                ])
                try? FileManager.default.removeItem(at: url)
            }
            if files.isEmpty {
                self.sendError(requestId: requestId, error: "No file selected")
            } else {
                self.sendSuccess(requestId: requestId, result: ["files": files])
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard let requestId = pendingFilesRequestId else { return }
        pendingFilesRequestId = nil
        sendError(requestId: requestId, error: "User cancelled")
    }
}
