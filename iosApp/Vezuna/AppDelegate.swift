import SwiftUI
import UIKit

/// Owns the shell model so the notification delegate and the background task are installed before launch finishes,
/// including cold launches caused only by a notification action or a background refresh.
final class AppDelegate: NSObject, UIApplicationDelegate {
    static let model = AppShellModel()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MedicationNotificationCoordinator.registerBackgroundRefresh { task in
            Self.model.runBackgroundRefresh(task)
        }
        Self.model.configure()
        Self.model.notifications.scheduleBackgroundRefresh()
        return true
    }
}
