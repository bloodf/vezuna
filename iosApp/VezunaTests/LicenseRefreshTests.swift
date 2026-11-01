import XCTest
@testable import Vezuna

final class LicenseRefreshTests: XCTestCase {
    private var clock = Date(timeIntervalSince1970: 1_800_000_000)

    private func manager() -> LicenseManager {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("json")
        return LicenseManager(cacheURL: file, now: { self.clock })
    }

    func testOfflineGraceIsThirtyDays() {
        XCTAssertEqual(LicenseManager.offlineGrace, 30 * 24 * 60 * 60)
    }

    func testFirstAutoRefreshIsDue() {
        XCTAssertTrue(manager().shouldAutoRefresh())
    }

    @MainActor
    func testAutoRefreshIsDebounced() async {
        let m = manager()
        await m.refreshIfDue()
        XCTAssertFalse(m.shouldAutoRefresh())
        clock = clock.addingTimeInterval(LicenseManager.autoRefreshInterval + 1)
        XCTAssertTrue(m.shouldAutoRefresh())
    }
}
