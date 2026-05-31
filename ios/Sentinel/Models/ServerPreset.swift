//
//  ServerPreset.swift
//  Mirrors the Android model exactly so JSON exports are interchangeable across platforms.
//

import Foundation

/// A saved streaming destination. Two URL surfaces:
///  • **Publish** — protocol/address/port/path/user/pass/tcp that SENTINEL pushes to.
///  • **Viewer**  — the URL advertised to ATAK in the CoT `<__video>` (peers tap → watch). May
///    differ from publish (SRT `read:` vs `publish:`, RTSP `?tcp` for forced TCP playback, etc.).
struct ServerPreset: Identifiable, Hashable, Codable {
    var id: UUID = .init()
    var name: String = ""
    var protocolName: String = "rtsp"       // rtsp, rtsps, rtmp, rtmps, srt, udp
    var address: String = ""
    var port: String = "8554"
    var path: String = ""
    var username: String = ""
    var password: String = ""
    var tcp: Bool = false
    /// Viewer URL sent to ATAK in CoT. Blank → auto-derive from the publish fields.
    var observerURL: String = ""

    enum CodingKeys: String, CodingKey {
        case id, name
        case protocolName = "protocol"
        case address, port, path, username, password, tcp, observerURL
    }

    /// One-line summary used in the preset list.
    var summary: String { "\(protocolName)://\(address):\(port)/\(path)" }

    /// Auto-derive a sensible viewer URL from the publish fields. Mirrors the Android
    /// `ServerPreset.suggestObserverUrl` behaviour exactly.
    static func suggestObserverURL(protocolName: String, address: String, port: String, path: String) -> String {
        let proto = protocolName.lowercased()
        switch proto {
        case "rtmp", "rtmps":
            return "\(proto)://\(address):\(port.isEmpty ? "1935" : port)/\(path)"
        case "srt":
            return "srt://\(address):\(port)?streamid=read:\(path)"
        case "udp":
            return "udp://\(address):\(port)"
        default:    // rtsp / rtsps
            return "\(proto)://\(address):\(port.isEmpty ? "8554" : port)/\(path)?tcp"
        }
    }
}

/// MediaMTX default ingest ports per protocol — used to auto-fill the Port field on protocol
/// change in the editor.
enum DefaultPorts {
    static func forProtocol(_ p: String) -> String {
        switch p.lowercased() {
        case "rtmp", "rtmps": return "1935"
        case "srt":           return "8890"
        case "udp":           return "1234"
        default:              return "8554"  // rtsp / rtsps
        }
    }
    static let knownDefaults: Set<String> = ["8554", "1935", "8890", "1234"]
}

/// Protocol pickers in the UI. `publishProtocols` matches what SENTINEL can push;
/// `viewerProtocols` is what ATAK can play (URLs only — no rtsps/rtmps separate from rtsp/rtmp).
enum StreamProtocols {
    static let publish: [String] = ["rtsp", "rtsps", "rtmp", "rtmps", "srt", "udp"]
    static let viewer:  [String] = ["rtsp", "rtmp", "srt"]

    /// Map a publish protocol to the playable viewer protocol ATAK should default to.
    static func mapToViewer(_ publish: String) -> String {
        switch publish.lowercased() {
        case "srt":            return "srt"
        case "rtmp", "rtmps":  return "rtmp"
        default:               return "rtsp"
        }
    }
}
