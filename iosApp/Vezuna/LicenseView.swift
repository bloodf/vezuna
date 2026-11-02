import SwiftUI

/// Store activation for the optional AI capability only. Records, backups and exports never depend on it, and the
/// failed state says so. Status is icon plus text, never color alone.
struct LicenseView: View {
    @ObservedObject var manager: LicenseManager

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: status.icon)
                            .font(.title2)
                            .foregroundStyle(status.isError ? Color.vezunaDanger : Color.vezunaAction)
                            .accessibilityHidden(true)
                        Text(status.text)
                            .font(.headline)
                            .foregroundStyle(Color.vezunaText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(16)
                    .background(Color.vezunaSurface, in: RoundedRectangle(cornerRadius: 20))
                    .accessibilityElement(children: .combine)

                    Button {
                        Task { await manager.refreshFromStore() }
                    } label: {
                        Label(L10n.text(.refresh_app_store), systemImage: "arrow.clockwise")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.vezunaAction)
                    .disabled(manager.refreshing)
                    if manager.refreshing { ProgressView().frame(maxWidth: .infinity) }

                    Text(L10n.text(.store_verification_description))
                        .font(.footnote)
                        .foregroundStyle(Color.vezunaTextMuted)
                }
                .padding(24)
            }
            .background(Color.vezunaBackground.ignoresSafeArea())
            .navigationTitle(L10n.text(.ai_activation))
        }
    }

    private var status: (text: String, icon: String, isError: Bool) {
        switch manager.state {
        case .inactive:
            return (L10n.text(.premium_inactive), "lock", false)
        case .activeVerified:
            return (L10n.text(.premium_verified), "checkmark.circle", false)
        case .activeOfflineUntil(let date):
            return (L10n.text(.premium_offline_until, date.formatted()), "checkmark.circle", false)
        case .verificationFailed:
            return (L10n.text(.store_verification_failed), "exclamationmark.triangle", true)
        }
    }
}
