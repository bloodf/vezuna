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
    @State private var candidates: [ImportedLabCandidate] = []
    @State private var extractionNote = ""

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
                            }
                            .padding(.vertical, 4)
                        }
                        Button(L10n.text(.add_unconfirmed_review)) { save() }.disabled(saving)
                    }
                }
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(Color.vezunaDanger) }
                }
            }
            .navigationTitle(L10n.text(.offline_import))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.text(.close)) { dismiss() } } }
            .disabled(saving)
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
                        Task { await process(data: data, name: "Camera-\(Date().timeIntervalSince1970).jpg", mediaType: "image/jpeg") }
                    case .failure(let error): errorMessage = error.localizedDescription
                    }
                }
                .ignoresSafeArea()
            }
        }
    }

    @MainActor
    private func process(data: Data, name: String, mediaType: String) async {
        busy = true
        errorMessage = nil
        defer { busy = false }
        do {
            let extracted = try await SourceTextExtractor.extract(data: data, mediaType: mediaType)
            let parsed = LabTextParser.parse(extracted.text, confidence: extracted.ocrConfidence)
            guard !parsed.isEmpty else { throw ImportError.noStructuredResults }
            sourceData = data
            sourceName = name
            sourceMediaType = mediaType
            candidates = parsed
            extractionNote = L10n.plural(extracted.usedOfflineOCR ? .extraction_results_ocr : .extraction_results_embedded, parsed.count)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @MainActor
    private func showError(_ error: Error) { errorMessage = error.localizedDescription }

    private func save() {
        guard let sourceData, let profileId = IosRuntime.shared.currentProfileId() else { return }
        saving = true
        let input = candidates.map {
            NativeCandidateInput(
                testCode: $0.testCode,
                displayName: $0.displayName,
                rawValue: $0.rawValue,
                rawUnit: $0.rawUnit,
                referenceLower: $0.referenceLower,
                referenceUpper: $0.referenceUpper,
                confidence: $0.confidence.map { KotlinFloat(float: $0) }
            )
        }
        IosRuntime.shared.importNativeCandidates(
            profileId: profileId,
            fileName: sourceName,
            mediaType: sourceMediaType,
            sourceBytes: sourceData.kotlinBytes,
            candidates: input,
            collectedEpochSeconds: Int64(Date().timeIntervalSince1970)
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
            completion?(PickedSource(fileName: url.lastPathComponent, mediaType: mediaType, bytes: data.kotlinBytes, candidates: []))
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
