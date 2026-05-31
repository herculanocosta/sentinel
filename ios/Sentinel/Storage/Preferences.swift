//
//  Preferences.swift
//  Central pref keys + `@AppStorage` defaults. One source of truth for every UserDefaults key.
//

import Foundation
import SwiftUI

enum Pref {
    // ---- Streaming -----------------------------------------------------------------------
    static let streamProtocol     = "stream_protocol"
    static let streamAddress      = "stream_address"
    static let streamPort         = "stream_port"
    static let streamPath         = "stream_path"
    static let streamUsername     = "stream_username"
    static let streamPassword     = "stream_password"
    static let streamTCP          = "stream_tcp"
    static let streamObserverURL  = "stream_observer_url"

    // ---- Video ---------------------------------------------------------------------------
    static let videoBitrate       = "video_bitrate"        // kbps
    static let videoFPS           = "video_fps"
    static let videoCodec         = "video_codec"          // h264 / hevc
    static let reliabilityMode    = "reliability_mode"     // caps to 854x480@24
    static let textOverlay        = "text_overlay"
    static let textOverlayUTC     = "text_overlay_utc"
    static let recordVideo        = "record_video"

    // ---- ATAK ----------------------------------------------------------------------------
    static let atakSendCoT        = "atak_send_cot"
    static let atakServerAddress  = "atak_server_address"
    static let atakServerPort     = "atak_server_port"
    static let atakCallsign       = "atak_callsign"
    static let atakMarkerType     = "atak_marker_type"
    static let atakVideoAlias     = "atak_video_alias"
    static let atakSSL            = "atak_ssl"
    static let atakTrustStorePath     = "atak_trust_store_path"
    static let atakTrustStorePassword = "atak_trust_store_password"
    static let atakClientCertPath     = "atak_client_cert_path"
    static let atakClientCertPassword = "atak_client_cert_password"

    // ---- Server presets ------------------------------------------------------------------
    static let serverPresets      = "server_presets"       // JSON array
    static let uid                = "device_uid"
}

/// Default values matching the Android build for cross-platform parity.
enum PrefDefaults {
    static let streamProtocol     = "rtsp"
    static let streamPort         = "8554"
    static let streamPath         = "my_path"
    static let videoBitrate       = 1500     // kbps — modest enough for cellular uplink
    static let videoFPS           = 30
    static let videoCodec         = "h264"
    static let reliabilityMode    = true
    static let textOverlayUTC     = true
    static let atakMarkerType     = "b-i-v"  // ATAK video-feed type (camera icon)
    static let atakCallsign       = "SENTINEL"
    static let atakServerPort     = "8089"   // TAK Server's TLS ingest port
}
