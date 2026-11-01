import XCTest
@testable import Vezuna

final class LicenseRefreshTests: XCTestCase {
    private struct Offline: Error {}
    private var clock = Date(timeIntervalSince1970: 1_800_000_000)

    private func manager(verify: @escaping () async throws -> Void = {}) -> LicenseManager {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("json")
        return LicenseManager(cacheURL: file, now: { self.clock }, verify: verify)
    }

    func testOfflineGraceIsThirtyDays() {
        XCTAssertEqual(LicenseManager.offlineGrace, 30 * 24 * 60 * 60)
    }

    func testFirstAutoRefreshIsDue() {
        XCTAssertTrue(manager().shouldAutoRefresh())
    }

    @MainActor
    func testAutoRefreshIsDebouncedAfterACompletedAttempt() async {
        var calls = 0
        let m = manager { calls += 1 }
        await m.refreshIfDue()
        await m.refreshIfDue()
        XCTAssertEqual(calls, 1)
        XCTAssertEqual(m.state, .activeVerified)
        clock = clock.addingTimeInterval(LicenseManager.autoRefreshInterval + 1)
        await m.refreshIfDue()
        XCTAssertEqual(calls, 2)
    }

    @MainActor
    func testAutomaticRefreshOfflineWithoutCacheStaysInactive() async {
        let m = manager { throw Offline() }
        await m.refreshIfDue()
        XCTAssertEqual(m.state, .inactive)
    }

    @MainActor
    func testManualRefreshOfflineWithoutCacheReportsFailure() async {
        let m = manager { throw Offline() }
        await m.refreshFromStore()
        guard case .verificationFailed = m.state else { return XCTFail("expected verificationFailed, got \(m.state)") }
    }

    @MainActor
    func testAutomaticRefreshOfflineKeepsCachedLicenseWithinGrace() async {
        var online = true
        let m = manager { if !online { throw Offline() } }
        await m.refreshFromStore()
        online = false
        clock = clock.addingTimeInterval(LicenseManager.autoRefreshInterval + 1)
        await m.refreshIfDue()
        XCTAssertTrue(m.isPremiumActive())
    }
}
