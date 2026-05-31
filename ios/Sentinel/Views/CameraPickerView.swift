//
//  CameraPickerView.swift
//  Lists every video device the iPhone/iPad can see — built-in lenses + connected USB-C UVC
//  cameras (iPad iOS 17+). The picker re-attaches the chosen device to the active stream.
//

import SwiftUI
import AVFoundation

struct CameraPickerView: View {
    @EnvironmentObject var deps: AppDependencies
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("External") {
                    let externals = deps.streaming.cameraService.availableCameras.filter(\.isExternal)
                    if externals.isEmpty {
                        Text("No external camera connected.")
                            .font(.callout).foregroundStyle(.secondary)
                    } else {
                        ForEach(externals) { opt in row(opt) }
                    }
                }
                Section("Built-in") {
                    ForEach(deps.streaming.cameraService.availableCameras.filter { !$0.isExternal }) { opt in
                        row(opt)
                    }
                }
                Section {
                    Button {
                        Task {
                            await deps.streaming.cameraService.selectCamera(nil, on: deps.streaming.activeStream)
                            dismiss()
                        }
                    } label: {
                        Label("Auto (best built-in)", systemImage: "wand.and.stars")
                    }
                }
            }
            .navigationTitle("Camera")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        deps.streaming.cameraService.refreshAvailableCameras()
                    } label: { Image(systemName: "arrow.clockwise") }
                }
            }
            .onAppear { deps.streaming.cameraService.refreshAvailableCameras() }
        }
    }

    @ViewBuilder
    private func row(_ opt: CameraService.CameraOption) -> some View {
        Button {
            Task {
                await deps.streaming.cameraService.selectCamera(opt, on: deps.streaming.activeStream)
                dismiss()
            }
        } label: {
            HStack {
                Image(systemName: opt.isExternal ? "cable.connector" : "camera.fill")
                    .foregroundStyle(opt.isExternal ? Color.accentColor : .secondary)
                Text(opt.label)
                Spacer()
                if deps.streaming.cameraService.selectedCamera?.id == opt.id {
                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                }
            }
        }
        .foregroundStyle(.primary)
    }
}
