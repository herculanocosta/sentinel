//
//  GoProHEVCIngest.swift
//  Pulls the GoPro's UDP preview (MPEG-TS over Wi-Fi), demuxes to HEVC NAL units, decodes via
//  VideoToolbox, and emits CVPixelBuffers ready for HaishinKit's encoder. Mirrors the Android
//  GoProSource architecture line-for-line — the same PAT/PMT walk, the same VPS+SPS+PPS → csd
//  pattern, the same NAL-type masks.
//
//  Critical: the NWConnection is bound to `.wifi` so the GoPro fetch can't hijack the
//  process default route. The broadcast NWConnection is bound to `.cellular` separately.
//  Together these keep "video in over Wi-Fi / out over cellular" working.
//

import Foundation
import Network
import VideoToolbox
import CoreVideo
import CoreMedia
import AVFoundation

protocol GoProHEVCIngestDelegate: AnyObject {
    func goProDidDecode(_ pixelBuffer: CVPixelBuffer, presentationTime: CMTime)
}

final class GoProHEVCIngest {
    weak var delegate: GoProHEVCIngestDelegate?

    // MARK: - Public lifecycle

    func start(port: UInt16 = 8554) {
        guard connection == nil else { return }
        // Listen on a UDP port; bind to Wi-Fi so this socket only ever sees GoPro packets.
        let params = NWParameters.udp
        params.requiredInterfaceType = .wifi
        params.allowLocalEndpointReuse = true

        do {
            let listener = try NWListener(using: params, on: NWEndpoint.Port(integerLiteral: port))
            listener.newConnectionHandler = { [weak self] conn in
                self?.adopt(connection: conn)
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            NSLog("GoPro HEVC ingest: listener failed: \(error)")
        }
    }

    func stop() {
        listener?.cancel(); listener = nil
        connection?.cancel(); connection = nil
        if let s = decompressionSession {
            VTDecompressionSessionInvalidate(s)
            decompressionSession = nil
        }
        formatDescription = nil
        videoPID = nil
        pesBuffer.removeAll()
        vps.removeAll(); sps.removeAll(); pps.removeAll()
    }

    // MARK: - Internal

    private let queue = DispatchQueue(label: "gopro.hevc.ingest", qos: .userInitiated)
    private var listener: NWListener?
    private var connection: NWConnection?

    /// Discovered from the MPEG-TS PMT. HEVC stream_type is 0x24.
    private var videoPID: UInt16?
    /// PES reassembly buffer for the video PID.
    private var pesBuffer: [UInt8] = []
    private var pesStartCollected = false

    /// HEVC parameter sets — once we have VPS+SPS+PPS we can build a CMVideoFormatDescription.
    private var vps: [UInt8] = []
    private var sps: [UInt8] = []
    private var pps: [UInt8] = []

    private var formatDescription: CMVideoFormatDescription?
    private var decompressionSession: VTDecompressionSession?

    private func adopt(connection conn: NWConnection) {
        self.connection = conn
        conn.start(queue: queue)
        receiveNext(conn)
    }

    private func receiveNext(_ conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, _ in
            if let data = data, !data.isEmpty {
                self?.feed(Array(data))
            }
            self?.receiveNext(conn)
        }
    }

    // MARK: - MPEG-TS demux (matches Android GoProSource.processTsPacket / parsePmt)

    private func feed(_ bytes: [UInt8]) {
        var i = 0
        while i + 188 <= bytes.count {
            if bytes[i] != 0x47 {
                i += 1; continue   // resync on next sync byte
            }
            processTsPacket(Array(bytes[i..<i+188]))
            i += 188
        }
    }

