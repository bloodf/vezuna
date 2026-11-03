import SwiftUI
import VezunaShared

/// UIKit scene lifecycle instead of SwiftUI's `App`/`WindowGroup`: the scene delegate owns the root hosting
/// controller, which is the only supported place to choose the status bar style (see `LightStatusHostingController`).
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var lock: AppLockObserver?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let root: AnyView
        if AppShellModel.isHostingTests {
            // Unit tests use the app only as a host: do not boot the real vault/runtime.
            root = AnyView(Color.clear)
        } else {
            let lock = AppLockObserver()
            self.lock = lock
            root = AnyView(
                ContentView()
                    .font(.vezunaBody)
                    .tint(Color.vezunaAction)
                    .environmentObject(AppDelegate.model)
                    .environmentObject(lock)
            )
        }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = LightStatusHostingController(rootView: root)
        window.makeKeyAndVisible()
        self.window = window
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        guard !AppShellModel.isHostingTests else { return }
        IosRuntime.shared.appLock.refresh()
        IosRuntime.shared.appLock.onForeground()
        AppDelegate.model.sceneBecameActive()
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        guard !AppShellModel.isHostingTests else { return }
        IosRuntime.shared.appLock.onBackground()
    }
}

/// Every screen starts with a pine header or status band, in light and dark appearance alike, so the status bar
/// content is always light. SwiftUI would otherwise derive the style from the colour scheme (dark text on pine).
final class LightStatusHostingController<Content: View>: UIHostingController<Content> {
    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
}
