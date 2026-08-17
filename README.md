<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="120" alt="RTSP CCTV App icon"/>

# RTSP CCTV App

**Turn any spare Android phone into a real security camera.**

Runs an RTSP server and a web dashboard on your device, streams to VLC, OBS, Frigate,
Home Assistant or any NVR, and records motion events locally — no cloud, no account,
no subscription.

[![CI](https://github.com/Zektopic/RSTP-CCTV-App/actions/workflows/ci.yml/badge.svg)](https://github.com/Zektopic/RSTP-CCTV-App/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Zektopic/RSTP-CCTV-App?sort=semver)](https://github.com/Zektopic/RSTP-CCTV-App/releases)
[![Min SDK](https://img.shields.io/badge/minSdk-24-blue)](https://developer.android.com/about/versions/nougat)
[![Target SDK](https://img.shields.io/badge/targetSdk-36-blue)](https://developer.android.com/about/versions/16)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

</div>

---

## Contents

- [Why](#why)
- [Features](#features)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Connecting a client](#connecting-a-client)
- [Security](#security)
- [HTTP API](#http-api)
- [Permissions](#permissions)
- [Object detection setup](#object-detection-setup)
- [Building from source](#building-from-source)
- [Troubleshooting](#troubleshooting)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Why

An old phone already has everything a security camera needs: a decent sensor, a
hardware H.264/H.265 encoder, Wi-Fi, a battery that doubles as a UPS, and a torch for
night use. This app turns that hardware into a standards-compliant RTSP source that any
NVR can consume, and keeps every frame on your own network.

---

## Features

### Streaming
- **RTSP server** on port `8554`, low latency over Wi-Fi
- **Hardware-accelerated codecs** — H.264, H.265 (HEVC), AV1, with automatic fallback to
  H.264 when the selected codec cannot be prepared
- **Resolutions** from 640×480 up to the camera's maximum (capped at 4K to stay within
  encoder limits), with bitrate scaled to the resolution
- **Optional audio** — off by default, so the app does not claim the microphone unless
  you ask it to
- **Background operation** via a foreground service; keeps streaming with the screen off
- **Front/back camera switching**, from the app, the dashboard, or the API

### Monitoring
- **Web dashboard** on port `8080` — live preview, every setting, battery and Wi-Fi status
- **Motion detection** with adjustable sensitivity, running entirely on-device
- **Person and animal detection** via MediaPipe Tasks *(requires a model — see
  [setup](#object-detection-setup))*
- **Event captions** describing each snapshot in plain language, generated on-device by
  Gemini Nano *(only on hardware with AICore — silently absent everywhere else)*
- **Event storage** with snapshots, a 72-hour retention window and a hard event cap
- **Events browser** in-app, with thumbnails, and a Frigate-style JSON API

### Overlays and camera control
- Timestamp and date overlay, positionable in any corner, three text sizes
- Torch control, plus a **night mode** that switches the torch on automatically using the
  ambient light sensor

---

## How it works

```mermaid
flowchart LR
    CAM[Camera2 + OpenGL surface] --> ENC[Hardware encoder<br/>H.264 / H.265 / AV1]
    CAM --> SNAP[JPEG snapshot loop<br/>throttled when idle]

    ENC --> RTSP[RTSP server<br/>:8554]
    SNAP --> WEB[Web server<br/>:8080]
    SNAP --> DET

    subgraph DET [Detection pipeline]
        MOT[Motion detector<br/>luma differencing]
        OBJ[MediaPipe object detector<br/>person / animal]
    end

    DET --> STORE[(Event store<br/>JSON + snapshots)]
    STORE --> WEB

    RTSP --> NVR[VLC / OBS / Frigate / NVR]
    WEB --> BROWSER[Browser dashboard]
```

Everything runs inside one foreground service. The RTSP stream comes straight off the
hardware encoder; the dashboard, the detection pipeline and the event snapshots all share
a single JPEG capture loop, which idles automatically when nothing is watching and
detection is off.

| Component | File |
|---|---|
| Foreground service, camera, stream lifecycle | `CctvServerService.kt` |
| HTTP server, dashboard, events API | `WebServer.kt` |
| Authentication, CSRF and HTML escaping | `WebAuth.kt` |
| Motion detection | `MotionDetector.kt` |
| Object detection | `LiteRtObjectDetector.kt` |
| Event captions (Gemini Nano) | `EventCaptioner.kt` |
| Event persistence and retention | `EventStore.kt` |
| Settings | `AppPreferences.kt` |

---

## Quick start

1. **Install** the APK from [Releases](https://github.com/Zektopic/RSTP-CCTV-App/releases),
   or [build it yourself](#building-from-source).
2. **Grant permissions** on first launch — Camera, and *Display over other apps*
   (required to keep the camera surface alive in the background). Notifications and
   Microphone are optional.
3. **Note the generated dashboard password.** On first run the app creates a random
   password for the web dashboard and shows it to you once. It is also visible any time
   under *Authentication*.
4. **Configure** resolution, codec and any overlays you want.
5. **Toggle the server on.** The app shows the RTSP and dashboard URLs.
6. **Connect** from any client on the same network.

> [!TIP]
> Leave the phone plugged in. Encoding video continuously is a heavy, sustained load and
> will drain a battery in a few hours.

---

## Connecting a client

```
rtsp://<phone-ip>:8554/stream                 # no authentication
rtsp://<user>:<pass>@<phone-ip>:8554/stream   # with authentication enabled
```

| Client | How |
|---|---|
| **VLC** | Media → Open Network Stream → paste the RTSP URL |
| **ffplay** | `ffplay -fflags nobuffer rtsp://<phone-ip>:8554/stream` |
| **OBS** | Add a *Media Source*, uncheck *Local File*, paste the URL |
| **Home Assistant** | Generic Camera integration, or `go2rtc` |
| **Frigate** | Add as an `ffmpeg` input under `cameras:` |

The web dashboard lives at `http://<phone-ip>:8080`.

---

## Security

This app puts a camera on your network. The defaults are chosen accordingly.

### What is protected

| Control | Default | Notes |
|---|---|---|
| Web dashboard authentication | **On** | HTTP Basic. A strong password is generated on first run. |
| RTSP authentication | Off | Enable under *Authentication*; applies to the RTSP stream. |
| Cross-origin requests | **Rejected** | Stops a website you visit from driving the camera over your LAN. |
| Credentials in cloud backup | **Excluded** | Preferences and snapshots are excluded from Auto Backup and device transfer. |
| Audio capture | **Off** | The microphone is only claimed when you enable it. |
| Start on boot | **Off** | Opt-in. |
| Start when app opens | **Off** | Opt-in. Opening the app no longer starts streaming by itself. |

### What you should still do

> [!IMPORTANT]
> - **Never port-forward this app to the internet.** Both servers speak plaintext — RTSP
>   and HTTP, no TLS. Use a VPN (WireGuard, Tailscale) to reach it from outside.
> - **Keep dashboard authentication on** unless you are on a network you fully trust.
>   With it off, anyone who can reach port 8080 can watch the camera and change settings.
> - **Use a dedicated IoT VLAN or guest network** if your router supports it.

### Signing key rotation

> [!WARNING]
> The release signing key used before version 1.1.0 was committed to this public
> repository and **must be considered compromised**. It has been removed and replaced.
>
> **If you installed a release built before 1.1.0, you must uninstall it before
> installing a newer one.** Android refuses to update an app across a change of signing
> key, so the install will otherwise fail with a signature mismatch. Your settings will
> be lost; the RTSP and dashboard passwords need to be set again.

### Reporting a vulnerability

Please open a [security advisory](https://github.com/Zektopic/RSTP-CCTV-App/security/advisories/new)
rather than a public issue.

---

## HTTP API

Base URL `http://<phone-ip>:8080`.

**Authentication.** When the dashboard is secured (the default), every endpoint requires
HTTP Basic credentials and returns `401` with a `WWW-Authenticate` header otherwise.

```bash
curl -u admin:<password> http://192.168.1.50:8080/status
```

**Verbs.** State-changing endpoints accept `POST` (preferred) and `GET` (kept for
compatibility with existing scripts and NVR integrations). Requests carrying a
cross-origin `Origin` header are rejected with `403`.

### Read

| Endpoint | Returns |
|---|---|
| `GET /` | The dashboard |
| `GET /shot.jpg` | Current JPEG snapshot |
| `GET /status` | JSON status: streaming state, codec, resolution, every setting, battery, Wi-Fi |
| `GET /events?since=<epoch_ms>&limit=<n>` | Stored events, newest first. `limit` defaults to 100, capped at 500 |
| `GET /events/<id>` | A single event |
| `GET /events/<id>/snapshot.jpg` | That event's snapshot |
| `GET /events/<id>/clip.mp4` | Reserved — clip recording is not implemented yet |

### Write

| Endpoint | Parameters |
|---|---|
| `POST /action/toggle-stream` | — |
| `POST /action/switch-camera` | — |
| `POST /action/set-codec` | `codec=H264\|H265\|AV1` |
| `POST /action/set-resolution` | `w=<int>&h=<int>` (`0x0` = camera maximum) |
| `POST /action/set-setting` | `key=<key>&value=<value>` |
| `POST /action/set-auth` | `enabled=<bool>&username=<s>&password=<s>` |
| `POST /action/create-test-event` | — |

<details>
<summary><b>Keys accepted by <code>/action/set-setting</code></b></summary>

| Key | Type | Meaning |
|---|---|---|
| `show_timestamp` | bool | Timestamp overlay |
| `show_date` | bool | Date overlay |
| `timestamp_position` | `Top Left` \| `Top Right` \| `Bottom Left` \| `Bottom Right` | Overlay corner |
| `timestamp_size` | `Small` \| `Medium` \| `Large` | Overlay text size |
| `flashlight_enabled` | bool | Torch |
| `night_mode_enabled` | bool | Automatic torch by ambient light |
| `force_software` | bool | Prefer the software encoder |
| `show_preview` | bool | On-device preview overlay |
| `audio_enabled` | bool | Include microphone audio in the stream |
| `web_auth_enabled` | bool | Require authentication on port 8080 |
| `detection_enabled` | bool | Detection master switch |
| `motion_detection_enabled` | bool | Motion detection |
| `object_detection_enabled` | bool | Person/animal detection |
| `motion_sensitivity` | int 1–10 | Higher triggers on smaller changes |
| `detection_cooldown_seconds` | int 1–600 | Minimum gap between events of one type |

</details>

<details>
<summary><b>Example event response</b></summary>

```json
{
  "events": [
    {
      "id": "2f1c8e40-9a3b-4c21-b0d5-7e6f5a4c3b21",
      "type": "person",
      "score": 0.87,
      "start_time": 1755368400000,
      "end_time": 1755368400000,
      "snapshot": "2f1c8e40-9a3b-4c21-b0d5-7e6f5a4c3b21_snapshot.jpg",
      "has_snapshot": true,
      "has_clip": false,
      "created_at": 1755368400000
    }
  ],
  "count": 1
}
```

</details>

---

## Permissions

| Permission | Required | Why |
|---|---|---|
| `CAMERA` | **Yes** | Capturing video. Without it the server will not start. |
| `SYSTEM_ALERT_WINDOW` | **Yes** | The camera renders into an off-screen overlay surface, which is what keeps encoding alive when the app is not in the foreground. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Yes | Running the local RTSP and HTTP servers, and reporting the device IP. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA` | Yes | Long-running capture. |
| `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` | Only with audio on | Audio is opt-in; the microphone type is only claimed when you enable it. |
| `POST_NOTIFICATIONS` | Recommended | Android 13+. Without it the service notification is suppressed and you lose the visible indicator that the camera is live. |
| `RECEIVE_BOOT_COMPLETED` | Only with start-on-boot | Restarting after a reboot. |
| `ACCESS_WIFI_STATE` | Optional | Wi-Fi signal readout on the dashboard. |

No internet permission is used to send data anywhere. Nothing leaves your network.

---

## Object detection setup

Motion detection works out of the box. Person and animal detection needs a TensorFlow
Lite model, which is **not bundled** — model licences vary and the binary is large.

1. Download a COCO-trained object detection model, for example
   [SSD MobileNet V1](https://www.kaggle.com/models/tensorflow/ssd-mobilenet-v1) from
   TensorFlow Hub or Kaggle Models.
2. Rename it to `detect.tflite`.
3. Place it at `app/src/main/assets/detect.tflite`.
4. Rebuild and install.

The dashboard's *Model Status* row reports whether the model loaded. Recognised labels
are `person`, `cat`, `dog`, `bird`, `horse`, `sheep` and `cow`; the detection threshold is
`0.45`.

---

## Building from source

**Requirements:** JDK 21, Android SDK with API 36, Android Studio (Ladybug or newer) or
the command line.

```bash
git clone https://github.com/Zektopic/RSTP-CCTV-App.git
cd RSTP-CCTV-App

./gradlew testDebugUnitTest    # 62 unit tests
./gradlew lintDebug
./gradlew assembleDebug        # app/build/outputs/apk/debug/
```

### Building a signed release

The release keystore is **not** in this repository and never will be. Provide credentials
one of two ways.

**Locally** — create `keystore.properties` in the repository root (it is git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=release
keyPassword=...
```

**In CI** — set these repository secrets:

| Secret | Contents |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Without credentials the release build still succeeds and produces
`app-release-unsigned.apk`, so forks and pull requests are never blocked on secrets.

Releases are published by pushing a `v*` tag or running the *Android Release* workflow
manually — not on every commit to `master`.

> [!NOTE]
> Dependency versions are pinned in `gradle/libs.versions.toml`. Do not reintroduce
> `master-SNAPSHOT` coordinates: a moving snapshot previously upgraded RootEncoder
> underneath the project and broke a build whose CI had already passed.

---

## Troubleshooting

<details>
<summary><b>The server will not start</b></summary>

Check that both Camera and *Display over other apps* are granted. The overlay permission
is not a normal runtime permission — it has to be enabled from
Settings → Apps → RTSP CCTV App → Display over other apps.
</details>

<details>
<summary><b>The stream is black, or the client cannot connect</b></summary>

- Confirm the phone and the client are on the same network and the network is not using
  AP isolation (common on guest Wi-Fi).
- Try 640×480 with H.264 first — some devices cannot prepare H.265 or AV1 at high
  resolutions, and the app falls back to H.264 when preparation fails.
- Check the notification is present; if it is gone, the OS killed the service.
</details>

<details>
<summary><b>The dashboard asks for a password I do not have</b></summary>

Open the app and look under *Authentication* — the generated username and password are
shown there. You can also turn *Secure Web Dashboard* off, though that leaves the camera
open to everyone on the network.
</details>

<details>
<summary><b>Streaming stops when the screen turns off</b></summary>

Aggressive OEM battery management — worst on Xiaomi/MIUI, Huawei, Oppo and Samsung.
Disable battery optimisation for the app, and on Xiaomi also enable *Autostart*. See
[dontkillmyapp.com](https://dontkillmyapp.com) for per-vendor steps.
</details>

<details>
<summary><b>Start-on-boot does not work</b></summary>

Android 14+ blocks background components from starting a camera foreground service. The
app detects this and posts a *tap to resume* notification instead of crashing. Some OEMs
block autostart outright regardless of the setting.
</details>

<details>
<summary><b>Person detection stays unavailable</b></summary>

`detect.tflite` is missing — see [object detection setup](#object-detection-setup). Motion
detection is unaffected.
</details>

---

## Roadmap

- [ ] Clip recording (`/events/<id>/clip.mp4` is reserved but not yet implemented)
- [ ] HTTPS/TLS for the dashboard, so credentials are not sent in plaintext
- [x] ~~Migrate to a 16 KB page-size-aligned detection build~~ — done: replaced
      `tensorflow-lite-task-vision` (frozen at 0.4.4, `libtask_vision_jni.so` aligned to
      4 KB) with MediaPipe Tasks, whose native is aligned to 16 KB
- [ ] Enable R8 for release builds (needs keep rules for the reflection-heavy
      MediaPipe and RootEncoder dependencies, plus an on-device verification pass)
- [ ] ONVIF discovery so NVRs can find the camera automatically
- [ ] Continuous recording with a rolling buffer
- [ ] Multi-camera management from one dashboard

---

## Contributing

Issues and pull requests are welcome.

- Run `./gradlew testDebugUnitTest lintDebug` before opening a PR — CI runs both.
- Add tests for logic that can be tested on the JVM. Keeping such logic free of Android
  imports (as in `WebAuth`, `MotionDetector` and `EventStore`) is deliberate.
- Never commit keystores, passwords or `keystore.properties`.

---

## License

Released under the [MIT License](LICENSE) — you may use, modify and redistribute this
software, including commercially, provided the copyright notice and licence text are
retained.

The bundled dependencies listed below are Apache-2.0 licensed and remain under their own
terms.

### Built with

- [RootEncoder](https://github.com/pedroSG94/RootEncoder) and
  [RTSP-Server](https://github.com/pedroSG94/RTSP-Server) by pedroSG94
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)
- [MediaPipe Tasks](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector)
- [ML Kit GenAI](https://developers.google.com/ml-kit/genai) (Gemini Nano captions)
