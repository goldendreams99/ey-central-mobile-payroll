import Foundation
import UIKit

/// WebSocket service para recibir comandos del dashboard
/// Dashboard -> Backend -> WebSocket -> Este servicio -> NativeBridge
class WebSocketService: NSObject, URLSessionWebSocketDelegate {

    static let shared = WebSocketService()

    private var wsUrl: String?
    private var webSocketTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var isConnected = false
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 10
    private let reconnectDelay: TimeInterval = 5.0

    private let defaults = UserDefaults.standard
    private let keychainDeviceIdKey = "ai.employ.app.deviceId"

    // Command mapping: module.action -> bridge.action
    private let commandMap: [String: String] = [
        "geolocation.getCurrentPosition": "geolocation.getCurrentPosition",
        "geolocation.watchPosition": "geolocation.watchPosition",
        "geolocation.clearWatch": "geolocation.clearWatch",
        "geolocation.checkPermissions": "geolocation.checkPermissions",
        "geolocation.requestPermissions": "geolocation.requestPermissions",
        "notifications.send": "localNotifications.schedule",
        "notifications.cancel": "localNotifications.cancel",
        "notifications.cancelAll": "localNotifications.cancelAll",
        "notifications.checkPermissions": "localNotifications.checkPermissions",
        "notifications.requestPermissions": "localNotifications.requestPermissions",
        "camera.getPhoto": "camera.getPhoto",
        "camera.pickImages": "camera.pickImages",
        "camera.pickVideo": "camera.pickVideo",
        "camera.checkPermissions": "camera.checkPermissions",
        "camera.requestPermissions": "camera.requestPermissions",
        "biometrics.isAvailable": "biometrics.isAvailable",
        "biometrics.verifyIdentity": "biometrics.verifyIdentity",
        "biometrics.setCredentials": "biometrics.setCredentials",
        "biometrics.getCredentials": "biometrics.getCredentials",
        "biometrics.deleteCredentials": "biometrics.deleteCredentials",
        "haptics.vibrate": "haptics.vibrate",
        "haptics.impact": "haptics.impact",
        "haptics.notification": "haptics.notification",
        "store.requestReview": "store.requestReview",
        "statusBar.setBackgroundColor": "statusBar.setBackgroundColor",
        "statusBar.setStyle": "statusBar.setStyle",
        "statusBar.show": "statusBar.show",
        "statusBar.hide": "statusBar.hide",
        "clipboard.write": "clipboard.write",
        "clipboard.read": "clipboard.read",
        "share.send": "share.share",
        "share.canShare": "share.canShare",
        "browser.open": "browser.open",
        "browser.close": "browser.close",
        "device.getInfo": "device.getInfo",
        "device.getId": "device.getId",
        "device.getDeviceId": "device.getDeviceId",
        "device.getBatteryInfo": "device.getBatteryInfo",
        "device.getNativeInfo": "device.getNativeInfo",
        "network.getStatus": "network.getStatus",
        "storage.get": "preferences.get",
        "storage.set": "preferences.set",
        "storage.remove": "preferences.remove",
        "storage.clear": "preferences.clear",
        "storage.keys": "preferences.keys",
        "session.save": "session.save",
        "session.clear": "session.clear",
        "session.get": "session.get",
        "session.enableBiometricLogin": "session.enableBiometricLogin",
        "session.checkBiometricLogin": "session.checkBiometricLogin",
        "push.refreshToken": "push.refreshToken",
        "push.getToken": "push.getToken",
        "push.checkPermissions": "push.checkPermissions",
        "push.requestPermissions": "push.requestPermissions",
        "localNotifications.schedule": "localNotifications.schedule",
        "localNotifications.cancel": "localNotifications.cancel",
        "localNotifications.checkPermissions": "localNotifications.checkPermissions",
        "localNotifications.requestPermissions": "localNotifications.requestPermissions",
        "cache.clear": "cache.clear",
        "cache.clearCache": "cache.clearCache",
        "cache.clearStorage": "cache.clearStorage",
        "cache.clearPreferences": "cache.clearPreferences",
        "cache.reload": "cache.reload",
        "cache.hardReload": "cache.hardReload",
        "cache.clearAndRestart": "cache.clearAndRestart",
        "app.getInfo": "app.getInfo",
        "app.minimize": "app.minimize",
        "app.exit": "app.exit",
        "keyboard.show": "keyboard.show",
        "keyboard.hide": "keyboard.hide",
        "onboarding.syncToFirebase": "onboarding.syncToFirebase"
    ]



    weak var nativeBridge: NativeBridge?

    private override init() {
        super.init()
    }

    /// Configure WebSocket URL (call before connect)
    func configure(wsUrl: String) {
        let oldUrl = self.wsUrl
        self.wsUrl = wsUrl
        print("[WebSocket] URL configured: \(wsUrl)")

        // If URL changed and we're connected, reconnect
        if isConnected && oldUrl != wsUrl {
            disconnect()
            connect()
        } else if !isConnected {
            // If not connected, connect now that we have a URL
            connect()
        }
    }

    func connect() {
        guard let wsUrl = wsUrl, !wsUrl.isEmpty else {
            print("[WebSocket] No URL configured - cannot connect")
            return
        }

        guard !isConnected else {
            print("[WebSocket] Already connected")
            return
        }

        print("[WebSocket] Connecting to: \(wsUrl)")

        let config = URLSessionConfiguration.default
        urlSession = URLSession(configuration: config, delegate: self, delegateQueue: OperationQueue.main)

        guard let url = URL(string: wsUrl) else {
            print("[WebSocket] Invalid URL")
            return
        }

        webSocketTask = urlSession?.webSocketTask(with: url)
        webSocketTask?.resume()

        receiveMessage()
    }

