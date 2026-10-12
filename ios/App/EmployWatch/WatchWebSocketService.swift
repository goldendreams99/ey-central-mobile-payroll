import Foundation
import WatchKit
import Network

/// WebSocket service para registrar el watch con el backend
/// Similar a WebSocketService.swift de la app iOS principal
class WatchWebSocketService: NSObject, URLSessionWebSocketDelegate {

    static let shared = WatchWebSocketService()

    private var wsUrl: String?
    private var webSocketTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var isConnected = false
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 5
    private let reconnectDelay: TimeInterval = 5.0
    private(set) var lastError: String?
    private(set) var connectionState: String = "idle"
    private(set) var networkStatus: String = "unknown"

    private let defaults = UserDefaults.standard
    private var pathMonitor: NWPathMonitor?
    private let monitorQueue = DispatchQueue(label: "NetworkMonitor")

    private override init() {
        super.init()
        startNetworkMonitor()
    }

    private func startNetworkMonitor() {
        pathMonitor = NWPathMonitor()
        pathMonitor?.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                if path.status == .satisfied {
                    if path.usesInterfaceType(.wifi) {
                        self?.networkStatus = "wifi"
                    } else if path.usesInterfaceType(.cellular) {
                        self?.networkStatus = "cellular"
                    } else if path.usesInterfaceType(.wiredEthernet) {
                        self?.networkStatus = "wired"
                    } else {
                        self?.networkStatus = "connected"
                    }
                    print("[WatchWS] Network available: \(self?.networkStatus ?? "")")
                } else {
                    self?.networkStatus = "no_network"
                    print("[WatchWS] Network unavailable")
                }
            }
        }
        pathMonitor?.start(queue: monitorQueue)
    }

    // MARK: - Public Methods

    /// Configure WebSocket URL from iPhone
    func configure(backendUrl: String) {
        // Convert http(s) to wss
        var wsUrl = backendUrl
        if wsUrl.hasPrefix("https://") {
            wsUrl = wsUrl.replacingOccurrences(of: "https://", with: "wss://")
        } else if wsUrl.hasPrefix("http://") {
            wsUrl = wsUrl.replacingOccurrences(of: "http://", with: "ws://")
        } else if !wsUrl.hasPrefix("wss://") && !wsUrl.hasPrefix("ws://") {
            wsUrl = "wss://\(wsUrl)"
        }

        let oldUrl = self.wsUrl
        let changed = oldUrl != wsUrl
        self.wsUrl = wsUrl
        print("[WatchWS] URL configured: \(wsUrl)")

        if isConnected {
            if changed {
                disconnect()
                connect()
            }
        } else {
            // Not connected - if we have session data, connect now
            if isLinked() {
                print("[WatchWS] URL configured and session exists - connecting now")
                connect()
            }
        }
    }

    func connect() {
        guard let wsUrl = wsUrl, !wsUrl.isEmpty else {
            print("[WatchWS] No URL configured - cannot connect")
            connectionState = "no_url"
            lastError = "No URL"
            return
        }

        guard !isConnected else {
            print("[WatchWS] Already connected")
            return
        }

        // Skip HTTP test, go directly to WebSocket
        // watchOS tiene problemas con URLSession.shared para HTTP
        connectionState = "connecting"
        lastError = nil
        connectWebSocket()
    }

    private func connectWebSocket() {
        guard let wsUrl = wsUrl else { return }

        print("[WatchWS] Connecting WS to: \(wsUrl)")
        connectionState = "ws_connect"
        lastError = nil

        // Config para watchOS - esperar conectividad
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        config.waitsForConnectivity = true  // Esperar a tener red
        config.allowsCellularAccess = true
        config.allowsConstrainedNetworkAccess = true
        config.allowsExpensiveNetworkAccess = true

        urlSession = URLSession(configuration: config, delegate: self, delegateQueue: OperationQueue.main)

        guard let url = URL(string: wsUrl) else {
            connectionState = "invalid_url"
            lastError = "Invalid URL"
            return
        }

        print("[WatchWS] Creating WebSocket task for: \(url.absoluteString)")
        webSocketTask = urlSession?.webSocketTask(with: url)
        webSocketTask?.resume()

        print("[WatchWS] WebSocket task resumed, waiting for connection...")

        // Timeout: if not connected in 20 seconds, show timeout error
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
            guard let self = self else { return }
            if self.connectionState == "ws_connect" {
                self.connectionState = "ws_timeout"
                self.lastError = "WS timeout 20s"
                print("[WatchWS] Connection timed out after 20 seconds")
                // Cancel the task to clean up
                self.webSocketTask?.cancel(with: .abnormalClosure, reason: nil)
            }
        }

        receiveMessage()
    }

    func disconnect() {
        print("[WatchWS] Disconnecting")
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        isConnected = false
        connectionState = "disconnected"
    }

    // MARK: - URLSessionWebSocketDelegate

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("[WatchWS] Connected")
        isConnected = true
        reconnectAttempts = 0
        connectionState = "connected"
        lastError = nil
        registerDevice()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        print("[WatchWS] Closed with code: \(closeCode)")
        isConnected = false
        connectionState = "closed:\(closeCode.rawValue)"
        scheduleReconnect()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            let nsError = error as NSError
            print("[WatchWS] Error: \(error.localizedDescription)")
            print("[WatchWS] Error domain: \(nsError.domain), code: \(nsError.code)")
            isConnected = false
            // Show more specific error info
            if nsError.domain == NSURLErrorDomain {
                connectionState = "url_err:\(nsError.code)"
                // Common codes: -1001=timeout, -1003=host not found, -1004=can't connect
                // -1009=offline, -1200=SSL error
            } else {
                connectionState = "error"
            }
            lastError = String(error.localizedDescription.prefix(25))
            scheduleReconnect()
        }
    }

    /// Called when task is waiting for network connectivity
    func urlSession(_ session: URLSession, taskIsWaitingForConnectivity task: URLSessionTask) {
        print("[WatchWS] Waiting for network connectivity...")
        connectionState = "waiting_net"
        lastError = "Esperando red"
    }

    // MARK: - Device Registration

    private func registerDevice() {
        // userId y companyId son OBLIGATORIOS para el registro
        guard let userId = defaults.string(forKey: "userId"),
              let companyId = defaults.string(forKey: "companyId"),
              !userId.isEmpty,
              !companyId.isEmpty else {
            print("[WatchWS] Cannot register - watch not linked (missing userId or companyId)")
            return
        }

        // Generate deviceId with watch_ prefix
        var deviceId = defaults.string(forKey: "watch_device_id")
        if deviceId == nil || !deviceId!.hasPrefix("watch_") {
            let uniqueId = UUID().uuidString
            deviceId = "watch_\(uniqueId)"
            defaults.set(deviceId, forKey: "watch_device_id")
        }

        // Get watch info
        let watchModel = WKInterfaceDevice.current().model
        let watchVersion = WKInterfaceDevice.current().systemVersion

        let register: [String: Any] = [
            "type": "register",
            "deviceId": deviceId!,
            "platform": "watchos",
            "userId": userId,
            "companyId": companyId,
            "deviceInfo": [
                "model": watchModel,
                "systemVersion": watchVersion
            ]
        ]

        sendMessage(register)
        print("[WatchWS] Device registered: \(deviceId!) (userId: \(userId), companyId: \(companyId))")
    }

    /// Re-register device (call after linking or session change)
    func reRegister() {
        if isConnected {
            registerDevice()
        } else {
            // Connect first, then register will happen automatically
            connect()
        }
    }

    /// Check if watch is linked (has userId and companyId)
    func isLinked() -> Bool {
        guard let userId = defaults.string(forKey: "userId"),
              let companyId = defaults.string(forKey: "companyId"),
              !userId.isEmpty,
              !companyId.isEmpty else {
            return false
        }
        return true
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
                print("[WatchWS] Receive error: \(error.localizedDescription)")
                self.isConnected = false
                self.scheduleReconnect()
            }
        }
    }

    private func handleMessage(_ text: String) {
        print("[WatchWS] Message received: \(text)")

        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }

        switch type {
        case "registered":
            print("[WatchWS] Device registered with backend")
            NotificationCenter.default.post(name: .watchDeviceRegistered, object: nil)

        case "notification":
            handleNotification(json)

        case "ping":
            sendMessage(["type": "pong"])

        default:
            print("[WatchWS] Unknown message type: \(type)")
        }
    }

    private func handleNotification(_ json: [String: Any]) {
        guard let notifData = json["data"] as? [String: Any],
              let title = notifData["title"] as? String,
              let body = notifData["body"] as? String else {
            print("[WatchWS] Invalid notification format")
            return
        }

        let id = notifData["id"] as? String ?? UUID().uuidString
        let timestampStr = notifData["timestamp"] as? String
        var timestamp = Date()

        if let ts = timestampStr {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = formatter.date(from: ts) {
                timestamp = date
            }
        }

        let notification = WatchNotification(
            id: id,
            title: title,
            body: body,
            timestamp: timestamp,
            isRead: false
        )

        DispatchQueue.main.async {
            WatchSessionManager.shared.addNotification(notification)
        }

        print("[WatchWS] Notification received: \(title)")
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
                print("[WatchWS] Send error: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Reconnection

    private func scheduleReconnect() {
        guard reconnectAttempts < maxReconnectAttempts else {
            print("[WatchWS] Max reconnect attempts reached")
            return
        }

        reconnectAttempts += 1
        let delay = reconnectDelay * Double(reconnectAttempts)

        print("[WatchWS] Scheduling reconnect attempt \(reconnectAttempts) in \(delay)s")

        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.connect()
        }
    }

    // MARK: - Public Getters

    func getDeviceId() -> String? {
        return defaults.string(forKey: "watch_device_id")
    }

    var connectionStatus: Bool {
        return isConnected
    }

    /// Debug: get configured WebSocket URL
    func getWsUrl() -> String? {
        return wsUrl
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let watchDeviceRegistered = Notification.Name("watchDeviceRegistered")
}
