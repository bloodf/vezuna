import SwiftUI
import VitaHearthShared

struct ContentView: View {
    @EnvironmentObject private var model: AppShellModel

    var body: some View {
        Group {
            if model.ready {
                NavigationStack {
                    ComposeHostView()
                        .ignoresSafeArea(.container, edges: .bottom)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItemGroup(placement: .topBarTrailing) {
                                Menu {
                                    Button { model.sheet = .importSource } label: { Label(L10n.text(.import_lab_source), systemImage: "doc.viewfinder") }
                                    Button { model.sheet = .health } label: { Label(L10n.text(.healthkit_import_menu), systemImage: "heart") }
                                    Button { model.sheet = .export } label: { Label(L10n.text(.export_backup_menu), systemImage: "square.and.arrow.up") }
                                    Button { model.sheet = .license } label: { Label(L10n.text(.ai_activation_menu), systemImage: "checkmark.shield") }
                                } label: {
                                    Image(systemName: "ellipsis.circle")
                                        .accessibilityLabel(L10n.text(.native_actions))
                                }
                            }
                        }
                        .safeAreaInset(edge: .top) {
                            if let error = model.catalogError ?? model.modelLoader.lastError {
                                Text(error)
                                    .font(.footnote)
                                    .foregroundStyle(Color.vezunaDanger)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(8)
                                    .background(.regularMaterial)
                            }
                        }
                }
            } else if let error = model.startupError {
                VStack(spacing: 12) {
                    Image(systemName: "lock.trianglebadge.exclamationmark").font(.largeTitle)
                    Text(L10n.text(.storage_unavailable)).font(.headline)
                    Text(error).multilineTextAlignment(.center)
                }.padding()
            } else {
                ProgressView(L10n.text(.opening_storage))
            }
        }
        .task { model.configure() }
        .sheet(item: $model.sheet) { sheet in
            switch sheet {
            case .importSource:
                NativeImportView()
            case .health:
                HealthImportView(importer: model.healthImporter)
            case .export:
                NativeExportView()
            case .license:
                LicenseView(manager: model.licenseManager)
            }
        }
    }
}

struct ComposeHostView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ComposeBridgeKt.makeComposeViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
