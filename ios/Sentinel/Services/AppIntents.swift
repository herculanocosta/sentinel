//
//  AppIntents.swift
//  Siri Shortcuts + iOS-wide actions: "Hey Siri, start SENTINEL stream" / "stop stream" / "toggle
//  overlay". Also exposes intents in the Shortcuts app, on the Lock Screen (StandBy), and as
//  Focus filters. Backed by AppDependencies via a small singleton bridge — App Intents run in a
//  separate process from the app and need a way to reach the running services.
//

import AppIntents
import SwiftUI

// MARK: - Bridge

/// Static handle so App Intent processes can reach the running AppDependencies. Set by
/// SentinelApp at startup.
enum SentinelIntentBridge {
    nonisolated(unsafe) static var deps: AppDependencies?
}

// MARK: - Intents

@available(iOS 16.0, *)
struct StartSentinelStreamIntent: AppIntent {
    static var title: LocalizedStringResource = "Start SENTINEL Stream"
    static var description = IntentDescription("Start streaming from the camera to your configured server.")
    static var openAppWhenRun: Bool = true   // stream needs an active capture session — must be in foreground

    func perform() async throws -> some IntentResult {
        guard let deps = SentinelIntentBridge.deps else { return .result() }
        let preset = await MainActor.run { deps.presets.captureCurrent(name: "Current") }
        await deps.streaming.start(preset: preset, recordLocally: false)
        return .result(dialog: "Streaming started.")
    }
}

@available(iOS 16.0, *)
struct StopSentinelStreamIntent: AppIntent {
    static var title: LocalizedStringResource = "Stop SENTINEL Stream"
    static var description = IntentDescription("Stop the current stream.")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult {
        guard let deps = SentinelIntentBridge.deps else { return .result() }
        await deps.streaming.stop()
        return .result(dialog: "Stream stopped.")
    }
}

@available(iOS 16.0, *)
struct ToggleOverlayIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle Overlay"
    static var description = IntentDescription("Show or hide the timestamp/GPS burn-in overlay on the video.")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult {
        let now = UserDefaults.standard.bool(forKey: Pref.textOverlay)
        UserDefaults.standard.set(!now, forKey: Pref.textOverlay)
        return .result(dialog: !now ? "Overlay on." : "Overlay off.")
    }
}

@available(iOS 16.0, *)
struct CurrentStreamStateIntent: AppIntent {
    static var title: LocalizedStringResource = "Stream State"
    static var description = IntentDescription("Returns whether SENTINEL is currently streaming.")

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        guard let deps = SentinelIntentBridge.deps else { return .result(value: "unknown") }
        let state = await MainActor.run { deps.streaming.state }
        let s: String
        switch state {
        case .idle: s = "idle"
        case .connecting: s = "connecting"
        case .live: s = "live"
        case .reconnecting(let n): s = "reconnecting (attempt \(n))"
        case .failed(let e): s = "failed: \(e)"
        }
        return .result(value: s)
    }
}

// MARK: - Shortcut suggestions (appear in the Shortcuts app on first install)

@available(iOS 16.0, *)
struct SentinelShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartSentinelStreamIntent(),
            phrases: [
                "Start \(.applicationName) stream",
                "Go live with \(.applicationName)",
                "Start broadcast with \(.applicationName)"
            ],
            shortTitle: "Start Stream",
            systemImageName: "dot.radiowaves.left.and.right"
        )
        AppShortcut(
            intent: StopSentinelStreamIntent(),
            phrases: [
                "Stop \(.applicationName) stream",
                "End broadcast with \(.applicationName)"
            ],
            shortTitle: "Stop Stream",
            systemImageName: "stop.fill"
        )
        AppShortcut(
            intent: ToggleOverlayIntent(),
            phrases: [
                "Toggle \(.applicationName) overlay"
            ],
            shortTitle: "Toggle Overlay",
            systemImageName: "text.below.photo"
        )
    }
}
