import SwiftUI
import VezunaShared

@main
struct VezunaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppDelegate.model
    @StateObject private var lock = AppLockObserver()
    @Environment(\.scenePhase) private var scenePhase

    /// Unit tests use the app only as a host: do not boot the real vault/runtime.
    private static let isHostingTests = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    init() {
        guard !Self.isHostingTests else { return }
        IosRuntime.shared.deviceAuthenticator = AppLockAuthenticator()
        IosRuntime.shared.appLock.refresh()
        PrivacyCover.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            if Self.isHostingTests {
                Color.clear
            } else {
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
}
