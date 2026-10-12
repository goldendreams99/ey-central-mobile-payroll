import SwiftUI
import WatchKit

@main
struct EmployWatchApp: App {
    @WKApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

class AppDelegate: NSObject, WKApplicationDelegate {
    func applicationDidFinishLaunching() {
        print("[EmployWatch] App launched")

        // Pedir datos al iPhone (este enviará la URL y sesión)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            WatchSessionManager.shared.requestSessionFromiPhone()
            print("[EmployWatch] Requested session data from iPhone")
        }
    }

    func applicationDidBecomeActive() {
        // Reconnect if needed when app becomes active
        if !WatchWebSocketService.shared.connectionStatus {
            WatchWebSocketService.shared.connect()
        }
    }

    func applicationWillResignActive() {
        // Optionally disconnect when app goes to background
        // Keep connected for now to maintain registration
    }

    func didRegisterForRemoteNotifications(withDeviceToken deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02.2hhx", $0) }.joined()
        print("[EmployWatch] Push Token: \(token)")
    }

    func didFailToRegisterForRemoteNotificationsWithError(_ error: Error) {
        print("[EmployWatch] Failed to register for notifications: \(error)")
    }
}
