import Foundation
import WatchConnectivity
import UserNotifications

// MARK: - Notification Model
struct WatchNotification: Identifiable, Codable {
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

// MARK: - Schedule Model
struct WorkSchedule: Codable {
    let dayOfWeek: Int // 1 = Sunday, 2 = Monday, etc.
    let startTime: String // "09:00"
    let endTime: String // "18:00"

    func startDate(for date: Date = Date()) -> Date? {
        return timeToDate(startTime, for: date)
    }

    func endDate(for date: Date = Date()) -> Date? {
        return timeToDate(endTime, for: date)
    }

    private func timeToDate(_ timeString: String, for date: Date) -> Date? {
        let components = timeString.split(separator: ":")
        guard components.count == 2,
              let hour = Int(components[0]),
              let minute = Int(components[1]) else { return nil }

        var calendar = Calendar.current
        calendar.timeZone = TimeZone.current
        var dateComponents = calendar.dateComponents([.year, .month, .day], from: date)
        dateComponents.hour = hour
        dateComponents.minute = minute
        dateComponents.second = 0
        return calendar.date(from: dateComponents)
    }
}

// MARK: - Work Event for History
struct WorkEvent: Identifiable, Codable {
    let id: String
    let type: WorkEventType
    let timestamp: Date

    init(type: WorkEventType, timestamp: Date = Date()) {
        self.id = UUID().uuidString
        self.type = type
        self.timestamp = timestamp
    }
}

enum WorkEventType: String, Codable {
    case clockIn = "Entrada"
    case clockOut = "Salida"
    case breakStart = "Inicio descanso"
    case breakEnd = "Fin descanso"
}

class WatchSessionManager: NSObject, ObservableObject {
    static let shared = WatchSessionManager()

    // Datos que vienen del iPhone via WCSession
    @Published var companyLogoURL: String?
    @Published var companyName: String?
    @Published var isConnected: Bool = false
    @Published var notifications: [WatchNotification] = []
    @Published var schedules: [WorkSchedule] = []
    @Published var todayEvents: [WorkEvent] = []
    @Published var isSyncing: Bool = false

    private let defaults = UserDefaults.standard

    // Keys para persistencia
    private let kNotifications = "cached_notifications"
    private let kSchedules = "cached_schedules"
    private let kCompanyName = "cached_companyName"
    private let kCompanyLogoURL = "cached_companyLogoURL"
    private let kLastSync = "last_sync_timestamp"

    private override init() {
        super.init()

        // Cargar datos locales primero
        loadLocalData()

        if WCSession.isSupported() {
            WCSession.default.delegate = self
            WCSession.default.activate()
        }

        requestNotificationPermission()
    }

    // MARK: - Local Storage

    private func loadLocalData() {
        print("[WatchSession] Loading local data...")

        // Cargar notifications
        if let data = defaults.data(forKey: kNotifications),
           let decoded = try? JSONDecoder().decode([WatchNotification].self, from: data) {
            notifications = decoded
            print("[WatchSession] Loaded \(decoded.count) cached notifications")
        }

        // Cargar schedules
        if let data = defaults.data(forKey: kSchedules),
           let decoded = try? JSONDecoder().decode([WorkSchedule].self, from: data) {
            schedules = decoded
            print("[WatchSession] Loaded \(decoded.count) cached schedules")
        }

        // Cargar company info
        companyName = defaults.string(forKey: kCompanyName)
        companyLogoURL = defaults.string(forKey: kCompanyLogoURL)
    }

    private func saveNotifications() {
        if let data = try? JSONEncoder().encode(notifications) {
            defaults.set(data, forKey: kNotifications)
            print("[WatchSession] Saved \(notifications.count) notifications to cache")
        }
    }

    private func saveSchedules() {
        if let data = try? JSONEncoder().encode(schedules) {
            defaults.set(data, forKey: kSchedules)
            print("[WatchSession] Saved \(schedules.count) schedules to cache")
        }
    }

    private func saveCompanyInfo() {
        defaults.set(companyName, forKey: kCompanyName)
        defaults.set(companyLogoURL, forKey: kCompanyLogoURL)
    }

    private func updateLastSync() {
        defaults.set(Date().timeIntervalSince1970, forKey: kLastSync)
    }

    func getLastSyncDate() -> Date? {
        let timestamp = defaults.double(forKey: kLastSync)
        return timestamp > 0 ? Date(timeIntervalSince1970: timestamp) : nil
    }

