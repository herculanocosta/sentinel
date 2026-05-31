//
//  LiveActivityController.swift
//  Drives the Live Activity (lock screen + Dynamic Island) on iOS 16.1+. The widget extension
//  reads the same `SentinelLiveAttributes` declared below.
//
//  Architecture: this controller takes the streaming service's published state + stats and
//  pushes them into an `Activity<SentinelLiveAttributes>`. When the state goes to .live we start
//  the activity; on .idle we end it. Reconnecting updates the content without ending.
//

import Foundation
import ActivityKit
import SwiftUI

/// Attributes — declared in BOTH the app and the widget extension targets (same file shared via
/// XcodeGen's source list, or duplicated in the widget bundle). Keep the wire shape stable.
public struct SentinelLiveAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var stateLabel: String     // "Live" | "Reconnecting (3)" | …
        public var isLive: Bool
        public var startedAt: Date
        public var bitrateKbps: Int
        public var qualityBars: Int       // 0…4

        public init(stateLabel: String, isLive: Bool, startedAt: Date, bitrateKbps: Int, qualityBars: Int) {
            self.stateLabel = stateLabel
            self.isLive = isLive
            self.startedAt = startedAt
            self.bitrateKbps = bitrateKbps
            self.qualityBars = qualityBars
        }
    }
    public var serverLabel: String        // "Home MediaMTX" / etc.
    public init(serverLabel: String) { self.serverLabel = serverLabel }
}

@MainActor
final class LiveActivityController {
    private var activity: Activity<SentinelLiveAttributes>?
    private var startedAt: Date?

    func apply(state: StreamState, stats: StreamStats) {
        guard #available(iOS 16.1, *) else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

        let label: String
        let live: Bool
        switch state {
        case .idle, .failed:           label = "Off";                live = false
        case .connecting:              label = "Connecting…";        live = false
        case .live:                    label = "Live";               live = true
        case .reconnecting(let n):     label = "Reconnecting (\(n))"; live = false
        }

        if live {
            if startedAt == nil { startedAt = Date() }
        }

        let content = SentinelLiveAttributes.ContentState(
            stateLabel: label,
            isLive: live,
            startedAt: startedAt ?? Date(),
            bitrateKbps: stats.bitrateKbps,
            qualityBars: stats.qualityBars
        )

        switch state {
        case .idle, .failed:
            Task { await endActivity(final: content) }
        case .connecting, .live, .reconnecting:
            if activity == nil { startActivity(content: content) }
            else { Task { await activity?.update(.init(state: content, staleDate: nil)) } }
        }
    }

    @available(iOS 16.1, *)
    private func startActivity(content: SentinelLiveAttributes.ContentState) {
        let attributes = SentinelLiveAttributes(
            serverLabel: UserDefaults.standard.string(forKey: Pref.streamAddress) ?? "SENTINEL")
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: .init(state: content, staleDate: nil),
                pushType: nil
            )
        } catch {
            // Live Activity disabled at OS level — fail silently.
        }
    }

    @available(iOS 16.1, *)
    private func endActivity(final: SentinelLiveAttributes.ContentState) async {
        guard let a = activity else { startedAt = nil; return }
        await a.end(.init(state: final, staleDate: nil), dismissalPolicy: .immediate)
        activity = nil
        startedAt = nil
    }
}
