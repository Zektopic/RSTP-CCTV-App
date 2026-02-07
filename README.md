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

## Usage

1.  **Grant Permissions**: On first launch, grant Camera, Audio, and "Display over other apps" permissions (required for background streaming).
2.  **Configure**: Select your desired Resolution and Codec.
3.  **Start Server**: Toggle the "Server" switch to ON.
4.  **Connect**: Use the IP address displayed on the screen to connect via an RTSP client.
    *   Example: `rtsp://192.168.1.X:8554/live/stream` (Exact URL depends on library implementation, usually widely compatible).

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