    /// Pedir datos al iPhone (llamar cuando el Watch se abre)
    func requestSessionFromiPhone() {
        guard WCSession.default.activationState == .activated else {
            print("[WatchSession] Cannot request - WCSession not activated")
            return
        }

        DispatchQueue.main.async {
            self.isSyncing = true
        }

        let request: [String: Any] = ["request": "sessionData"]

        if WCSession.default.isReachable {
            // iPhone está activo, enviar mensaje directo
            WCSession.default.sendMessage(request, replyHandler: { reply in
                print("[WatchSession] iPhone replied: \(reply)")
                self.handleSessionReply(reply)
            }, errorHandler: { error in
                print("[WatchSession] Error requesting session: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.isSyncing = false
                }
            })
        } else {
            // iPhone en background, usar transferUserInfo
            WCSession.default.transferUserInfo(request)
            print("[WatchSession] Session request queued (iPhone not reachable)")
            // Quitar syncing después de 3 segundos si no hay respuesta
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                self.isSyncing = false
            }
        }
    }

    private func handleSessionReply(_ reply: [String: Any]) {
        DispatchQueue.main.async {
            if let userId = reply["userId"] as? String {
                self.defaults.set(userId, forKey: "userId")
                print("[WatchSession] Got userId from iPhone: \(userId)")
            }
            if let companyId = reply["companyId"] as? String {
                self.defaults.set(companyId, forKey: "companyId")
                print("[WatchSession] Got companyId from iPhone: \(companyId)")
            }
            if let apiUrl = reply["ws_url"] as? String {
                print("[WatchSession] Got ws_url from iPhone: \(apiUrl)")
                WatchWebSocketService.shared.configure(backendUrl: apiUrl)
            }

            self.isSyncing = false
            self.updateLastSync()

            // Conectar si tenemos todo
            if WatchWebSocketService.shared.isLinked() {
                WatchWebSocketService.shared.reRegister()
            }
        }
    }

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if granted {
                print("[WatchSession] Notification permission granted")
            }
        }
    }

    func getTodaySchedule() -> WorkSchedule? {
        let calendar = Calendar.current
        let weekday = calendar.component(.weekday, from: Date())
        return schedules.first { $0.dayOfWeek == weekday }
    }

    func canStartWork() -> Bool {
        guard let schedule = getTodaySchedule(),
              let startTime = schedule.startDate() else { return false }

        let now = Date()
        let oneMinuteBefore = startTime.addingTimeInterval(-60)
        return now >= oneMinuteBefore
    }

    func getTimeUntilStart() -> TimeInterval? {
        guard let schedule = getTodaySchedule(),
              let startTime = schedule.startDate() else { return nil }

        let now = Date()
        let oneMinuteBefore = startTime.addingTimeInterval(-60)
        if now < oneMinuteBefore {
            return oneMinuteBefore.timeIntervalSince(now)
        }
        return nil
    }

    func getTimeUntilNextShift() -> TimeInterval? {
        guard !schedules.isEmpty else { return nil }

        let calendar = Calendar.current
        let now = Date()
        let currentWeekday = calendar.component(.weekday, from: now)

        // Buscar en los próximos 7 días
        for dayOffset in 0..<7 {
            let targetWeekday = ((currentWeekday - 1 + dayOffset) % 7) + 1

            if let schedule = schedules.first(where: { $0.dayOfWeek == targetWeekday }) {
                guard let futureDate = calendar.date(byAdding: .day, value: dayOffset, to: now),
                      let startTime = schedule.startDate(for: futureDate) else { continue }

                let oneMinuteBefore = startTime.addingTimeInterval(-60)

                if oneMinuteBefore > now {
                    return oneMinuteBefore.timeIntervalSince(now)
                }
            }
        }
        return nil
    }

    func scheduleShiftNotification() {
        guard let schedule = getTodaySchedule(),
              let startTime = schedule.startDate() else { return }

        let oneMinuteBefore = startTime.addingTimeInterval(-60)
        let now = Date()

        guard oneMinuteBefore > now else { return }

        let content = UNMutableNotificationContent()
        content.title = "Jornada por comenzar"
        content.body = "Tu jornada comienza en 1 minuto"
        content.sound = .default

        let timeInterval = oneMinuteBefore.timeIntervalSince(now)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: timeInterval, repeats: false)
        let request = UNNotificationRequest(identifier: "shiftStart", content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("[WatchSession] Error scheduling notification: \(error)")
            } else {
                print("[WatchSession] Shift notification scheduled")
            }
        }
    }

    func addEvent(_ type: WorkEventType) {
        let event = WorkEvent(type: type)
        todayEvents.append(event)
    }

    func clearTodayEvents() {
        todayEvents.removeAll()
    }

    // MARK: - Notifications from WebSocket

    /// Agregar notificación recibida via WebSocket desde el backend
    func addNotification(_ notification: WatchNotification) {
        // Insertar al inicio para mostrar las más recientes primero
        notifications.insert(notification, at: 0)
        saveNotifications()
        print("[WatchSession] Notification added: \(notification.title)")
    }

    /// Limpiar todas las notificaciones
    func clearNotifications() {
        notifications.removeAll()
        saveNotifications()
    }
}

