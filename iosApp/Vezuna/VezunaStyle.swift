import SwiftUI

/// Mirror of the shared Living Color tokens (shared/.../ui/VezunaTokens.kt, brand/tokens/tokens.json "livingColor").
/// check_brand.py fails when a value here drifts from tokens.json.
private func hex(_ value: UInt32) -> UIColor {
    UIColor(red: CGFloat((value >> 16) & 0xFF) / 255, green: CGFloat((value >> 8) & 0xFF) / 255, blue: CGFloat(value & 0xFF) / 255, alpha: 1)
}

private func adaptive(light: UInt32, dark: UInt32) -> Color {
    Color(UIColor { traits in traits.userInterfaceStyle == .dark ? hex(dark) : hex(light) })
}

extension Color {
    static let vezunaNavy = Color(hex(0x123B46))
    static let vezunaTeal = Color(hex(0x087F8C))
    static let vezunaAqua = Color(hex(0x71E2CC))
    static let vezunaCoral = Color(hex(0xFF8A65))
    static let vezunaLavender = Color(hex(0xB8ABFF))
    static let vezunaCitron = Color(hex(0xDDF27C))
    static let vezunaPaper = Color(hex(0xF6F7F2))
    static let vezunaOffwhite = Color(hex(0xF5FAF9))
    static let vezunaInk = Color(hex(0x152E35))
    static let vezunaDeep = Color(hex(0x0C2027))
    static let vezunaMist = Color(hex(0xE7F5F2))
    /// Ink on category fills.
    static let vezunaOnCategory = Color(hex(0x123B46))

    static let vezunaBackground = adaptive(light: 0xF6F7F2, dark: 0x0C2027)
    static let vezunaSurface = adaptive(light: 0xFFFFFF, dark: 0x14313A)
    static let vezunaSurfaceSubtle = adaptive(light: 0xE7F3F1, dark: 0x1A3E48)
    static let vezunaText = adaptive(light: 0x152E35, dark: 0xE7F5F2)
    static let vezunaTextMuted = adaptive(light: 0x526C74, dark: 0xADC7CE)
    static let vezunaAction = adaptive(light: 0x087F8C, dark: 0x71E2CC)
    static let vezunaOnAction = adaptive(light: 0xFFFFFF, dark: 0x0C2027)
    static let vezunaBorder = adaptive(light: 0x6C878F, dark: 0x668992)
    static let vezunaDivider = adaptive(light: 0xDCE3DF, dark: 0x35545D)
    static let vezunaFocus = adaptive(light: 0x086A78, dark: 0x71E2CC)
    static let vezunaWarning = adaptive(light: 0x805500, dark: 0xF5CB73)
    static let vezunaDanger = adaptive(light: 0xB3263B, dark: 0xFF9AAA)
    static let vezunaSuccess = adaptive(light: 0x176B50, dark: 0x87DEB4)
    static let vezunaInfo = adaptive(light: 0x245DA8, dark: 0x9DC4FF)
    static let vezunaHeader = Color(hex(0x123B46))
    static let vezunaHeaderAction = adaptive(light: 0x71E2CC, dark: 0x71E2CC)
    static let vezunaOnHeaderAction = adaptive(light: 0x123B46, dark: 0x0C2027)
    static let vezunaOnHeader = adaptive(light: 0xFFFFFF, dark: 0xE7F5F2)
    static let vezunaNavIndicator = adaptive(light: 0x123B46, dark: 0x71E2CC)
    static let vezunaOnNavIndicator = adaptive(light: 0xFFFFFF, dark: 0x0C2027)
}

extension Font {
    static let vezunaTitleLarge = Font.custom("NotoSans-Bold", size: 32, relativeTo: .largeTitle)
    static let vezunaTitle = Font.custom("NotoSans-Bold", size: 24, relativeTo: .title)
    static let vezunaHeading = Font.custom("NotoSans-Bold", size: 22, relativeTo: .title2)
    static let vezunaHeadline = Font.custom("NotoSans-Bold", size: 17, relativeTo: .headline)
    static let vezunaBody = Font.custom("NotoSans-Regular", size: 17, relativeTo: .body)
    static let vezunaSecondary = Font.custom("NotoSans-Regular", size: 14, relativeTo: .subheadline)
    static let vezunaCaption = Font.custom("NotoSans-Regular", size: 12, relativeTo: .caption)
}
