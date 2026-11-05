import SwiftUI
import VezunaShared

struct ContentView: View {
    @EnvironmentObject private var model: AppShellModel
    @EnvironmentObject private var lock: AppLockObserver
    @StateObject private var nativeActions = NativeActionRouter()
    @State private var topInset: CGFloat = 0

    var body: some View {
        Group {
            if DebugLaunch.fixture {
                DesignFixtureHostView().ignoresSafeArea(.container)
            } else if model.ready {
                // The host reaches under the status bar, so a banner is stacked above it instead of inset: the banner
                // takes the top safe area itself and the host then only extends to the bottom.
                let error = model.catalogError ?? model.modelLoader.lastError
                VStack(spacing: 0) {
                    if let error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(Color.vezunaDanger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(8)
                            .background { GeometryReader { Color.clear.preference(key: TopInsetKey.self, value: $0.frame(in: .global).minY) } }
                            .background(Color(uiColor: .systemBackground), ignoresSafeAreaEdges: [.top, .leading, .trailing])
                            .zIndex(1)
                            // VoiceOver hears the banner when it appears or changes, without moving focus.
                            .onAppear { UIAccessibility.post(notification: .announcement, argument: error) }
                            .onChange(of: error) { UIAccessibility.post(notification: .announcement, argument: $0) }
                    }
                    // SwiftUI still gives the host the status-bar inset the banner took, so Compose would pad its pine
                    // headers a second time. The host starts that far above the banner's bottom edge instead, under
                    // the banner, so its own inset lands exactly on the banner and nothing is doubled.
                    ComposeHostView()
                        .ignoresSafeArea(.container, edges: error == nil ? .all : [.bottom, .leading, .trailing])
                        .padding(.top, error == nil ? 0 : -topInset)
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
        // (LightStatusHostingController). A zero-height band grows into the top safe area.
        .background(alignment: .top) { Color.vezunaHeader.frame(height: 0).ignoresSafeArea(edges: .top) }
        .onPreferenceChange(TopInsetKey.self) { topInset = $0 }
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
        .sheet(item: $model.sheet, onDismiss: { model.requestedHealthImporter = nil }) { sheet in
            switch sheet {
            case .health:
                HealthImportView(importer: model.requestedHealthImporter ?? model.healthImporter)
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

    func performHealth(context: VitalsSyncContext) {
        DispatchQueue.main.async { [weak self] in
            guard let model = self?.model, model.healthImporter != nil else { return }
            model.requestedHealthImporter = HealthKitImporter(expected: context)
            model.sheet = .health
        }
    }

    func perform(action: String) {
        let sheet: AppShellModel.Sheet?
        switch action {
        case "HEALTH": sheet = .health
        case "REMINDERS":
            Task { await model?.notifications.requestPermission() }
            return
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
        DispatchQueue.main.async { [weak self] in
            self?.model?.requestedHealthImporter = nil
            self?.model?.sheet = sheet
        }
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

/// Where the banner starts: the top safe-area inset it sits below. Measured in the view tree, so it follows rotation,
/// the Dynamic Island and the status bar (0 in landscape on iPhone).
private struct TopInsetKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
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

