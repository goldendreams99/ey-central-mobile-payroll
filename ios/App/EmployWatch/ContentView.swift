import SwiftUI
import WatchKit

enum WorkState {
    case idle
    case working
    case onBreak
    case finished
}

struct ContentView: View {
    @State private var selectedTab = 0
    @StateObject private var sessionManager = WatchSessionManager.shared

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $selectedTab) {
                InboxContent(sessionManager: sessionManager)
                    .tag(0)

                HorariosContent(sessionManager: sessionManager)
                    .tag(1)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            // Bottom navbar - puntitos minimalistas
            HStack(spacing: 8) {
                Circle()
                    .fill(selectedTab == 0 ? Color.white : Color.gray.opacity(0.3))
                    .frame(width: 6, height: 6)
                    .onTapGesture {
                        WKInterfaceDevice.current().play(.click)
                        withAnimation { selectedTab = 0 }
                    }

                Circle()
                    .fill(selectedTab == 1 ? Color.white : Color.gray.opacity(0.3))
                    .frame(width: 6, height: 6)
                    .onTapGesture {
                        WKInterfaceDevice.current().play(.click)
                        withAnimation { selectedTab = 1 }
                    }
            }
            .padding(.bottom, 4)
        }
        .background(Color.black)
        .ignoresSafeArea(.all)
        .onAppear {
            // Request session data from iPhone when app opens
            WatchSessionManager.shared.requestSessionFromiPhone()
        }
    }
}

// MARK: - Inbox
struct InboxContent: View {
    @ObservedObject var sessionManager: WatchSessionManager

    // Debug: connection status color
    var connectionStatusColor: Color {
        if !WatchWebSocketService.shared.isLinked() {
            return .red // No session (missing userId/companyId)
        } else if !WatchWebSocketService.shared.connectionStatus {
            return .yellow // Has session but not connected
        } else {
            return .green // Connected to backend
        }
    }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                // Header - Inicio a la izquierda
                HStack {
                    HStack(spacing: 6) {
                        // Debug: connection status indicator
                        Circle()
                            .fill(connectionStatusColor)
                            .frame(width: 6, height: 6)

                        Text("Inicio")
                            .font(.system(size: 16, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                        if !sessionManager.notifications.isEmpty {
                            Text("\(sessionManager.notifications.count)")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 18, height: 18)
                                .background(Color.red)
                                .clipShape(Circle())
                        }
                    }
                    Spacer()
                }
                .padding(.horizontal, 10)
                .padding(.top, 9)

                if sessionManager.notifications.isEmpty {
                    // DEBUG INFO - ScrollView para ver todo
                    ScrollView {
                        VStack(spacing: 8) {
                            // Spinner de sincronización
                            if sessionManager.isSyncing {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .cyan))
                                    .scaleEffect(0.8)
                            } else {
                                Image(systemName: "tray")
                                    .font(.system(size: 30))
                                    .foregroundColor(.gray.opacity(0.4))
                                Text("Sin notificaciones")
                                    .font(.system(size: 13, design: .rounded))
                                    .foregroundColor(.gray)
                            }

                            // DEBUG INFO
                            VStack(alignment: .leading, spacing: 3) {
                                let hasUserId = UserDefaults.standard.string(forKey: "userId") != nil
                                let hasCompanyId = UserDefaults.standard.string(forKey: "companyId") != nil
                                let wsUrl = WatchWebSocketService.shared.getWsUrl()
                                let connState = WatchWebSocketService.shared.connectionState
                                let lastErr = WatchWebSocketService.shared.lastError
                                let netStatus = WatchWebSocketService.shared.networkStatus

                                Text("uid: \(hasUserId ? "✓" : "✗") | cid: \(hasCompanyId ? "✓" : "✗")")
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundColor(.white)
                                Text("net: \(netStatus) | ws: \(connState)")
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundColor(netStatus == "no_network" ? .orange : .cyan)
                                if let err = lastErr {
                                    Text("err: \(err)")
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundColor(.red)
                                }
                                if let url = wsUrl {
                                    Text(url.replacingOccurrences(of: "wss://", with: ""))
                                        .font(.system(size: 9, design: .monospaced))
                                        .foregroundColor(.white.opacity(0.7))
                                }
                            }
                            .padding(.top, 9)

                        }
                        .padding(.top, 20)
                    }
                } else {
                    ScrollView {
                        // Spinner arriba si está sincronizando
                        if sessionManager.isSyncing {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .cyan))
                                .scaleEffect(0.6)
                                .padding(.top, 4)
                        }

                        LazyVStack(spacing: 8) {
                            ForEach(sessionManager.notifications) { notification in
                                NotificationRow(notification: notification)
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.top, 4)
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .ignoresSafeArea(edges: .top)
        .onTapGesture(count: 2) {
            refreshData()
        }
    }

    // MARK: - Refresh

    func refreshData() {
        WKInterfaceDevice.current().play(.click)
        WatchSessionManager.shared.requestSessionFromiPhone()
    }
}

