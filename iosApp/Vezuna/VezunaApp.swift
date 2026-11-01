import SwiftUI

@main
struct VezunaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppDelegate.model
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
