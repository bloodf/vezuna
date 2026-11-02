import SwiftUI
import VezunaShared

struct HealthImportView: View {
    let importer: HealthKitImporter?
    @State private var busy = false
    @State private var result: HealthSampleBatch?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L10n.text(.healthkit_description))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section {
                    if importer == nil {
                        Text(L10n.text(.healthkit_unavailable))
                    } else {
                        Button {
                            Task { await importHealth() }
                        } label: {
                            Label(L10n.text(.authorize_import_health), systemImage: "heart.text.square")
                        }
                        .disabled(busy)
                    }
                }
                if busy {
                    Section {
                        HStack { ProgressView(); Text(L10n.text(.reading_health)) }
                            .accessibilityElement(children: .combine)
                    }
                }
                if let result {
                    Section(L10n.text(.latest_batch)) {
                        LabeledContent(L10n.text(.heart_rate_samples), value: "\(result.heartRate)")
                        LabeledContent(L10n.text(.step_samples), value: "\(result.steps)")
                        LabeledContent(L10n.text(.weight_samples), value: "\(result.weight)")
                        // HealthKit never reveals a read denial: an empty batch is reported as just that.
                        if result.heartRate + result.steps + result.weight == 0 {
                            Text(L10n.text(.health_no_readings))
                        }
                    }
                }
                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(Color.vezunaDanger)
                    }
                }
                Section {
                    Text(L10n.text(.health_revoke_ios))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(L10n.text(.health_import))
        }
    }

    @MainActor
    private func importHealth() async {
        guard let importer else { return }
        busy = true
        errorMessage = nil
        defer { busy = false }
        do {
            try await importer.authorizeReadOnly()
            let batch = try await importer.importRecent()
            result = batch
            let total = batch.heartRate + batch.steps + batch.weight
            AccessibilityNotification.Announcement(
                total == 0 ? L10n.text(.health_no_readings) : L10n.text(.latest_batch)
            ).post()
        } catch {
            errorMessage = error.localizedDescription
            AccessibilityNotification.Announcement(error.localizedDescription).post()
        }
    }
}
