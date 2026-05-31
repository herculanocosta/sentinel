//
//  CoTEvent.swift
//  Minimal CoT XML builder for SENTINEL's video marker. Mirrors the Android cot/* classes —
//  same `b-i-v` type so ATAK renders the camera icon, same `<__video>` + structured
//  `<ConnectionEntry>` parsed from the viewer URL.
//

import Foundation

/// Output of `CoTEvent.toXML(...)` — a single Cursor-on-Target event string ready to send to a
/// TAK server over TCP/TLS or multicast.
struct CoTEvent {
    let uid: String
    let type: String        // "b-i-v" for a video feed; ATAK renders a camera icon
    let callsign: String
    let viewerURL: String?  // when set, included as <__video> with structured <ConnectionEntry>
    let alias: String?      // optional friendly name shown in ATAK's video pane
    let lat: Double         // 9999999 when unknown
    let lon: Double
    let hae: Double
    let staleSeconds: TimeInterval

    init(uid: String,
         type: String = "b-i-v",
         callsign: String,
         viewerURL: String? = nil,
         alias: String? = nil,
         lat: Double = 9_999_999,
         lon: Double = 9_999_999,
         hae: Double = 9_999_999,
         staleSeconds: TimeInterval = 300) {
        self.uid = uid
        self.type = type
        self.callsign = callsign
        self.viewerURL = viewerURL
        self.alias = alias
        self.lat = lat
        self.lon = lon
        self.hae = hae
        self.staleSeconds = staleSeconds
    }

    /// Build the CoT XML. The `<ConnectionEntry>` is parsed from the viewer URL — ATAK needs the
    /// structured entry to play RTSP/RTMP/SRT (a bare `url=` only works reliably for plain HTTP).
    func toXML(now: Date = Date()) -> String {
        let df = ISO8601DateFormatter()
        df.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let nowS = df.string(from: now)
        let staleS = df.string(from: now.addingTimeInterval(staleSeconds))

        var detail = """
        <detail>
        <contact callsign="\(xml(callsign))"/>
        """

        if let url = viewerURL?.trimmingCharacters(in: .whitespaces), !url.isEmpty,
           let ce = ConnectionEntry.parse(url: url, alias: alias ?? callsign, feedUID: "\(uid)-feed") {
            let aliasS = (alias ?? callsign)
            detail += """
            <__video url="\(xml(ce.cleanURL))" uid="\(uid)-feed" sender="\(xml(callsign))" alias="\(xml(aliasS))">
            <ConnectionEntry address="\(xml(ce.host))" alias="\(xml(aliasS))" uid="\(uid)-feed" port="\(ce.port)" path="\(xml(ce.path))" protocol="\(ce.proto)" rtspReliable="\(ce.rtspReliable)" networkTimeout="5000" bufferTime="-1" roverPort="-1" ignoreEmbeddedKLV="false"/>
            </__video>
            """
        }
        detail += "</detail>"

        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="\(xml(uid))" type="\(type)" how="m-g" time="\(nowS)" start="\(nowS)" stale="\(staleS)">
        <point lat="\(lat)" lon="\(lon)" hae="\(hae)" ce="9999999" le="9999999"/>
        \(detail)
        </event>
        """
    }

    /// XML-attribute-escape. Good enough for callsigns and URLs.
    private func xml(_ s: String) -> String {
        s.replacingOccurrences(of: "&",  with: "&amp;")
         .replacingOccurrences(of: "<",  with: "&lt;")
         .replacingOccurrences(of: ">",  with: "&gt;")
         .replacingOccurrences(of: "\"", with: "&quot;")
         .replacingOccurrences(of: "'",  with: "&apos;")
    }
}

/// Parsed shape of a viewer URL, ready to slot into `<ConnectionEntry>` attributes.
struct ConnectionEntry {
    let host: String
    let port: Int
    let path: String         // includes leading "/" for rtsp; "?streamid=…" for SRT
    let proto: String        // rtsp/rtmp/srt/udp — lowercase, ATAK's accepted set
    let rtspReliable: Int    // 1 when "?tcp" was on the viewer URL or proto is rtsp by default
    let cleanURL: String     // url stripped of ?tcp, kept verbatim for ATAK to play

    static func parse(url: String, alias: String, feedUID: String) -> ConnectionEntry? {
        var work = url.trimmingCharacters(in: .whitespaces)
        // scheme
        guard let schemeEnd = work.range(of: "://") else { return nil }
        var scheme = String(work[..<schemeEnd.lowerBound]).lowercased()
        work = String(work[schemeEnd.upperBound...])
        // query
        var query = ""
        if let q = work.firstIndex(of: "?") {
            query = String(work[work.index(after: q)...])
            work = String(work[..<q])
        }
        // user:pass@
        if let at = work.firstIndex(of: "@") {
            work = String(work[work.index(after: at)...])
        }
        // host[:port]/path
        var hostport = work
        var pathPart = ""
        if let slash = work.firstIndex(of: "/") {
            hostport = String(work[..<slash])
            pathPart = String(work[slash...])     // includes the leading "/"
        }
        var host = hostport
        var port = 8554
        if let colon = hostport.lastIndex(of: ":") {
            host = String(hostport[..<colon])
            port = Int(hostport[hostport.index(after: colon)...]) ?? 8554
        }
        // ATAK has no rtsps — normalise.
        if scheme == "rtsps" { scheme = "rtsp" }

        let isTCP = query.lowercased().contains("tcp")
        let reliable = (scheme == "udp") ? 0 : (isTCP ? 1 : 0)

        let cePath: String
        let clean: String
        if scheme == "srt" {
            cePath = query.isEmpty ? pathPart : "?\(query)"
            clean = "\(scheme)://\(host):\(port)\(query.isEmpty ? "" : "?\(query)")"
        } else {
            cePath = pathPart
            clean = "\(scheme)://\(host):\(port)\(pathPart)"
        }

        return ConnectionEntry(host: host, port: port, path: cePath,
                               proto: scheme, rtspReliable: reliable, cleanURL: clean)
    }
}
