//
//  ServerPresetsView.swift
//  Card list of saved server destinations. Tap a card to apply it; long-press for edit/delete.
//  Mirrors the Android ServerPresetsActivity.
//

import SwiftUI

struct ServerPresetsView: View {
    @EnvironmentObject var deps: AppDependencies
    @State private var editing: ServerPreset?
    @State private var newPreset = false

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                if deps.presets.presets.isEmpty {
                    emptyState
                } else {
                    ForEach(deps.presets.presets) { preset in
                        PresetCard(
                            preset: preset,
                            isActive: deps.presets.isActive(preset),
                            onApply: {
                                deps.presets.apply(preset)
                                // light haptic, then snackbar-ish feedback
                                UINotificationFeedbackGenerator().notificationOccurred(.success)
                            },
                            onEdit:   { editing = preset },
                            onDelete: { deps.presets.delete(preset) }
                        )
                        .padding(.horizontal)
                    }
                }
            }
            .padding(.vertical)
        }
        .navigationTitle("Server presets")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { newPreset = true } label: { Image(systemName: "plus.circle.fill") }
            }
        }
        .sheet(item: $editing) { preset in
            NavigationStack {
                ServerPresetEditView(preset: preset) { updated in
                    deps.presets.update(updated)
                    editing = nil
                }
            }
        }
        .sheet(isPresented: $newPreset) {
            NavigationStack {
                ServerPresetEditView(preset: deps.presets.captureCurrent(name: "New preset")) { created in
                    deps.presets.add(created)
                    newPreset = false
                }
            }
        }
    }

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "server.rack").font(.system(size: 44)).foregroundStyle(.tertiary)
            Text("No presets yet").font(.title3.weight(.semibold))
            Text("Tap + to save a streaming destination you can switch to in one tap.")
                .font(.subheadline).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)
        }
        .padding(.top, 60)
    }
}

private struct PresetCard: View {
    let preset: ServerPreset
    let isActive: Bool
    let onApply: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(preset.name.isEmpty ? "(unnamed)" : preset.name).font(.headline)
                if isActive {
                    Text("ACTIVE")
                        .font(.caption2.weight(.heavy))
                        .padding(.horizontal, 8).padding(.vertical, 2)
                        .background(Color.accentColor, in: Capsule())
                        .foregroundStyle(.white)
                }
                Spacer()
                Menu {
                    Button("Apply", systemImage: "checkmark.circle", action: onApply)
                    Button("Edit",  systemImage: "pencil",            action: onEdit)
                    Button(role: .destructive) { onDelete() } label: { Label("Delete", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis").font(.body) }
            }
            Text(preset.summary).font(.caption.monospaced()).foregroundStyle(.secondary)
            if !preset.observerURL.isEmpty {
                Text("▶ \(preset.observerURL)").font(.caption.monospaced()).foregroundStyle(Color.accentColor)
            }
        }
        .padding(14)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(Color.accentColor, lineWidth: isActive ? 2 : 0)
        )
        .contentShape(Rectangle())
        .onTapGesture(perform: onApply)
    }
}
