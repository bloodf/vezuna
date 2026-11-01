import SwiftUI
import UIKit
import VezunaShared

struct ActivityItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct NativeExportView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var activity: ActivityItem?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L10n.text(.export_always_available))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section(L10n.text(.doctor_export)) {
                    Button { exportCSV() } label: { Label(L10n.text(.share_save_csv), systemImage: "tablecells") }
                    Button { exportPDF() } label: { Label(L10n.text(.share_save_pdf), systemImage: "doc.richtext") }
                }
                Section(L10n.text(.profile_backup)) {
                    Button { exportBackup() } label: { Label(L10n.text(.share_save_encrypted_backup), systemImage: "lock.doc") }
                    Text(L10n.text(.backup_profile_description))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(Color.vezunaDanger) }
                }
            }
            .navigationTitle(L10n.text(.export_title))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.text(.close)) { dismiss() } } }
            .disabled(busy)
            .overlay { if busy { ProgressView(L10n.text(.preparing_device)) } }
            .sheet(item: $activity) { item in
                ActivityViewController(activityItems: [item.url]).ignoresSafeArea()
            }
        }
    }

    private func exportCSV() {
        begin()
        IosRuntime.shared.exportCsv { csv, error in
            guard let csv else { self.fail(error); return }
            self.present(data: Data(csv.utf8), fileName: "vezuna-doctor-export.csv")
        }
    }

    private func exportPDF() {
        begin()
        IosRuntime.shared.exportPdf { pdf, error in
            guard let pdf else { self.fail(error); return }
            self.present(data: pdf.bytes.toData(), fileName: pdf.fileName)
        }
    }

    private func exportBackup() {
        begin()
        IosRuntime.shared.exportBackup { bytes, error in
            guard let bytes else { self.fail(error); return }
            self.present(data: bytes.toData(), fileName: "vezuna-profile-backup.vezunabackup")
        }
    }

    private func begin() {
        busy = true
        errorMessage = nil
    }

    private func present(data: Data, fileName: String) {
        DispatchQueue.main.async {
            do {
                activity = ActivityItem(url: try AppFileStorage.temporaryExportURL(fileName: fileName, data: data))
                busy = false
            } catch {
                busy = false
                errorMessage = L10n.text(.export_prepare_failed)
            }
        }
    }

    private func fail(_ message: NativeMessage?) {
        DispatchQueue.main.async {
            busy = false
            errorMessage = message?.localized() ?? L10n.text(.export_prepare_failed)
        }
    }
}

struct ActivityViewController: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
