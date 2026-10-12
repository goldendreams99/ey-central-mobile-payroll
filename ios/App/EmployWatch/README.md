# EmployWatch - Apple Watch Extension

Watch app extension for the Employ iOS app.

## Setup in Xcode

Since watchOS targets need to be added through Xcode, follow these steps:

### 1. Open the project in Xcode

```bash
cd app-mobile/ios/App
open App.xcworkspace
```

### 2. Add Watch App Target

1. In Xcode, go to **File → New → Target...**
2. Select **watchOS** tab
3. Choose **App** (not "App for Existing iOS App" in older Xcode)
4. Click **Next**
5. Configure:
   - **Product Name:** `EmployWatch`
   - **Bundle Identifier:** `ai.employ.app.watch`
   - **Language:** Swift
   - **User Interface:** SwiftUI
   - **Watch App:** Check "Watch App for Existing iOS App"
   - **Existing iOS App:** Select "App"
   - Uncheck "Include Notification Scene" (we'll use mirrored notifications)
   - Uncheck "Include Tests"
6. Click **Finish**

### 3. Replace Generated Files

Xcode will create a new folder. Replace its contents with the files in this folder:

1. Delete the auto-generated Swift files in the new target
2. Add the following files to the EmployWatch target:
   - `EmployWatchApp.swift`
   - `ContentView.swift`
   - `Info.plist` (merge with generated one or replace)
   - `EmployWatch.entitlements`
   - `Assets.xcassets/`

### 4. Configure Build Settings

In the EmployWatch target settings:

| Setting | Value |
|---------|-------|
| Bundle Identifier | `ai.employ.app.watch` |
| Deployment Target | watchOS 9.0 |
| Swift Version | 5.0 |
| Code Signing Entitlements | `EmployWatch/EmployWatch.entitlements` |

### 5. Configure App Group

In **Apple Developer Portal:**

1. Go to Identifiers → App Groups
2. Create new: `group.ai.employ.app`
3. Add to both App IDs:
   - `ai.employ.app` (iPhone)
   - `ai.employ.app.watch` (Watch)

### 6. Configure Push Notifications

The watch app uses **mirrored notifications** by default:
- When iPhone receives a push and screen is locked, it mirrors to Watch
- No additional configuration needed for basic notifications

For custom watch notifications (future):
- Add Notification Scene to the watch target
- Create custom notification views

### 7. Build & Run

1. Select the **EmployWatch** scheme
2. Choose a Watch simulator paired with an iPhone simulator
3. Build and run (Cmd + R)

## Files Structure

```
EmployWatch/
├── EmployWatchApp.swift        # App entry point
├── ContentView.swift           # Main dashboard view
├── WatchSessionManager.swift   # Comunicación con iPhone via WCSession
├── WatchWebSocketService.swift # Registro de device con backend via WebSocket
├── Info.plist                  # App configuration
├── EmployWatch.entitlements    # Capabilities (notifications, app groups)
├── Assets.xcassets/            # Icons and colors
│   ├── AppIcon.appiconset/
│   └── AccentColor.colorset/
└── README.md                   # This file
```

## Notifications

### Mirrored Notifications (Current)
- All iPhone notifications automatically appear on Watch when:
  - iPhone is locked
  - Watch is on wrist and unlocked
- No code changes needed

### Custom Notifications (Future)
To customize how notifications appear on Watch:
1. Add `NotificationView.swift`
2. Add Notification Scene to `EmployWatchApp.swift`
3. Use `UNNotificationContentExtension`

## Bundle IDs

| App | Bundle ID |
|-----|-----------|
| iPhone App | `ai.employ.app` |
| Watch App | `ai.employ.app.watch` |

## Troubleshooting

### "Watch app requires companion app"
- Ensure `WKCompanionAppBundleIdentifier` in Info.plist matches iPhone bundle ID

### Notifications not appearing on Watch
- Check iPhone is locked
- Verify push notifications work on iPhone first
- Check Watch notification settings in Watch app on iPhone

### App Groups not working
- Verify both targets have the same App Group in entitlements
- Ensure App Group is registered in Apple Developer Portal