    private func processTsPacket(_ pkt: [UInt8]) {
        let pid = (UInt16(pkt[1] & 0x1F) << 8) | UInt16(pkt[2])
        let payloadStart = (pkt[1] & 0x40) != 0
        let adaptation = (pkt[3] & 0x30) >> 4
        var off = 4
        if adaptation == 2 || adaptation == 3 {
            let len = Int(pkt[4])
            off += 1 + len
        }
        guard off < 188 else { return }
        let payload = Array(pkt[off..<188])

        if pid == 0 && payloadStart {
            // PAT - typically points to PMT on PID ~0x1000. We don't need to track PMT PID;
            // we just walk PMT packets and pick the one with HEVC.
            return
        }
        if videoPID == nil {
            // Try to parse this as a PMT (any non-zero PID). Cheap heuristic — Android does the
            // same: look for stream_type 0x24 (HEVC) anywhere in the elementary-stream loop.
            tryParsePMT(payload, payloadStart: payloadStart)
        }
        if let vp = videoPID, pid == vp {
            handleVideoPayload(payload, payloadStart: payloadStart)
        }
    }

    private func tryParsePMT(_ payload: [UInt8], payloadStart: Bool) {
        guard payloadStart, payload.count > 12 else { return }
        // payload[0] = pointer field (usually 0)
        let ptr = Int(payload[0])
        guard payload.count > 12 + ptr else { return }
        let p = ptr + 1
        // payload[p] = table_id (PMT = 0x02)
        guard payload[p] == 0x02 else { return }
        let sectionLength = (Int(payload[p+1] & 0x0F) << 8) | Int(payload[p+2])
        guard sectionLength + p + 3 <= payload.count else { return }
        // Skip header → program_info_length
        let programInfoLength = (Int(payload[p+10] & 0x0F) << 8) | Int(payload[p+11])
        var idx = p + 12 + programInfoLength
        let end = p + 3 + sectionLength - 4   // exclude CRC
        while idx + 5 <= end && idx + 5 <= payload.count {
            let streamType = payload[idx]
            let elemPID = (UInt16(payload[idx+1] & 0x1F) << 8) | UInt16(payload[idx+2])
            let esInfoLength = (Int(payload[idx+3] & 0x0F) << 8) | Int(payload[idx+4])
            if streamType == 0x24 {                 // HEVC
                videoPID = elemPID
                return
            }
            idx += 5 + esInfoLength
        }
    }

    private func handleVideoPayload(_ payload: [UInt8], payloadStart: Bool) {
        if payloadStart {
            if pesStartCollected, pesBuffer.count > 14 {
                emitCompletedPES(pesBuffer)
            }
            pesBuffer.removeAll(keepingCapacity: true)
            pesStartCollected = true
        }
        if pesStartCollected { pesBuffer.append(contentsOf: payload) }
    }

    private func emitCompletedPES(_ pes: [UInt8]) {
        // PES header: bytes 0–8 are fixed; pes[8] = PES_header_data_length; then the actual ES.
        guard pes.count >= 9 else { return }
        let pesHeaderDataLength = Int(pes[8])
        let esStart = 9 + pesHeaderDataLength
        guard esStart < pes.count else { return }
        let es = Array(pes[esStart..<pes.count])
        extractNALUnits(es)
    }

    /// Annex-B NAL extraction. HEVC NAL header is 2 bytes; type = (firstByte >> 1) & 0x3F.
    private func extractNALUnits(_ es: [UInt8]) {
        var i = 0
        var nalStart = -1
        while i < es.count - 3 {
            if es[i] == 0 && es[i+1] == 0 && (es[i+2] == 1 || (es[i+2] == 0 && es[i+3] == 1)) {
                if nalStart >= 0 {
                    emitNAL(Array(es[nalStart..<i]))
                }
                nalStart = i + (es[i+2] == 1 ? 3 : 4)
                i = nalStart
                continue
            }
            i += 1
        }
        if nalStart >= 0 && nalStart < es.count {
            emitNAL(Array(es[nalStart..<es.count]))
        }
    }

    private func emitNAL(_ nal: [UInt8]) {
        guard !nal.isEmpty else { return }
        let nalType = (nal[0] >> 1) & 0x3F
        switch nalType {
        case 32: vps = nal              // VPS
        case 33: sps = nal              // SPS
        case 34: pps = nal              // PPS
        default:
            // VCL NAL (slice). Once we have parameter sets, ship to the decoder.
            tryBuildFormatDescriptionIfNeeded()
            if let fd = formatDescription {
                decodeAccessUnit(nal: nal, format: fd)
            }
        }
    }

