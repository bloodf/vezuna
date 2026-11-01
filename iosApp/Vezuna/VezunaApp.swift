import SwiftUI
import VezunaShared

@main
struct VezunaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppDelegate.model
    @StateObject private var lock = AppLockObserver()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        IosRuntime.shared.deviceAuthenticator = AppLockAuthenticator()
        IosRuntime.shared.appLock.refresh()
        PrivacyCover.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .font(.vezunaBody)
                .tint(Color.vezunaAction)
                .environmentObject(model)
                .environmentObject(lock)
                .onChange(of: scenePhase) { phase in
                    if phase == .active {
                        IosRuntime.shared.appLock.refresh()
                        IosRuntime.shared.appLock.onForeground()
                        model.sceneBecameActive()
                    } else if phase == .background {
                        IosRuntime.shared.appLock.onBackground()
                    }
                }
        }
    }
}
