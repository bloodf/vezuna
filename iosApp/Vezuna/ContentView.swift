import SwiftUI
import VezunaShared

struct ContentView: View {
    @EnvironmentObject private var model: AppShellModel
    @EnvironmentObject private var lock: AppLockObserver
    @StateObject private var nativeActions = NativeActionRouter()

    var body: some View {
        Group {
            if DebugLaunch.fixture {
                DesignFixtureHostView().ignoresSafeArea(.container)
            } else if model.ready {
                ComposeHostView()
                    .ignoresSafeArea(.container)
                    .safeAreaInset(edge: .top) {
                        if let error = model.catalogError ?? model.modelLoader.lastError {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(Color.vezunaDanger)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(8)
                                .background(.regularMaterial)
                                // VoiceOver hears the banner when it appears or changes, without moving focus.
                                .onAppear { UIAccessibility.post(notification: .announcement, argument: error) }
                                .onChange(of: error) { UIAccessibility.post(notification: .announcement, argument: $0) }
                        }
                    }
            } else if model.needsVaultRecovery {
                VaultRecoveryHostView(
                    onRetry: { model.recoverVault(reset: false) },
                    onReset: { model.recoverVault(reset: true) },
                    onRestore: { model.recoverVault(reset: true) }
                )
                .ignoresSafeArea()
            } else if let error = model.startupError {
                // Scrolls so the message never clips at the largest accessibility text sizes.
                ScrollView {
                    VStack(spacing: 12) {
                        Image(systemName: "lock.trianglebadge.exclamationmark").font(.largeTitle).accessibilityHidden(true)
                        Text(L10n.text(.storage_unavailable)).font(.headline).accessibilityAddTraits(.isHeader)
                        Text(error).multilineTextAlignment(.center)
                    }.padding()
                }
            } else {
                ProgressView(L10n.text(.opening_storage))
            }
        }
        // The status bar always sits on pine, continuing the root and task headers, so its text is light
        // (UIStatusBarStyleLightContent in Vezuna-Info.plist). A zero-height band grows into the top safe area.
        .background(alignment: .top) { Color.vezunaHeader.frame(height: 0).ignoresSafeArea(edges: .top) }
        .task {
            IosRuntime.shared.nativeActionHandler = nativeActions
            // The fixture skips configure(), but its export preview still saves and shares through the system sheets.
            if DebugLaunch.fixture { IosRuntime.shared.exportFiles = ComposeExportFiles() }
            if !DebugLaunch.fixture { model.configure() }
        }
        .onChange(of: lock.locked) { locked in
            guard locked else { return }
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { model.sheet = nil }
        }
        .onAppear { nativeActions.model = model }
        .sheet(item: $model.sheet) { sheet in
            switch sheet {
            case .health:
                HealthImportView(importer: model.healthImporter)
            case .license:
                LicenseView(manager: model.licenseManager)
            case .notices:
                ThirdPartyNoticesView()
            case .privacy:
                PrivacyPolicyView()
            }
        }
    }
}

/// Opens the native sheets that More lists (they used to live in the toolbar menu). Runs on the main queue.
final class NativeActionRouter: NSObject, ObservableObject, NativeActionHandler {
    weak var model: AppShellModel?

    func perform(action: String) {
        let sheet: AppShellModel.Sheet?
        switch action {
        case "HEALTH": sheet = .health
        case "LICENSE": sheet = .license
        case "PRIVACY": sheet = .privacy
        case "NOTICES": sheet = .notices
        case "NOTIFICATION_SETTINGS":
            // System-owned screen: Care offers it only when notifications are denied.
            DispatchQueue.main.async {
                if let url = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(url) }
            }
            return
        default: sheet = nil
        }
        DispatchQueue.main.async { [weak self] in self?.model?.sheet = sheet }
    }
}

/// Screenshot harness entry points, compiled into debug builds only.
enum DebugLaunch {
    #if DEBUG
    static let fixture = ProcessInfo.processInfo.arguments.contains("-VezunaFixture")
    static let route = UserDefaults.standard.string(forKey: "VezunaRoute")
    #else
    static let fixture = false
    static let route: String? = nil
    #endif
}

struct DesignFixtureHostView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ComposeBridgeKt.makeDesignFixtureViewController(route: DebugLaunch.route)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ComposeHostView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ComposeBridgeKt.makeComposeViewController(route: DebugLaunch.route)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct VaultRecoveryHostView: UIViewControllerRepresentable {
    let onRetry: () -> Void
    let onReset: () -> Void
    let onRestore: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        ComposeBridgeKt.makeVaultRecoveryViewController(onRetry: onRetry, onReset: onReset, onRestore: onRestore)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
