import SwiftUI

@main
struct HaldIPTVApp: App {
    @StateObject private var store = AppStore()
    @StateObject private var player = PlayerController()

    var body: some Scene {
        WindowGroup("Hald IPTV") {
            ContentView()
                .environmentObject(store)
                .environmentObject(player)
                .frame(minWidth: 1000, minHeight: 600)
        }
        .commands {
            CommandMenu("Воспроизведение") {
                Button("Пауза / Продолжить") { player.togglePause() }
                    .keyboardShortcut("p", modifiers: .command)
                Button("Следующий канал") { NotificationCenter.default.post(name: .channelStep, object: 1) }
                    .keyboardShortcut(.downArrow, modifiers: .command)
                Button("Предыдущий канал") { NotificationCenter.default.post(name: .channelStep, object: -1) }
                    .keyboardShortcut(.upArrow, modifiers: .command)
                Divider()
                Button("Без звука") { player.muted.toggle() }.keyboardShortcut("m", modifiers: .command)
                Button("Полный экран") { NSApp.keyWindow?.toggleFullScreen(nil) }
                    .keyboardShortcut("f", modifiers: [.command, .control])
                Divider()
                Button("Обновить плейлист") { store.refresh() }.keyboardShortcut("r", modifiers: .command)
            }
        }
        Settings { SettingsView() }
    }
}
