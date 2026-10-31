import SwiftUI
import VitaHearthShared

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
                    Section { HStack { ProgressView(); Text(L10n.text(.reading_health)) } }
                }
                if let result {
                    Section(L10n.text(.latest_batch)) {
                        LabeledContent(L10n.text(.heart_rate_samples), value: "\(result.heartRate.count)")
                        LabeledContent(L10n.text(.step_samples), value: "\(result.steps.count)")
                        LabeledContent(L10n.text(.weight_samples), value: "\(result.weightKilograms.count)")
                        LabeledContent(L10n.text(.deleted_sample_references), value: "\(result.deletedSampleIDs.count)")
                    }
                }
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(Color.vezunaDanger) }
                }
            }
            .navigationTitle(L10n.text(.health_import))
        }
    }

    @MainActor
    private func importHealth() async {
        guard let importer, let profileId = IosRuntime.shared.currentProfileId() else { return }
        busy = true
        errorMessage = nil
        defer { busy = false }
        do {
            try await importer.authorizeReadOnly()
            result = try await importer.importIncremental(profileId: profileId)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
