//
//  SentinelApp.swift
//  SENTINEL — tactical live-streaming for iOS.
//
//  Entry point. Builds the AppDependencies graph once and injects it into the SwiftUI
//  environment, so views can pull services without singletons.
//

import SwiftUI

@main
struct SentinelApp: App {
    @StateObject private var deps = AppDependencies()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(deps)
                .preferredColorScheme(.dark)
        }
    }
}

/// Container for the long-lived services. Created once at app start, lives until process death.
/// Held as a `@StateObject` in the app and pulled via `@EnvironmentObject` in views.
final class AppDependencies: ObservableObject {
    let presets = PresetStore()
    let location = LocationService()
    let streaming = StreamingService()
    let cot = CoTService()
    let goPro = GoProService()
}

/// Top-level navigation. Always starts on the source-selection screen so the operator picks
/// what they're streaming before anything else fires up.
struct RootView: View {
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            SourceSelectionView(path: $path)
        }
    }
}
