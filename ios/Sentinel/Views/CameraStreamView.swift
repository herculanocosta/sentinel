//
//  CameraStreamView.swift
//  Live preview + the record / switch-camera / overlay toggle controls. Mirrors what the Android
//  Camera2Fragment shows.
//

import SwiftUI
import HaishinKit
import AVFoundation

struct CameraStreamView: View {
    @EnvironmentObject var deps: AppDependencies
    @State private var currentPreset: ServerPreset = ServerPreset(name: "Current")
    @State private var showingPresets = false
    @AppStorage(Pref.textOverlay) private var overlayOn: Bool = false

    var body: some View {
        ZStack {
            // The HaishinKit preview is wired into the camera service once we have a stream.
            CameraPreview(streaming: deps.streaming)
                .ignoresSafeArea()

            VStack {
                HStack {
                    statePill
                    Spacer()
                    Button { showingPresets = true } label: {
                        Image(systemName: "server.rack")
                            .padding(10).background(.thinMaterial, in: Circle())
                    }
                }
                .padding()

                Spacer()

                // Bottom control bar
                HStack(spacing: 28) {
                    controlButton(systemImage: "arrow.triangle.2.circlepath.camera") {
                        Task { await deps.streaming.cameraService.switchCamera(on: nil) }
                    }
                    recordButton
                    controlButton(systemImage: overlayOn ? "text.below.photo.fill" : "text.below.photo") {
                        overlayOn.toggle()
                    }
                    .tint(overlayOn ? Color.accentColor : .white)
                }
                .padding(.bottom, 30)
            }
        }
        .navigationBarBackButtonHidden(false)
        .sheet(isPresented: $showingPresets) {
            NavigationStack { ServerPresetsView() }
        }
        .task {
            deps.location.start()
            // Seed the current preset from the live STREAM_* prefs.
            currentPreset = deps.presets.captureCurrent(name: "Current")
        }
    }

    @ViewBuilder
    private var statePill: some View {
        HStack(spacing: 6) {
            Circle().fill(stateColor).frame(width: 8, height: 8)
            Text(stateText).font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(.thinMaterial, in: Capsule())
    }

    private var stateColor: Color {
        switch deps.streaming.state {
        case .idle: return .gray
        case .connecting, .reconnecting: return .orange
        case .live: return .green
        case .failed: return .red
        }
    }
    private var stateText: String {
        switch deps.streaming.state {
        case .idle: return "Ready"
        case .connecting: return "Connecting…"
        case .live: return "Live"
        case .reconnecting: return "Reconnecting…"
        case .failed(let e): return e
        }
    }

    @ViewBuilder
    private func controlButton(systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title2)
                .foregroundStyle(.white)
                .frame(width: 52, height: 52)
                .background(.ultraThinMaterial, in: Circle())
        }
    }

    @ViewBuilder
    private var recordButton: some View {
        Button {
            Task {
                if case .live = deps.streaming.state {
                    await deps.streaming.stop()
                } else {
                    let p = deps.presets.captureCurrent(name: "Current")
                    await deps.streaming.start(preset: p)
                }
            }
        } label: {
            Circle()
                .fill(.white)
                .frame(width: 72, height: 72)
                .overlay(
                    Image(systemName: isStreaming ? "stop.fill" : "record.circle")
                        .font(.system(size: 28))
                        .foregroundStyle(.red)
                )
                .shadow(radius: 4)
        }
    }
    private var isStreaming: Bool { if case .live = deps.streaming.state { return true }; return false }
}

/// HaishinKit's preview UIView wrapped for SwiftUI.
struct CameraPreview: UIViewRepresentable {
    let streaming: StreamingService

    func makeUIView(context: Context) -> MTHKView {
        let view = MTHKView(frame: .zero)
        view.videoGravity = .resizeAspectFill
        // The view binds to the stream once one exists; until then it shows black.
        // CameraStreamView's task() seeds the preset; user taps Record to start.
        return view
    }
    func updateUIView(_ uiView: MTHKView, context: Context) {
        // Attach the active stream so it renders the camera feed (HaishinKit handles this).
        // Implemented as a lookup against the StreamingService — in this scaffold we don't yet
        // expose the IOStream publicly; wire up on first stream-start in a follow-up.
    }
}
