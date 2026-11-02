import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import VezunaShared

struct NativeImportView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var photoItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var showFiles = false
    @State private var busy = false
    @State private var saving = false
    @State private var errorMessage: String?
    @State private var sourceData: Data?
    @State private var sourceName = ""
    @State private var sourceMediaType = ""
    /// Further camera photos of the same report; the first photo is `sourceData`.
    @State private var extraPages: [Data] = []
    @State private var appendingPage = false
    @State private var laboratoryName = ""
    @State private var orderedAt: Date?
    /// True while `orderedAt` came from the page text (noon UTC), so it is stored as read instead of re-derived from the local calendar.
    @State private var orderedFromHeader = false
    @State private var editingOrderDate = false
    @State private var draftOrderDate = Date()
    @State private var candidates: [ImportedLabCandidate] = []
    @State private var extractionNote = ""
    @State private var editingDateId: UUID?
    @State private var draftDate = Date()

    var body: some View {
        NavigationStack {
            List {
                Section(L10n.text(.source)) {
                    PhotosPicker(selection: $photoItem, matching: .images, photoLibrary: .shared()) {
                        Label(L10n.text(.photo_library), systemImage: "photo.on.rectangle")
                    }
                    Button {
                        if UIImagePickerController.isSourceTypeAvailable(.camera) { showCamera = true }
                        else { errorMessage = ImportError.cameraUnavailable.localizedDescription }
                    } label: { Label(L10n.text(.camera), systemImage: "camera") }
                    Button { showFiles = true } label: { Label(L10n.text(.pdf_or_image), systemImage: "doc") }
                }
                if busy {
                    Section { HStack { ProgressView(); Text(L10n.text(.extracting_text)) } }
                }
                if !extractionNote.isEmpty {
                    Section { Text(extractionNote).font(.footnote).foregroundStyle(.secondary) }
                }
                if !candidates.isEmpty {
                    Section {
                        TextField(L10n.text(.laboratory_name), text: $laboratoryName)
                        let orderLabel = orderedAt.map { $0.formatted(date: .abbreviated, time: .omitted) }
                        Button {
                            draftOrderDate = orderedAt ?? Date()
                            editingOrderDate = true
                        } label: {
                            Text("\(L10n.text(.order_date)): \(orderLabel ?? "-")")
                        }
                        .accessibilityLabel(L10n.text(.order_date))
                        .accessibilityValue(orderLabel ?? "")
                        .sheet(isPresented: $editingOrderDate) {
                            NavigationStack {
                                DatePicker(L10n.text(.order_date), selection: $draftOrderDate, in: ...Date(), displayedComponents: .date)
                                    .datePickerStyle(.graphical)
                                    .padding()
                                    .navigationTitle(L10n.text(.order_date))
                                    .toolbar {
                                        ToolbarItem(placement: .cancellationAction) { Button(L10n.text(.close)) { editingOrderDate = false } }
                                        ToolbarItem(placement: .confirmationAction) {
                                            Button(L10n.text(.choose_date_button)) { orderedAt = draftOrderDate; orderedFromHeader = false; editingOrderDate = false }
                                        }
                                    }
                            }
                            .presentationDetents([.medium, .large])
                        }
                        if orderedAt != nil {
                            Button(L10n.text(.clear_date)) { orderedAt = nil }
                        }
                        if !extraPages.isEmpty {
                            Text(L10n.text(.pages_in_report, extraPages.count + 1)).font(.footnote).foregroundStyle(.secondary)
                        }
                        if sourceMediaType == "image/jpeg", extraPages.count < 9, UIImagePickerController.isSourceTypeAvailable(.camera) {
                            Button { appendingPage = true; showCamera = true } label: { Label(L10n.text(.add_page), systemImage: "camera.badge.ellipsis") }
                        }
                    }
                    Section(L10n.text(.review_extracted_results)) {
                        ForEach($candidates) { $candidate in
                            VStack(alignment: .leading, spacing: 8) {
                                TextField(L10n.text(.test_code), text: $candidate.testCode)
                                TextField(L10n.text(.test_name), text: $candidate.displayName)
                                HStack {
                                    TextField(L10n.text(.value), text: $candidate.rawValue).keyboardType(.decimalPad)
                                    TextField(L10n.text(.unit), text: $candidate.rawUnit)
                                }
                                HStack {
                                    TextField(L10n.text(.reference_lower), text: $candidate.referenceLower).keyboardType(.decimalPad)
                                    TextField(L10n.text(.reference_upper), text: $candidate.referenceUpper).keyboardType(.decimalPad)
                                }
                                TextField(L10n.text(.reference_text), text: $candidate.referenceText)
                                if let token = candidate.rawToken {
                                    Text(L10n.text(.ocr_read_token, token)).font(.subheadline)
                                    Text(L10n.text(.ambiguous_number_warning)).font(.footnote).foregroundStyle(Color.vezunaDanger)
                                }
                                let dateLabel = candidate.collectedAt.map { $0.formatted(date: .abbreviated, time: .omitted) }
                                Button {
                                    draftDate = candidate.collectedAt ?? Date()
                                    editingDateId = candidate.id
                                } label: {
                                    Text("\(L10n.text(.collection_date)): \(dateLabel ?? "-")")
                                }
                                .accessibilityLabel(L10n.text(.collection_date))
                                .accessibilityValue(dateLabel ?? "")
                                .accessibilityHint(L10n.text(.choose_date_button))
                                if let chosen = candidate.collectedAt {
                                    Button(L10n.text(.apply_date_to_all)) { applyDateToAll(chosen) }
                                }
                            }
                            .padding(.vertical, 4)
                        }
                        if candidates.contains(where: { $0.collectedAt == nil }) {
                            Text(L10n.text(.collection_date_required)).font(.footnote).foregroundStyle(Color.vezunaDanger)
                        }
                        Button(L10n.text(.add_unconfirmed_review)) { save() }
                            .disabled(saving || candidates.contains(where: { $0.collectedAt == nil }))
                    }
                }
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(Color.vezunaDanger) }
                }
            }
            .navigationTitle(L10n.text(.offline_import))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.text(.close)) { dismiss() } } }
            .disabled(saving)
            .sheet(isPresented: Binding(get: { editingDateId != nil }, set: { if !$0 { editingDateId = nil } })) {
                NavigationStack {
                    DatePicker(L10n.text(.collection_date), selection: $draftDate, in: ...Date(), displayedComponents: .date)
                        .datePickerStyle(.graphical)
                        .padding()
                        .navigationTitle(L10n.text(.collection_date))
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button(L10n.text(.close)) { editingDateId = nil } }
                            ToolbarItem(placement: .confirmationAction) {
                                Button(L10n.text(.choose_date_button)) {
                                    if let id = editingDateId, let index = candidates.firstIndex(where: { $0.id == id }) {
                                        candidates[index].collectedAt = draftDate
                                    }
                                    editingDateId = nil
                                }
                            }
                        }
                }
                .presentationDetents([.medium, .large])
            }
            .overlay { if saving { ProgressView(L10n.text(.encrypting_saving)) } }
            .onChange(of: photoItem) { item in
                guard let item else { return }
                Task {
                    do {
                        guard let data = try await item.loadTransferable(type: Data.self) else { throw ImportError.unreadableImage }
                        await process(data: data, name: "Photo-\(Date().timeIntervalSince1970).jpg", mediaType: "image/jpeg")
                    } catch { showError(error) }
                }
            }
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.pdf, .image]) { result in
                do {
                    let url = try result.get()
                    let accessing = url.startAccessingSecurityScopedResource()
                    defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                    let data = try Data(contentsOf: url)
                    let type = UTType(filenameExtension: url.pathExtension)
                    let mediaType = type?.conforms(to: .pdf) == true ? "application/pdf" : (type?.preferredMIMEType ?? "image/*")
                    Task { await process(data: data, name: url.lastPathComponent, mediaType: mediaType) }
                } catch { errorMessage = error.localizedDescription }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraCaptureView { result in
                    showCamera = false
                    switch result {
                    case .success(let data):
                        let appending = appendingPage
                        Task { await process(data: data, name: "Camera-\(Date().timeIntervalSince1970).jpg", mediaType: "image/jpeg", appending: appending) }
                    case .failure(let error):
                        if (error as? CocoaError)?.code != .userCancelled { errorMessage = error.localizedDescription }
                    }
                    appendingPage = false
                }
                .ignoresSafeArea()
            }
        }
    }

    @MainActor
    private func process(data: Data, name: String, mediaType: String, appending: Bool = false) async {
        busy = true
        errorMessage = nil
        defer { busy = false }
        do {
            let extracted = try await SourceTextExtractor.extract(data: data, mediaType: mediaType)
            let parsed = LabTextBridge.shared.parse(text: extracted.text, ocrConfidence: extracted.ocrConfidence.map { KotlinFloat(float: $0) })
                .map(ImportedLabCandidate.init(parsed:))
            guard !parsed.isEmpty else { throw ImportError.noStructuredResults }
            let header = LabTextBridge.shared.parseHeader(text: extracted.text)
            if appending, sourceData != nil {
                extraPages.append(data)
                candidates += parsed.filter { row in !candidates.contains { $0.testCode == row.testCode && $0.rawValue == row.rawValue && $0.rawUnit == row.rawUnit && $0.collectedAt == row.collectedAt } }
            } else {
                sourceData = data
                sourceName = name
                sourceMediaType = mediaType
                extraPages = []
                candidates = parsed
                laboratoryName = ""
                orderedAt = nil
                orderedFromHeader = false
            }
            if laboratoryName.isEmpty, let found = header.laboratoryName { laboratoryName = found }
            if orderedAt == nil, let seconds = header.orderedEpochSeconds { orderedAt = Date(timeIntervalSince1970: TimeInterval(truncating: seconds)); orderedFromHeader = true }
            extractionNote = L10n.plural(extracted.usedOfflineOCR ? .extraction_results_ocr : .extraction_results_embedded, parsed.count)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @MainActor
    private func showError(_ error: Error) { errorMessage = error.localizedDescription }

    private func applyDateToAll(_ date: Date?) {
        guard let date else { return }
        candidates = candidates.map { var copy = $0; copy.collectedAt = date; return copy }
    }

    /// Noon UTC of the user's chosen local calendar day, so the day is stable in every time zone.
    private func storedInstant(_ date: Date) -> Int64 {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        let noon = utc.date(from: DateComponents(year: parts.year, month: parts.month, day: parts.day, hour: 12)) ?? date
        return Int64(noon.timeIntervalSince1970)
    }

    private func save() {
        guard let sourceData, let profileId = IosRuntime.shared.currentProfileId() else { return }
        let dated = candidates.compactMap { candidate in candidate.collectedAt.map { (candidate, $0) } }
        guard dated.count == candidates.count else { errorMessage = L10n.text(.collection_date_required); return }
        saving = true
        let input = dated.map { candidate, date in
            NativeCandidateInput(
                testCode: candidate.testCode,
                displayName: candidate.displayName,
                rawValue: candidate.rawValue,
                rawUnit: candidate.rawUnit,
                referenceLower: candidate.referenceLower,
                referenceUpper: candidate.referenceUpper,
                confidence: candidate.confidence.map { KotlinFloat(float: $0) },
                collectedEpochSeconds: storedInstant(date),
                rawToken: candidate.rawToken,
                referenceText: candidate.referenceText.isEmpty ? nil : candidate.referenceText,
                referenceUnit: candidate.referenceUnit,
                qualitative: candidate.qualitative
            )
        }
        IosRuntime.shared.importNativeCandidates(
            profileId: profileId,
            fileName: sourceName,
            mediaType: sourceMediaType,
            sourceBytes: sourceData.kotlinBytes,
            candidates: input,
            additionalPages: extraPages.map { $0.kotlinBytes },
            laboratoryName: laboratoryName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : laboratoryName,
            orderedEpochSeconds: orderedAt.map { KotlinLong(value: orderedFromHeader ? Int64($0.timeIntervalSince1970) : storedInstant($0)) }
        ) { error in
            DispatchQueue.main.async {
                saving = false
                if let error { errorMessage = error.localized() } else { dismiss() }
            }
        }
    }
}

struct CameraCaptureView: UIViewControllerRepresentable {
    let completion: (Result<Data, Error>) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(completion: completion) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let completion: (Result<Data, Error>) -> Void
        init(completion: @escaping (Result<Data, Error>) -> Void) { self.completion = completion }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            completion(.failure(CocoaError(.userCancelled)))
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            guard let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.92) else {
                completion(.failure(ImportError.unreadableImage))
                return
            }
            completion(.success(data))
        }
    }
}

