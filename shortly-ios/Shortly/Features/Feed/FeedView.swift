import SwiftUI

struct FeedView: View {
    @EnvironmentObject private var appModel: AppModel
    @State private var currentVideoID: UUID?

    private var playableVideos: [Video] {
        appModel.videos.filter { $0.playbackURL != nil }
    }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.ignoresSafeArea()

                if playableVideos.isEmpty {
                    emptyState
                } else {
                    ScrollView(.vertical) {
                        LazyVStack(spacing: 0) {
                            ForEach(playableVideos) { video in
                                VideoPageView(video: video, isActive: video.id == currentVideoID)
                                    .frame(width: proxy.size.width, height: proxy.size.height)
                                    .id(video.id)
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .scrollIndicators(.hidden)
                    .scrollTargetBehavior(.paging)
                    .scrollPosition(id: $currentVideoID)
                    .refreshable {
                        await appModel.refreshVideos()
                    }
                }
            }
        }
        .ignoresSafeArea()
        .onAppear {
            selectFirstVideoIfNeeded()
        }
        .onChange(of: appModel.videos) {
            selectFirstVideoIfNeeded()
        }
    }

    private var emptyState: some View {
        VStack(spacing: 18) {
            Image(systemName: "play.rectangle.on.rectangle")
                .font(.system(size: 58, weight: .semibold))
                .foregroundStyle(.pink)
            Text("Your feed starts here")
                .font(.title2.bold())
            Text("Upload a video from the Upload tab. It will appear here and on your profile as soon as the current backend marks it ready.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 36)

            if appModel.isLoadingVideos {
                ProgressView()
                    .tint(.white)
            } else {
                Button("Refresh") {
                    Task {
                        await appModel.refreshVideos()
                    }
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private func selectFirstVideoIfNeeded() {
        guard currentVideoID == nil || !playableVideos.contains(where: { $0.id == currentVideoID }) else {
            return
        }

        currentVideoID = playableVideos.first?.id
    }
}

private struct VideoPageView: View {
    let video: Video
    let isActive: Bool
    @StateObject private var playerModel: LoopingPlayerModel

    init(video: Video, isActive: Bool) {
        self.video = video
        self.isActive = isActive
        _playerModel = StateObject(wrappedValue: LoopingPlayerModel(url: video.playbackURL))
    }

    var body: some View {
        ZStack {
            Color.black

            if video.playbackURL != nil {
                LoopingPlayerView(model: playerModel)
                    .ignoresSafeArea()
            } else {
                ProgressView()
                    .tint(.white)
            }

            LinearGradient(
                colors: [.clear, .black.opacity(0.78)],
                startPoint: .center,
                endPoint: .bottom
            )
            .allowsHitTesting(false)

            VStack {
                HStack {
                    Spacer()
                    Button {
                        playerModel.toggleMuted()
                    } label: {
                        Image(systemName: playerModel.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                            .font(.title3.bold())
                            .frame(width: 44, height: 44)
                            .background(.black.opacity(0.45), in: Circle())
                    }
                    .foregroundStyle(.white)
                    .accessibilityLabel(playerModel.isMuted ? "Unmute video" : "Mute video")
                }
                .padding(.top, 64)
                .padding(.horizontal, 20)

                Spacer()

                HStack(alignment: .bottom, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("@\(creatorName)")
                            .font(.headline)
                        Text(video.title)
                            .font(.title3.bold())
                        if let description = video.description, !description.isEmpty {
                            Text(description)
                                .font(.subheadline)
                                .lineLimit(3)
                        }
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Image(systemName: "chevron.up")
                        .font(.title.bold())
                        .frame(width: 48, height: 48)
                        .background(.white.opacity(0.14), in: Circle())
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 92)
            }
        }
        .accessibilityIdentifier("feed.video.\(video.id.uuidString)")
        .onAppear {
            playerModel.setActive(isActive)
        }
        .onChange(of: isActive) {
            playerModel.setActive(isActive)
        }
        .onDisappear {
            playerModel.setActive(false)
        }
    }

    private var creatorName: String {
        String(video.userId.suffix(8))
    }
}
