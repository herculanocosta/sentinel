//
//  SentinelLiveAttributes.swift
//  Wire shape for the SENTINEL Live Activity. Shared (via ios/Shared/) with the widget
//  extension target so the activity displayed on the lock-screen / Dynamic Island shows the same
//  state model the iPhone app publishes.
//

import Foundation
import ActivityKit

@available(iOS 16.1, *)
public struct SentinelLiveAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var stateLabel: String        // "Live" | "Connecting…" | "Reconnecting (3)" | …
        public var isLive: Bool
        public var startedAt: Date
        public var bitrateKbps: Int
        public var qualityBars: Int          // 0…4

        public init(stateLabel: String, isLive: Bool, startedAt: Date, bitrateKbps: Int, qualityBars: Int) {
            self.stateLabel = stateLabel
            self.isLive = isLive
            self.startedAt = startedAt
            self.bitrateKbps = bitrateKbps
            self.qualityBars = qualityBars
        }
    }
    public var serverLabel: String           // "Home MediaMTX" / "Field relay" / etc.
    public init(serverLabel: String) { self.serverLabel = serverLabel }
}
