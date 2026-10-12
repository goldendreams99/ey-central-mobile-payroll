import Foundation
import WatchConnectivity

/// Servicio para enviar datos al Apple Watch via WatchConnectivity
/// El watch recibe estos datos en WatchSessionManager
class WatchConnectivityService: NSObject, ObservableObject {

    static let shared = WatchConnectivityService()

    @Published var isWatchPaired = false
    @Published var isWatchReachable = false

    private let defaults = UserDefaults.standard

    private override init() {
        super.init()

        if WCSession.isSupported() {
            WCSession.default.delegate = self
            WCSession.default.activate()
            print("[WatchConn] WCSession activating...")
        } else {
            print("[WatchConn] WCSession not supported on this device")
        }
    }

    // MARK: - Public Methods

    /// Enviar datos de sesión al watch (userId, companyId, etc.)
    func sendSessionData() {
        guard WCSession.default.activationState == .activated else {
            print("[WatchConn] Session not activated")
            return
        }

        guard WCSession.default.isPaired else {
            print("[WatchConn] No watch paired")
            return
        }

        let userId = defaults.string(forKey: "userId")
        let companyId = defaults.string(forKey: "companyId")
        let companyName = defaults.string(forKey: "companyName")
        let companyLogoURL = defaults.string(forKey: "companyLogoURL")
        let wsUrl = defaults.string(forKey: "cached_ws_url")

        var context: [String: Any] = [:]

        if let userId = userId {
            context["userId"] = userId
        }
        if let companyId = companyId {
            context["companyId"] = companyId
        }
        if let companyName = companyName {
            context["companyName"] = companyName
        }
        if let companyLogoURL = companyLogoURL {
            context["companyLogoURL"] = companyLogoURL
        }
        if let wsUrl = wsUrl {
            context["ws_url"] = wsUrl
        }

        // Notificaciones ahora vienen directo del backend via WebSocket al watch

        // Enviar horarios de prueba (después esto vendrá del backend)
        let testSchedules = getTestSchedules()
        if let schedulesData = try? JSONEncoder().encode(testSchedules) {
            context["schedules"] = schedulesData
        }

        guard !context.isEmpty else {
            print("[WatchConn] No data to send")
            return
        }

        do {
            try WCSession.default.updateApplicationContext(context)
            print("[WatchConn] Sent to watch: userId=\(userId ?? "nil"), companyId=\(companyId ?? "nil"), ws_url=\(wsUrl ?? "NIL - MISSING!")")
        } catch {
            print("[WatchConn] Error sending context: \(error.localizedDescription)")
        }
    }

    /// Enviar mensaje inmediato al watch (si está alcanzable)
    func sendMessageToWatch(_ message: [String: Any]) {
        guard WCSession.default.isReachable else {
            print("[WatchConn] Watch not reachable, using applicationContext instead")
            sendSessionData()
            return
        }

        WCSession.default.sendMessage(message, replyHandler: { reply in
            print("[WatchConn] Watch replied: \(reply)")
        }, errorHandler: { error in
            print("[WatchConn] Error sending message: \(error.localizedDescription)")
        })
    }

    /// Notificar al watch que el usuario hizo login
    func notifyLogin(userId: String, companyId: String) {
        defaults.set(userId, forKey: "userId")
        defaults.set(companyId, forKey: "companyId")
        sendSessionData()
    }

    /// Notificar al watch que el usuario hizo logout
    func notifyLogout() {
        defaults.removeObject(forKey: "userId")
        defaults.removeObject(forKey: "companyId")

        // Enviar contexto vacío para limpiar el watch
        do {
            try WCSession.default.updateApplicationContext(["logout": true])
            print("[WatchConn] Logout sent to watch")
        } catch {
            print("[WatchConn] Error sending logout: \(error.localizedDescription)")
        }
    }

    // MARK: - Test Data (desde WatchTestData.json)
    // TODO: En producción, los horarios también deberían venir del backend

    /// Estructura para decodificar el JSON de prueba
    private struct WatchTestData: Codable {
        let schedules: [WorkSchedule]
    }

