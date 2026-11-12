import AppKit
import Combine
import SwiftUI

// Shims so the app runs on macOS 11 Big Sur (2014 Macs) and still uses the newest APIs where present.

extension URLSession {
    /// async `data(for:)` exists only on macOS 12+.
    func fetch(_ request: URLRequest) async throws -> (Data, URLResponse) {
        try await withCheckedThrowingContinuation { cont in
            dataTask(with: request) { data, resp, err in
                if let err { cont.resume(throwing: err) }
                else { cont.resume(returning: (data ?? Data(), resp ?? URLResponse())) }
            }.resume()
        }
    }
    func fetch(_ url: URL) async throws -> (Data, URLResponse) { try await fetch(URLRequest(url: url)) }
}

/// Re-renders its content once per second (TimelineView is macOS 12+).
struct EverySecond<Content: View>: View {
    @State private var now = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    let content: () -> Content
    init(@ViewBuilder content: @escaping () -> Content) { self.content = content }
    var body: some View {
        content()
            .background(Text("\(Int(now.timeIntervalSince1970))").hidden().frame(width: 0, height: 0))
            .onReceive(timer) { now = $0 }
    }
}

extension View {
    /// count on the right of a sidebar row (`.badge` is macOS 12+)
    func countBadge(_ n: Int) -> some View {
        HStack {
            self
            Spacer(minLength: 4)
            if n > 0 { Text("\(n)").font(.caption).foregroundColor(.secondary) }
        }
    }

    @ViewBuilder func hideScrollBackground() -> some View {
        if #available(macOS 13.0, *) { self.scrollContentBackground(.hidden) } else { self }
    }

    @ViewBuilder func windowToolbarHidden(_ hidden: Bool) -> some View {
        if #available(macOS 13.0, *) {
            self.toolbar(hidden ? .hidden : .visible, for: .windowToolbar)
        } else {
            self.onChange(of: hidden) { h in NSApp.windows.forEach { $0.toolbar?.isVisible = !h } }
        }
    }

    /// mouse moved inside the view (`onContinuousHover` is macOS 13+)
    @ViewBuilder func onMouseMove(_ action: @escaping () -> Void) -> some View {
        if #available(macOS 13.0, *) {
            self.onContinuousHover { phase in if case .active = phase { action() } }
        } else {
            self.modifier(MouseMoveMonitor(action: action))
        }
    }

    /// `.task(id:)` replacement for macOS 11
    func reload<T: Equatable>(on id: T, _ work: @escaping () async -> Void) -> some View {
        self.modifier(ReloadTask(id: id, work: work))
    }
}

private struct MouseMoveMonitor: ViewModifier {
    let action: () -> Void
    @State private var monitor: Any?
    func body(content: Content) -> some View {
        content
            .onHover { if $0 { action() } }
            .onAppear {
                NSApp.windows.forEach { $0.acceptsMouseMovedEvents = true }
                monitor = NSEvent.addLocalMonitorForEvents(matching: [.mouseMoved]) { e in action(); return e }
            }
            .onDisappear { if let m = monitor { NSEvent.removeMonitor(m) } }
    }
}

private struct ReloadTask<T: Equatable>: ViewModifier {
    let id: T
    let work: () async -> Void
    @State private var task: Task<Void, Never>?
    func body(content: Content) -> some View {
        content
            .onAppear { start() }
            .onChange(of: id) { _ in start() }
            .onDisappear { task?.cancel() }
    }
    private func start() {
        task?.cancel()
        task = Task { await work() }
    }
}
