//
//  iCloudSync.swift
//  NSUbiquitousKeyValueStore-backed preset sync. Same iCloud account → presets appear on iPhone +
//  iPad + Watch automatically. Lighter than CloudKit (no schema to design, no record types) and
//  the 1 MB limit is fine for hundreds of small ServerPreset entries.
//
//  Conflict resolution: last-writer-wins per device. When iCloud notifies us of an external
//  change, we merge IDs we don't know yet and replace entries whose iCloud timestamp is newer.
//

import Foundation

/// Tracks the iCloud KV store and bridges presets in/out. Hooked up by `PresetStore` automatically
/// when the user is signed in to iCloud.
final class iCloudSync {
    private let store = NSUbiquitousKeyValueStore.default
    private let presetsKey = "presets_v1"
    private let modifiedAtKey = "presets_modified_at"

    /// Caller is notified when iCloud has fresher presets — should re-load.
    var onRemoteChange: (() -> Void)?

    init() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(externalChanged(_:)),
            name: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
            object: store
        )
        store.synchronize()
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    /// Push the local presets up to iCloud. Quietly no-ops if the user isn't signed in.
    func push(_ presets: [ServerPreset]) {
        guard let data = try? JSONEncoder().encode(presets) else { return }
        // iCloud KV per-value cap is ~1 MB; that's plenty for thousands of small presets.
        store.set(data, forKey: presetsKey)
        store.set(Date().timeIntervalSince1970, forKey: modifiedAtKey)
        store.synchronize()
    }

    /// Pull the latest iCloud presets. Returns nil if iCloud has nothing yet.
    func pull() -> [ServerPreset]? {
        guard let data = store.data(forKey: presetsKey),
              let list = try? JSONDecoder().decode([ServerPreset].self, from: data) else {
            return nil
        }
        return list
    }

    var remoteModifiedAt: Date? {
        let ts = store.double(forKey: modifiedAtKey)
        return ts > 0 ? Date(timeIntervalSince1970: ts) : nil
    }

    @objc private func externalChanged(_ note: Notification) {
        DispatchQueue.main.async { [weak self] in self?.onRemoteChange?() }
    }
}
