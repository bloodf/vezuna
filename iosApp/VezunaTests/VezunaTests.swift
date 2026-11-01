import XCTest
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

    func testSyntheticLabTextParsing() {
        let text = """
        Hemoglobin 13.2 g/dL 12.0-15.5
        WBC: 6.4 10^9/L (4.0–11.0)
        HbA1c 5,7 % 4,0-6,0
        Patient 42 years
        """
        let results = LabTextParser.parse(text, confidence: 0.91)
        XCTAssertEqual(results.count, 3)
        XCTAssertEqual(results[0].testCode, "HGB")
        XCTAssertEqual(results[0].rawValue, "13.2")
        XCTAssertEqual(results[0].referenceLower, "12.0")
        XCTAssertEqual(results[0].referenceUpper, "15.5")
        XCTAssertEqual(results[1].testCode, "WBC")
        XCTAssertEqual(results[1].referenceUpper, "11.0")
        XCTAssertEqual(results[2].testCode, "HBA1C")
        XCTAssertEqual(results[2].rawValue, "5.7")
        XCTAssertEqual(results[2].confidence, 0.91)
    }

    func testPtBrThousandsSeparatorsAreNotMisread() {
        let cases: [(String, String)] = [
            ("250.000", "250000"), ("1.234,5", "1234.5"), ("5,7", "5.7"), ("13.2", "13.2"),
            ("0.500", "0.500"), ("0,500", "0.500"), ("1.234.567", "1234567"), ("-1.234,5", "-1234.5")
        ]
        for (raw, expected) in cases {
            XCTAssertEqual(LabTextParser.canonicalNumber(raw), expected, raw)
        }
        let results = LabTextParser.parse("Plaquetas 250.000 /mm3 150.000-450.000\nGlucose 1.234,5 mg/dL")
        XCTAssertEqual(results.map(\.rawValue), ["250000", "1234.5"])
        XCTAssertEqual(results[0].referenceLower, "150000")
        XCTAssertEqual(results[0].referenceUpper, "450000")
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
