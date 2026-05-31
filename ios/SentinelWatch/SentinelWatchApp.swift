//
//  SentinelWatchApp.swift
//  SENTINEL companion app for watchOS. Mirrors the iPhone streaming state and exposes a single
//  big record button so the operator can start/stop from their wrist without taking the phone
//  out of their pocket.
//

import SwiftUI
import WatchConnectivity

@main
struct SentinelWatchApp: App {
    @StateObject private var bridge = WatchClient()
    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(bridge)
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var bridge: WatchClient

    var body: some View {
        VStack(spacing: 12) {
            statePill
            if bridge.payload.isLive, let started = bridge.payload.startedAt {
                Text(timerInterval: started...Date.distantFuture, countsDown: false)
                    .font(.title3.monospacedDigit())
            }
            recordButton
            HStack(spacing: 6) {
                bars(bridge.payload.qualityBars)
                Text("\(bridge.payload.bitrateKbps) kb/s").font(.caption.monospacedDigit())
            }
            .foregroundStyle(.secondary)
        }
        .padding()
    }

    @ViewBuilder
    private var statePill: some View {
        HStack(spacing: 6) {
            Circle().fill(stateColor).frame(width: 8, height: 8)
            Text(bridge.payload.stateLabel).font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(.thinMaterial, in: Capsule())
    }

    private var stateColor: Color {
        bridge.payload.isLive ? .red : (bridge.payload.stateLabel.contains("Reconn") ? .orange : .gray)
    }

    @ViewBuilder
    private var recordButton: some View {
        Button {
            bridge.sendCommand(bridge.payload.isLive ? "stop" : "start")
        } label: {
            Image(systemName: bridge.payload.isLive ? "stop.fill" : "record.circle")
                .font(.system(size: 36))
                .foregroundStyle(.red)
                .frame(width: 64, height: 64)
                .background(Color.white, in: Circle())
        }
        .buttonStyle(.plain)
    }

    private func bars(_ n: Int) -> some View {
        HStack(spacing: 2) {
            ForEach(0..<4) { idx in
                Capsule()
                    .fill(idx < n ? barColor(n) : Color.white.opacity(0.25))
                    .frame(width: 3, height: CGFloat(5 + idx * 2))
            }
        }
    }
    private func barColor(_ n: Int) -> Color {
        switch n { case 0,1: return .red; case 2: return .orange; default: return .green }
    }
}

// MARK: - Watch-side WatchConnectivity client

final class WatchClient: NSObject, ObservableObject {
    @Published var payload = WatchPayload(stateLabel: "Idle", isLive: false,
                                          bitrateKbps: 0, qualityBars: 4, startedAt: nil)
    private let session: WCSession?

    override init() {
        session = WCSession.isSupported() ? WCSession.default : nil
        super.init()
        session?.delegate = self
        session?.activate()
    }

    func sendCommand(_ cmd: String) {
        guard let s = session, s.activationState == .activated else { return }
        if s.isReachable {
            s.sendMessage(["cmd": cmd], replyHandler: nil)
        } else {
            // Fall back to a userInfo message (queued, delivered when phone wakes).
            s.transferUserInfo(["cmd": cmd])
        }
    }
}

extension WatchClient: WCSessionDelegate {
    func session(_ session: WCSession,
                 activationDidCompleteWith activationState: WCSessionActivationState,
                 error: Error?) {}

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String : Any]) {
        guard let data = applicationContext["payload"] as? Data,
              let payload = try? JSONDecoder().decode(WatchPayload.self, from: data) else { return }
        DispatchQueue.main.async { self.payload = payload }
    }
}

// WatchPayload lives in ios/Shared/ — included in this target via project.yml.
