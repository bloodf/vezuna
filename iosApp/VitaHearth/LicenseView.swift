import SwiftUI

struct LicenseView: View {
    @ObservedObject var manager: LicenseManager

    var body: some View {
        NavigationStack {
            List {
                Section(L10n.text(.premium_ai_activation)) {
                    Text(statusText)
                    Button {
                        Task { await manager.refreshFromStore() }
                    } label: {
                        Label(L10n.text(.refresh_app_store), systemImage: "arrow.clockwise")
                    }
                    .disabled(manager.refreshing)
                    if manager.refreshing { ProgressView() }
                }
                Section {
                    Text(L10n.text(.store_verification_description))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(L10n.text(.ai_activation))
        }
    }

    private var statusText: String {
        switch manager.state {
        case .inactive:
            return L10n.text(.premium_inactive)
        case .activeVerified:
            return L10n.text(.premium_verified)
        case .activeOfflineUntil(let date):
            return L10n.text(.premium_offline_until, date.formatted())
        case .verificationFailed:
            return L10n.text(.store_verification_failed)
        }
    }
}
