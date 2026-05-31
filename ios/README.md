# SENTINEL — iOS

Native iOS port of [SENTINEL](https://github.com/herculanocosta/sentinel) (Android) — tactical
live-streaming from iPhone to RTMP / RTSP / SRT, with on-device transcoding, an ATAK CoT video
marker, server presets, and (planned) GoPro WiFi-in + cellular-out.

Target: **iOS 16+**, SwiftUI, Swift Package Manager.

## Status

Phased delivery. Tick = working end-to-end. Half-tick = scaffolded with TODOs.

- [x] Project scaffold (XcodeGen-driven)
- [x] SwiftUI source-selection landing
- [x] Phone camera → RTMP/RTSP/SRT (via HaishinKit)
- [x] Server presets (publish/viewer split, protocol picker — matches Android UX)
- [x] GPS/timestamp burn-in overlay (CoreImage filter chain on the capture pipeline)
- [x] ATAK CoT video marker (`b-i-v` type, structured `ConnectionEntry`)
- [~] Camera control buttons (switch front/back, zoom, flashlight)
- [~] Settings screen
- [ ] GoPro WiFi-in + cellular-out (BLE pair + `NEHotspotConfiguration` + interface-bound
      `NWConnection` streaming) — biggest remaining piece
- [ ] Background streaming (audio background mode keeps it alive screen-off)
- [ ] App Store submission readiness

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