    // MARK: - VideoToolbox HEVC decode

    private func tryBuildFormatDescriptionIfNeeded() {
        guard formatDescription == nil, !vps.isEmpty, !sps.isEmpty, !pps.isEmpty else { return }
        let psArrays: [[UInt8]] = [vps, sps, pps]
        var pointers: [UnsafePointer<UInt8>] = []
        var sizes: [Int] = []
        for ps in psArrays {
            sizes.append(ps.count)
            ps.withUnsafeBufferPointer { buf in
                if let base = buf.baseAddress {
                    pointers.append(base)
                }
            }
        }
        guard pointers.count == 3 else { return }

        var fd: CMVideoFormatDescription?
        let status = pointers.withUnsafeBufferPointer { ptrs -> OSStatus in
            sizes.withUnsafeBufferPointer { szs in
                CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 3,
                    parameterSetPointers: ptrs.baseAddress!,
                    parameterSetSizes: szs.baseAddress!,
                    nalUnitHeaderLength: 4,
                    extensions: nil,
                    formatDescriptionOut: &fd
                )
            }
        }
        guard status == noErr, let fd = fd else { return }
        formatDescription = fd
        createDecompressionSession(format: fd)
    }

    private func createDecompressionSession(format: CMVideoFormatDescription) {
        let attrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String:
                Int(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange),
            kCVPixelBufferMetalCompatibilityKey as String: true
        ]
        var cb = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: vtCallback,
            decompressionOutputRefCon: Unmanaged.passUnretained(self).toOpaque()
        )
        var session: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: format,
            decoderSpecification: nil,
            imageBufferAttributes: attrs as CFDictionary,
            outputCallback: &cb,
            decompressionSessionOut: &session
        )
        guard status == noErr else { NSLog("VTDecompressionSessionCreate failed: \(status)"); return }
        decompressionSession = session
    }

    /// Build an AVCC-style sample buffer (4-byte length prefix instead of Annex-B start code)
    /// and feed it to the decoder.
    private func decodeAccessUnit(nal: [UInt8], format: CMVideoFormatDescription) {
        guard let session = decompressionSession else { return }
        var avcc = withUnsafeBytes(of: UInt32(nal.count).bigEndian, Array.init)
        avcc.append(contentsOf: nal)

        var bbuf: CMBlockBuffer?
        let bbufStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: nil,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: 0,
            blockBufferOut: &bbuf
        )
        guard bbufStatus == kCMBlockBufferNoErr, let bbuf = bbuf else { return }
        _ = avcc.withUnsafeBytes { buf in
            CMBlockBufferReplaceDataBytes(with: buf.baseAddress!, blockBuffer: bbuf,
                                           offsetIntoDestination: 0, dataLength: avcc.count)
        }

        var sbuf: CMSampleBuffer?
        var sampleSize = avcc.count
        let sbufStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: bbuf,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 0,
            sampleTimingArray: nil,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sbuf
        )
        guard sbufStatus == noErr, let sbuf = sbuf else { return }

        var flagsOut: VTDecodeInfoFlags = []
        VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sbuf,
            flags: [._EnableAsynchronousDecompression],
            frameRefcon: nil,
            infoFlagsOut: &flagsOut
        )
    }
}

// Free function — VideoToolbox callback (C signature, no Swift method).
private let vtCallback: VTDecompressionOutputCallback = { refcon, _, status, _, imageBuffer, pts, _ in
    guard status == noErr, let imageBuffer = imageBuffer, let refcon = refcon else { return }
    let ingest = Unmanaged<GoProHEVCIngest>.fromOpaque(refcon).takeUnretainedValue()
    DispatchQueue.main.async {
        ingest.delegate?.goProDidDecode(imageBuffer, presentationTime: pts)
    }
}
