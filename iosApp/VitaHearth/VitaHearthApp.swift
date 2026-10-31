import SwiftUI

@main
struct VitaHearthApp: App {
    @StateObject private var model = AppShellModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
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
