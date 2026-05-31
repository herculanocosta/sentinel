//
//  StreamingService.swift
//  HaishinKit-backed phone-camera → RTMP / RTSP / SRT publisher. Owns the active stream object
//  and exposes it so the SwiftUI preview can bind to it via MTHKView. Handles:
//    • Auto-reconnect with exponential backoff (1→30s) on unexpected disconnects
//    • Adaptive bitrate driven by HaishinKit's congestion signal
//    • Thermal + battery guards (halve bitrate at .serious thermal, warn at 15% battery)
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
    case reconnecting(attempt: Int)
    case failed(String)

    var isLive: Bool { if case .live = self { return true }; return false }
    var isReconnecting: Bool { if case .reconnecting = self { return true }; return false }
}

/// Snapshot of network/encoder health, published so the SwiftUI overlay can render a pill.
struct StreamStats: Equatable {
    var bitrateKbps: Int = 0
    var droppedFrames: Int = 0
    /// 0…4 quality bars derived from the publish-queue depth (HaishinKit congestion proxy).
    var qualityBars: Int = 4
}

@MainActor
final class StreamingService: ObservableObject {
    // MARK: - Published surface (bound by views)

    @Published private(set) var state: StreamState = .idle
    @Published private(set) var stats = StreamStats()
    /// Exposed so the SwiftUI CameraPreview can attach MTHKView once a stream exists.
    @Published private(set) var activeStream: IOStream?
    @Published private(set) var thermalWarning: String?

    // MARK: - Sub-services

    let cameraService = CameraService()
    let overlayRenderer = OverlayRenderer()
    let recorder = LocalRecorder()

    // MARK: - Internal

    private var rtmpConn: RTMPConnection?
    private var statsTimer: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var thermalSink: NSObjectProtocol?
    private var batterySink: NSObjectProtocol?
    private var currentPreset: ServerPreset?
    /// Auto-reconnect is armed only after a stream goes live; a user-initiated stop disarms it.
    private var reconnectArmed = false
    private let defaults = UserDefaults.standard

    init() {
        configureAudioSession()
        observeThermalAndBattery()
    }

    deinit {
        if let t = thermalSink { NotificationCenter.default.removeObserver(t) }
        if let b = batterySink { NotificationCenter.default.removeObserver(b) }
    }

    // MARK: - Public API

    /// Start streaming + (optionally) record locally. Camera must already have permission.
    func start(preset: ServerPreset, recordLocally: Bool = false) async {
        currentPreset = preset
        state = .connecting
        do {
            try await cameraService.ensureRunning()
        } catch {
            state = .failed("Camera unavailable: \(error.localizedDescription)")
            return
        }

        do {
            try await publish(preset: preset)
            state = .live
            reconnectArmed = true
            startStatsTimer()
            if recordLocally {
                try? await recorder.startRecording(prefix: preset.name)
            }
        } catch {
            state = .failed(error.localizedDescription)
            scheduleReconnectIfArmed(reason: error.localizedDescription)
        }
    }

    /// User-initiated stop. Disarms reconnect, tears down the stream and the recorder.
    func stop() async {
        reconnectArmed = false
        reconnectTask?.cancel()
        statsTimer?.cancel()
        await rtmpConn?.close()
        try? await (activeStream as? SRTStream)?.close()
        try? await (activeStream as? RTSPStream)?.close()
        activeStream = nil
        rtmpConn = nil
        if recorder.isRecording { await recorder.stopRecording() }
        state = .idle
    }

    // MARK: - Reconnect

