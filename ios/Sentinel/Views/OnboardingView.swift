//
//  OnboardingView.swift
//  One-time multi-step welcome that requests every permission SENTINEL needs up front, so the
//  operator isn't ambushed by system dialogs in the middle of a mission. Persists a "completed"
//  flag and gates RootView.
//

import SwiftUI
import AVFoundation
import CoreLocation
import CoreBluetooth
import UserNotifications

extension Pref {
    static let onboardingCompleted = "onboarding_completed"
}

struct OnboardingView: View {
    @Binding var completed: Bool
    @State private var step = 0
    @State private var requestingPermission = false

    var body: some View {
        TabView(selection: $step) {
            welcomePage.tag(0)
            permissionPage(
                title: "Camera",
                detail: "We need the camera to capture and stream video.",
                icon: "camera.fill",
                granted: AVCaptureDevice.authorizationStatus(for: .video) == .authorized,
                request: requestCamera
            ).tag(1)
            permissionPage(
                title: "Microphone",
                detail: "We capture audio alongside the video. You can mute mid-stream.",
                icon: "mic.fill",
                granted: AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
                request: requestMicrophone
            ).tag(2)
            permissionPage(
                title: "Location",
                detail: "Used for the GPS overlay and the TAK marker position. We never share it elsewhere.",
                icon: "location.fill",
                granted: CLLocationManager().authorizationStatus.isAuthorized,
                request: requestLocation
            ).tag(3)
            permissionPage(
                title: "Bluetooth",
                detail: "Required to pair with GoPro cameras over Bluetooth LE.",
                icon: "antenna.radiowaves.left.and.right",
                granted: CBCentralManager.authorization == .allowedAlways,
                request: requestBluetooth
            ).tag(4)
            permissionPage(
                title: "Notifications",
                detail: "Used for the Live Activity (Dynamic Island + lock screen) while streaming.",
                icon: "bell.badge.fill",
                granted: false,   // can't synchronously read without an async call; just request
                request: requestNotifications
            ).tag(5)
            finishedPage.tag(6)
        }
        .tabViewStyle(.page(indexDisplayMode: .always))
        .indexViewStyle(.page(backgroundDisplayMode: .always))
        .background(Color.black.ignoresSafeArea())
    }

    // MARK: - Pages

    private var welcomePage: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("SENTINEL").font(.system(size: 44, weight: .heavy, design: .rounded))
                .foregroundStyle(Color.accentColor)
            Text("Tactical live-streaming")
                .font(.title3).foregroundStyle(.secondary)
            Spacer()
            VStack(alignment: .leading, spacing: 14) {
                bullet("Camera, USB, screen, GoPro sources", "camera.viewfinder")
                bullet("RTMP / RTSP / SRT push to your TAK server or MediaMTX", "antenna.radiowaves.left.and.right")
                bullet("On-device transcoding for weak networks", "speedometer")
                bullet("ATAK CoT video marker — peers see your feed", "mappin.circle")
            }
            .padding()
            Spacer()
            Button("Get started") { withAnimation { step = 1 } }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .padding(.bottom, 50)
        }
        .padding()
    }

    private func permissionPage(title: String, detail: String, icon: String,
                                granted: Bool, request: @escaping () async -> Void) -> some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: icon).font(.system(size: 70)).foregroundStyle(Color.accentColor)
            Text(title).font(.title.weight(.bold))
            Text(detail).font(.body).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 30)
            Spacer()
            if granted {
                Label("Granted", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green).font(.title3.weight(.semibold))
                Button("Continue") { withAnimation { step += 1 } }
                    .buttonStyle(.borderedProminent).controlSize(.large)
            } else {
                Button {
                    requestingPermission = true
                    Task { await request(); requestingPermission = false; withAnimation { step += 1 } }
                } label: {
                    Text(requestingPermission ? "…" : "Allow")
                        .frame(minWidth: 180)
                }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(requestingPermission)
                Button("Skip") { withAnimation { step += 1 } }.font(.callout)
            }
            Spacer().frame(height: 50)
        }
        .padding()
    }

    private var finishedPage: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "checkmark.seal.fill").font(.system(size: 80)).foregroundStyle(.green)
            Text("Ready to stream").font(.title.weight(.bold))
            Text("You can change permissions later in iOS Settings → SENTINEL.")
                .font(.body).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 30)
            Spacer()
            Button {
                UserDefaults.standard.set(true, forKey: Pref.onboardingCompleted)
                withAnimation { completed = true }
            } label: { Text("Enter SENTINEL").frame(minWidth: 200) }
            .buttonStyle(.borderedProminent).controlSize(.large)
            Spacer().frame(height: 50)
        }
        .padding()
    }

    private func bullet(_ text: String, _ icon: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.title3).foregroundStyle(Color.accentColor).frame(width: 28)
            Text(text).font(.body)
            Spacer()
        }
    }

    // MARK: - Permission requests

    private func requestCamera() async { _ = await AVCaptureDevice.requestAccess(for: .video) }
    private func requestMicrophone() async { _ = await AVCaptureDevice.requestAccess(for: .audio) }
    private func requestLocation() async {
        await MainActor.run {
            CLLocationManager().requestWhenInUseAuthorization()
        }
    }
    private func requestBluetooth() async {
        // Touch CBCentralManager; system shows the permission dialog on first interaction.
        await MainActor.run { _ = CBCentralManager(delegate: nil, queue: nil) }
    }
    private func requestNotifications() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])
    }
}

private extension CLAuthorizationStatus {
    var isAuthorized: Bool {
        self == .authorizedAlways || self == .authorizedWhenInUse
    }
}