struct NotificationRow: View {
    let notification: WatchNotification

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(notification.isRead ? Color.gray.opacity(0.3) : Color.blue)
                .frame(width: 8, height: 8)

            VStack(alignment: .leading, spacing: 2) {
                Text(notification.title)
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundColor(.white)
                    .lineLimit(1)

                Text(notification.body)
                    .font(.system(size: 10, design: .rounded))
                    .foregroundColor(.gray)
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(8)
        .background(Color.white.opacity(0.08))
        .cornerRadius(8)
    }
}

// MARK: - Horarios
struct HorariosContent: View {
    @ObservedObject var sessionManager: WatchSessionManager

    @State private var workState: WorkState = .idle
    @State private var workStartTime: Date? = nil
    @State private var totalWorkedSeconds: Int = 0
    @State private var breakStartTime: Date? = nil
    @State private var currentBreakSeconds: Int = 0
    @State private var currentTime = Date()
    @State private var showHistory = false

    // Sistema de confirmación con countdown
    enum PendingAction: String {
        case start = "Iniciando..."
        case breakToggle = "Procesando..."
        case stop = "Finalizando..."
        case ok = "Cerrando..."

        var question: String {
            switch self {
            case .start: return "¿Iniciar jornada?"
            case .breakToggle: return "¿Cambiar estado?"
            case .stop: return "¿Finalizar jornada?"
            case .ok: return "¿Cerrar el día?"
            }
        }
    }
    @State private var pendingAction: PendingAction? = nil
    @State private var showConfirmation = false
    @State private var countdown: Int = 0

    let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var timerFormatted: String {
        let hours = totalWorkedSeconds / 3600
        let minutes = (totalWorkedSeconds % 3600) / 60
        let seconds = totalWorkedSeconds % 60
        return String(format: "%d:%02d:%02d", hours, minutes, seconds)
    }

    var breakTimerFormatted: String {
        let hours = currentBreakSeconds / 3600
        let minutes = (currentBreakSeconds % 3600) / 60
        let seconds = currentBreakSeconds % 60
        return String(format: "%d:%02d:%02d", hours, minutes, seconds)
    }

    var canStart: Bool {
        sessionManager.canStartWork()
    }

    // Total break time from history (for summary)
    var totalBreakSecondsFromHistory: Int {
        var total = 0
        let events = sessionManager.todayEvents
        var i = 0
        while i < events.count {
            if events[i].type == .breakStart {
                if i + 1 < events.count && events[i + 1].type == .breakEnd {
                    total += Int(events[i + 1].timestamp.timeIntervalSince(events[i].timestamp))
                    i += 2
                    continue
                }
            }
            i += 1
        }
        return total
    }

    var totalBreakFormatted: String {
        let hours = totalBreakSecondsFromHistory / 3600
        let minutes = (totalBreakSecondsFromHistory % 3600) / 60
        let seconds = totalBreakSecondsFromHistory % 60
        return String(format: "%d:%02d:%02d", hours, minutes, seconds)
    }

    var timeUntilStartFormatted: String? {
        guard let interval = sessionManager.getTimeUntilStart() else { return nil }
        let hours = Int(interval) / 3600
        let minutes = (Int(interval) % 3600) / 60
        if hours > 0 {
            return "\(hours)h \(minutes)m"
        }
        return "\(minutes) min"
    }

