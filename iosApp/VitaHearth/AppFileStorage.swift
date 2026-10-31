import Foundation

enum AppFileStorage {
    static func applicationSupportDirectory() throws -> URL {
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let directory = root.appendingPathComponent("VitaHearth", isDirectory: true)
        try preparePrivateDirectory(directory)
        return directory
    }

    static func preparePrivateDirectory(_ url: URL) throws {
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        try protectAndExclude(url)
    }

    static func protectAndExclude(_ url: URL) throws {
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutable = url
        try mutable.setResourceValues(values)
    }

    static func writeAtomically(_ data: Data, to url: URL) throws {
        try preparePrivateDirectory(url.deletingLastPathComponent())
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        try protectAndExclude(url)
    }

    static func temporaryExportURL(fileName: String, data: Data) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try preparePrivateDirectory(directory)
        let safeName = URL(fileURLWithPath: fileName).lastPathComponent
        let url = directory.appendingPathComponent(safeName)
        try writeAtomically(data, to: url)
        return url
    }
}
