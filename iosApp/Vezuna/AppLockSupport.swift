import LocalAuthentication
import SwiftUI
import VezunaShared

/// Face ID / Touch ID with the device passcode as fallback.
final class AppLockAuthenticator: NativeDeviceAuthenticator {
    func canAuthenticate() -> Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
    }

    // Held for the whole evaluation: an LAContext released early invalidates the prompt.
    private var context: LAContext?

    func authenticate(completion: @escaping (KotlinBoolean) -> Void) {
        let context = LAContext()
        self.context = context
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: L10n.text(.app_lock_reason)) { [weak self] success, _ in
            DispatchQueue.main.async {
                self?.context = nil
                completion(KotlinBoolean(bool: success))
            }
        }
    }
}

/// Mirrors the shared lock state so native chrome can hide while locked.
final class AppLockObserver: ObservableObject {
    @Published private(set) var locked = true

    init() {
        IosRuntime.shared.observeLock { [weak self] locked in
            self?.locked = locked.boolValue
        }
    }
}

/// Opaque window above everything (sheets included) while the scene is inactive, so the app switcher
/// snapshot shows no health data. Only active when the app lock setting is on.
final class PrivacyCover {
    static let shared = PrivacyCover()
    private var window: UIWindow?
    private var observers: [NSObjectProtocol] = []

    func start() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIScene.willDeactivateNotification, object: nil, queue: .main) { [weak self] note in
                guard let scene = note.object as? UIWindowScene,
                      UserDefaults.standard.bool(forKey: Self.enabledKey) else { return }
                self?.show(in: scene)
            },
            center.addObserver(forName: UIScene.didActivateNotification, object: nil, queue: .main) { [weak self] _ in
                // Lock state first, so the lock screen is in place before the cover goes away.
                IosRuntime.shared.appLock.refresh()
                IosRuntime.shared.appLock.onForeground()
                DispatchQueue.main.async { self?.hide() }
            },
        ]
    }

    private static let enabledKey = ComposeBridgeKt.APP_LOCK_ENABLED_KEY

    private func show(in scene: UIWindowScene) {
        guard window == nil else { return }
        let cover = UIWindow(windowScene: scene)
        cover.windowLevel = .alert + 1
        cover.rootViewController = UIHostingController(rootView: PrivacyCoverView())
        cover.isHidden = false
        window = cover
    }

    private func hide() {
        window?.isHidden = true
        window = nil
    }
}

private struct PrivacyCoverView: View {
    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            Image(systemName: "lock.fill").font(.largeTitle).foregroundStyle(Color.vezunaAction)
        }
        .accessibilityHidden(true)
    }
}
