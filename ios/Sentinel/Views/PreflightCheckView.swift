//
//  PreflightCheckView.swift
//  Pre-flight checklist screen — one row per check, colour-coded. Live Go button is disabled
//  while any check is .fail; warnings let you proceed.
//

import SwiftUI

struct PreflightCheckView: View {
    @StateObject private var checker = PreflightChecker()
    @EnvironmentObject var deps: AppDependencies
    let preset: ServerPreset
    let onGoLive: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(checker.items) { item in
                        HStack(spacing: 12) {
                            Image(systemName: item.result.color)
                                .font(.title3)
                                .foregroundStyle(tint(for: item.result))
                            VStack(alignment: .leading) {
                                Text(item.title).font(.body)
                                if let d = detail(for: item) {
                                    Text(d).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                        }
                    }
                }
                Section {
                    Button {
                        Task { await checker.run(against: preset) }
                    } label: {
                        Label("Re-run checks", systemImage: "arrow.clockwise")
                    }
                    .disabled(checker.running)
                }
                Section {
                    Button {
                        dismiss()
                        onGoLive()
                    } label: {
                        Label(
                            checker.items.contains { $0.result.isFatal } ? "Fix issues to go live" : "Go live",
                            systemImage: "dot.radiowaves.left.and.right"
                        )
                        .frame(maxWidth: .infinity)
                    }
                    .tint(checker.allowsGoLive ? .green : .red)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!checker.allowsGoLive || checker.running)
                }
            }
            .navigationTitle("Pre-flight")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
            .task { await checker.run(against: preset) }
        }
    }

    private func tint(for r: CheckResult) -> Color {
        switch r {
        case .ok:      return .green
        case .warn:    return .yellow
        case .fail:    return .red
        case .pending: return .gray
        }
    }

    private func detail(for item: PreflightItem) -> String? {
        switch item.result {
        case .warn(let s), .fail(let s): return s
        default: return item.detail
        }
    }
}
