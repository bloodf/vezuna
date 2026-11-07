import Foundation
import os
import VezunaShared

/// Process dirty-limit headroom in bytes, not system available RAM or a pressure threshold.
final class ProcessHeadroomProvider: NativeProcessHeadroom {
    private let sample: () -> UInt

    init(sample: @escaping () -> UInt = { os_proc_available_memory() }) {
        self.sample = sample
    }

    func headroomBytes() -> Int64 {
        // Zero also covers non-app callers and an exceeded process limit. Deny both.
        guard let bytes = Int64(exactly: sample()), bytes > 0 else { return 0 }
        return bytes
    }
}
