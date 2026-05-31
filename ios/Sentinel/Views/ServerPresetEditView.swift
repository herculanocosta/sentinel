//
//  ServerPresetEditView.swift
//  Publish / Viewer split editor — exactly matches the Android editor. Picking a publish protocol
//  auto-fills its standard port; picking a viewer protocol auto-rebuilds the URL (read: for SRT).
//

import SwiftUI

struct ServerPresetEditView: View {
    @State var preset: ServerPreset
    @State private var viewerProtocol: String = "rtsp"
    @State private var viewerEdited: Bool = false   // user typed in observer field
    @State private var viewerChosen: Bool = false   // user picked a viewer protocol explicitly
    let onSave: (ServerPreset) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("Preset") {
                TextField("Name", text: $preset.name).textInputAutocapitalization(.words)
            }
            Section("Publish — where SENTINEL sends") {
                Picker("Protocol", selection: $preset.protocolName) {
                    ForEach(StreamProtocols.publish, id: \.self) { Text($0).tag($0) }
                }
                .onChange(of: preset.protocolName) { _, newProto in
                    if preset.port.isEmpty || DefaultPorts.knownDefaults.contains(preset.port) {
                        preset.port = DefaultPorts.forProtocol(newProto)
                    }
                    if !viewerChosen && !viewerEdited {
                        viewerProtocol = StreamProtocols.mapToViewer(newProto)
                        rebuildObserver()
                    }
                }
                LabeledContent("Address") {
                    TextField("host or IP", text: $preset.address)
                        .textInputAutocapitalization(.never).keyboardType(.URL).multilineTextAlignment(.trailing)
                }
                LabeledContent("Port") {
                    TextField("port", text: $preset.port).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                }
                LabeledContent("Stream name / path") {
                    TextField("stream", text: $preset.path)
                        .textInputAutocapitalization(.never).multilineTextAlignment(.trailing)
                        .onChange(of: preset.path) { _, _ in if !viewerEdited { rebuildObserver() } }
                }
                if preset.protocolName.lowercased() == "srt" {
                    Text("SRT pushes as streamid=publish:\(preset.path); viewers use read:\(preset.path)")
                        .font(.caption).foregroundStyle(.secondary)
                }
                LabeledContent("Username (optional)") {
                    TextField("user", text: $preset.username).textInputAutocapitalization(.never).multilineTextAlignment(.trailing)
                }
                LabeledContent("Password (optional)") {
                    SecureField("pass", text: $preset.password).multilineTextAlignment(.trailing)
                }
                Toggle("Use TCP (RTSP)", isOn: $preset.tcp)
            }
            Section("Viewer — what ATAK opens") {
                Picker("Viewer protocol", selection: $viewerProtocol) {
                    ForEach(StreamProtocols.viewer, id: \.self) { Text($0).tag($0) }
                }
                .onChange(of: viewerProtocol) { _, _ in
                    viewerChosen = true; viewerEdited = false; rebuildObserver()
                }
                LabeledContent("Observer URL (blank = auto)") {
                    TextField("rtsp://… or srt://…", text: $preset.observerURL)
                        .textInputAutocapitalization(.never).keyboardType(.URL).multilineTextAlignment(.trailing)
                        .onChange(of: preset.observerURL) { _, _ in viewerEdited = true }
                }
                Button("Rebuild from fields") {
                    viewerEdited = false; rebuildObserver()
                }
            }
        }
        .navigationTitle(preset.name.isEmpty ? "New preset" : "Edit preset")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") {
                    if preset.name.isEmpty { preset.name = "Preset" }
                    if preset.port.isEmpty { preset.port = DefaultPorts.forProtocol(preset.protocolName) }
                    onSave(preset)
                }.bold()
            }
        }
        .onAppear {
            // Seed viewer state from the existing observer URL if any.
            if !preset.observerURL.isEmpty {
                viewerEdited = true
                viewerProtocol = schemeOf(preset.observerURL)
            } else {
                viewerProtocol = StreamProtocols.mapToViewer(preset.protocolName)
                rebuildObserver()
            }
        }
    }

    private func rebuildObserver() {
        preset.observerURL = ServerPreset.suggestObserverURL(
            protocolName: viewerProtocol,
            address: preset.address,
            port: DefaultPorts.forProtocol(viewerProtocol),
            path: preset.path
        )
    }

    private func schemeOf(_ url: String) -> String {
        guard let range = url.range(of: "://") else { return "rtsp" }
        let scheme = String(url[..<range.lowerBound]).lowercased()
        return StreamProtocols.mapToViewer(scheme)
    }
}
