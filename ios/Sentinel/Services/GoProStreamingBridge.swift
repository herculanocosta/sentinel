//
//  GoProStreamingBridge.swift
//  Glues GoProHEVCIngest's decoded CVPixelBuffers into a HaishinKit IOStream, replacing the
//  built-in AVCaptureSession source. When activated:
//
//    1. We detach the camera from the stream (no more iPhone capture).
//    2. We start GoProHEVCIngest on the Wi-Fi-bound UDP port.
//    3. Each delivered CVPixelBuffer is wrapped in a CMSampleBuffer with the GoPro's PTS and
//       handed to the stream via stream.append(_:). HaishinKit's encoder re-compresses to the
//       chosen bitrate/codec and pushes it out on cellular.
//
//  Outcome: video in over Wi-Fi (GoPro), broadcast out over cellular — exactly the Android
//  flow, achieved on iOS via interface-bound NWConnection + an external-source path.
//

import Foundation
import AVFoundation
import VideoToolbox
import CoreMedia
import HaishinKit

@MainActor
final class GoProStreamingBridge: NSObject, ObservableObject, GoProHEVCIngestDelegate {
    @Published private(set) var isActive = false

    private let ingest = GoProHEVCIngest()
    private weak var attachedStream: IOStream?
    /// Reused CMVideoFormatDescription so we don't rebuild it for every frame at the same size.
    private var cachedFormat: CMVideoFormatDescription?
    private var cachedSize: CGSize = .zero

    override init() {
        super.init()
        ingest.delegate = self
    }

    // MARK: - Public

    /// Replace the live camera source on the given stream with the GoPro pixel-buffer feed.
    func activate(on stream: IOStream) async {
        guard !isActive else { return }
        attachedStream = stream
        // Detach the iPhone camera — HaishinKit lets a stream run without a capture device.
        try? await stream.attachCamera(nil)
        ingest.start()
        isActive = true
    }

    /// Restore the iPhone camera as the source.
    func deactivate(restoreWith cameraService: CameraService, overlay: OverlayRenderer) async {
        guard isActive else { return }
        ingest.stop()
        if let s = attachedStream {
            await cameraService.attach(to: s, overlay: overlay)
        }
        attachedStream = nil
        cachedFormat = nil
        cachedSize = .zero
        isActive = false
    }

    // MARK: - GoProHEVCIngestDelegate

    nonisolated func goProDidDecode(_ pixelBuffer: CVPixelBuffer, presentationTime: CMTime) {
        // Hop to MainActor — append() touches HaishinKit state owned there.
        Task { @MainActor [weak self] in
            self?.appendFrame(pixelBuffer, pts: presentationTime)
        }
    }

    // MARK: - Frame wrap + append

    private func appendFrame(_ pixelBuffer: CVPixelBuffer, pts: CMTime) {
        guard let stream = attachedStream else { return }

        let w = CVPixelBufferGetWidth(pixelBuffer)
        let h = CVPixelBufferGetHeight(pixelBuffer)
        let size = CGSize(width: w, height: h)
        if cachedFormat == nil || size != cachedSize {
            var fd: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(
                allocator: kCFAllocatorDefault,
                imageBuffer: pixelBuffer,
                formatDescriptionOut: &fd
            )
            cachedFormat = fd
            cachedSize = size
        }
        guard let fd = cachedFormat else { return }

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: 30),
            presentationTimeStamp: pts.isValid ? pts : CMClockGetTime(CMClockGetHostTimeClock()),
            decodeTimeStamp: .invalid
        )

        var sampleBuffer: CMSampleBuffer?
        let status = CMSampleBufferCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            dataReady: true,
            makeDataReadyCallback: nil,
            refcon: nil,
            formatDescription: fd,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        )
        guard status == noErr, let sb = sampleBuffer else { return }

        // Send the frame to HaishinKit. The framework re-encodes it with our bitrate/codec
        // settings and ships it out on the publish socket (cellular).
        Task { await stream.append(sb) }
    }
}
