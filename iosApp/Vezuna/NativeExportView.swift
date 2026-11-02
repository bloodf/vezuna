import SwiftUI
import UIKit
import UniformTypeIdentifiers
import VezunaShared

struct ExportTestChoice: Identifiable, Decodable {
    let code: String
    let name: String
    var id: String { code }
}

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
    @State private var fromDate = ""
    @State private var toDate = ""
    @State private var availableTests: [ExportTestChoice] = []
    @State private var selectedTests: Set<String> = []
    @State private var activeMedicationsOnly = false
    @State private var includeMedicationsCsv = false

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
                Section(L10n.text(.export_options)) {
                    TextField(L10n.text(.export_from_date), text: $fromDate)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.numbersAndPunctuation)
                    TextField(L10n.text(.export_to_date), text: $toDate)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.numbersAndPunctuation)
                    if let dateError {
                        Text(dateError).font(.footnote).foregroundStyle(Color.vezunaDanger)
                    }
                    if !availableTests.isEmpty {
                        Text(L10n.text(.export_tests)).font(.footnote).foregroundStyle(.secondary)
                        ForEach(availableTests) { test in
                            Toggle(test.name, isOn: Binding(
                                get: { selectedTests.contains(test.code) },
                                set: { isOn in if isOn { selectedTests.insert(test.code) } else { selectedTests.remove(test.code) } }
                            ))
                        }
                    }
                    Toggle(L10n.text(.export_active_medications_only), isOn: $activeMedicationsOnly)
                    Toggle(L10n.text(.export_include_medications_csv), isOn: $includeMedicationsCsv)
                }
                Section(L10n.text(.doctor_export)) {
                    Button { exportCSV() } label: { Label(L10n.text(.share_save_csv), systemImage: "tablecells") }
                        .disabled(dateError != nil)
                    Button { exportPDF() } label: { Label(L10n.text(.share_save_pdf), systemImage: "doc.richtext") }
                        .disabled(dateError != nil)
                }
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(Color.vezunaDanger) }
                }
            }
            .navigationTitle(L10n.text(.export_title))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.text(.close)) { dismiss() } } }
            .disabled(busy)
            .onAppear(perform: loadTests)
            .overlay { if busy { ProgressView(L10n.text(.preparing_device)) } }
            .sheet(item: $activity) { item in
                ActivityViewController(activityItems: [item.url]).ignoresSafeArea()
            }
        }
    }

    /// Inline validation for the optional ISO date fields; nil when both are empty or valid and not reversed.
    private var dateError: String? {
        let from = fromDate.trimmingCharacters(in: .whitespaces)
        let to = toDate.trimmingCharacters(in: .whitespaces)
        let valid = { (text: String) in text.isEmpty || text.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil }
        if !valid(from) || !valid(to) { return L10n.text(.export_date_invalid) }
        // ISO dates compare correctly as strings.
        if !from.isEmpty && !to.isEmpty && from > to { return L10n.text(.export_date_range_reversed) }
        return nil
    }

    private func loadTests() {
        IosRuntime.shared.listExportTests { json, _ in
            guard let json, let data = json.data(using: .utf8),
                  let tests = try? JSONDecoder().decode([ExportTestChoice].self, from: data) else { return }
            DispatchQueue.main.async { availableTests = tests }
        }
    }

    private func exportCSV() {
        begin()
        IosRuntime.shared.exportCsv(languageTag: exportLanguage, fromDate: fromDate, toDate: toDate, testCodes: Array(selectedTests).sorted(), activeMedicationsOnly: activeMedicationsOnly, includeMedicationsCsv: includeMedicationsCsv) { csv, error in
            guard let csv else { self.fail(error); return }
            self.present(data: Data(csv.utf8), fileName: "vezuna-doctor-export.csv")
        }
    }

    private func exportPDF() {
        begin()
        IosRuntime.shared.exportPdf(languageTag: exportLanguage, fromDate: fromDate, toDate: toDate, testCodes: Array(selectedTests).sorted(), activeMedicationsOnly: activeMedicationsOnly, includeMedicationsCsv: includeMedicationsCsv) { pdf, error in
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
