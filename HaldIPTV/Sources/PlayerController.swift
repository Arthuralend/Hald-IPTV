import AppKit
import SwiftUI
import VLCKit

@MainActor
final class PlayerController: NSObject, ObservableObject, VLCMediaPlayerDelegate {
    let player = VLCMediaPlayer()
    let videoView = VLCVideoView()

    @Published var isPlaying = false
    @Published var isBuffering = false
    @Published var statusText = ""
    @Published var volume: Double = 80 { didSet { player.audio?.volume = Int32(volume) } }
    @Published var muted = false { didSet { player.audio?.isMuted = muted } }
    @Published var audioTracks: [(index: Int32, name: String)] = []
    @Published var currentAudioTrack: Int32 = -1

    @AppStorage("networkCaching") var networkCaching: Int = 1500
    @AppStorage("defaultUserAgent") var defaultUserAgent: String = ""

    override init() {
        super.init()
        videoView.autoresizingMask = [.width, .height]
        videoView.fillScreen = false
        videoView.backColor = .black
        player.drawable = videoView
        player.delegate = self
        player.audio?.volume = 80
    }

    func play(_ c: Channel, playlistUA: String?) {
        guard let url = URL(string: c.url) else { return }
        player.stop()
        let media = VLCMedia(url: url)
        media.addOption(":network-caching=\(networkCaching)")
        media.addOption(":live-caching=\(networkCaching)")
        media.addOption(":http-reconnect")
        let ua = c.userAgent ?? playlistUA ?? (defaultUserAgent.isEmpty ? nil : defaultUserAgent)
        if let ua { media.addOption(":http-user-agent=\(ua)") }
        player.media = media
        player.play()
        statusText = "Подключение…"
        isBuffering = true
        audioTracks = []
    }

    func togglePause() {
        if player.isPlaying { player.pause() } else { player.play() }
    }

    func stop() { player.stop() }

    func setAudioTrack(_ i: Int32) {
        player.currentAudioTrackIndex = i
        currentAudioTrack = i
    }

    private func refreshTracks() {
        let names = (player.audioTrackNames as? [String]) ?? []
        let idx = (player.audioTrackIndexes as? [NSNumber]) ?? []
        audioTracks = zip(idx, names).map { ($0.int32Value, $1) }.filter { $0.index >= 0 }
        currentAudioTrack = player.currentAudioTrackIndex
    }

    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor in
            switch player.state {
            case .opening: statusText = "Подключение…"; isBuffering = true
            case .buffering: isBuffering = !player.isPlaying
            case .playing: statusText = ""; isBuffering = false; isPlaying = true; refreshTracks()
            case .paused: isPlaying = false; statusText = "Пауза"
            case .stopped, .ended: isPlaying = false; isBuffering = false
            case .error: isPlaying = false; isBuffering = false; statusText = "Ошибка воспроизведения потока"
            default: break
            }
        }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor in
            if isBuffering { isBuffering = false; statusText = "" }
            if audioTracks.isEmpty { refreshTracks() }
            if !isPlaying { isPlaying = player.isPlaying }
        }
    }
}

struct VLCVideoContainer: NSViewRepresentable {
    let controller: PlayerController
    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        v.wantsLayer = true
        v.layer?.backgroundColor = NSColor.black.cgColor
        controller.videoView.frame = v.bounds
        v.addSubview(controller.videoView)
        return v
    }
    func updateNSView(_ nsView: NSView, context: Context) {
        if controller.videoView.superview !== nsView {
            controller.videoView.removeFromSuperview()
            controller.videoView.frame = nsView.bounds
            nsView.addSubview(controller.videoView)
        }
    }
}
