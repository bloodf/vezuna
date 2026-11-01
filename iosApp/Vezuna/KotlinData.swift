import Foundation
import VezunaShared

extension KotlinByteArray {
    static func fromData(_ data: Data) -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }

    func toData() -> Data {
        var data = Data(count: Int(size))
        for index in 0..<Int(size) {
            data[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return data
    }
}

extension Data {
    var kotlinBytes: KotlinByteArray { KotlinByteArray.fromData(self) }
}
