import SwiftUI

struct MainTabView: View {
    private enum AppTab: Hashable {
        case feed
        case upload
        case profile
    }

    @State private var selectedTab: AppTab = .feed

    var body: some View {
        TabView(selection: $selectedTab) {
            FeedView()
                .tabItem {
                    Label("Feed", systemImage: "play.rectangle.fill")
                }
                .tag(AppTab.feed)

            UploadView {
                selectedTab = .feed
            }
            .tabItem {
                Label("Upload", systemImage: "plus.circle.fill")
            }
            .tag(AppTab.upload)

            ProfileView()
                .tabItem {
                    Label("Profile", systemImage: "person.crop.circle.fill")
                }
                .tag(AppTab.profile)
        }
    }
}
