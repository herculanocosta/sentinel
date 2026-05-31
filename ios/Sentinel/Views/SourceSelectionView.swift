//
//  SourceSelectionView.swift
//  Landing screen. Operator picks what they're streaming before anything else fires up — same
//  flow as Android's SourceSelectionFragment.
//

import SwiftUI

enum StreamSource: String, Hashable {
    case camera, screen, goPro
}

struct SourceSelectionView: View {
    @Binding var path: NavigationPath
    @EnvironmentObject var deps: AppDependencies

    var body: some View {
        VStack(spacing: 16) {
            Text("SENTINEL")
                .font(.system(size: 36, weight: .bold, design: .rounded))
                .foregroundStyle(Color.accentColor)
                .padding(.top, 30)

            Text("Choose a source")
                .font(.title2.weight(.semibold))
                .foregroundStyle(.secondary)

            Spacer()

            sourceCard(.camera,    title: "Phone camera",  icon: "video.fill",
                       subtitle: "Front/back, low-light tuned")
            sourceCard(.screen,    title: "Screen",        icon: "rectangle.on.rectangle",
                       subtitle: "Capture this app's screen")
            sourceCard(.goPro,     title: "GoPro",         icon: "wifi.circle.fill",
                       subtitle: "WiFi link, cellular broadcast")

            Spacer()

            NavigationLink(value: Destination.settings) {
                Label("Settings", systemImage: "gearshape.fill")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .padding(.horizontal)
        }
        .padding(.bottom, 20)
        .navigationDestination(for: Destination.self) { dest in
            switch dest {
            case .settings:  SettingsView()
            case .camera:    CameraStreamView()
            case .presets:   ServerPresetsView()
            }
        }
        .toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder
    private func sourceCard(_ source: StreamSource, title: String, icon: String, subtitle: String) -> some View {
        Button {
            path.append(Destination.camera)    // all sources land on CameraStreamView for now
        } label: {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.title.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 52)
                    .background(Color.accentColor.gradient, in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(14)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .padding(.horizontal)
    }
}

enum Destination: Hashable { case settings, camera, presets }
