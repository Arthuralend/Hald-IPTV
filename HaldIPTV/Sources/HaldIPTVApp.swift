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
            CommandMenu(L("Воспроизведение")) {
                Button(L("Пауза / Продолжить")) { player.togglePause() }
                    .keyboardShortcut("p", modifiers: .command)
                Button(L("Следующий канал")) { NotificationCenter.default.post(name: .channelStep, object: 1) }
                    .keyboardShortcut(.downArrow, modifiers: .command)
                Button(L("Предыдущий канал")) { NotificationCenter.default.post(name: .channelStep, object: -1) }
                    .keyboardShortcut(.upArrow, modifiers: .command)
                Divider()
                Button(L("Без звука")) { player.muted.toggle() }.keyboardShortcut("m", modifiers: .command)
                Button(L("Полный экран канала")) { FullScreen.channel(store) }
                    .keyboardShortcut(.return, modifiers: .command)
                Button(L("Полный экран программы")) { FullScreen.app(store) }
                    .keyboardShortcut("f", modifiers: [.command, .control])
                Button(L("Только видео в окне")) { store.videoOnly.toggle() }
                    .keyboardShortcut("e", modifiers: [.command, .shift])
                Divider()
                Button(player.isRecording ? L("Остановить запись") : L("Запись")) { player.toggleRecording() }
                    .keyboardShortcut("r", modifiers: [.command, .shift])
                Button(L("Открыть папку записей")) { player.showRecordings() }
                Divider()
                Button(L("Очистить недавние")) { store.clearRecents() }
                Divider()
                Button(L("Обновить плейлист")) { store.refresh() }.keyboardShortcut("r", modifiers: .command)
            }
        }
        Settings { SettingsView() }
    }
}
