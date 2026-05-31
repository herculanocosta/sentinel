//
//  WatchBridge.swift
//  iPhone side of the Apple Watch companion. Uses WatchConnectivity to:
//    • Push streaming state + bitrate to the Watch every time it changes
//    • Receive start/stop commands from the Watch and forward them to StreamingService
//
//  The Watch app is a tiny SwiftUI app that displays the same state pill + a record button.
//

import Foundation
import WatchConnectivity

// WatchPayload lives in ios/Shared/ — included in both iPhone + watchOS targets via project.yml.

@MainActor
final class WatchBridge: NSObject, ObservableObject {
    @Published private(set) var watchReachable = false

    private let session: WCSession?
    private var lastPushed: WatchPayload?

    override init() {
        if WCSession.isSupported() {
            session = WCSession.default
        } else {
            session = nil
        }
        super.init()
        session?.delegate = self
        session?.activate()
    }

    /// Called by AppDependencies whenever streaming state changes.
    func publish(state: StreamState, stats: StreamStats) {
        let label: String
        let isLive: Bool
        switch state {
        case .idle:                  label = "Idle";             isLive = false
        case .connecting:            label = "Connecting…";      isLive = false
        case .live:                  label = "Live";             isLive = true
        case .reconnecting(let n):   label = "Reconnecting (\(n))"; isLive = false
        case .failed(let e):         label = e;                  isLive = false
        }
        let payload = WatchPayload(
            stateLabel: label,
            isLive: isLive,
            bitrateKbps: stats.bitrateKbps,
            qualityBars: stats.qualityBars,
            startedAt: isLive ? (lastPushed?.startedAt ?? Date()) : nil
        )
        if payload == lastPushed { return }
        lastPushed = payload
        send(payload)
    }

    private func send(_ payload: WatchPayload) {
        guard let s = session, s.activationState == .activated else { return }
        guard let data = try? JSONEncoder().encode(payload) else { return }
        let dict: [String: Any] = ["payload": data]
        // applicationContext = latest-only state delivery; preferable to userInfo for live state.
        try? s.updateApplicationContext(dict)
    }
}

// MARK: - WCSession delegate

extension WatchBridge: WCSessionDelegate {
    nonisolated func session(_ session: WCSession,
                             activationDidCompleteWith activationState: WCSessionActivationState,
                             error: Error?) {
        Task { @MainActor in
            self.watchReachable = session.isReachable
        }
    }
    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in self.watchReachable = session.isReachable }
    }
    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String : Any],
                             replyHandler: @escaping ([String : Any]) -> Void) {
        // Watch sent a command.
        if let cmd = message["cmd"] as? String {
            Task { @MainActor [weak self] in
                guard let deps = SentinelIntentBridge.deps else { replyHandler(["ok": false]); return }
                switch cmd {
                case "start":
                    let preset = deps.presets.captureCurrent(name: "Current")
                    await deps.streaming.start(preset: preset, recordLocally: false)
                    replyHandler(["ok": true])
                case "stop":
                    await deps.streaming.stop()
                    replyHandler(["ok": true])
                default:
                    replyHandler(["ok": false])
                }
                _ = self                                 // silence unused-self warning
            }
        } else {
            replyHandler(["ok": false])
        }
    }
    // Required no-op stubs.
    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}
    nonisolated func sessionDidDeactivate(_ session: WCSession) { session.activate() }
}