    func disconnect() {
        print("[WebSocket] Disconnecting")
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        isConnected = false
    }

    // MARK: - URLSessionWebSocketDelegate

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("[WebSocket] Connected")
        isConnected = true
        reconnectAttempts = 0
        registerDevice()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        print("[WebSocket] Closed with code: \(closeCode)")
        isConnected = false
        scheduleReconnect()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            print("[WebSocket] Error: \(error.localizedDescription)")
            isConnected = false
            scheduleReconnect()
        }
    }

    // MARK: - Keychain Helpers (para deviceId persistente entre reinstalaciones)

    private func saveToKeychain(key: String, value: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }

        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]

        return SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess
    }

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

    private func getPersistentDeviceId() -> String {
        // 1. Buscar en Keychain
        if let keychainId = readFromKeychain(key: keychainDeviceIdKey) {
            defaults.set(keychainId, forKey: "device_id")
            return keychainId
        }

        // 2. Migrar de UserDefaults si existe
        if let storedId = defaults.string(forKey: "device_id"), storedId.hasPrefix("mobile_") {
            _ = saveToKeychain(key: keychainDeviceIdKey, value: storedId)
            return storedId
        }

        // 3. Generar nuevo ID
        let vendorId = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        let newDeviceId = "mobile_\(vendorId)"

        _ = saveToKeychain(key: keychainDeviceIdKey, value: newDeviceId)
        defaults.set(newDeviceId, forKey: "device_id")

        return newDeviceId
    }

    // MARK: - Device Registration

    private func registerDevice() {
        // Get deviceId with mobile_ prefix (usando Keychain para persistencia)
        let deviceId = getPersistentDeviceId()

        // Get userId and companyId from session
        let userId = defaults.string(forKey: "userId")
        let companyId = defaults.string(forKey: "companyId")
        // EY-CENTRAL 2026-10-08 (seguridad): la credencial de la sesión (POST /api/sessions/login, la guarda session.save)
        let sessionId = defaults.string(forKey: "sessionId")

        let register: [String: Any?] = [
            "type": "register",
            "deviceId": deviceId,
            "platform": "ios",
            "userId": userId,
            "companyId": companyId,
            "sessionId": sessionId
        ]

        sendMessage(register)
        print("[WebSocket] Device registration sent: \(deviceId ?? "nil") (userId: \(userId ?? "nil"), companyId: \(companyId ?? "nil"))")
    }

    /// Re-register device (call after login/session change)
    func reRegister() {
        if isConnected {
            registerDevice()
        }
    }

    // MARK: - Message Handling

    private func receiveMessage() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }

            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleMessage(text)
                    }
                @unknown default:
                    break
                }
                // Continue receiving
                self.receiveMessage()

            case .failure(let error):
                print("[WebSocket] Receive error: \(error.localizedDescription)")
                self.isConnected = false
                self.scheduleReconnect()
            }
        }
    }

    private func handleMessage(_ text: String) {
        print("[WebSocket] Message received: \(text)")

        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }

        switch type {
        case "command":
            handleCommand(json)
        case "registered":
            print("[WebSocket] Device registered with backend")
        case "ping":
            sendMessage(["type": "pong"])
        default:
            print("[WebSocket] Unknown message type: \(type)")
        }
    }

    private func handleCommand(_ message: [String: Any]) {
        let module = message["module"] as? String ?? ""
        let action = message["action"] as? String ?? ""
        let params = message["params"] as? [String: Any] ?? [:]
        let commandId = message["commandId"] as? String ?? ""

        print("[WebSocket] handleCommand - module: \(module), action: \(action), commandId: \(commandId)")

        // Map module.action to bridge action
        guard let bridgeAction = commandMap["\(module).\(action)"] else {
            print("[WebSocket] No bridge mapping for: \(module).\(action)")
            return
        }

        // Create bridge message
        let bridgeMessage: [String: Any] = [
            "type": "CAPACITOR_BRIDGE",
            "action": bridgeAction,
            "payload": params,
            "requestId": commandId
        ]

        // Execute in NativeBridge
        DispatchQueue.main.async { [weak self] in
            self?.nativeBridge?.handleWebSocketCommand(bridgeMessage)
        }
    }

    // MARK: - Send Message

    private func sendMessage(_ dict: [String: Any?]) {
        guard isConnected, let task = webSocketTask else { return }

        // Filter out nil values
        let filtered = dict.compactMapValues { $0 }

        guard let data = try? JSONSerialization.data(withJSONObject: filtered),
              let text = String(data: data, encoding: .utf8) else {
            return
        }

        task.send(.string(text)) { error in
            if let error = error {
                print("[WebSocket] Send error: \(error.localizedDescription)")
            }
        }
    }

    /// Send response back to backend/dashboard
    func sendResponse(_ response: [String: Any]) {
        guard isConnected else {
            print("[WebSocket] Cannot send response - not connected")
            return
        }

        print("[WebSocket] Sending response: \(response["commandId"] ?? "?")")
        sendMessage(response)
    }

    // MARK: - Reconnection

    private func scheduleReconnect() {
        guard reconnectAttempts < maxReconnectAttempts else {
            print("[WebSocket] Max reconnect attempts reached")
            return
        }

        reconnectAttempts += 1
        let delay = reconnectDelay * Double(reconnectAttempts)

        print("[WebSocket] Scheduling reconnect attempt \(reconnectAttempts) in \(delay)s")

        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.connect()
        }
    }
}
