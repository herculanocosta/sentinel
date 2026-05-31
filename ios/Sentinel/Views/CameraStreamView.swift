//
//  CameraStreamView.swift
//  Live preview + record / switch-camera / overlay toggle controls. Mirrors what the Android
//  Camera2Fragment shows, with iOS niceties:
//    • Quality bars + bitrate pill (top right)
//    • Thermal/battery banner (top center) when applicable
//    • Pre-flight modal before going live
//    • Live Activity is fired in the streaming service on state changes
//

import SwiftUI
import HaishinKit
import AVFoundation

struct CameraStreamView: View {
    @EnvironmentObject var deps: AppDependencies
    @AppStorage(Pref.textOverlay) private var overlayOn: Bool = false
    @AppStorage(Pref.recordVideo) private var recordLocally: Bool = false
    @State private var showingPresets = false
    @State private var showingPreflight = false
    @State private var showingCameraPicker = false
    @State private var pendingPreset: ServerPreset?

    var body: some View {
        ZStack {
            CameraPreview(stream: deps.streaming.activeStream)
                .ignoresSafeArea()

            VStack {
                topBar
                Spacer()
                bottomControls
            }
        }
        .sheet(isPresented: $showingPresets) {
            NavigationStack { ServerPresetsView() }
        }
        .sheet(isPresented: $showingPreflight) {
            if let preset = pendingPreset {
                PreflightCheckView(preset: preset) {
                    Task { await deps.streaming.start(preset: preset, recordLocally: recordLocally) }
                }
            }
        }
        .sheet(isPresented: $showingCameraPicker) { CameraPickerView() }
        .task { deps.location.start() }
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        VStack(spacing: 8) {
            HStack {
                statePill
                Spacer()
                qualityPill
                Spacer()
                Button { showingPresets = true } label: {
                    Image(systemName: "server.rack")
                        .padding(10).background(.thinMaterial, in: Circle())
                }
            }
            .padding(.horizontal)

            if let warning = deps.streaming.thermalWarning {
                Label(warning, systemImage: "thermometer.medium")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(.yellow.opacity(0.85), in: Capsule())
                    .foregroundStyle(.black)
            }
        }
        .padding(.top, 12)
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

    @ViewBuilder
    private var qualityPill: some View {
        if deps.streaming.state.isLive || deps.streaming.state.isReconnecting {
            HStack(spacing: 6) {
                bars(deps.streaming.stats.qualityBars)
                Text("\(deps.streaming.stats.bitrateKbps) kb/s")
                    .font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.thinMaterial, in: Capsule())
        }
    }

    private func bars(_ count: Int) -> some View {
        HStack(spacing: 2) {
            ForEach(0..<4) { idx in
                Capsule()
                    .fill(idx < count ? barColor(count) : Color.white.opacity(0.25))
                    .frame(width: 3, height: CGFloat(6 + idx * 2))
            }
        }
    }
    private func barColor(_ n: Int) -> Color {
        switch n { case 0,1: return .red; case 2: return .orange; default: return .green }
    }

    private var stateColor: Color {
        switch deps.streaming.state {
        case .idle: return .gray
        case .connecting: return .orange
        case .reconnecting: return .orange
        case .live: return .green
        case .failed: return .red
        }
    }
    private var stateText: String {
        switch deps.streaming.state {
        case .idle: return "Ready"
        case .connecting: return "Connecting…"
        case .live: return "Live"
        case .reconnecting(let n): return "Reconnecting (\(n))"
        case .failed(let e): return e
        }
    }

    // MARK: - Bottom controls

    @ViewBuilder
    private var bottomControls: some View {
        HStack(spacing: 28) {
            // Short tap: flip front/back. Long press: full camera picker (built-in + external).
            controlButton(systemImage: "arrow.triangle.2.circlepath.camera") {
                Task { await deps.streaming.cameraService.switchCamera(on: deps.streaming.activeStream) }
            }
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                showingCameraPicker = true
            })
            recordButton
            controlButton(systemImage: overlayOn ? "text.below.photo.fill" : "text.below.photo") {
                overlayOn.toggle()
            }
            .tint(overlayOn ? Color.accentColor : .white)
        }
        .padding(.bottom, 30)
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
            if deps.streaming.state.isLive {
                Task { await deps.streaming.stop() }
            } else {
                // Run pre-flight against the live preset before going live.
                let p = deps.presets.captureCurrent(name: "Current")
                pendingPreset = p
                showingPreflight = true
            }
        } label: {
            Circle()
                .fill(.white)
                .frame(width: 72, height: 72)
                .overlay(
                    Image(systemName: deps.streaming.state.isLive ? "stop.fill" : "record.circle")
                        .font(.system(size: 28))
                        .foregroundStyle(.red)
                )
                .shadow(radius: 4)
        }
    }
}

/// HaishinKit's MTHKView wrapped for SwiftUI. Updates whenever the active stream changes so the
/// preview binds to it once the stream object exists.
struct CameraPreview: UIViewRepresentable {
    let stream: IOStream?

    func makeUIView(context: Context) -> MTHKView {
        let view = MTHKView(frame: .zero)
        view.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: MTHKView, context: Context) {
        // Attach the current stream to the preview view. nil → black; HaishinKit handles teardown.
        Task { @MainActor in
            if let s = stream { await uiView.attachStream(s) }
        }
    }
}
