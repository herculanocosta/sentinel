//
//  GoProService.swift
//  GoPro integration — BLE pairing, WiFi join, HEVC MPEG-TS ingest. The Android version's hardest
//  feature; on iOS the architecture is similar but with completely different APIs.
//
//  Status: scaffold + BLE scan/connect working surface. The full HEVC demuxer/decoder pipeline +
//  cellular-bound RTSP push are deferred to the next session.
//

import Foundation
import CoreBluetooth
import NetworkExtension
import Network
import Combine

// MARK: - Open GoPro v2 BLE UUIDs (same on every HERO / MAX / LIT)
private enum GoProBLE {
    static let service       = CBUUID(string: "0000FEA6-0000-1000-8000-00805F9B34FB")
    // Command + response characteristics
    static let cmd           = CBUUID(string: "B5F90072-AA8D-11E3-9046-0002A5D5C51B")
    static let cmdResp       = CBUUID(string: "B5F90073-AA8D-11E3-9046-0002A5D5C51B")
    // Settings (e.g. enable WiFi AP)
    static let setting       = CBUUID(string: "B5F90074-AA8D-11E3-9046-0002A5D5C51B")
    static let settingResp   = CBUUID(string: "B5F90075-AA8D-11E3-9046-0002A5D5C51B")
    // Wi-Fi credentials read-back
    static let ssid          = CBUUID(string: "B5F90002-AA8D-11E3-9046-0002A5D5C51B")
    static let password      = CBUUID(string: "B5F90003-AA8D-11E3-9046-0002A5D5C51B")
}

struct GoProDevice: Identifiable, Hashable {
    let id: UUID
    let name: String
    let rssi: Int
}

enum GoProConnectionState: Equatable {
    case idle
    case scanning
    case bleConnecting
    case fetchingCredentials
    case enablingWiFi
    case joiningWiFi
    case connected(ssid: String)
    case failed(String)
}

@MainActor
final class GoProService: NSObject, ObservableObject {
    @Published private(set) var state: GoProConnectionState = .idle
    @Published private(set) var devices: [GoProDevice] = []

    private var central: CBCentralManager?
    private var connectedPeripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var hotspotConfigured = false

    /// Begin scanning. Returns when we've found at least one device or 5s passes.
    func startScan() async {
        await stopScan()
        state = .scanning
        devices = []
        let c = CBCentralManager(delegate: self, queue: .main)
        central = c
        // Wait for poweredOn then start scanning.
    }

    func stopScan() async {
        central?.stopScan()
        state = .idle
    }

    /// Connect over BLE to the picked device, enable its WiFi AP, fetch credentials, and join.
    func connect(to device: GoProDevice) async {
        guard let c = central else { return }
        state = .bleConnecting
        guard let peripheral = c.retrievePeripherals(withIdentifiers: [device.id]).first
                ?? devicesMap[device.id] else {
            state = .failed("Device gone — scan again"); return
        }
        connectedPeripheral = peripheral
        peripheral.delegate = self
        c.connect(peripheral)
        // The CBPeripheral state machine drives the rest from here (see delegate callbacks).
    }

    /// Once we have SSID + password, configure iOS to join the GoPro WiFi *without* making it the
    /// system-default network. iOS keeps cellular as the default route — exactly what we need so
    /// the outbound RTSP push keeps going over carrier while we fetch the GoPro preview over WiFi.
    private func joinWiFi(ssid: String, password: String) async {
        state = .joiningWiFi
        let cfg = NEHotspotConfiguration(ssid: ssid, passphrase: password, isWEP: false)
        cfg.joinOnce = true            // don't add to "saved" networks; iOS forgets on stop
        do {
            try await NEHotspotConfigurationManager.shared.apply(cfg)
            hotspotConfigured = true
            state = .connected(ssid: ssid)
        } catch {
            state = .failed("Wi-Fi join failed: \(error.localizedDescription)")
        }
    }

