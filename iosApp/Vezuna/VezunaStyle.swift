import SwiftUI

extension Color {
    static let vezunaNavy = Color(red: 0x12 / 255, green: 0x3B / 255, blue: 0x46 / 255)
    static let vezunaTeal = Color(red: 0x08 / 255, green: 0x7F / 255, blue: 0x8C / 255)
    static let vezunaAqua = Color(red: 0x71 / 255, green: 0xE2 / 255, blue: 0xCC / 255)
    static let vezunaOffwhite = Color(red: 0xF5 / 255, green: 0xFA / 255, blue: 0xF9 / 255)
    static let vezunaInk = Color(red: 0x15 / 255, green: 0x2E / 255, blue: 0x35 / 255)
    static let vezunaDeep = Color(red: 0x0C / 255, green: 0x20 / 255, blue: 0x27 / 255)
    static let vezunaMist = Color(red: 0xE7 / 255, green: 0xF5 / 255, blue: 0xF2 / 255)
    static let vezunaDanger = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(red: 1, green: 0x9A / 255, blue: 0xAA / 255, alpha: 1) : UIColor(red: 0xB3 / 255, green: 0x26 / 255, blue: 0x3B / 255, alpha: 1)
    })
    static let vezunaAction = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(red: 0x71 / 255, green: 0xE2 / 255, blue: 0xCC / 255, alpha: 1) : UIColor(red: 0x08 / 255, green: 0x7F / 255, blue: 0x8C / 255, alpha: 1)
    })
}

extension Font {
    static let vezunaBody = Font.custom("NotoSans-Regular", size: 17, relativeTo: .body)
    static let vezunaTitle = Font.custom("NotoSans-Bold", size: 24, relativeTo: .title)
    static let vezunaHeadline = Font.custom("NotoSans-Bold", size: 17, relativeTo: .headline)
}
