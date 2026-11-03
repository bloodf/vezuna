import SwiftUI
import UIKit
import VezunaShared

/// Owns the shell model so the notification delegate and the background task are installed before launch finishes,
/// including cold launches caused only by a notification action or a background refresh.
@main
final class AppDelegate: NSObject, UIApplicationDelegate {
    static let model = AppShellModel()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Unit tests use the app only as a host: no vault, no background task registration or submission.
        guard !AppShellModel.isHostingTests else { return true }
        IosRuntime.shared.deviceAuthenticator = AppLockAuthenticator()
        IosRuntime.shared.appLock.refresh()
        PrivacyCover.shared.start()
        NotificationCenter.default.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { _ in
            Self.model.protectedDataBecameAvailable()
        }
        MedicationNotificationCoordinator.registerBackgroundRefresh { task in
            Self.model.runBackgroundRefresh(task)
        }
        Self.model.configure()
        Self.model.notifications.scheduleBackgroundRefresh()
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}
