//
//  PresetStore.swift
//  ServerPreset persistence as a JSON array in UserDefaults. Observable so views update live.
//

import Foundation
import Combine

@MainActor
final class PresetStore: ObservableObject {
    @Published private(set) var presets: [ServerPreset] = []

    private let defaults: UserDefaults
    private let key = Pref.serverPresets

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        load()
    }

    private func load() {
        guard let data = defaults.data(forKey: key) else { presets = []; return }
        presets = (try? JSONDecoder().decode([ServerPreset].self, from: data)) ?? []
    }

    private func persist() {
        let data = (try? JSONEncoder().encode(presets)) ?? Data()
        defaults.set(data, forKey: key)
    }

    // ---- CRUD --------------------------------------------------------------------------------

    func add(_ p: ServerPreset) {
        presets.append(p)
        persist()
    }

    func update(_ p: ServerPreset) {
        guard let idx = presets.firstIndex(where: { $0.id == p.id }) else {
            add(p); return
        }
        presets[idx] = p
        persist()
    }

    func delete(_ p: ServerPreset) {
        presets.removeAll { $0.id == p.id }
        persist()
    }

    // ---- Capture / apply against live STREAM_* prefs -----------------------------------------

    /// Snapshot the current STREAM_* prefs as a named preset.
    func captureCurrent(name: String) -> ServerPreset {
        ServerPreset(
            name: name,
            protocolName: defaults.string(forKey: Pref.streamProtocol) ?? PrefDefaults.streamProtocol,
            address: defaults.string(forKey: Pref.streamAddress) ?? "",
            port: defaults.string(forKey: Pref.streamPort) ?? PrefDefaults.streamPort,
            path: defaults.string(forKey: Pref.streamPath) ?? PrefDefaults.streamPath,
            username: defaults.string(forKey: Pref.streamUsername) ?? "",
            password: defaults.string(forKey: Pref.streamPassword) ?? "",
            tcp: defaults.bool(forKey: Pref.streamTCP),
            observerURL: defaults.string(forKey: Pref.streamObserverURL) ?? ""
        )
    }

    /// Write a preset into the live STREAM_* prefs so the next stream uses it.
    func apply(_ p: ServerPreset) {
        defaults.set(p.protocolName, forKey: Pref.streamProtocol)
        defaults.set(p.address,      forKey: Pref.streamAddress)
        defaults.set(p.port,         forKey: Pref.streamPort)
        defaults.set(p.path,         forKey: Pref.streamPath)
        defaults.set(p.username,     forKey: Pref.streamUsername)
        defaults.set(p.password,     forKey: Pref.streamPassword)
        defaults.set(p.tcp,          forKey: Pref.streamTCP)
        defaults.set(p.observerURL,  forKey: Pref.streamObserverURL)
    }

    func isActive(_ p: ServerPreset) -> Bool {
        defaults.string(forKey: Pref.streamProtocol) == p.protocolName
            && defaults.string(forKey: Pref.streamAddress) == p.address
            && defaults.string(forKey: Pref.streamPort) == p.port
            && defaults.string(forKey: Pref.streamPath) == p.path
    }
}
