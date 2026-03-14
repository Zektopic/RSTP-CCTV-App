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

Response format example (`GET /events?limit=2`):

```json
{
    "events": [
        {
            "id": "0a5d7d4f-...",
            "type": "person",
            "score": 0.91,
            "start_time": 1741963450123,
            "end_time": 1741963450123,
            "snapshot": "0a5d7d4f-..._snapshot.jpg",
            "has_snapshot": true,
            "has_clip": false,
            "created_at": 1741963450123
        }
    ],
    "count": 1
}
```

### Quick methods to fetch/copy events

PowerShell (copy to clipboard):

```powershell
$events = Invoke-RestMethod -Uri "http://<server-ip>:8080/events?limit=500"
$events | ConvertTo-Json -Depth 10 | Set-Clipboard
```

PowerShell (save to file):

```powershell
Invoke-WebRequest -Uri "http://<server-ip>:8080/events?limit=500" -OutFile ".\events.json"
```

cURL:

```bash
curl "http://<server-ip>:8080/events?limit=100"
```

Python:

```python
import requests

base = "http://<server-ip>:8080"
events = requests.get(f"{base}/events", params={"limit": 100}, timeout=10).json()
print(events)
```

### Home Assistant integration examples

`configuration.yaml` (REST sensor showing latest event type):

```yaml
sensor:
    - platform: rest
        name: cctv_latest_event
        resource: http://<server-ip>:8080/events?limit=1
        scan_interval: 10
        value_template: >-
            {% set e = value_json.events %}
            {{ e[0].type if e and e | count > 0 else 'none' }}
        json_attributes_path: "$.events[0]"
        json_attributes:
            - id
            - score
            - start_time
            - has_snapshot
```

`configuration.yaml` (REST command to create a test event):

```yaml
rest_command:
    cctv_create_test_event:
        url: "http://<server-ip>:8080/action/create-test-event"
        method: get
```

Automation idea:

- Trigger on `sensor.cctv_latest_event` state change.
- Send a mobile notification with event type and link to snapshot:
    - `http://<server-ip>:8080/events/<event_id>/snapshot.jpg`

### Custom script integration patterns

Recommended polling strategy:

1. Store a `last_seen_timestamp` (epoch ms).
2. Poll `GET /events?since=<last_seen_timestamp>&limit=100` every few seconds.
3. Process returned events sorted by `start_time`.
4. Update `last_seen_timestamp` to the newest processed event.
5. Optionally download snapshot via `GET /events/<id>/snapshot.jpg`.

Minimal Python poller:

```python
import time
import requests

base = "http://<server-ip>:8080"
last_seen = 0

while True:
        r = requests.get(f"{base}/events", params={"since": last_seen, "limit": 100}, timeout=10)
        data = r.json()
        events = sorted(data.get("events", []), key=lambda e: e.get("start_time", 0))

        for e in events:
            print(f"[{e.get('type')}] id={e.get('id')} score={e.get('score')}")
            last_seen = max(last_seen, int(e.get("start_time", 0)))

        time.sleep(5)
```

Retention behavior:

- Events and media files are retained for 72 hours.
- Cleanup runs at service startup and then periodically while the service is running.
- Yes, events are deleted automatically after about 3 days (72 hours), including associated snapshots/clips when present.

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
