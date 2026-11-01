import SwiftUI
import UIKit

struct ThirdPartyNoticesView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            BundledDocumentTextView(text: notices)
                .navigationTitle(L10n.text(.third_party_notices))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.text(.close)) { dismiss() }
                    }
                }
        }
    }

    private var notices: String {
        guard let url = Bundle.main.url(forResource: "ThirdPartyNotices", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return L10n.text(.third_party_notices_unavailable)
        }
        return text
    }
}

struct BundledDocumentTextView: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isSelectable = true
        view.adjustsFontForContentSizeCategory = true
        view.font = .preferredFont(forTextStyle: .footnote)
        view.textContainerInset = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        view.text = text
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {}
}
