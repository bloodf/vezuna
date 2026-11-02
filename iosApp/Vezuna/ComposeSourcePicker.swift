import AVFoundation
import PhotosUI
import UIKit
import UniformTypeIdentifiers
import VezunaShared

/// The iOS half of Add a record: presents the real system camera, photo picker or document picker, extracts text
/// on the device, and hands a source with review drafts to the shared Compose review. It has no review UI of its own.
final class ComposeSourcePicker: NSObject, NativeSourcePicker {
    private static let maxPhotos = 10
    private static let maxBytes = 64 * 1024 * 1024

    private var completion: ((PickedSource?, String?, String?) -> Void)?
    /// Camera pages of one report, in capture order.
    private var photos: [Data] = []

    func pickSource(kind: String, completion: @escaping (PickedSource?, String?, String?) -> Void) {
        guard self.completion == nil else { completion(nil, nil, nil); return }
        self.completion = completion
        photos = []
        DispatchQueue.main.async {
            switch kind {
            case "CAMERA": self.startCamera()
            case "PHOTOS": self.presentPhotos()
            default: self.presentFiles()
            }
        }
    }

    // MARK: Camera

    private func startCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else { return fail("CAMERA_UNAVAILABLE") }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            presentCamera()
        case .notDetermined:
            // The system permission prompt (image 034); a refusal is reported, not hidden.
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async { granted ? self.presentCamera() : self.fail("CAMERA_DENIED") }
            }
        default:
            fail("CAMERA_DENIED")
        }
    }

    private func presentCamera() {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = self
        present(picker)
    }

    /// System alert between shots: another page of the same report, or done.
    private func askForAnotherPhoto() {
        let alert = UIAlertController(title: L10n.text(.camera), message: L10n.text(.pages_in_report, photos.count), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L10n.text(.add_page), style: .default) { _ in self.presentCamera() })
        alert.addAction(UIAlertAction(title: L10n.text(.photos_done), style: .cancel) { _ in self.extractPhotos(self.photos) })
        present(alert)
    }

    // MARK: Photos and files

    private func presentPhotos() {
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = Self.maxPhotos
        configuration.selection = .ordered
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = self
        present(picker)
    }

    private func presentFiles() {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf, .image], asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        present(picker)
    }

    // MARK: Extraction

    private func extractPhotos(_ pages: [Data]) {
        guard !pages.isEmpty else { return finish(nil) }
        let name = pages.count == 1 ? "lab-image.jpg" : "lab-photos-\(pages.count).jpg"
        extract(name: name, mediaType: "image/jpeg", files: pages, texts: { try await Self.texts(pages) })
    }

    private static func texts(_ pages: [Data]) async throws -> ExtractedSourceText {
        var texts: [String] = []
        var confidences: [Float] = []
        for page in pages {
            let extracted = try await SourceTextExtractor.extract(data: page, mediaType: "image/jpeg")
            texts += extracted.pages
            if let confidence = extracted.ocrConfidence { confidences.append(confidence) }
        }
        let mean = confidences.isEmpty ? nil : confidences.reduce(0, +) / Float(confidences.count)
        return ExtractedSourceText(pages: texts, ocrConfidence: mean, usedOfflineOCR: true)
    }

    private func extract(name: String, mediaType: String, files: [Data], texts: @escaping () async throws -> ExtractedSourceText) {
        IosRuntime.shared.importReading(fileName: name)
        Task { @MainActor in
            do {
                let extracted = try await texts()
                let source = IosRuntime.shared.extractedSource(
                    fileName: name,
                    mediaType: mediaType,
                    pages: files.map { $0.kotlinBytes },
                    pageTexts: extracted.pages,
                    embeddedText: extracted.pages.map { _ in KotlinBoolean(bool: !extracted.usedOfflineOCR) },
                    ocrConfidence: extracted.ocrConfidence.map { KotlinFloat(float: $0) }
                )
                finish(source)
            } catch {
                fail("UNREADABLE", name)
            }
        }
    }

    // MARK: Completion

    private func finish(_ source: PickedSource?) {
        let done = completion
        completion = nil
        photos = []
        done?(source, nil, nil)
    }

    private func fail(_ reason: String, _ fileName: String? = nil) {
        let done = completion
        completion = nil
        photos = []
        done?(nil, reason, fileName)
    }

    private func present(_ controller: UIViewController) {
        guard let presenter = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController?.topMost else {
            return finish(nil)
        }
        presenter.present(controller, animated: true)
    }

    /// Lab photos are stored as JPEG so every reader of the vault can open them.
    private static func jpeg(_ data: Data) -> Data? {
        UIImage(data: data)?.jpegData(compressionQuality: 0.92)
    }
}

extension ComposeSourcePicker: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true) { self.extractPhotos(self.photos) }
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        let data = (info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.92)
        picker.dismiss(animated: true) {
            guard let data else { return self.fail("UNREADABLE") }
            self.photos.append(data)
            if self.photos.count >= Self.maxPhotos { self.extractPhotos(self.photos) } else { self.askForAnotherPhoto() }
        }
    }
}

extension ComposeSourcePicker: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard !results.isEmpty else { return finish(nil) }
        Task { @MainActor in
            var pages: [Data] = []
            for result in results.prefix(Self.maxPhotos) {
                let raw: Data? = await withCheckedContinuation { continuation in
                    result.itemProvider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                        continuation.resume(returning: data)
                    }
                }
                guard let raw, let page = Self.jpeg(raw) else { return fail("UNREADABLE") }
                guard page.count <= Self.maxBytes else { return fail("TOO_LARGE") }
                pages.append(page)
            }
            extractPhotos(pages)
        }
    }
}

extension ComposeSourcePicker: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return finish(nil) }
        let name = url.lastPathComponent
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size <= Self.maxBytes else { return fail("TOO_LARGE", name) }
        guard let data = try? Data(contentsOf: url) else { return fail("UNREADABLE", name) }
        let type = UTType(filenameExtension: url.pathExtension)
        if type?.conforms(to: .pdf) == true {
            extract(name: name, mediaType: "application/pdf", files: [data]) {
                try await SourceTextExtractor.extract(data: data, mediaType: "application/pdf")
            }
        } else {
            guard let page = Self.jpeg(data) else { return fail("UNREADABLE", name) }
            extract(name: name, mediaType: "image/jpeg", files: [page]) {
                try await SourceTextExtractor.extract(data: page, mediaType: "image/jpeg")
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finish(nil)
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
