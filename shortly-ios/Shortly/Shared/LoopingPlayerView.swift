import AVFoundation
import AVKit
import Combine
import SwiftUI

@MainActor
final class LoopingPlayerModel: ObservableObject {
    let player = AVQueuePlayer()
    @Published private(set) var isMuted = false

    private var looper: AVPlayerLooper?

    init(url: URL?) {
        player.actionAtItemEnd = .none
        player.isMuted = isMuted

        if let url {
            let item = AVPlayerItem(url: url)
            looper = AVPlayerLooper(player: player, templateItem: item)
        }
    }

    func setActive(_ active: Bool) {
        if active {
            player.play()
        } else {
            player.pause()
        }
    }

    func toggleMuted() {
        isMuted.toggle()
        player.isMuted = isMuted
    }
}

struct LoopingPlayerView: View {
    @ObservedObject var model: LoopingPlayerModel

    var body: some View {
        VideoPlayer(player: model.player)
            .disabled(true)
    }
}
