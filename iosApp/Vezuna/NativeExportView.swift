import SwiftUI
import UIKit
import UniformTypeIdentifiers
import VezunaShared

/// System save and share sheets for the shared Compose export preview. Both receive the exact bytes the preview
/// rendered; the temporary copy is protected, excluded from backup and removed when the sheet closes.
final class ComposeExportFiles: NSObject, NativeExportFiles {
    private var saveCompletion: ((String) -> Void)?
    private var savedURL: URL?

    func saveExport(fileName: String, bytes: KotlinByteArray, completion: @escaping (String) -> Void) {
        DispatchQueue.main.async {
            guard self.saveCompletion == nil, let presenter = ComposeExportFiles.presenter,
                  let url = try? AppFileStorage.temporaryExportURL(fileName: fileName, data: bytes.toData()) else {
                completion("failed")
                return
            }
            self.saveCompletion = completion
            self.savedURL = url
            let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
            picker.delegate = self
            presenter.present(picker, animated: true)
        }
    }

    func shareExport(fileName: String, bytes: KotlinByteArray, completion: @escaping (KotlinBoolean) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = ComposeExportFiles.presenter,
                  let url = try? AppFileStorage.temporaryExportURL(fileName: fileName, data: bytes.toData()) else {
                completion(KotlinBoolean(bool: false))
                return
            }
            let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            sheet.completionWithItemsHandler = { _, _, _, _ in
                try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
            }
            sheet.popoverPresentationController?.sourceView = presenter.view
            presenter.present(sheet, animated: true)
            completion(KotlinBoolean(bool: true))
        }
    }

    private func finish(_ result: String) {
        if let url = savedURL { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        savedURL = nil
        saveCompletion?(result)
        saveCompletion = nil
    }

    fileprivate static var presenter: UIViewController? {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController?.topMost
    }
}

extension ComposeExportFiles: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        finish(urls.isEmpty ? "failed" : "saved")
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finish("cancelled")
    }
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
