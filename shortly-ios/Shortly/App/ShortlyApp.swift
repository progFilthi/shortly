import SwiftUI

@main
struct ShortlyApp: App {
    @StateObject private var appModel = AppModel.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appModel)
                .tint(.pink)
                .task {
                    await appModel.restoreSession()
                }
        }
    }
}

private struct RootView: View {
    @EnvironmentObject private var appModel: AppModel

    var body: some View {
        Group {
            if appModel.isRestoringSession {
                ZStack {
                    Color.black.ignoresSafeArea()
                    ProgressView("Restoring session")
                        .tint(.white)
                        .foregroundStyle(.white)
                }
            } else if appModel.session == nil {
                RegistrationView()
            } else {
                MainTabView()
            }
        }
        .preferredColorScheme(.dark)
        .alert(item: $appModel.appAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("OK"))
            )
        }
    }
}
