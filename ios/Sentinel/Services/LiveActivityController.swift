//
//  LiveActivityController.swift
//  Drives the Live Activity (lock screen + Dynamic Island) on iOS 16.2+. The wire shape
//  (`SentinelLiveAttributes`) lives in `ios/Shared/` so the widget extension reads the same
//  type. This file is iPhone-app-only — it imports `StreamState` / `StreamStats` from
//  `StreamingService`, which doesn't exist in the widget bundle.
//
//  Lifecycle:
//   • .connecting / .live / .reconnecting  → start (if not running) / update
//   • .idle / .failed                      → end (immediate dismissal)
//

import Foundation
import ActivityKit
import SwiftUI

@MainActor
final class LiveActivityController {
    private var activity: Activity<SentinelLiveAttributes>?
    private var startedAt: Date?

    func apply(state: StreamState, stats: StreamStats) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

        let label: String
        let live: Bool
        switch state {
        case .idle, .failed:           label = "Off";                  live = false
        case .connecting:              label = "Connecting…";          live = false
        case .live:                    label = "Live";                 live = true
        case .reconnecting(let n):     label = "Reconnecting (\(n))";  live = false
        }
        if live, startedAt == nil { startedAt = Date() }

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
            else { Task { await activity?.update(ActivityContent(state: content, staleDate: nil)) } }
        }
    }

    private func startActivity(content: SentinelLiveAttributes.ContentState) {
        let attributes = SentinelLiveAttributes(
            serverLabel: UserDefaults.standard.string(forKey: Pref.streamAddress) ?? "SENTINEL")
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: ActivityContent(state: content, staleDate: nil),
                pushType: nil
            )
        } catch {
            // Live Activity disabled at OS level — fail silently.
        }
    }

    private func endActivity(final: SentinelLiveAttributes.ContentState) async {
        guard let a = activity else { startedAt = nil; return }
        await a.end(ActivityContent(state: final, staleDate: nil), dismissalPolicy: .immediate)
        activity = nil
        startedAt = nil
    }
}
