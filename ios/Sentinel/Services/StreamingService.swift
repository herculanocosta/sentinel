//
//  StreamingService.swift
//  HaishinKit-backed phone-camera → RTMP / RTSP / SRT publisher. Mirrors what pedroSG94 does on
//  the Android side: take the camera feed, encode H.264/HEVC, push to the configured server.
//
//  Public surface is intentionally tiny — the views just call `start(preset:)` / `stop()` and
//  observe `state`. All of the camera/session wiring lives in CameraService.
//

import Foundation
import AVFoundation
import HaishinKit
import SRTHaishinKit
import Combine
import UIKit

enum StreamState: Equatable {
    case idle
    case connecting
    case live
    case reconnecting
    case failed(String)
}

@MainActor
final class StreamingService: ObservableObject {
    @Published private(set) var state: StreamState = .idle
    @Published private(set) var lastBitrateKbps: Int = 0
    @Published private(set) var dropped: Int = 0

    let cameraService = CameraService()
    let overlayRenderer = OverlayRenderer()

    /// Currently active HaishinKit stream (RTMP/RTSP/SRT). nil when idle.
    private var stream: IOStream?
    /// RTMP connection (only when protocol is rtmp/rtmps).
    private var rtmpConn: RTMPConnection?

    private let defaults = UserDefaults.standard

    init() {
        // The "audio" UIBackgroundMode entitlement requires an active AVAudioSession in
        // .playAndRecord to actually keep us alive screen-off. Set it up once.
        configureAudioSession()
    }

    // MARK: - Public API

    /// Start streaming using the given preset. The capture session is wired up if not already.
    func start(preset: ServerPreset) async {
        state = .connecting
        do {
            try await cameraService.ensureRunning()
        } catch {
            state = .failed("Camera unavailable: \(error.localizedDescription)")
            return
        }

        let url = buildPublishURL(from: preset)
        do {
            try await publish(url: url, preset: preset)
            state = .live
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    func stop() async {
        await rtmpConn?.close()
        try? await (stream as? SRTStream)?.close()
        try? await (stream as? RTSPStream)?.close()
        stream = nil
        rtmpConn = nil
        state = .idle
    }

    // MARK: - Internal

    /// Build the URL HaishinKit expects from a preset.
    private func buildPublishURL(from p: ServerPreset) -> String {
        switch p.protocolName.lowercased() {
        case "rtmp", "rtmps":
            return "\(p.protocolName)://\(p.address):\(p.port)/\(p.path)"
        case "srt":
            return "srt://\(p.address):\(p.port)?streamid=publish:\(p.path)"
        case "rtsp", "rtsps":
            return "\(p.protocolName)://\(p.address):\(p.port)/\(p.path)"
        case "udp":
            return "udp://\(p.address):\(p.port)"
        default:
            return "\(p.protocolName)://\(p.address):\(p.port)/\(p.path)"
        }
    }

    private func publish(url: String, preset: ServerPreset) async throws {
        // Bitrate/codec settings shared by all three transports.
        let videoBitrate = (defaults.object(forKey: Pref.videoBitrate) as? Int) ?? PrefDefaults.videoBitrate
        let fps = (defaults.object(forKey: Pref.videoFPS) as? Int) ?? PrefDefaults.videoFPS
        let codec = defaults.string(forKey: Pref.videoCodec) ?? PrefDefaults.videoCodec

        switch preset.protocolName.lowercased() {
        case "rtmp", "rtmps":
            let conn = RTMPConnection()
            let rtmp = RTMPStream(connection: conn)
            await configure(rtmp, videoBitrate: videoBitrate, fps: fps, codec: codec)
            try await conn.connect(rtmpURL(url, address: preset.address, port: preset.port))
            await rtmp.publish(preset.path)
            self.rtmpConn = conn
            self.stream = rtmp

        case "srt":
            let srt = SRTStream(connection: SRTConnection())
            await configure(srt, videoBitrate: videoBitrate, fps: fps, codec: codec)
            try await srt.publish(URL(string: url))
            self.stream = srt

        case "rtsp", "rtsps":
            let rtsp = RTSPStream(connection: RTSPConnection())
            await configure(rtsp, videoBitrate: videoBitrate, fps: fps, codec: codec)
            try await rtsp.publish(URL(string: url))
            self.stream = rtsp

        default:
            throw StreamingError.unsupportedProtocol(preset.protocolName)
        }
    }

    /// HaishinKit's RTMP connect takes the base URL without the publish key.
    private func rtmpURL(_ full: String, address: String, port: String) -> String {
        // Strip path component for connect; path is passed to publish() separately.
        guard let scheme = full.range(of: "://") else { return full }
        let tail = full[scheme.upperBound...]
        if let slash = tail.firstIndex(of: "/") {
            return String(full[..<full.index(slash, offsetBy: 0)])
        }
        return full
    }

    /// Apply video/audio encoder settings to a HaishinKit stream.
    private func configure(_ s: IOStream, videoBitrate kbps: Int, fps: Int, codec: String) async {
        var v = await s.videoSettings
        v.bitRate = kbps * 1000
        v.maxKeyFrameIntervalDuration = 2
        if codec == "hevc" { v.profileLevel = kVTProfileLevel_HEVC_Main_AutoLevel as String }
        await s.setVideoSettings(v)

        var a = await s.audioSettings
        a.bitRate = 128 * 1000
        await s.setAudioSettings(a)

        // Hand the camera's capture session to HaishinKit. The CameraService owns the session.
        await cameraService.attach(to: s, overlay: overlayRenderer)
    }

    // MARK: - Audio session (background streaming)

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .videoRecording,
                                 options: [.defaultToSpeaker, .allowBluetooth, .mixWithOthers])
        try? session.setActive(true)
    }
}

enum StreamingError: LocalizedError {
    case unsupportedProtocol(String)
    var errorDescription: String? {
        switch self {
        case .unsupportedProtocol(let p): return "Unsupported stream protocol: \(p)"
        }
    }
}
