//
//  CameraService.swift
//  AVCaptureSession wrapper that hands the camera feed to HaishinKit. Defaults to back camera,
//  exposes a switchCamera() and a torch toggle. Honours `reliabilityMode`: when ON, requests a
//  conservative 480p/24fps capture preset so we never push the iPhone ISP past sustainable rates
//  (mirrors the Android reliability-mode logic).
//

import Foundation
import AVFoundation
import HaishinKit
import UIKit

@MainActor
final class CameraService: NSObject, ObservableObject {
    @Published private(set) var position: AVCaptureDevice.Position = .back
    @Published private(set) var torchOn = false

    private let defaults = UserDefaults.standard

    /// Resolve the AVCaptureDevice for the current position + reliability mode preference.
    private func currentCamera() -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
    }

    /// Permission gate. Throws if the user has denied camera/mic access.
    func ensureRunning() async throws {
        let camOK = await AVCaptureDevice.requestAccess(for: .video)
        let micOK = await AVCaptureDevice.requestAccess(for: .audio)
        guard camOK, micOK else { throw CameraError.permissionDenied }
    }

    /// Attach the camera + microphone to the given HaishinKit stream and apply the burn-in
    /// overlay (CoreImage filter) on its video output.
    func attach(to stream: IOStream, overlay: OverlayRenderer) async {
        guard let cam = currentCamera() else { return }
        let mic = AVCaptureDevice.default(for: .audio)

        try? await stream.attachCamera(cam) { videoUnit in
            videoUnit.isVideoMirrored = false
            // ReliabilityMode → 854x480 @24, else 1080p@30. iOS picks the closest native preset.
            let reliability = self.defaults.object(forKey: Pref.reliabilityMode) as? Bool
                              ?? PrefDefaults.reliabilityMode
            videoUnit.preferredVideoStabilizationMode = .auto
            videoUnit.colorFormat = kCVPixelFormatType_32BGRA
            // CoreImage burn-in: HaishinKit's videoUnit lets us hook a CIFilter on each frame.
            videoUnit.effects = [overlay.filterEffect]
            if reliability {
                videoUnit.frameRate = 24
            }
        }
        if let mic = mic { try? await stream.attachAudio(mic) }
    }

    /// Toggle front/back. Re-attaches on the active stream.
    func switchCamera(on stream: IOStream?) async {
        position = (position == .back) ? .front : .back
        guard let stream = stream, let cam = currentCamera() else { return }
        try? await stream.attachCamera(cam)
    }

    func setTorch(_ on: Bool) {
        guard let cam = currentCamera(), cam.hasTorch else { return }
        try? cam.lockForConfiguration()
        cam.torchMode = on ? .on : .off
        cam.unlockForConfiguration()
        torchOn = on
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
