import SwiftUI

struct PrivacyPolicyView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            BundledDocumentTextView(text: policy)
                .navigationTitle(L10n.text(.privacy_policy))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.text(.close)) { dismiss() }
                    }
                }
        }
    }

    private var policy: String {
        guard let url = Bundle.main.url(forResource: "PrivacyPolicy", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return L10n.text(.privacy_policy_unavailable)
        }
        return text
    }
}