    var timeUntilNextShiftFormatted: String? {
        guard let interval = sessionManager.getTimeUntilNextShift() else { return nil }
        let totalHours = Int(interval) / 3600
        let days = totalHours / 24
        let hours = totalHours % 24
        let minutes = (Int(interval) % 3600) / 60

        if days > 0 {
            return "Faltan \(days)d \(hours)h para tu jornada"
        } else if hours > 0 {
            return "Faltan \(hours)h \(minutes)m para tu jornada"
        }
        return "Faltan \(minutes) min para tu jornada"
    }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                if showHistory {
                    historyView
                } else {
                    switch workState {
                    case .idle:
                        idleView
                    case .working, .onBreak:
                        workingView
                    case .finished:
                        finishedView
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .ignoresSafeArea(edges: .top)
        .onReceive(timer) { _ in
            currentTime = Date()
            if workState == .working, let start = workStartTime {
                totalWorkedSeconds = Int(Date().timeIntervalSince(start))
            } else if workState == .onBreak, let breakStart = breakStartTime {
                currentBreakSeconds = Int(Date().timeIntervalSince(breakStart))
            }
            // Countdown para acciones pendientes
            if pendingAction != nil && countdown > 0 {
                countdown -= 1
                if countdown == 0 {
                    executePendingAction()
                }
            }
        }
        // Confirmación inicial
        .confirmationDialog(
            pendingAction?.question ?? "",
            isPresented: $showConfirmation,
            titleVisibility: .visible
        ) {
            Button("Confirmar") {
                startCountdown()
            }
            Button("Cancelar", role: .cancel) {
                cancelPendingAction()
            }
        }
        // Overlay de countdown (solo si countdown activo)
        .overlay {
            if let action = pendingAction, countdown > 0 {
                countdownOverlay(action: action)
            }
        }
    }

    // MARK: - Countdown Overlay
    func countdownOverlay(action: PendingAction) -> some View {
        let progress = Double(3 - countdown) / 3.0

        return ZStack {
            Color.black.opacity(0.9)
                .ignoresSafeArea()

            VStack(spacing: 16) {
                Text(action.rawValue)
                    .font(.system(size: 14, weight: .medium, design: .rounded))
                    .foregroundColor(.white.opacity(0.8))

                // Círculo de progreso estilo Workout
                ZStack {
                    // Fondo del círculo
                    Circle()
                        .stroke(Color.gray.opacity(0.3), lineWidth: 8)
                        .frame(width: 100, height: 100)

                    // Progreso
                    Circle()
                        .trim(from: 0, to: progress)
                        .stroke(Color.cyan, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                        .frame(width: 100, height: 100)
                        .rotationEffect(.degrees(-90))
                        .animation(.linear(duration: 1), value: progress)

                    // Número
                    Text("\(countdown)")
                        .font(.system(size: 42, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                        .monospacedDigit()
                }

                Button(action: cancelPendingAction) {
                    Text("Cancelar")
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundColor(.white.opacity(0.9))
                }
                .buttonStyle(.plain)
            }
        }
        .onTapGesture {
            cancelPendingAction()
        }
    }

    // MARK: - Pending Action Helpers

    /// Paso 1: Mostrar pregunta de confirmación
    func askConfirmation(_ action: PendingAction) {
        pendingAction = action
        showConfirmation = true
    }

    /// Paso 2: Usuario confirmó, iniciar countdown
    func startCountdown() {
        showConfirmation = false
        WKInterfaceDevice.current().play(.notification)
        countdown = 3
    }

    /// Cancelar en cualquier momento
    func cancelPendingAction() {
        WKInterfaceDevice.current().play(.stop)
        showConfirmation = false
        pendingAction = nil
        countdown = 0
    }

    /// Paso 3: Countdown terminó, ejecutar acción
    func executePendingAction() {
        guard let action = pendingAction else { return }
        pendingAction = nil
        countdown = 0

        switch action {
        case .start:
            doStartWork()
        case .breakToggle:
            doToggleBreak()
        case .stop:
            doStopWork()
        case .ok:
            doResetDay()
        }
    }

    // MARK: - Idle
    var idleView: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Horario")
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
                Spacer()
            }
            .padding(.horizontal, 10)
            .padding(.top, 9)

            Spacer()

            // Horario del día (estilo Apple)
            if let schedule = sessionManager.getTodaySchedule() {
                Text("\(schedule.startTime) - \(schedule.endTime)")
                    .font(.system(size: 15, design: .rounded))
                    .foregroundColor(.white)
                    .padding(.bottom, 8)
            }

            // Play centrado - tap para iniciar
            Button(action: {
                if canStart {
                    askConfirmation(.start)
                }
            }) {
                ZStack {
                    Circle()
                        .fill(canStart ? Color.green : Color.gray.opacity(0.3))
                        .frame(width: 70, height: 70)

                    Image(systemName: "play.fill")
                        .font(.system(size: 30))
                        .foregroundColor(canStart ? .white : .gray)
                }
            }
            .buttonStyle(.plain)
            .disabled(!canStart)

            if canStart {
                Text("Iniciar")
                    .font(.system(size: 15, design: .rounded))
                    .foregroundColor(.white)
                    .padding(.top, 9)
            } else if let timeUntil = timeUntilNextShiftFormatted {
                Text(timeUntil)
                    .font(.system(size: 11, design: .rounded))
                    .foregroundColor(.cyan)
                    .padding(.top, 9)
                    .multilineTextAlignment(.center)
            }

            Spacer()
        }
    }

    // MARK: - Working
    var workingView: some View {
        VStack(spacing: 0) {
            // Header: estado
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: "circle.fill")
                        .font(.system(size: 16))
                        .foregroundColor(workState == .working ? .green : .cyan)
                    Text(workState == .working ? "Trabajando" : "Descanso")
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .foregroundColor(workState == .working ? .green : .cyan)
                }
                Spacer()
            }
            .padding(.horizontal, 10)
            .padding(.top, 9)

