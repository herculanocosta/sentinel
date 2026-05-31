//
//  WatchPayload.swift
//  Wire shape shared by the iPhone app and the watchOS companion. Lives in `ios/Shared/` so the
//  project.yml can include it in both targets — single source of truth, no drift.
//

import Foundation

public struct WatchPayload: Codable, Equatable {
    public var stateLabel: String       // "Idle" / "Live" / "Connecting…" / "Reconnecting (n)" / failure
    public var isLive: Bool
    public var bitrateKbps: Int
    public var qualityBars: Int         // 0…4
    public var startedAt: Date?

    public init(stateLabel: String, isLive: Bool, bitrateKbps: Int, qualityBars: Int, startedAt: Date?) {
        self.stateLabel = stateLabel
        self.isLive = isLive
        self.bitrateKbps = bitrateKbps
        self.qualityBars = qualityBars
        self.startedAt = startedAt
    }
}
