import XCTest
import VezunaShared
@testable import Vezuna

final class VezunaTests: XCTestCase {
    func testRecordsAndExportsNeverDependOnPremiumOrModels() {
        XCTAssertTrue(FeaturePolicy.recordsAccessible)
        XCTAssertTrue(FeaturePolicy.exportAccessible)
        XCTAssertFalse(FeaturePolicy.canActivateModel(premiumActive: false))
        XCTAssertFalse(FeaturePolicy.canUseAI(premiumActive: true, modelLoaded: false))
        XCTAssertFalse(FeaturePolicy.canUseAI(premiumActive: false, modelLoaded: true))
        XCTAssertTrue(FeaturePolicy.canUseAI(premiumActive: true, modelLoaded: true))
    }

    func testCryptoKitRoundTripAndTamperFailure() throws {
        let key = Data((0..<32).map(UInt8.init))
        let plaintext = Data("synthetic profile payload".utf8)
        let aad = Data("synthetic-profile-v1".utf8)
        let encrypted = try CryptoKitAESGCM.encrypt(key: key, plaintext: plaintext, associatedData: aad)
        XCTAssertNotEqual(encrypted.ciphertextAndTag, plaintext)
        XCTAssertEqual(try CryptoKitAESGCM.decrypt(key: key, value: encrypted), plaintext)

        var tamperedBytes = encrypted.ciphertextAndTag
        tamperedBytes[0] ^= 0x01
        let tampered = AESGCMValue(nonce: encrypted.nonce, ciphertextAndTag: tamperedBytes, associatedData: aad)
        XCTAssertThrowsError(try CryptoKitAESGCM.decrypt(key: key, value: tampered))

        let wrongAAD = AESGCMValue(nonce: encrypted.nonce, ciphertextAndTag: encrypted.ciphertextAndTag, associatedData: Data("other-profile".utf8))
        XCTAssertThrowsError(try CryptoKitAESGCM.decrypt(key: key, value: wrongAAD))
    }

    // Golden backup made by Python hashlib + cryptography: opens only if the real iOS PBKDF2 (CommonCrypto),
    // NFC normalization and AES-GCM (CryptoKit bridge) match the other platforms byte for byte.
    private static let goldenBackup = "VlpCSwEBAAknwBAAAQIDBAUGBwgJCgsMDQ4PDKChoqOkpaanqKmqqwjkjnhrpA8RLx9TKTMNw8PQY7w+8Ldn5Qd8la/2CrEk3O4sdjDpOKpUCmJe8G9x12dKuOY4RshQ2uOa6AR7yhZmNnrKqgDxo6e8dUn2SjMwxtCOAnziTp3CGXPuEmnMiTP7pdYwaWMR6jkYX5jJqcZoXCX7wPiCtmhLrI41V/F1gP69lkMMssxc26MboTDb4diQE6jrNiNqJw6wNrnCC+PuUhtOyMPloIubISU4w/C20UlRzSBGJVun2COqzDF53F2I4mC0EzzbsndQOOfbpwA="

    private func chars(_ value: String) -> KotlinCharArray {
        let units = Array(value.utf16)
        let array = KotlinCharArray(size: Int32(units.count))
        for (index, unit) in units.enumerated() { array.set(index: Int32(index), value: unit) }
        return array
    }

    func testGoldenBackupOpensThroughTheIosActuals() throws {
        IosAesGcmBridge.shared.install(implementation: CryptoKitAesGcm())
        let file = Data(base64Encoded: Self.goldenBackup)!.kotlinBytes
        let nfc = "p\u{00E4}ssw\u{00F6}rd correct horse"
        let nfd = "pa\u{0308}sswo\u{0308}rd correct horse"
        XCTAssertNotEqual(Array(nfc.unicodeScalars), Array(nfd.unicodeScalars))
        for passphrase in [nfc, nfd] {
            let plain = try PassphraseBackupCodec.shared.open(file: file, passphrase: chars(passphrase)).toData()
            XCTAssertTrue(String(decoding: plain, as: UTF8.self).contains("golden-profile"))
        }
        XCTAssertThrowsError(try PassphraseBackupCodec.shared.open(file: file, passphrase: chars("not the passphrase")))
    }

    func testDailyAndWeeklyReminderPlanningAndSnooze() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 10, day: 30, hour: 7, minute: 0)))
        let dailySchedule = NativeDoseSchedule(
            profileId: "profile-a", scheduleId: "daily", medicationId: "med-a", recurrence: "DAILY", intervalDays: 1,
            daysOfWeek: [], startDate: "2026-10-29", endDate: nil, timesLocal: ["08:30"]
        )
        let weeklySchedule = NativeDoseSchedule(
            profileId: "profile-a", scheduleId: "weekly", medicationId: "med-b", recurrence: "WEEKLY", intervalDays: 1,
            daysOfWeek: ["MONDAY"], startDate: "2026-10-29", endDate: nil, timesLocal: ["09:00"]
        )
        let medications = [
            NativeMedication(medicationId: "med-a", displayName: "Synthetic A", strength: nil, instructions: nil, schedules: [dailySchedule], active: true),
            NativeMedication(medicationId: "med-b", displayName: "Synthetic B", strength: nil, instructions: nil, schedules: [weeklySchedule], active: true)
        ]
        let reminders = NotificationPlanner(calendar: calendar).reminders(for: medications, after: now, horizonDays: 7)
        XCTAssertEqual(reminders.filter { $0.scheduleId == "daily" }.count, 7)
        XCTAssertEqual(reminders.filter { $0.scheduleId == "weekly" }.count, 1)
        XCTAssertEqual(calendar.component(.weekday, from: try XCTUnwrap(reminders.first { $0.scheduleId == "weekly" }?.scheduledAt)), 2)
        let snoozed = NotificationPlanner(calendar: calendar).snooze(reminders[0], from: now)
        XCTAssertEqual(snoozed.scheduledAt.timeIntervalSince(now), 600, accuracy: 0.001)
    }

    func testProtectedFilePersistenceAndBackupExclusion() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("selection.txt")
        try AppFileStorage.writeAtomically(Data("model-a".utf8), to: file)
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "model-a")
        let values = try file.resourceValues(forKeys: [.isExcludedFromBackupKey, .fileProtectionKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
        XCTAssertEqual(values.fileProtection, .complete)
    }

    func testOfflineLicenseCacheGraceAndExpiration() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("json")
        defer { try? FileManager.default.removeItem(at: file) }
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        try AppFileStorage.writeAtomically(try JSONEncoder().encode(CachedLicense(lastVerifiedAt: now.addingTimeInterval(-3600))), to: file)
        let active = LicenseManager(cacheURL: file, now: { now })
        XCTAssertTrue(active.isPremiumActive())
        XCTAssertEqual(active.state, .activeOfflineUntil(now.addingTimeInterval(LicenseManager.offlineGrace - 3600)))

        let expired = LicenseManager(cacheURL: file, now: { now.addingTimeInterval(LicenseManager.offlineGrace + 1) })
        XCTAssertFalse(expired.isPremiumActive())
        XCTAssertEqual(expired.state, .inactive)
    }
}
