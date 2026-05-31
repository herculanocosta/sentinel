//
//  SettingsView.swift
//  Main settings — grouped by Video, Streaming, ATAK, About.
//

import SwiftUI

struct SettingsView: View {
    @AppStorage(Pref.reliabilityMode) private var reliabilityMode: Bool = PrefDefaults.reliabilityMode
    @AppStorage(Pref.textOverlay) private var textOverlay: Bool = false
    @AppStorage(Pref.textOverlayUTC) private var textOverlayUTC: Bool = PrefDefaults.textOverlayUTC
    @AppStorage(Pref.videoBitrate) private var videoBitrate: Int = PrefDefaults.videoBitrate
    @AppStorage(Pref.videoFPS) private var videoFPS: Int = PrefDefaults.videoFPS
    @AppStorage(Pref.videoCodec) private var videoCodec: String = PrefDefaults.videoCodec

    @AppStorage(Pref.atakSendCoT) private var atakSendCoT: Bool = false
    @AppStorage(Pref.atakServerAddress) private var atakServerAddress: String = ""
    @AppStorage(Pref.atakServerPort) private var atakServerPort: String = PrefDefaults.atakServerPort
    @AppStorage(Pref.atakCallsign) private var atakCallsign: String = PrefDefaults.atakCallsign
    @AppStorage(Pref.atakSSL) private var atakSSL: Bool = false

    var body: some View {
        Form {
            Section("Video") {
                Toggle("Reliability mode", isOn: $reliabilityMode)
                Text("Caps capture to 480p / 24fps for the most stable broadcast on weaker iPhones.")
                    .font(.caption).foregroundStyle(.secondary)

                Picker("Codec", selection: $videoCodec) {
                    Text("H.264").tag("h264")
                    Text("HEVC (H.265)").tag("hevc")
                }
                Stepper("Frame rate: \(videoFPS) fps", value: $videoFPS, in: 15...60, step: 1)
                LabeledContent("Bitrate") {
                    TextField("kbps", value: $videoBitrate, format: .number)
                        .keyboardType(.numberPad).multilineTextAlignment(.trailing)
                }
            }
            Section("Overlay") {
                Toggle("Burn-in timestamp / GPS", isOn: $textOverlay)
                Toggle("UTC (off = local time)", isOn: $textOverlayUTC).disabled(!textOverlay)
            }
            Section("Streaming") {
                NavigationLink("Server presets", destination: ServerPresetsView())
            }
            Section("ATAK") {
                Toggle("Publish video marker on TAK map", isOn: $atakSendCoT)
                LabeledContent("Marker name") {
                    TextField("SENTINEL", text: $atakCallsign).multilineTextAlignment(.trailing)
                }
                LabeledContent("TAK Server") {
                    TextField("host", text: $atakServerAddress)
                        .textInputAutocapitalization(.never).keyboardType(.URL).multilineTextAlignment(.trailing)
                }
                LabeledContent("Port") {
                    TextField("8089", text: $atakServerPort).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                }
                Toggle("TLS (recommended)", isOn: $atakSSL)
            }
            Section("About") {
                LabeledContent("Version", value: "2.0.0")
                Link("Repository", destination: URL(string: "https://github.com/herculanocosta/sentinel")!)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}