    /// Cargar datos de prueba desde WatchTestData.json
    private func loadTestData() -> WatchTestData? {
        guard let url = Bundle.main.url(forResource: "WatchTestData", withExtension: "json") else {
            print("[WatchConn] WatchTestData.json not found in bundle")
            return nil
        }

        do {
            let data = try Data(contentsOf: url)
            let decoder = JSONDecoder()
            let testData = try decoder.decode(WatchTestData.self, from: data)
            print("[WatchConn] Loaded test data: \(testData.schedules.count) schedules")
            return testData
        } catch {
            print("[WatchConn] Error loading WatchTestData.json: \(error.localizedDescription)")
            return nil
        }
    }

    private func getTestSchedules() -> [WorkSchedule] {
        return loadTestData()?.schedules ?? []
    }
}

// MARK: - Models (deben coincidir con los del watch)

struct WatchNotification: Codable {
    let id: String
    let title: String
    let body: String
    let timestamp: Date
    var isRead: Bool

    init(id: String = UUID().uuidString, title: String, body: String, timestamp: Date = Date(), isRead: Bool = false) {
        self.id = id
        self.title = title
        self.body = body
        self.timestamp = timestamp
        self.isRead = isRead
    }
}

struct WorkSchedule: Codable {
    let dayOfWeek: Int
    let startTime: String
    let endTime: String
}

// MARK: - WCSessionDelegate

extension WatchConnectivityService: WCSessionDelegate {

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        DispatchQueue.main.async {
            self.isWatchPaired = session.isPaired
            self.isWatchReachable = session.isReachable
        }

        if let error = error {
            print("[WatchConn] Activation error: \(error.localizedDescription)")
        } else {
            print("[WatchConn] Activated - paired: \(session.isPaired), reachable: \(session.isReachable)")

            // Si ya hay sesión guardada, enviar al watch
            if self.defaults.string(forKey: "userId") != nil {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                    self.sendSessionData()
                }
            }
        }
    }

    func sessionDidBecomeInactive(_ session: WCSession) {
        print("[WatchConn] Session became inactive")
    }

    func sessionDidDeactivate(_ session: WCSession) {
        print("[WatchConn] Session deactivated")
        // Reactivar para dispositivos que cambian de watch
        WCSession.default.activate()
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        DispatchQueue.main.async {
            self.isWatchReachable = session.isReachable
        }
        print("[WatchConn] Reachability changed: \(session.isReachable)")

        // Si el watch se vuelve alcanzable y hay sesión, enviar datos
        if session.isReachable && defaults.string(forKey: "userId") != nil {
            sendSessionData()
        }
    }

    // Recibir mensajes del watch (si el watch envía algo)
    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        print("[WatchConn] Received from watch: \(message)")

        // Watch pidió datos de sesión
        if message["request"] as? String == "sessionData" {
            print("[WatchConn] Watch requested session data - sending via applicationContext")
            sendSessionData()
        }
    }

    // Recibir mensaje con reply handler (el Watch espera respuesta)
    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        print("[WatchConn] Received from watch (with reply): \(message)")

        // Watch pidió datos de sesión
        if message["request"] as? String == "sessionData" {
            let userId = defaults.string(forKey: "userId")
            let companyId = defaults.string(forKey: "companyId")
            let wsUrl = defaults.string(forKey: "cached_ws_url")

            var reply: [String: Any] = [:]
            if let userId = userId { reply["userId"] = userId }
            if let companyId = companyId { reply["companyId"] = companyId }
            if let wsUrl = wsUrl { reply["ws_url"] = wsUrl }

            print("[WatchConn] Replying to watch: userId=\(userId ?? "nil"), companyId=\(companyId ?? "nil"), wsUrl=\(wsUrl ?? "nil")")
            replyHandler(reply)
        } else {
            replyHandler([:])
        }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        print("[WatchConn] Received context from watch: \(applicationContext)")
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        print("[WatchConn] Received userInfo from watch: \(userInfo)")

        // Watch pidió datos de sesión (cuando iPhone no estaba reachable)
        if userInfo["request"] as? String == "sessionData" {
            print("[WatchConn] Watch requested session data via userInfo - sending")
            sendSessionData()
        }
    }
}