extension WatchSessionManager: WCSessionDelegate {
    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        DispatchQueue.main.async {
            self.isConnected = activationState == .activated
        }

        if let error = error {
            print("[WatchSession] Activation error: \(error.localizedDescription)")
        } else {
            print("[WatchSession] Activated with state: \(activationState.rawValue)")
            scheduleShiftNotification()

            // If watch already has session data, try to register with backend
            if WatchWebSocketService.shared.isLinked() {
                print("[WatchSession] Watch already linked, registering with backend...")
                WatchWebSocketService.shared.reRegister()
            }
        }
    }

    // Receive application context from iPhone
    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        DispatchQueue.main.async {
            self.processReceivedData(applicationContext)
        }
    }

    /// Limpiar datos cuando el usuario hace logout en el iPhone
    private func handleLogout() {
        print("[WatchSession] Logout received from iPhone")
        defaults.removeObject(forKey: "userId")
        defaults.removeObject(forKey: "companyId")
        defaults.removeObject(forKey: kNotifications)
        defaults.removeObject(forKey: kSchedules)
        defaults.removeObject(forKey: kCompanyName)
        defaults.removeObject(forKey: kCompanyLogoURL)
        defaults.removeObject(forKey: kLastSync)
        self.companyLogoURL = nil
        self.companyName = nil
        self.notifications = []
        self.schedules = []
        self.isSyncing = false
        WatchWebSocketService.shared.disconnect()
    }

    /// Procesa datos recibidos del iPhone (método común para evitar duplicación)
    private func processReceivedData(_ data: [String: Any]) {
        print("[WatchSession] ========== RECEIVED DATA FROM IPHONE ==========")
        print("[WatchSession] Keys: \(data.keys.joined(separator: ", "))")
        print("[WatchSession] ws_url present: \(data["ws_url"] != nil)")
        print("[WatchSession] userId present: \(data["userId"] != nil)")
        print("[WatchSession] companyId present: \(data["companyId"] != nil)")
        print("[WatchSession] ================================================")

        // Handle logout
        if data["logout"] as? Bool == true {
            handleLogout()
            return
        }

        var sessionDataReceived = false

        if let logoURL = data["companyLogoURL"] as? String {
            companyLogoURL = logoURL
        }
        if let name = data["companyName"] as? String {
            companyName = name
        }
        if let userId = data["userId"] as? String {
            defaults.set(userId, forKey: "userId")
            sessionDataReceived = true
            print("[WatchSession] Received userId: \(userId)")
        }
        if let companyId = data["companyId"] as? String {
            defaults.set(companyId, forKey: "companyId")
            sessionDataReceived = true
            print("[WatchSession] Received companyId: \(companyId)")
        }
        if let apiUrl = data["ws_url"] as? String {
            print("[WatchSession] Received ws_url: \(apiUrl)")
            WatchWebSocketService.shared.configure(backendUrl: apiUrl)
        }
        if let notificationsData = data["notifications"] as? Data,
           let decoded = try? JSONDecoder().decode([WatchNotification].self, from: notificationsData) {
            notifications = decoded
            saveNotifications()
            print("[WatchSession] Received \(decoded.count) notifications")
        }
        if let schedulesData = data["schedules"] as? Data,
           let decoded = try? JSONDecoder().decode([WorkSchedule].self, from: schedulesData) {
            schedules = decoded
            saveSchedules()
            scheduleShiftNotification()
            print("[WatchSession] Received \(decoded.count) schedules")
        }

        // Guardar company info
        saveCompanyInfo()
        updateLastSync()
        isSyncing = false

        if sessionDataReceived {
            WatchWebSocketService.shared.reRegister()
        }
    }

    // Receive message from iPhone
    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        DispatchQueue.main.async {
            self.processReceivedData(message)
        }
    }

    // Receive user info from iPhone
    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        DispatchQueue.main.async {
            self.processReceivedData(userInfo)
        }
    }
}
