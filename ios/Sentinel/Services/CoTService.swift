//
//  CoTService.swift
//  Publishes SENTINEL's video marker to a TAK server. Uses Network framework directly so the
//  GoPro WiFi route doesn't accidentally steal the connection — when active, we'll pin the CoT
//  TCP socket to .cellular so it always uses the carrier link.
//

import Foundation
import Network
import CoreLocation
import UIKit

@MainActor
final class CoTService: ObservableObject {
    @Published private(set) var connected = false
    @Published private(set) var lastError: String?

    private var connection: NWConnection?
    private var heartbeatTask: Task<Void, Never>?
    private let defaults = UserDefaults.standard

    /// Publishes a marker CoT and starts a heartbeat re-asserting it every 3 s (ATAK keeps
    /// re-broadcasting its self marker without the `__video`, so we need to refresh).
    func startPublishing(viewerURL: String?, location: CLLocation?) async {
        guard defaults.bool(forKey: Pref.atakSendCoT) else { return }
        guard let host = defaults.string(forKey: Pref.atakServerAddress), !host.isEmpty,
              let portStr = defaults.string(forKey: Pref.atakServerPort),
              let port = UInt16(portStr).flatMap(NWEndpoint.Port.init(rawValue:)) else {
            lastError = "ATAK server address/port not set"
            return
        }
        await stopPublishing()
        // Pin to cellular so the GoPro WiFi (when active) can't divert this connection.
        let params: NWParameters
        if defaults.bool(forKey: Pref.atakSSL) {
            params = .tls
        } else {
            params = .tcp
        }
        params.requiredInterfaceType = .cellular   // best-effort; falls back to wifi if no cell
        let conn = NWConnection(host: .init(host), port: port, using: params)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .ready: self?.connected = true; self?.lastError = nil
                case .failed(let err): self?.connected = false; self?.lastError = err.localizedDescription
                case .cancelled: self?.connected = false
                default: break
                }
            }
        }
        conn.start(queue: .global(qos: .utility))

        // Heartbeat: every 3 seconds, re-send the marker so it stays fresh on the TAK map.
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.publishOnce(viewerURL: viewerURL, location: location)
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }

    func stopPublishing() async {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        // Best-effort revoke: send a marker without <__video> so peers drop the feed.
        await publishOnce(viewerURL: nil, location: nil)
        connection?.cancel()
        connection = nil
        connected = false
    }

    private func publishOnce(viewerURL: String?, location: CLLocation?) async {
        guard let conn = connection else { return }
        let uid = defaults.string(forKey: Pref.uid) ?? UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        let callsign = defaults.string(forKey: Pref.atakCallsign) ?? PrefDefaults.atakCallsign
        let type = defaults.string(forKey: Pref.atakMarkerType) ?? PrefDefaults.atakMarkerType
        let alias = defaults.string(forKey: Pref.atakVideoAlias)

        let event = CoTEvent(
            uid: "\(uid)-video",
            type: type,
            callsign: callsign,
            viewerURL: viewerURL,
            alias: alias?.isEmpty == false ? alias : callsign,
            lat: location?.coordinate.latitude ?? 9_999_999,
            lon: location?.coordinate.longitude ?? 9_999_999,
            hae: location?.altitude ?? 9_999_999
        )

        let xml = event.toXML()
        let data = (xml + "\n").data(using: .utf8) ?? Data()
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            conn.send(content: data, completion: .contentProcessed { _ in cont.resume() })
        }
    }
}
