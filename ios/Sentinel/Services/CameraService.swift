//
//  CameraService.swift
//  AVCaptureSession wrapper. Picks the best available camera device — preferring the multi-lens
//  virtual device (.builtInTripleCamera / .builtInDualWideCamera) when present so we get continuous
//  optical zoom across UW/Wide/Tele without re-binding sessions. Adds tap-to-focus + AE lock and
//  smooth zoom (pinch / programmatic).
//

import Foundation
import AVFoundation
import HaishinKit
import UIKit

@MainActor
final class CameraService: NSObject, ObservableObject {
    @Published private(set) var position: AVCaptureDevice.Position = .back
    @Published private(set) var torchOn = false
    @Published private(set) var zoom: CGFloat = 1.0
    @Published private(set) var zoomRange: ClosedRange<CGFloat> = 1.0...10.0
    @Published private(set) var availableLenses: [LensStop] = []

    private let defaults = UserDefaults.standard

    /// A discrete optical zoom stop the user can tap (e.g. .5x / 1x / 3x on iPhone Pro).
    struct LensStop: Identifiable, Hashable {
        let id = UUID()
        let label: String        // ".5×", "1×", "3×"
        let zoomFactor: CGFloat
    }

    // MARK: - Device discovery

    /// Pick the best camera for the current position. iPhone Pro: builtInTripleCamera (UW/Wide/Tele
    /// fused into one device with a continuous virtual zoom). Otherwise builtInDualWideCamera
    /// (UW/Wide), then fall back to plain wide.
    private func currentCamera() -> AVCaptureDevice? {
        let preferred: [AVCaptureDevice.DeviceType] = [
            .builtInTripleCamera,
            .builtInDualWideCamera,
            .builtInDualCamera,
            .builtInWideAngleCamera
        ]
        for type in preferred {
            if let dev = AVCaptureDevice.default(type, for: .video, position: position) {
                return dev
            }
        }
        return nil
    }

    // MARK: - Permission gate

    func ensureRunning() async throws {
        let camOK = await AVCaptureDevice.requestAccess(for: .video)
        let micOK = await AVCaptureDevice.requestAccess(for: .audio)
        guard camOK, micOK else { throw CameraError.permissionDenied }
    }

    // MARK: - Attach to HaishinKit

    func attach(to stream: IOStream, overlay: OverlayRenderer) async {
        guard let cam = currentCamera() else { return }
        let mic = AVCaptureDevice.default(for: .audio)

        try? await stream.attachCamera(cam) { videoUnit in
            videoUnit.isVideoMirrored = false
            videoUnit.preferredVideoStabilizationMode = .auto
            videoUnit.colorFormat = kCVPixelFormatType_32BGRA
            videoUnit.effects = [overlay.filterEffect]
            // Reliability mode: cap to a sustainable rate.
            let reliability = self.defaults.object(forKey: Pref.reliabilityMode) as? Bool
                              ?? PrefDefaults.reliabilityMode
            if reliability { videoUnit.frameRate = 24 }
        }
        if let mic = mic { try? await stream.attachAudio(mic) }

        // Compute the zoom range + named lens stops from this device's optical switch points.
        refreshZoomCapabilities(for: cam)
    }

    func switchCamera(on stream: IOStream?) async {
        position = (position == .back) ? .front : .back
        guard let stream = stream, let cam = currentCamera() else { return }
        try? await stream.attachCamera(cam)
        refreshZoomCapabilities(for: cam)
    }

    func setTorch(_ on: Bool) {
        guard let cam = currentCamera(), cam.hasTorch else { return }
        try? cam.lockForConfiguration()
        cam.torchMode = on ? .on : .off
        cam.unlockForConfiguration()
        torchOn = on
    }

    // MARK: - Zoom