    /// Remove our GoPro WiFi config so iOS's "join automatically" can't divert routing later.
    func disconnect() async {
        if hotspotConfigured {
            NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: lastJoinedSSID ?? "")
            hotspotConfigured = false
        }
        if let p = connectedPeripheral { central?.cancelPeripheralConnection(p) }
        connectedPeripheral = nil
        characteristics = [:]
        state = .idle
    }

    private var devicesMap: [UUID: CBPeripheral] = [:]
    private var lastJoinedSSID: String?
}

// MARK: - CBCentralManagerDelegate

extension GoProService: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else { return }
        central.scanForPeripherals(withServices: [GoProBLE.service])
    }
    nonisolated func centralManager(_ central: CBCentralManager,
                                    didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String : Any],
                                    rssi RSSI: NSNumber) {
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? "GoPro"
        Task { @MainActor in
            self.devicesMap[peripheral.identifier] = peripheral
            let dev = GoProDevice(id: peripheral.identifier, name: name, rssi: RSSI.intValue)
            if !self.devices.contains(dev) { self.devices.append(dev) }
        }
    }
    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([GoProBLE.service])
    }
    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in self.state = .failed("BLE connect failed: \(error?.localizedDescription ?? "unknown")") }
    }
}

// MARK: - CBPeripheralDelegate

extension GoProService: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        for svc in peripheral.services ?? [] where svc.uuid == GoProBLE.service {
            peripheral.discoverCharacteristics(
                [GoProBLE.cmd, GoProBLE.cmdResp, GoProBLE.setting, GoProBLE.settingResp,
                 GoProBLE.ssid, GoProBLE.password], for: svc)
        }
    }
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for ch in service.characteristics ?? [] {
            Task { @MainActor in self.characteristics[ch.uuid] = ch }
            if ch.uuid == GoProBLE.cmdResp || ch.uuid == GoProBLE.settingResp {
                peripheral.setNotifyValue(true, for: ch)
            }
        }
        // Once we have the cmd char, enable the WiFi AP (Open GoPro: 0x03 0x17 0x01 0x01).
        if let cmd = service.characteristics?.first(where: { $0.uuid == GoProBLE.cmd }) {
            let enableWifi = Data([0x03, 0x17, 0x01, 0x01])
            peripheral.writeValue(enableWifi, for: cmd, type: .withResponse)
            Task { @MainActor in self.state = .enablingWiFi }
        }
        if let s = service.characteristics?.first(where: { $0.uuid == GoProBLE.ssid }),
           let p = service.characteristics?.first(where: { $0.uuid == GoProBLE.password }) {
            peripheral.readValue(for: s)
            peripheral.readValue(for: p)
            Task { @MainActor in self.state = .fetchingCredentials }
        }
    }
    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let v = characteristic.value else { return }
        if characteristic.uuid == GoProBLE.ssid {
            let ssid = String(data: v, encoding: .utf8) ?? ""
            Task { @MainActor in self.pendingSSID = ssid; self.tryJoinIfReady() }
        } else if characteristic.uuid == GoProBLE.password {
            let pwd = String(data: v, encoding: .utf8) ?? ""
            Task { @MainActor in self.pendingPwd = pwd; self.tryJoinIfReady() }
        }
    }

    @MainActor private func tryJoinIfReady() {
        guard let s = pendingSSID, let p = pendingPwd, !s.isEmpty, !p.isEmpty else { return }
        lastJoinedSSID = s
        Task { await self.joinWiFi(ssid: s, password: p) }
        pendingSSID = nil; pendingPwd = nil
    }
}

// MARK: - State held while we wait for both characteristics to arrive
private extension GoProService {
    var pendingSSID: String? { get { objc_getAssociatedObject(self, &kSSID) as? String } set { objc_setAssociatedObject(self, &kSSID, newValue, .OBJC_ASSOCIATION_RETAIN) } }
    var pendingPwd:  String? { get { objc_getAssociatedObject(self, &kPwd)  as? String } set { objc_setAssociatedObject(self, &kPwd,  newValue, .OBJC_ASSOCIATION_RETAIN) } }
}
private var kSSID: UInt8 = 0
private var kPwd:  UInt8 = 0
