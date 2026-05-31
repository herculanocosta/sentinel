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

    @State private var pendingImportURL: URL?

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(deps)
                .preferredColorScheme(.dark)
                // AirDrop / share-sheet / Files "Open with SENTINEL" route lands here.
                .onOpenURL { url in
                    if url.pathExtension.lowercased() == "zip" {
                        pendingImportURL = url
                    }
                }
                .sheet(item: Binding(
                    get: { pendingImportURL.map { IdentifiableURL(url: $0) } },
                    set: { pendingImportURL = $0?.url }
                )) { wrapped in
                    NavigationStack {
                        TAKImportView(autoImport: wrapped.url)
                    }
                }
        }
    }
}

private struct IdentifiableURL: Identifiable {
    let url: URL
    var id: URL { url }
}

/// Container for the long-lived services. Created once at app start, lives until process death.
/// Held as a `@StateObject` in the app and pulled via `@EnvironmentObject` in views.
@MainActor
final class AppDependencies: ObservableObject {
    let presets = PresetStore()
    let location = LocationService()
    let streaming = StreamingService()
    let cot = CoTService()
    let goPro = GoProService()
    let liveActivity = LiveActivityController()

    init() {
        // Expose this dependency graph to App Intents (which run in a separate process and need
        // a static handle into the running app).
        SentinelIntentBridge.deps = self

        // When the streaming state changes, hand it to the Live Activity controller so the
        // Dynamic Island + lock-screen widget update without each view having to wire it.
        streaming.objectWillChange
            .sink { [weak self] in
                guard let self else { return }
                Task { @MainActor in
                    self.liveActivity.apply(state: self.streaming.state,
                                            stats: self.streaming.stats)
                    self.watchBridge.publish(state: self.streaming.state,
                                              stats: self.streaming.stats)
                }
            }
            .store(in: &cancellables)
    }
    let watchBridge = WatchBridge()
    private var cancellables = Set<AnyCancellable>()
}

import Combine

/// Top-level navigation. Shows onboarding once; thereafter, starts on source selection so the
/// operator picks what they're streaming before anything else fires up.
struct RootView: View {
    @State private var path = NavigationPath()
    @AppStorage(Pref.onboardingCompleted) private var onboardingDone: Bool = false

    var body: some View {
        if !onboardingDone {
            OnboardingView(completed: $onboardingDone)
        } else {
            NavigationStack(path: $path) {
                SourceSelectionView(path: $path)
            }
        }
    }
}