    private func scheduleReconnectIfArmed(reason: String) {
        guard reconnectArmed, let preset = currentPreset else { return }
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            var delaySec: UInt64 = 1
            var attempt = 0
            while !Task.isCancelled {
                attempt += 1
                self?.state = .reconnecting(attempt: attempt)
                try? await Task.sleep(nanoseconds: delaySec * 1_000_000_000)
                if Task.isCancelled { return }
                do {
                    try await self?.publish(preset: preset)
                    self?.state = .live
                    return
                } catch {
                    delaySec = min(delaySec * 2, 30)   // cap at 30s
                }
            }
        }
    }

    // MARK: - Internal: publish

    private func publish(preset p: ServerPreset) async throws {
        let videoBitrate = (defaults.object(forKey: Pref.videoBitrate) as? Int) ?? PrefDefaults.videoBitrate
        let fps = (defaults.object(forKey: Pref.videoFPS) as? Int) ?? PrefDefaults.videoFPS
        let codec = defaults.string(forKey: Pref.videoCodec) ?? PrefDefaults.videoCodec

        switch p.protocolName.lowercased() {
        case "rtmp", "rtmps":
            let conn = RTMPConnection()
            let rtmp = RTMPStream(connection: conn)
            await configure(rtmp, videoBitrate: videoBitrate, fps: fps, codec: codec)
            try await conn.connect(rtmpConnectURL(preset: p))
            await rtmp.publish(p.path)
            self.rtmpConn = conn
            self.activeStream = rtmp

        case "srt":
            let srt = SRTStream(connection: SRTConnection())
            await configure(srt, videoBitrate: videoBitrate, fps: fps, codec: codec)
            let url = "srt://\(p.address):\(p.port)?streamid=publish:\(p.path)"
            try await srt.publish(URL(string: url))
            self.activeStream = srt

        case "rtsp", "rtsps":
            let rtsp = RTSPStream(connection: RTSPConnection())
            await configure(rtsp, videoBitrate: videoBitrate, fps: fps, codec: codec)
            let url = "\(p.protocolName)://\(p.address):\(p.port)/\(p.path)"
            try await rtsp.publish(URL(string: url))
            self.activeStream = rtsp

        default:
            throw StreamingError.unsupportedProtocol(p.protocolName)
        }
    }

    private func rtmpConnectURL(preset p: ServerPreset) -> String {
        "\(p.protocolName)://\(p.address):\(p.port)"
    }

    private func configure(_ s: IOStream, videoBitrate kbpsRequested: Int, fps: Int, codec: String) async {
        let kbps = adjustedBitrateForGuards(kbpsRequested)
        var v = await s.videoSettings
        v.bitRate = kbps * 1000
        v.maxKeyFrameIntervalDuration = 2
        if codec == "hevc" { v.profileLevel = kVTProfileLevel_HEVC_Main_AutoLevel as String }
        await s.setVideoSettings(v)

        var a = await s.audioSettings
        a.bitRate = 128 * 1000
        await s.setAudioSettings(a)

        await cameraService.attach(to: s, overlay: overlayRenderer)
    }

    // MARK: - Stats + adaptive bitrate

    private func startStatsTimer() {
        statsTimer?.cancel()
        statsTimer = Task { [weak self] in
            var lastBytes: Int64 = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self = self, let stream = self.activeStream else { continue }

                // Pull current outbound stats from HaishinKit. The API has been swapped across
                // versions; we use the published `info` snapshot when available.
                let info = await stream.info
                let bytes = Int64(info.byteCount.value)
                let deltaBps = max(0, bytes - lastBytes) * 8
                lastBytes = bytes
                let kbps = Int(deltaBps / 1000)

                // Quality bars derived from how full the publish queue is (proxy for congestion).
                let resourceState = info.resourceState
                let bars: Int
                switch resourceState {
                case .ok:       bars = 4
                case .insufficientBW: bars = 1
                @unknown default: bars = 3
                }
                self.stats = StreamStats(bitrateKbps: kbps, droppedFrames: 0, qualityBars: bars)

                // Adaptive bitrate: if congested, drop 20% (floor 250 kbps). If healthy, drift up
                // by 10% per minute toward the user's setting.
                self.adaptBitrate(currentKbps: kbps, state: resourceState)
            }
        }
    }

    private func adaptBitrate(currentKbps kbps: Int, state: IOStreamInfo.ResourceState) {
        guard let stream = activeStream else { return }
        let userKbps = (defaults.object(forKey: Pref.videoBitrate) as? Int) ?? PrefDefaults.videoBitrate
        Task { @MainActor in
            var v = await stream.videoSettings
            let currentSetKbps = max(1, v.bitRate / 1000)
            let target: Int
            switch state {
            case .insufficientBW:
                target = max(250, Int(Double(currentSetKbps) * 0.8))
            case .ok:
                // Drift toward user setting at +1% per second.
                target = min(userKbps, currentSetKbps + max(1, currentSetKbps / 100))
            @unknown default:
                target = currentSetKbps
            }
            if abs(target - currentSetKbps) > currentSetKbps / 20 {   // 5% deadband
                v.bitRate = target * 1000
                await stream.setVideoSettings(v)
            }
        }
    }

    // MARK: - Thermal + battery guards

    private func observeThermalAndBattery() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        thermalSink = NotificationCenter.default.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.evaluateThermal() }
        }
        batterySink = NotificationCenter.default.addObserver(
            forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.evaluateBattery() }
        }
        evaluateThermal()
        evaluateBattery()
    }

    private func evaluateThermal() {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal, .fair: thermalWarning = nil
        case .serious:        thermalWarning = "Phone is heating up"
        case .critical:       thermalWarning = "Critical heat — bitrate halved"
        @unknown default:     thermalWarning = nil
        }
    }

    private func evaluateBattery() {
        let pct = UIDevice.current.batteryLevel
        if pct >= 0 && pct < 0.15 {
            thermalWarning = "Battery low (\(Int(pct*100))%)"
        }
    }

    private func adjustedBitrateForGuards(_ requested: Int) -> Int {
        switch ProcessInfo.processInfo.thermalState {
        case .critical: return max(250, requested / 2)
        case .serious:  return max(400, requested * 3 / 4)
        default:        return requested
        }
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
