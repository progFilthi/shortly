import AVKit
import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var appModel: AppModel
    @State private var selectedVideo: Video?
    @State private var confirmsSignOut = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 3)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    profileHeader
                    Divider()
                    videosSection
                }
                .padding(.bottom, 24)
            }
            .background(Color.black)
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Sign out") {
                        confirmsSignOut = true
                    }
                    .accessibilityIdentifier("profile.signOut")
                }
            }
            .refreshable {
                await appModel.refreshVideos()
            }
            .task {
                if appModel.videos.isEmpty {
                    await appModel.refreshVideos()
                }
            }
            .sheet(item: $selectedVideo) { video in
                VideoDetailView(video: video)
            }
        }
        // Attached to the NavigationStack rather than the ScrollView inside it. With
        // both this and a .sheet on the same view, only one of them presents reliably
        // and the sign-out confirmation silently never appears.
        .confirmationDialog(
            "Sign out?",
            isPresented: $confirmsSignOut,
            titleVisibility: .visible
        ) {
            Button("Sign out", role: .destructive) {
                Task { await appModel.signOut() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            // Worth saying plainly: this revokes the refresh token server-side, so
            // a copy taken from the Keychain is dead rather than merely unused.
            Text("Your refresh token is revoked on the server, so this device cannot be used to restore the session.")
        }
    }

    private var profileHeader: some View {
        VStack(spacing: 14) {
            Text(initials)
                .font(.system(size: 34, weight: .bold, design: .rounded))
                .frame(width: 92, height: 92)
                .background(
                    LinearGradient(
                        colors: [.pink, .purple],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    in: Circle()
                )

            VStack(spacing: 5) {
                Text("@\(appModel.session?.username ?? "creator")")
                    .font(.title2.bold())
                    .accessibilityIdentifier("profile.username")
                Text(appModel.session?.email ?? "")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 34) {
                statistic(value: appModel.videos.count, label: "Videos")
                statistic(value: 0, label: "Likes")
                statistic(value: 0, label: "Following")
            }

            if let count = appModel.profile?.activeSessionCount {
                Text("\(count) active session\(count == 1 ? "" : "s")")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(.white.opacity(0.08), in: Capsule())
                    .accessibilityIdentifier("profile.sessions")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 18)
    }

    private var videosSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Videos")
                    .font(.headline)
                Spacer()
                if appModel.isLoadingVideos {
                    ProgressView()
                        .tint(.white)
                }
            }
            .padding(.horizontal, 16)

            if appModel.videos.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "film.stack")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text("No videos yet")
                        .font(.headline)
                    Text("Your uploaded videos will appear here.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 60)
            } else {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(appModel.videos) { video in
                        Button {
                            selectedVideo = video
                        } label: {
                            ZStack {
                                Rectangle()
                                    .fill(.white.opacity(0.07))
                                Image(systemName: "play.fill")
                                    .font(.title3.bold())
                                    .foregroundStyle(.white.opacity(0.8))
                                VStack {
                                    Spacer()
                                    Text(video.title)
                                        .font(.caption2.weight(.semibold))
                                        .lineLimit(1)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(6)
                                        .background(.black.opacity(0.55))
                                }
                            }
                            .aspectRatio(0.75, contentMode: .fit)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Play \(video.title)")
                    }
                }
            }
        }
    }

    private var initials: String {
        let username = appModel.session?.username ?? "S"
        return username.prefix(2).uppercased()
    }

    private func statistic(value: Int, label: String) -> some View {
        VStack(spacing: 3) {
            Text(value, format: .number)
                .font(.headline)
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

private struct VideoDetailView: View {
    let video: Video
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer

    init(video: Video) {
        self.video = video
        _player = State(initialValue: video.playbackURL.map(AVPlayer.init(url:)) ?? AVPlayer())
    }

    var body: some View {
        NavigationStack {
            VideoPlayer(player: player)
                .ignoresSafeArea(edges: .bottom)
                .onAppear {
                    player.play()
                }
                .onDisappear {
                    player.pause()
                }
                .navigationTitle(video.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") {
                            dismiss()
                        }
                    }
                }
        }
    }
}