    /// Programmatic zoom — clamps + smoothly ramps to avoid optical-switch judder. UI binds to
    /// `zoom` so pinch gestures and the lens-pill taps converge here.
    func setZoom(_ requested: CGFloat, rate: Float = 1.5) {
        guard let cam = currentCamera() else { return }
        let z = min(max(requested, zoomRange.lowerBound), zoomRange.upperBound)
        do {
            try cam.lockForConfiguration()
            // ramp() makes optical lens switching look continuous (no harsh cut).
            cam.ramp(toVideoZoomFactor: z, withRate: rate)
            cam.unlockForConfiguration()
            zoom = z
        } catch {
            NSLog("setZoom failed: \(error)")
        }
    }

    /// Tap-to-focus + tap-to-meter at a normalised device point (0..1, 0..1). Re-enables AE.
    func focusAndExpose(atDevicePoint p: CGPoint) {
        guard let cam = currentCamera() else { return }
        do {
            try cam.lockForConfiguration()
            if cam.isFocusPointOfInterestSupported {
                cam.focusPointOfInterest = p
                cam.focusMode = cam.isFocusModeSupported(.autoFocus) ? .autoFocus : .continuousAutoFocus
            }
            if cam.isExposurePointOfInterestSupported {
                cam.exposurePointOfInterest = p
                cam.exposureMode = cam.isExposureModeSupported(.autoExpose) ? .autoExpose : .continuousAutoExposure
            }
            cam.unlockForConfiguration()
        } catch {
            NSLog("focusAndExpose failed: \(error)")
        }
    }

    /// Lock auto-exposure + auto-focus at the current setpoint (toggle).
    func toggleAELock() {
        guard let cam = currentCamera() else { return }
        do {
            try cam.lockForConfiguration()
            if cam.exposureMode == .locked {
                if cam.isExposureModeSupported(.continuousAutoExposure) { cam.exposureMode = .continuousAutoExposure }
                if cam.isFocusModeSupported(.continuousAutoFocus)       { cam.focusMode = .continuousAutoFocus }
            } else {
                if cam.isExposureModeSupported(.locked) { cam.exposureMode = .locked }
                if cam.isFocusModeSupported(.locked)    { cam.focusMode = .locked }
            }
            cam.unlockForConfiguration()
        } catch {
            NSLog("toggleAELock failed: \(error)")
        }
    }

    private func refreshZoomCapabilities(for cam: AVCaptureDevice) {
        // The virtualDeviceSwitchOverVideoZoomFactors property exposes the optical switch
        // points (e.g. [2, 6] on a TripleCamera, where 2× hands off from UW→Wide and 6× → Tele).
        // We turn them into named stops + clamp the slider range to the device's max.
        let max = min(cam.activeFormat.videoMaxZoomFactor, 10)
        zoomRange = 1.0 ... max
        zoom = cam.videoZoomFactor

        let switchPoints = cam.virtualDeviceSwitchOverVideoZoomFactors.map { CGFloat(truncating: $0) }
        var stops: [LensStop] = []
        // Always include 1× as a baseline.
        // If UW is present (TripleCamera or DualWide), the device's min zoom is 0.5 (we expose it as 0.5× though internal zoom factor remains 1).
        if cam.deviceType == .builtInTripleCamera || cam.deviceType == .builtInDualWideCamera {
            stops.append(LensStop(label: ".5×", zoomFactor: 1.0))    // UW
            stops.append(LensStop(label: "1×",  zoomFactor: 2.0))    // Wide kicks in
        } else {
            stops.append(LensStop(label: "1×",  zoomFactor: 1.0))
        }
        // Telephoto switch (TripleCamera only) — second value in switchPoints.
        if switchPoints.count >= 2 {
            let teleZoom = switchPoints[1]
            let lensLabel: String
            if teleZoom >= 5.5 { lensLabel = "5×" }
            else if teleZoom >= 4.5 { lensLabel = "5×" }
            else if teleZoom >= 3.5 { lensLabel = "3×" }
            else { lensLabel = String(format: "%.0f×", teleZoom / 2.0) }
            stops.append(LensStop(label: lensLabel, zoomFactor: teleZoom))
        }
        availableLenses = stops
    }
}

enum CameraError: LocalizedError {
    case permissionDenied
    var errorDescription: String? {
        switch self {
        case .permissionDenied: return "Camera or microphone permission was denied. Enable in Settings."
        }
    }
}
