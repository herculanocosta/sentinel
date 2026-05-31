//
//  PreflightChecker.swift
//  Runs every pre-flight check in parallel and publishes the results. Each check returns one of
//  three states: .ok / .warn(reason) / .fail(reason). The view renders a coloured row per check.
//

import Foundation
import AVFoundation
import CoreLocation
import Network
import CoreBluetooth
import UIKit
import Combine

enum CheckResult: Equatable {
    case ok
    case warn(String)
    case fail(String)
    case pending

    var color: String {
        switch self {
        case .ok:      return "checkmark.circle.fill"
        case .warn:    return "exclamationmark.triangle.fill"
        case .fail:    return "xmark.octagon.fill"
        case .pending: return "circle.dashed"
        }
    }
    var isFatal: Bool { if case .fail = self { return true }; return false }
}

struct PreflightItem: Identifiable, Equatable {
    let id: String
    let title: String
    var detail: String?
    var result: CheckResult
}

@MainActor
final class PreflightChecker: ObservableObject {
    @Published var items: [PreflightItem] = []
    @Published private(set) var running = false

    /// True only when no check is .fail. Warnings are non-blocking.
    var allowsGoLive: Bool { !items.contains { $0.result.isFatal } }

    /// Run all checks against the given preset. Re-runnable.
    func run(against preset: ServerPreset) async {
        running = true
        items = [
            .init(id: "camera",  title: "Camera permission",      result: .pending),
            .init(id: "mic",     title: "Microphone permission",  result: .pending),
            .init(id: "gps",     title: "GPS permission",         result: .pending),
            .init(id: "net",     title: "Network reachable",      result: .pending),
            .init(id: "server",  title: "Streaming server reachable", result: .pending),
            .init(id: "disk",    title: "Disk space for recording", result: .pending),
            .init(id: "thermal", title: "Thermal headroom",       result: .pending),
            .init(id: "battery", title: "Battery level",          result: .pending),
        ]

        // Run independently — let each settle as quickly as it can.
        async let cam     = checkCamera()
        async let mic     = checkMicrophone()
        async let gps     = checkLocation()
        async let net     = checkNetwork()
        async let server  = checkServerReachable(preset: preset)
        async let disk    = checkDiskSpace()
        async let thermal = checkThermal()
        async let battery = checkBattery()

        update("camera",  await cam)
        update("mic",     await mic)
        update("gps",     await gps)
        update("net",     await net)
        update("server",  await server)
        update("disk",    await disk)
        update("thermal", await thermal)
        update("battery", await battery)
        running = false
    }

    private func update(_ id: String, _ result: CheckResult) {
        if let i = items.firstIndex(where: { $0.id == id }) {
            items[i].result = result
        }
    }

    // MARK: - Individual checks

    private func checkCamera() async -> CheckResult {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .ok
        case .notDetermined:
            let ok = await AVCaptureDevice.requestAccess(for: .video)
            return ok ? .ok : .fail("User denied")
        default: return .fail("Denied — enable in Settings")
        }
    }

    private func checkMicrophone() async -> CheckResult {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: return .ok
        case .notDetermined:
            let ok = await AVCaptureDevice.requestAccess(for: .audio)
            return ok ? .ok : .warn("User denied — video only")
        default: return .warn("Denied — video only")
        }
    }

    private func checkLocation() async -> CheckResult {
        let status = CLLocationManager().authorizationStatus
        switch status {
        case .authorizedAlways, .authorizedWhenInUse: return .ok
        case .notDetermined: return .warn("Will request on first use")
        default: return .warn("Denied — overlay & CoT marker will have no GPS")
        }
    }

    /// Quick interface sweep — anything is fine; primarily catches airplane mode.
    private func checkNetwork() async -> CheckResult {
        let monitor = NWPathMonitor()
        let queue = DispatchQueue(label: "preflight.net")
        defer { monitor.cancel() }
        return await withCheckedContinuation { (cont: CheckedContinuation<CheckResult, Never>) in
            monitor.pathUpdateHandler = { path in
                if path.status == .satisfied {
                    let kind: String
                    if path.usesInterfaceType(.cellular)      { kind = "cellular" }
                    else if path.usesInterfaceType(.wifi)     { kind = "Wi-Fi" }
                    else if path.usesInterfaceType(.wiredEthernet) { kind = "ethernet" }
                    else { kind = "connected" }
                    cont.resume(returning: .ok)
                    _ = kind   // detail in future build
                } else {
                    cont.resume(returning: .fail("No network — airplane mode?"))
                }
            }
            monitor.start(queue: queue)
        }
    }

    /// TCP connect to the streaming server's address:port. Times out at 2 s.
    private func checkServerReachable(preset: ServerPreset) async -> CheckResult {
        guard !preset.address.isEmpty, let port = UInt16(preset.port) else {
            return .fail("Server address not configured")
        }
        return await withCheckedContinuation { (cont: CheckedContinuation<CheckResult, Never>) in
            let conn = NWConnection(host: .init(preset.address),
                                    port: NWEndpoint.Port(integerLiteral: port),
                                    using: .tcp)
            let timeout = DispatchWorkItem {
                conn.cancel()
                cont.resume(returning: .fail("\(preset.address):\(preset.port) timed out"))
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    timeout.cancel()
                    conn.cancel()
                    cont.resume(returning: .ok)
                case .failed(let err):
                    timeout.cancel()
                    cont.resume(returning: .fail(err.localizedDescription))
                default: break
                }
            }
            conn.start(queue: .global(qos: .utility))
            DispatchQueue.global().asyncAfter(deadline: .now() + 2, execute: timeout)
        }
    }

    private func checkDiskSpace() async -> CheckResult {
        do {
            let url = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: false)
            let values = try url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            let gb = Double(values.volumeAvailableCapacityForImportantUsage ?? 0) / 1_073_741_824
            if gb < 0.5 { return .fail(String(format: "%.1f GB free — too low to record", gb)) }
            if gb < 2   { return .warn(String(format: "%.1f GB free", gb)) }
            return .ok
        } catch {
            return .warn("Couldn't read disk space")
        }
    }

    private func checkThermal() async -> CheckResult {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal, .fair: return .ok
        case .serious:        return .warn("Phone warm — long stream may throttle")
        case .critical:       return .fail("Phone overheated — let it cool down")
        @unknown default:     return .ok
        }
    }

    private func checkBattery() async -> CheckResult {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let pct = UIDevice.current.batteryLevel
        if pct < 0 { return .ok }     // simulator / unknown
        let p = Int(pct * 100)
        switch p {
        case 0..<15:  return .fail("\(p)% — connect power before going live")
        case 15..<30: return .warn("\(p)%")
        default:      return .ok
        }
    }
}