            Spacer()

            // Timer principal
            Text(workState == .onBreak ? breakTimerFormatted : timerFormatted)
                .font(.system(size: 42, weight: .medium, design: .rounded))
                .foregroundColor(workState == .onBreak ? .cyan : .white)
                .monospacedDigit()

            // Tiempo secundario
            if workState == .onBreak {
                Text(timerFormatted)
                    .font(.system(size: 13, design: .rounded))
                    .foregroundColor(.gray.opacity(0.6))
                    .monospacedDigit()
                    .padding(.top, 4)
            }

            Spacer()

            // Botones - tap para activar
            HStack(spacing: 12) {
                // Descanso
                Button(action: {
                    askConfirmation(.breakToggle)
                }) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(workState == .onBreak ? Color.green : Color.cyan.opacity(0.25))
                            .frame(width: 52, height: 44)
                        Image(systemName: workState == .onBreak ? "play.fill" : "cup.and.saucer.fill")
                            .font(.system(size: 18))
                            .foregroundColor(workState == .onBreak ? .white : .cyan)
                    }
                }
                .buttonStyle(.plain)

                // Stop
                Button(action: {
                    askConfirmation(.stop)
                }) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.pink.opacity(0.25))
                            .frame(width: 52, height: 44)
                        Image(systemName: "stop.fill")
                            .font(.system(size: 18))
                            .foregroundColor(.pink)
                    }
                }
                .buttonStyle(.plain)

                // Historial
                Button(action: {
                    WKInterfaceDevice.current().play(.click)
                    showHistory = true
                }) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.purple.opacity(0.25))
                            .frame(width: 52, height: 44)
                        Image(systemName: "list.bullet")
                            .font(.system(size: 18))
                            .foregroundColor(.purple)
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(.bottom, 16)
        }
    }

    // MARK: - History
    var historyView: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Button(action: {
                    WKInterfaceDevice.current().play(.click)
                    showHistory = false
                }) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.blue)
                }
                .buttonStyle(.plain)

                Text("Actividad")
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
                    .padding(.leading, 4)

                Spacer()
            }
            .padding(.horizontal, 10)
            .padding(.top, 9)

            if sessionManager.todayEvents.isEmpty {
                Spacer()
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 30))
                    .foregroundColor(.gray.opacity(0.4))
                Text("Sin registros")
                    .font(.system(size: 14, design: .rounded))
                    .foregroundColor(.gray)
                    .padding(.top, 9)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(groupedEvents(), id: \.id) { item in
                            EventRow(item: item)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.top, 4)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    // MARK: - Finished
    var finishedView: some View {
        VStack(spacing: 0) {
            // Header - checkmark + "Buen trabajo!"
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundColor(.green)
                    Text("Buen trabajo!")
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .foregroundColor(.white)
                }
                Spacer()
            }
            .padding(.horizontal, 10)
            .padding(.top, 9)

            // Log de actividad
            if sessionManager.todayEvents.isEmpty {
                Spacer()
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 30))
                    .foregroundColor(.gray.opacity(0.4))
                Text("Sin registros")
                    .font(.system(size: 14, design: .rounded))
                    .foregroundColor(.gray)
                    .padding(.top, 9)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(groupedEvents(), id: \.id) { item in
                            EventRow(item: item)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.top, 4)
                }
            }

            // Botón OK - tap para confirmar
            Button(action: {
                askConfirmation(.ok)
            }) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.blue)
                        .frame(width: 80, height: 36)
                    Text("OK")
                        .font(.system(size: 14, weight: .semibold, design: .rounded))
                        .foregroundColor(.white)
                }
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
            .padding(.bottom, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    // MARK: - Actions

    func doStartWork() {
        workState = .working
        workStartTime = Date()
        totalWorkedSeconds = 0
        currentBreakSeconds = 0
        sessionManager.addEvent(.clockIn)
        WKInterfaceDevice.current().play(.start)
    }

    func doToggleBreak() {
        if workState == .working {
            // Iniciar descanso - contador desde 0
            workState = .onBreak
            breakStartTime = Date()
            currentBreakSeconds = 0
            sessionManager.addEvent(.breakStart)
            WKInterfaceDevice.current().play(.directionDown)
        } else if workState == .onBreak {
            // Terminar descanso - ajustar tiempo de trabajo
            if let breakStart = breakStartTime, let workStart = workStartTime {
                let breakDuration = Date().timeIntervalSince(breakStart)
                workStartTime = workStart.addingTimeInterval(breakDuration)
            }
            workState = .working
            breakStartTime = nil
            currentBreakSeconds = 0
            sessionManager.addEvent(.breakEnd)
            WKInterfaceDevice.current().play(.directionUp)
        }
    }

    func doStopWork() {
        sessionManager.addEvent(.clockOut)
        workState = .finished
        WKInterfaceDevice.current().play(.success)
    }

    func doResetDay() {
        workState = .idle
        workStartTime = nil
        breakStartTime = nil
        totalWorkedSeconds = 0
        currentBreakSeconds = 0
        sessionManager.clearTodayEvents()
        showHistory = false
        WKInterfaceDevice.current().play(.click)
    }

    func formattedTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    func formattedDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd/MM"
        return formatter.string(from: date)
    }

    func groupedEvents() -> [EventItem] {
        var items: [EventItem] = []
        var i = 0
        let events = sessionManager.todayEvents

        while i < events.count {
            let event = events[i]

            if event.type == .breakStart {
                if i + 1 < events.count && events[i + 1].type == .breakEnd {
                    items.append(EventItem(
                        type: .breakRange,
                        startTime: event.timestamp,
                        endTime: events[i + 1].timestamp
                    ))
                    i += 2
                    continue
                } else {
                    items.append(EventItem(
                        type: .breakRange,
                        startTime: event.timestamp,
                        endTime: nil
                    ))
                }
            } else if event.type == .clockIn {
                items.append(EventItem(type: .clockIn, startTime: event.timestamp))
            } else if event.type == .clockOut {
                items.append(EventItem(type: .clockOut, startTime: event.timestamp))
            }
            i += 1
        }

        return items
    }
}

