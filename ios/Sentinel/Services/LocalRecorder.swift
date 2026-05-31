//
//  LocalRecorder.swift
//  AVAssetWriter-based local recording that runs concurrently with HaishinKit broadcasting.
//
//  Architecture: HaishinKit's video capture output is shared via an `AVCaptureVideoDataOutput`
//  delegate fan-out. We tap that delegate stream and write each CMSampleBuffer into an
//  AVAssetWriter at the same time it's encoded for the broadcast. So if the broadcast drops, the
//  operator still has the full take on the iPhone.
//
//  Output: app sandbox `Recordings/<prefix>-<timestamp>.mp4`. On stopRecording() we offer to
//  copy into Photos via PHPhotoLibrary if permission is granted (best-effort, no blocking).
//

import Foundation
import AVFoundation
import Photos
import UIKit

@MainActor
final class LocalRecorder: ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var currentFile: URL?
    @Published private(set) var lastError: String?

    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var pixelAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var sessionStartedAt: CMTime?

    // MARK: - Lifecycle

    func startRecording(prefix: String) async throws {
        guard !isRecording else { return }

        let dir = try recordingsDirectory()
        let safe = prefix.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: " ", with: "_")
        let ts  = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
        let url = dir.appendingPathComponent("\(safe.isEmpty ? "stream" : safe)-\(ts).mp4")

        let w = try AVAssetWriter(outputURL: url, fileType: .mp4)

        // Resolution / bitrate match the broadcast settings so the local file mirrors what went
        // on the wire. We let AVAssetWriter pick H.264 main profile — universal playback.
        let bitrateKbps = (UserDefaults.standard.object(forKey: Pref.videoBitrate) as? Int)
                         ?? PrefDefaults.videoBitrate
        let width  = 1920
        let height = 1080

        let videoSettings: [String: Any] = [
            AVVideoCodecKey:  AVVideoCodecType.h264,
            AVVideoWidthKey:  width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitrateKbps * 1000,
                AVVideoMaxKeyFrameIntervalKey: 60,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264MainAutoLevel
            ]
        ]
        let v = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        v.expectsMediaDataInRealTime = true

        let audioSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVNumberOfChannelsKey: 1,
            AVSampleRateKey: 44_100,
            AVEncoderBitRateKey: 128_000
        ]
        let a = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
        a.expectsMediaDataInRealTime = true

        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: v,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String:  width,
                kCVPixelBufferHeightKey as String: height
            ])

        guard w.canAdd(v), w.canAdd(a) else { throw RecorderError.cannotAddInputs }
        w.add(v); w.add(a)
        guard w.startWriting() else { throw RecorderError.writerStartFailed(w.error?.localizedDescription ?? "unknown") }

        self.writer = w
        self.videoInput = v
        self.audioInput = a
        self.pixelAdaptor = adaptor
        self.sessionStartedAt = nil   // started on first sample
        self.currentFile = url
        self.isRecording = true
    }

    func stopRecording() async {
        guard isRecording, let w = writer else { return }
        isRecording = false

        videoInput?.markAsFinished()
        audioInput?.markAsFinished()
        await w.finishWriting()

        let outURL = w.outputURL
        writer = nil; videoInput = nil; audioInput = nil; pixelAdaptor = nil
        sessionStartedAt = nil

        // Best-effort save to Photos. Silent failure (no nag) — the file is still in the sandbox.
        await offerSaveToPhotos(outURL)
    }

    // MARK: - Sample feed (called by CameraService's tap)

    /// Hand off a CMSampleBuffer captured for the broadcast. Cheap when not recording.
    func appendVideo(_ sample: CMSampleBuffer) {
        guard isRecording,
              let w = writer, w.status == .writing,
              let input = videoInput, input.isReadyForMoreMediaData else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        if sessionStartedAt == nil {
            w.startSession(atSourceTime: pts)
            sessionStartedAt = pts
        }
        input.append(sample)
    }

    func appendAudio(_ sample: CMSampleBuffer) {
        guard isRecording,
              let input = audioInput, input.isReadyForMoreMediaData else { return }
        input.append(sample)
    }

    // MARK: - Helpers

    private func recordingsDirectory() throws -> URL {
        let docs = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        let dir = docs.appendingPathComponent("Recordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func offerSaveToPhotos(_ url: URL) async {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        let granted: Bool
        switch status {
        case .authorized, .limited: granted = true
        case .notDetermined:
            granted = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                PHPhotoLibrary.requestAuthorization(for: .addOnly) { s in
                    cont.resume(returning: s == .authorized || s == .limited)
                }
            }
        default: granted = false
        }
        guard granted else { return }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                _ = PHAssetCreationRequest.creationRequestForAssetFromVideo(atFileURL: url)
            }
        } catch {
            lastError = "Couldn't save to Photos: \(error.localizedDescription)"
        }
    }
}

enum RecorderError: LocalizedError {
    case cannotAddInputs
    case writerStartFailed(String)
    var errorDescription: String? {
        switch self {
        case .cannotAddInputs:          return "AVAssetWriter rejected the inputs."
        case .writerStartFailed(let s): return "AVAssetWriter couldn't start: \(s)"
        }
    }
}
