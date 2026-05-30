# SENTINEL

**Tactical live-streaming for Android.** Stream your phone camera, a USB camera, the screen, or a
**GoPro** to an RTSP / RTMP / SRT server (e.g. [MediaMTX](https://github.com/bluenviron/mediamtx))
with **on-device transcoding** for weak-network areas — and advertise the feed as a **camera marker
on the ATAK map** so teammates can tap your marker and watch the live video.

Forked from [OpenTAK ICU](https://github.com/brian7704/OpenTAK_ICU) by Brian Adams, built on
[pedroSG94/RootEncoder](https://github.com/pedroSG94/RootEncoder).

> Package `org.artyllm.sentinel` · v2.0.0 · minSdk 26 / target 35

---

## What SENTINEL adds over OpenTAK ICU

- **GoPro source** — connects to a GoPro over BLE, joins its WiFi AP, pulls the HEVC preview and
  re-encodes it. The GoPro WiFi is **app-bound**, so the outbound broadcast keeps using
  **cellular / mobile data**: video comes in over WiFi while the broadcast goes out over LTE,
  simultaneously. Device-list picker, retry, and an AP-warmup delay make the connect reliable.
- **Video marker on the ATAK map** — publishes a CoT `b-i-v` (camera-icon) marker carrying the feed
  as a `<__video>` media source with a fully structured `<ConnectionEntry>` (parsed from the viewer
  URL), so peers can tap it and play. The marker **name is configurable**.
- **Server presets** with a **Publish / Viewer split** — save multiple destinations and switch in one
  tap. The **viewer URL has its own protocol** (independent of how you publish — MediaMTX re-serves
  the same stream on RTSP/RTMP/SRT), and **SRT is handled correctly**: `streamid=publish:` to push,
  `streamid=read:` to view.
- **GPS + timestamp burn-in overlay** (UTC or local) baked into the encoded *and* recorded video.
- **Source-selection landing page**, choose output orientation per stream, **auto-reconnect** on a
  dropped broadcast, a **battery / thermal guard**, themed status-bar notification, and a Material 3 UI.

## Features (inherited + new)

- **Sources:** phone camera (front/back), USB camera, screen capture, **GoPro**
- **Video:** H.264 / H.265 (and AV1 where the device supports it) · **Audio:** AAC / G711 / OPUS
- **Protocols:** RTSP(S), RTMP(S), SRT, multicast UDP
- Background streaming + recording (screen off), record-while-streaming, photo capture
- Adaptive bitrate, authentication, servers with self-signed certificates
- ATAK / TAK: CoT to a TAK server over TLS with a client cert (import a TAK data package zip)

## Build

Standard Android build (requires the Android SDK / Android Studio):

```bash
./gradlew assembleRelease      # or assembleDebug
```

The APK lands in `app/build/outputs/apk/`.

<details>
<summary>Headless build via Docker (used by this project)</summary>

Windows JDK 21 has an NIO loopback bug, so release builds here run inside a container:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v ".:/workspace" -w /workspace \
  reactnativecommunity/react-native-android:latest \
  bash -c "./gradlew assembleRelease -x lint --no-daemon"
```
</details>

### Release signing

Signing material is **not** in the repo. Without it, release builds automatically fall back to
debug signing. To sign for release, provide both (both are gitignored):

- `app/sentinel.jks` — your keystore
- `keystore.properties` at the repo root — see [`keystore.properties.example`](keystore.properties.example):

```properties
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_key_password
RELEASE_STORE_PASSWORD=your_store_password
```

## Viewing a stream

**In ATAK** — enable *Settings → ATAK → Send CoT* and point it at your TAK server. A camera marker
named with your callsign appears on the map; tap it to watch. To add a feed manually instead, use
ATAK's Video tool: Type `rtsp`, Address = your server, Port `8554`, Path = your stream name, and
enable "Reliable" (TCP) if there's a NAT/firewall between you and the server.

**In VLC** — `rtsp://your_server:8554/your_path`

**In a browser** (MediaMTX + H264) — HLS at `http://your_server:8888/path` or WebRTC at
`http://your_server:8889/path`. Most browsers can't play H265 ([CanIUse](https://caniuse.com/hevc));
WebRTC needs OPUS or G711 audio.

## Publish vs. viewer URLs

When you save a preset, SENTINEL keeps the **publish** side (where it pushes) separate from the
**viewer** side (the URL advertised to ATAK). For RTSP/RTMP they're usually the same; for SRT they
differ (`publish:` vs `read:`). The editor suggests the viewer URL per protocol:

| Protocol | Publish | Viewer (sent to ATAK) |
|----------|---------|------------------------|
| RTSP     | `rtsp://host:8554/name`  | `rtsp://host:8554/name?tcp` |
| RTMP     | `rtmp://host:1935/name`  | `rtmp://host:1935/name` |
| SRT      | `srt://host:8890?streamid=publish:name` | `srt://host:8890?streamid=read:name` |

## Credits & license

- Forked from **OpenTAK ICU** © Brian Adams — [brian7704/OpenTAK_ICU](https://github.com/brian7704/OpenTAK_ICU)
- Streaming engine: **RootEncoder** by [pedroSG94](https://github.com/pedroSG94/RootEncoder)
- GoPro control via the **Open GoPro** BLE / HTTP spec
- **ATAK** © US DoD / Civil release

SENTINEL is a derivative work of OpenTAK ICU and follows the upstream project's license terms.
