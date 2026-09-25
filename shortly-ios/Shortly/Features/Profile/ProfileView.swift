import AVKit
import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var appModel: AppModel
    @State private var selectedVideo: Video?
    @State private var confirmsSessionReset = false

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
                    Button("Reset") {
                        confirmsSessionReset = true
                    }
                    .accessibilityIdentifier("profile.reset")
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
            .confirmationDialog(
                "Forget this device session?",
                isPresented: $confirmsSessionReset,
                titleVisibility: .visible
            ) {
                Button("Forget session", role: .destructive) {
                    appModel.clearSession()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The current backend has no server-side logout, so the token remains valid until it expires.")
            }
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

            Text("Prototype profile")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(.white.opacity(0.08), in: Capsule())
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