final class ComposeSourcePicker: NSObject, NativeSourcePicker {
    private var completion: ((PickedSource?) -> Void)?

    func pickSource(completion: @escaping (PickedSource?) -> Void) {
        guard self.completion == nil else { completion(nil); return }
        self.completion = completion
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf, .image], asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        guard let presenter = topViewController else {
            self.completion = nil
            completion(nil)
            return
        }
        presenter.present(picker, animated: true)
    }

    private var topViewController: UIViewController? {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController?.topMost
    }
}

extension ComposeSourcePicker: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        defer { completion = nil }
        guard let url = urls.first else { completion?(nil); return }
        do {
            let data = try Data(contentsOf: url)
            let type = UTType(filenameExtension: url.pathExtension)
            let mediaType = type?.conforms(to: .pdf) == true ? "application/pdf" : (type?.preferredMIMEType ?? "image/*")
            completion?(PickedSource(fileName: url.lastPathComponent, mediaType: mediaType, bytes: data.kotlinBytes, candidates: [], laboratoryName: nil, orderedAt: nil, additionalPages: []))
        } catch {
            completion?(nil)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        completion?(nil)
        completion = nil
    }
}

extension UIViewController {
    var topMost: UIViewController {
        if let presented = presentedViewController { return presented.topMost }
        if let navigation = self as? UINavigationController { return navigation.visibleViewController?.topMost ?? navigation }
        if let tabs = self as? UITabBarController { return tabs.selectedViewController?.topMost ?? tabs }
        return self
    }
}
