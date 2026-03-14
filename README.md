# RTSP CCTV App

Turn your old or unused Android device into a fully functional RTSP CCTV camera. This application runs a background RTSP server that allows you to stream video over your local network to any RTSP-compatible client (like VLC, OBS, or security NVRs).

**Open Source, Free to Contribute, Fork, and Change!**

## Features

-   **High Performance RTSP Streaming**: Low latency streaming over Wi-Fi.
-   **Background Operation**: continues streaming even when the app is minimized or the screen is off (uses a foreground service).
-   **Multiple Codec Support**:
    -   H.264
    -   H.265 (HEVC)
    -   VP9
    -   AV1
-   **Adjustable Resolution**:
    -   640x480 (SD)
    -   1280x720 (HD)
    -   1920x1080 (Full HD)
-   **Camera Controls**:
    -   Switch between Front and Back cameras.
    -   Toggle Local Preview (save battery by hiding the preview).
    -   Force Software Encoding option.
-   **Web Interface**: Includes a built-in web server for easy management and snapshots.
-   **Event Storage (Phase 1)**: Local event persistence with automatic 72-hour retention cleanup.
-   **Events API (Phase 1)**: Frigate-style event listing and per-event media endpoints on port 8080.

## Usage

1.  **Grant Permissions**: On first launch, grant Camera, Audio, and "Display over other apps" permissions (required for background streaming).
2.  **Configure**: Select your desired Resolution and Codec.
3.  **Start Server**: Toggle the "Server" switch to ON.
4.  **Connect**: Use the IP address displayed on the screen to connect via an RTSP client.
    *   Example: `rtsp://192.168.1.X:8554/live/stream` (Exact URL depends on library implementation, usually widely compatible).

## Events API (Phase 1)

Base URL:

`http://<server-ip>:8080`

Available endpoints:

- `GET /events?since=<epoch_ms>&limit=<n>`
    - Returns stored events sorted by newest first.
    - `since` is optional. `limit` defaults to 100 and is capped at 500.
- `GET /events/<event_id>`
    - Returns one event by id.
- `GET /events/<event_id>/snapshot.jpg`
    - Returns the event snapshot if present.
- `GET /events/<event_id>/clip.mp4`
    - Reserved for clip retrieval (clip persistence comes in later phases).
- `GET /action/create-test-event`
    - Creates a synthetic test event using the latest snapshot.

Retention behavior:

- Events and media files are retained for 72 hours.
- Cleanup runs at service startup and then periodically while the service is running.

## Detection (Motion + LiteRT)

The app now includes:

- Motion detection (frame-difference based)
- People/animal detection using LiteRT/TensorFlow Object Detection

Enable/disable from:

- Android app Detection card
- Web dashboard (`http://<server-ip>:8080`) Detection section

LiteRT model setup:

1. Place your TensorFlow Lite model at:
    `app/src/main/assets/detect.tflite`
2. Start streaming and enable:
    - Enable Detection
    - Motion Detection
    - People/Animal Detection (LiteRT)

If `detect.tflite` is missing, motion detection still works, but people/animal detection will remain unavailable.

## Build & Install

### Requirements
-   Android Studio Ladybug or newer.
-   JDK 17.

### Building
Clone the repository and build using Gradle:

```bash
git clone https://github.com/yourusername/RTSP-CCTV-App.git
cd RTSP-CCTV-App
./gradlew app:assembleDebug
```

### GitHub Actions
This repository includes a CI/CD workflow that automatically builds the APK on every push to `main` and publishes it to GitHub Releases.

## Contributing

We welcome contributions! This project is open source and free for anyone to use, fork, and modify.

1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

## License

Distributed under the MIT License. See `LICENSE` for more information.
