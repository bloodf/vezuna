import SwiftUI

@main
struct VezunaApp: App {
    @StateObject private var model = AppShellModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil {
                // Unit tests use the app only as a host: do not boot the real vault/runtime.
                Color.clear
            } else {
                ContentView()
                    .font(.vezunaBody)
                    .tint(Color.vezunaAction)
                    .environmentObject(model)
                    .onChange(of: scenePhase) { phase in
                        if phase == .active { model.sceneBecameActive() }
                    }
            }
        }
    }
}
