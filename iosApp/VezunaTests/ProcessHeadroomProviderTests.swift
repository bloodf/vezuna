import XCTest
import VezunaShared
@testable import Vezuna

final class ProcessHeadroomProviderTests: XCTestCase {
    func testSamplesFreshBytesAcrossNativeBridge() {
        var calls = 0
        let provider = ProcessHeadroomProvider {
            calls += 1
            return calls == 1 ? 123 : 456
        }
        let capacity = IosDeviceCapacity(processHeadroom: provider)
        XCTAssertEqual(capacity.loadMemorySnapshot()?.headroomBytes, 123)
        XCTAssertEqual(capacity.loadMemorySnapshot()?.headroomBytes, 456)
        XCTAssertEqual(calls, 2)
    }

    func testZeroAndUnsignedOverflowFailClosedAcrossBridge() {
        for value in [UInt(0), UInt.max] {
            let capacity = IosDeviceCapacity(processHeadroom: ProcessHeadroomProvider { value })
            XCTAssertNil(capacity.loadMemorySnapshot())
        }
    }

    func testSignedBoundaryIsPreservedWithoutPhysicalRamClamp() {
        let capacity = IosDeviceCapacity(processHeadroom: ProcessHeadroomProvider { UInt(Int64.max) })
        XCTAssertEqual(capacity.loadMemorySnapshot()?.headroomBytes, Int64.max)
    }

    func testMissingNativeProviderFailsClosed() {
        XCTAssertNil(IosDeviceCapacity(processHeadroom: nil).loadMemorySnapshot())
    }
}