// MARK: - Event Models
struct EventItem: Identifiable {
    let id = UUID()
    let type: EventItemType
    let startTime: Date
    var endTime: Date? = nil
}

enum EventItemType {
    case clockIn
    case clockOut
    case breakRange
}

struct EventRow: View {
    let item: EventItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Primera línea: círculo + título
            HStack(spacing: 6) {
                Circle()
                    .fill(colorFor(item.type))
                    .frame(width: 8, height: 8)

                Text(labelFor(item.type))
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundColor(.white)
            }

            // Segunda línea: horario a la izquierda
            if item.type == .breakRange {
                Text("\(formatTime(item.startTime)) - \(item.endTime != nil ? formatTime(item.endTime!) : "...")")
                    .font(.system(size: 11, design: .rounded))
                    .foregroundColor(.gray)
                    .monospacedDigit()
                    .padding(.leading, 14)
            } else {
                Text(formatTime(item.startTime))
                    .font(.system(size: 11, design: .rounded))
                    .foregroundColor(.gray)
                    .monospacedDigit()
                    .padding(.leading, 14)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color.white.opacity(0.08))
        .cornerRadius(8)
    }

    func colorFor(_ type: EventItemType) -> Color {
        switch type {
        case .clockIn: return .green
        case .clockOut: return .pink
        case .breakRange: return .cyan
        }
    }

    func labelFor(_ type: EventItemType) -> String {
        switch type {
        case .clockIn: return "Entrada"
        case .clockOut: return "Salida"
        case .breakRange: return "Descanso"
        }
    }

    func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}

#Preview {
    ContentView()
}
