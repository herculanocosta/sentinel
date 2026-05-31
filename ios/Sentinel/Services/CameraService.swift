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
    /// All capture devices the user can pick from — built-in cameras + connected USB-C / UVC
    /// devices on iPad iOS 17+. Updated when the AV session reports a connect/disconnect.
    @Published private(set) var availableCameras: [CameraOption] = []
    /// Currently-selected camera. nil → auto (best built-in for `position`).
    @Published private(set) var selectedCamera: CameraOption?

    /// One row in the camera picker — wraps an `AVCaptureDevice` with a friendly label.
    struct CameraOption: Identifiable, Hashable {
        let id: String                  // device.uniqueID
        let label: String
        let isExternal: Bool
        let position: AVCaptureDevice.Position
        let deviceTypeRaw: String

        /// Look up the live AVCaptureDevice. Returns nil if the camera was unplugged.
        var device: AVCaptureDevice? { AVCaptureDevice(uniqueID: id) }
    }

    private let defaults = UserDefaults.standard
    private var deviceObserver: NSObjectProtocol?

    override init() {
        super.init()
        refreshAvailableCameras()
        // Re-scan whenever a device connects/disconnects (USB-C UVC plug events).
        deviceObserver = NotificationCenter.default.addObserver(
            forName: .AVCaptureDeviceWasConnected, object: nil, queue: .main
        ) { [weak self] _ in Task { @MainActor in self?.refreshAvailableCameras() } }
        _ = NotificationCenter.default.addObserver(
            forName: .AVCaptureDeviceWasDisconnected, object: nil, queue: .main
        ) { [weak self] _ in Task { @MainActor in self?.refreshAvailableCameras() } }
    }

    deinit { if let d = deviceObserver { NotificationCenter.default.removeObserver(d) } }

    /// Build the picker list. Includes external (USB-C UVC) cameras on iPad iOS 17+.
    func refreshAvailableCameras() {
        var types: [AVCaptureDevice.DeviceType] = [
            .builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera,
            .builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera
        ]
        if #available(iOS 17.0, *) { types.append(.external) }

        let session = AVCaptureDevice.DiscoverySession(
            deviceTypes: types, mediaType: .video, position: .unspecified)

        availableCameras = session.devices.map { dev in
            let isExt: Bool
            if #available(iOS 17.0, *) { isExt = (dev.deviceType == .external) } else { isExt = false }
            let label = friendlyLabel(for: dev, isExternal: isExt)
            return CameraOption(id: dev.uniqueID, label: label, isExternal: isExt,
                                position: dev.position, deviceTypeRaw: dev.deviceType.rawValue)
        }
    }

    /// Pick a specific camera (e.g. the user tapped a row in the camera-picker sheet). Pass nil
    /// to revert to auto (best built-in for `position`).
    func selectCamera(_ option: CameraOption?, on stream: IOStream?) async {
        selectedCamera = option
        if let dev = option?.device {
            position = dev.position == .unspecified ? .back : dev.position
            if let stream = stream { try? await stream.attachCamera(dev) }
            refreshZoomCapabilities(for: dev)
        } else if let stream = stream, let dev = currentCamera() {
            try? await stream.attachCamera(dev)
            refreshZoomCapabilities(for: dev)
        }
    }

    /// Human-readable name for the picker.
    private func friendlyLabel(for dev: AVCaptureDevice, isExternal: Bool) -> String {
        if isExternal {
            return dev.localizedName.isEmpty ? "External camera" : dev.localizedName
        }
        let face = (dev.position == .front) ? "Front" : "Back"
        switch dev.deviceType {
        case .builtInTripleCamera:    return "\(face) Triple"
        case .builtInDualWideCamera:  return "\(face) Dual Wide"
        case .builtInDualCamera:      return "\(face) Dual"
        case .builtInUltraWideCamera: return "\(face) Ultrawide"
        case .builtInTelephotoCamera: return "\(face) Telephoto"
        default:                      return "\(face) Wide"
        }
    }

    /// A discrete optical zoom stop the user can tap (e.g. .5x / 1x / 3x on iPhone Pro).
    struct LensStop: Identifiable, Hashable {
        let id = UUID()
        let label: String        // ".5×", "1×", "3×"
        let zoomFactor: CGFloat
    }

    // MARK: - Device discovery

    /// Resolve to the AVCaptureDevice we'll capture from. If the user explicitly picked one in the
    /// camera picker, use that. Otherwise auto-pick: builtInTripleCamera (UW/Wide/Tele fused with a
    /// continuous virtual zoom), then DualWide, then DualCamera, then plain wide.
    private func currentCamera() -> AVCaptureDevice? {
        if let chosen = selectedCamera?.device { return chosen }
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
