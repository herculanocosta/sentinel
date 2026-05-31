# SENTINEL — iOS

Native iOS port of [SENTINEL](https://github.com/herculanocosta/sentinel) (Android) — tactical
live-streaming from iPhone to RTMP / RTSP / SRT, with on-device transcoding, an ATAK CoT video
marker, server presets, and (planned) GoPro WiFi-in + cellular-out.

Target: **iOS 16+**, SwiftUI, Swift Package Manager.

## Status

- [x] Project scaffold (XcodeGen-driven)
- [x] SwiftUI source-selection landing + onboarding flow
- [x] Phone camera → RTMP/RTSP/SRT (HaishinKit) + adaptive bitrate
- [x] Smooth multi-lens zoom + tap-to-focus + external camera (iPad iOS 17+)
- [x] Server presets (publish/viewer split, protocol picker, iCloud sync)
- [x] GPS/timestamp burn-in overlay (CoreImage)
- [x] ATAK CoT video marker (`b-i-v`, structured `ConnectionEntry`)
- [x] TAK data package (.zip) importer + connection test (share-sheet integration)
- [x] Auto-reconnect with exponential backoff
- [x] Local recording while streaming
- [x] Pre-flight check screen
- [x] Battery + thermal guard
- [x] Live Activity / Dynamic Island
- [x] Siri Shortcuts / App Intents
- [x] Apple Watch companion (WatchConnectivity)
- [x] GoPro WiFi-in + cellular-out (BLE pair, `NEHotspotConfiguration`,
      `NWConnection(requiredInterfaceType: .wifi/.cellular)`, HEVC ingest, encoder feed)
- [x] Localization (English + Portuguese)
- [x] Privacy manifest (`PrivacyInfo.xcprivacy`)
- [x] TestFlight CI workflow

## App Store submission checklist

When you're ready to ship a TestFlight build:

1. **App ID** on developer.apple.com matches `org.artyllm.sentinel`. Enable Capabilities:
   - Hotspot Configuration (GoPro Wi-Fi join)
   - iCloud (Key-value storage)
   - Background Modes (Audio + Bluetooth Central)
   - Push Notifications (for App Intents / Live Activity)
2. **App Store Connect** → My Apps → create the app record with the same bundle ID.
3. **GitHub secrets** under repo Settings → Secrets:
   - `APP_STORE_CONNECT_API_KEY_ID`
   - `APP_STORE_CONNECT_API_ISSUER_ID`
   - `APP_STORE_CONNECT_API_KEY_P8` (paste the entire `.p8` file)
   - `IOS_DEVELOPER_TEAM_ID`
4. Push a tag: `git tag ios-v2.0.1 && git push origin ios-v2.0.1`.
   The `.github/workflows/ios-testflight.yml` workflow archives, exports, and uploads.
5. **Privacy nutrition label** in App Store Connect mirrors `PrivacyInfo.xcprivacy`:
   - Data collected: precise location, photos/videos, audio
   - Use: app functionality only
   - Linked to user: no
   - Used for tracking: no

## Build

```bash
# One-time tool install (on the Mac).
brew install xcodegen

# Generate the Xcode project from project.yml.
cd ios
xcodegen generate

# Open in Xcode and build.
open Sentinel.xcodeproj
```

The project depends on [HaishinKit](https://github.com/shogo4405/HaishinKit.swift) for
RTMP/RTSP/SRT publishing — Swift Package Manager resolves it automatically the first time you
build. Min iOS 16.0.

## Permissions

Set in `Sentinel/Info.plist`:

- **NSCameraUsageDescription** — camera capture
- **NSMicrophoneUsageDescription** — audio capture
- **NSLocationWhenInUseUsageDescription** — overlay GPS + CoT marker position
- **NSBluetoothAlwaysUsageDescription** — GoPro pairing
- **NSLocalNetworkUsageDescription** — talking to a local TAK server / MediaMTX

Background modes (Capabilities):

- **Audio** — keeps the encoder running when the screen locks (the only way iOS allows this)
- **Bluetooth Central** — GoPro BLE link survives backgrounding

## Architecture

SwiftUI views, `@Observable`-style view models, services as plain Swift classes wired in `SentinelApp`.

```
Sentinel/
├── SentinelApp.swift           @main + AppDependencies wiring
├── Models/                     ServerPreset, CoTEvent, StreamConfig
├── Services/                   CameraService, StreamingService,
│                               LocationService, OverlayRenderer,
│                               CoTService, GoProService
├── Storage/                    Preferences (@AppStorage), PresetStore (UserDefaults JSON)
├── Views/                      SourceSelection, CameraStream, Settings,
│                               ServerPresets list+edit, ATAKSettings
└── Resources/                  Assets, Info.plist, entitlements
```

## What differs from Android (and why)

| Android | iOS — same | iOS — different |
|---|---|---|
| Camera2 + pedroSG94 RootEncoder | AVCaptureSession + HaishinKit | iOS handles HAL stability natively — no need for the watchdog/recovery loop |
| `WifiNetworkSpecifier` + `Network.getSocketFactory()` | `NEHotspotConfiguration` + `NWConnection(requiredInterfaceType:)` | iOS won't let third-party libs (HaishinKit's URLSession) bind per-network; the GoPro path needs hand-rolled MPEG-TS over NWConnection |
| Foreground service | Audio background mode | iOS may kill the app under memory pressure; no permanent service guarantee |
| `TextObjectFilterRender` (pedroSG94) | `CIFilter` chain on the capture buffer | iOS overlay path is cleaner — no aspect-ratio quirks |
| `MediaProjection` (screen capture) | ReplayKit (own app only) or Broadcast Extension (system-wide) | Broadcast Extension is a separate process; v1 ships without it |
