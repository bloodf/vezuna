import SwiftUI
import UIKit
import UniformTypeIdentifiers
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
    /// Per-export language override; empty follows the device language.
    @State private var exportLanguage = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L10n.text(.export_always_available))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section(L10n.text(.export_language)) {
                    Picker(L10n.text(.export_language), selection: $exportLanguage) {
                        Text(L10n.text(.export_language_device)).tag("")
                        Text("English").tag("en")
                        Text("Português").tag("pt")
                        Text("Español").tag("es")
                    }
                    .pickerStyle(.segmented)
                }
                Section(L10n.text(.doctor_export)) {
                    Button { exportCSV() } label: { Label(L10n.text(.share_save_csv), systemImage: "tablecells") }
                    Button { exportPDF() } label: { Label(L10n.text(.share_save_pdf), systemImage: "doc.richtext") }
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
        IosRuntime.shared.exportCsv(languageTag: exportLanguage) { csv, error in
            guard let csv else { self.fail(error); return }
            self.present(data: Data(csv.utf8), fileName: "vezuna-doctor-export.csv")
        }
    }

    private func exportPDF() {
        begin()
        IosRuntime.shared.exportPdf(languageTag: exportLanguage) { pdf, error in
            guard let pdf else { self.fail(error); return }
            self.present(data: pdf.bytes.toData(), fileName: pdf.fileName)
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

/// Native file access for portable backups, called from the shared Compose settings and recovery screens.
final class ComposeBackupFiles: NSObject, NativeBackupFiles {
    private var pickCompletion: ((KotlinByteArray?) -> Void)?

    func pickBackup(completion: @escaping (KotlinByteArray?) -> Void) {
        DispatchQueue.main.async {
            guard self.pickCompletion == nil, let presenter = Self.presenter else { completion(nil); return }
            self.pickCompletion = completion
            // Backups carry no registered type, so any file can be chosen; the shared code validates the content.
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: true)
            picker.delegate = self
            picker.allowsMultipleSelection = false
            presenter.present(picker, animated: true)
        }
    }

    func saveBackup(fileName: String, bytes: KotlinByteArray, completion: @escaping (KotlinBoolean) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = Self.presenter,
                  let url = try? AppFileStorage.temporaryExportURL(fileName: fileName, data: bytes.toData()) else {
                completion(KotlinBoolean(bool: false))
                return
            }
            let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            sheet.completionWithItemsHandler = { _, completed, _, _ in
                try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
                completion(KotlinBoolean(bool: completed))
            }
            sheet.popoverPresentationController?.sourceView = presenter.view
            presenter.present(sheet, animated: true)
        }
    }

    private static var presenter: UIViewController? {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController?.topMost
    }
}

extension ComposeBackupFiles: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        defer {
            // asCopy: the picker leaves a temporary copy of the (possibly private) backup behind.
            urls.forEach { try? FileManager.default.removeItem(at: $0) }
            pickCompletion = nil
        }
        guard let url = urls.first else { pickCompletion?(nil); return }
        let limit = 64 * 1024 * 1024
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? Int.max
        // An empty array tells the shared flow the file is unreadable or too large, before it is read into memory.
        guard size <= limit, let data = try? Data(contentsOf: url) else { pickCompletion?(KotlinByteArray(size: 0)); return }
        pickCompletion?(data.kotlinBytes)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        pickCompletion?(nil)
        pickCompletion = nil
    }
}
