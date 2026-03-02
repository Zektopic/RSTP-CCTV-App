package com.zektopic.cctvapp

import android.content.Context
import android.content.Intent
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class WebServer(
    private val context: Context,
    private val ipAddress: String,
    private val imageProvider: () -> ByteArray?,
    private val onSwitchCamera: () -> Unit,
    private val onStartStream: () -> Unit,
    private val onStopStream: () -> Unit,
    private val isStreaming: () -> Boolean,
    private val onCodecUpdate: (String) -> Unit,
    private val getCurrentCodec: () -> String,
    private val onResolutionUpdate: (Int, Int) -> Unit,
    private val getCurrentResolution: () -> String
) : NanoHTTPD(8080) {

    override fun serve(session: IHTTPSession): Response {
        try {
            return processRequest(session)
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error")
        }
    }

    private fun processRequest(session: IHTTPSession): Response {
        val uri = session.uri
        
        if (uri == "/shot.jpg") {
            val imageBytes = imageProvider()
            return if (imageBytes != null) {
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(imageBytes), imageBytes.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Camera not ready")
            }
        }

        if (uri == "/action/switch-camera") {
            onSwitchCamera()
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Switched")
        }
        
        if (uri == "/action/toggle-stream") {
            if (isStreaming()) {
                onStopStream()
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Stopped")
            } else {
                onStartStream()
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Started")
            }
        }

        if (uri == "/action/set-codec") {
            val codec = session.parameters["codec"]?.get(0) ?: "H264"
            onCodecUpdate(codec)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Codec Updated")
        }
        
        if (uri == "/action/set-resolution") {
            val w = session.parameters["w"]?.get(0)?.toIntOrNull() ?: 640
            val h = session.parameters["h"]?.get(0)?.toIntOrNull() ?: 480
            onResolutionUpdate(w, h)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Resolution Updated")
        }

        // JSON status endpoint
        if (uri == "/status") {
            val streaming = isStreaming()
            val codec = getCurrentCodec()
            val resolution = getCurrentResolution()
            val json = """{"streaming":$streaming,"codec":"$codec","resolution":"$resolution"}"""
            val response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        if (uri == "/" || uri == "/greet.html") {
            return newFixedLengthResponse(buildDashboardHtml())
        } 
        
        // Legacy redirect
        if (uri.startsWith("/server/")) {
             if (uri.endsWith("on")) {
                 onStartStream()
             } else {
                 onStopStream()
             }
             val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "")
             response.addHeader("Location", "/")
             return response
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun buildDashboardHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <title>CCTV Dashboard — $ipAddress</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <style>
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
        
        :root {
            --bg: #0f1117;
            --surface: #1a1d27;
            --surface-hover: #22263a;
            --border: rgba(255,255,255,0.06);
            --text: #e8eaed;
            --text-secondary: #9aa0b0;
            --accent: #00b894;
            --accent-glow: rgba(0,184,148,0.15);
            --danger: #e74c3c;
            --warning: #f39c12;
            --radius: 16px;
            --radius-sm: 10px;
        }
        
        body {
            font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
            background: var(--bg);
            color: var(--text);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            padding: 24px 16px;
            -webkit-font-smoothing: antialiased;
        }
        
        .container { width: 100%; max-width: 520px; }
        
        /* Header */
        .header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 24px;
        }
        .header h1 {
            font-size: 22px;
            font-weight: 700;
            letter-spacing: -0.5px;
            background: linear-gradient(135deg, #00b894, #00cec9);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }
        .status-badge {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 5px 12px;
            border-radius: 20px;
            font-size: 12px;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            transition: all 0.3s ease;
        }
        .status-badge.live {
            background: var(--accent-glow);
            color: var(--accent);
            box-shadow: 0 0 20px rgba(0,184,148,0.1);
        }
        .status-badge.offline {
            background: rgba(231,76,60,0.12);
            color: var(--danger);
        }
        .status-dot {
            width: 7px; height: 7px;
            border-radius: 50%;
            background: currentColor;
        }
        .status-badge.live .status-dot {
            animation: pulse 2s ease-in-out infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.5; transform: scale(1.3); }
        }
        
        /* Preview Card */
        .preview-card {
            background: var(--surface);
            border-radius: var(--radius);
            border: 1px solid var(--border);
            overflow: hidden;
            margin-bottom: 16px;
            box-shadow: 0 8px 32px rgba(0,0,0,0.3);
        }
        .preview-wrapper {
            position: relative;
            width: 100%;
            aspect-ratio: 4/3;
            background: #000;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .preview-wrapper img {
            width: 100%;
            height: 100%;
            object-fit: contain;
            display: block;
            transition: opacity 0.2s;
        }
        .preview-overlay {
            position: absolute;
            top: 12px;
            right: 12px;
            display: flex;
            gap: 6px;
        }
        .chip {
            padding: 3px 10px;
            border-radius: 6px;
            font-size: 11px;
            font-weight: 600;
            background: rgba(0,0,0,0.65);
            backdrop-filter: blur(8px);
            color: var(--text);
            border: 1px solid rgba(255,255,255,0.1);
        }
        
        /* Controls Section */
        .controls {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 10px;
            padding: 16px;
        }
        
        /* Buttons */
        .btn {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            padding: 12px 16px;
            border: 1px solid var(--border);
            border-radius: var(--radius-sm);
            background: var(--surface);
            color: var(--text);
            font-family: inherit;
            font-size: 13px;
            font-weight: 500;
            cursor: pointer;
            transition: all 0.2s ease;
            outline: none;
        }
        .btn:hover { background: var(--surface-hover); border-color: rgba(255,255,255,0.1); }
        .btn:active { transform: scale(0.97); }
        .btn.primary {
            background: var(--accent);
            color: #fff;
            border-color: transparent;
        }
        .btn.primary:hover { background: #00a884; }
        .btn.danger {
            background: rgba(231,76,60,0.12);
            color: var(--danger);
            border-color: rgba(231,76,60,0.2);
        }
        .btn.danger:hover { background: rgba(231,76,60,0.2); }
        .btn svg { width: 16px; height: 16px; fill: currentColor; }
        
        /* Settings Card */
        .settings-card {
            background: var(--surface);
            border-radius: var(--radius);
            border: 1px solid var(--border);
            padding: 20px;
            margin-bottom: 16px;
            box-shadow: 0 4px 16px rgba(0,0,0,0.2);
        }
        .settings-card h3 {
            font-size: 13px;
            font-weight: 600;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 1px;
            margin-bottom: 16px;
        }
        .setting-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 10px 0;
        }
        .setting-row + .setting-row { border-top: 1px solid var(--border); }
        .setting-label {
            font-size: 14px;
            font-weight: 500;
        }
        
        /* Custom Select */
        .select-wrap {
            position: relative;
        }
        .select-wrap select {
            appearance: none;
            -webkit-appearance: none;
            background: var(--bg);
            color: var(--text);
            border: 1px solid var(--border);
            padding: 8px 32px 8px 12px;
            border-radius: 8px;
            font-family: inherit;
            font-size: 13px;
            font-weight: 500;
            cursor: pointer;
            outline: none;
            transition: border-color 0.2s;
        }
        .select-wrap select:hover { border-color: rgba(255,255,255,0.15); }
        .select-wrap select:focus { border-color: var(--accent); }
        .select-wrap::after {
            content: '▾';
            position: absolute;
            right: 10px;
            top: 50%;
            transform: translateY(-50%);
            color: var(--text-secondary);
            pointer-events: none;
            font-size: 12px;
        }
        
        /* Toggle Switch */
        .toggle { position: relative; display: inline-block; width: 44px; height: 24px; }
        .toggle input { opacity: 0; width: 0; height: 0; }
        .toggle-track {
            position: absolute; cursor: pointer;
            top: 0; left: 0; right: 0; bottom: 0;
            background: #2d3143;
            border-radius: 24px;
            transition: all 0.3s ease;
        }
        .toggle-track::before {
            content: '';
            position: absolute;
            width: 18px; height: 18px;
            left: 3px; bottom: 3px;
            background: white;
            border-radius: 50%;
            transition: all 0.3s ease;
            box-shadow: 0 1px 3px rgba(0,0,0,0.3);
        }
        .toggle input:checked + .toggle-track { background: var(--accent); }
        .toggle input:checked + .toggle-track::before { transform: translateX(20px); }
        
        /* Info Card */
        .info-card {
            background: var(--surface);
            border-radius: var(--radius);
            border: 1px solid var(--border);
            padding: 20px;
            margin-bottom: 16px;
            box-shadow: 0 4px 16px rgba(0,0,0,0.2);
        }
        .info-card h3 {
            font-size: 13px;
            font-weight: 600;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 1px;
            margin-bottom: 16px;
        }
        .url-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 10px 0;
        }
        .url-row + .url-row { border-top: 1px solid var(--border); }
        .url-label { font-size: 12px; color: var(--text-secondary); margin-bottom: 4px; }
        .url-value {
            font-family: 'JetBrains Mono', 'SF Mono', monospace;
            font-size: 13px;
            color: var(--accent);
            word-break: break-all;
        }
        .copy-btn {
            background: none;
            border: 1px solid var(--border);
            color: var(--text-secondary);
            border-radius: 6px;
            padding: 6px 8px;
            cursor: pointer;
            transition: all 0.2s;
            flex-shrink: 0;
            margin-left: 12px;
        }
        .copy-btn:hover { background: var(--surface-hover); color: var(--text); }
        .copy-btn svg { width: 14px; height: 14px; fill: currentColor; display: block; }
        
        /* Toast */
        .toast {
            position: fixed;
            bottom: 32px;
            left: 50%;
            transform: translateX(-50%) translateY(80px);
            background: var(--surface);
            color: var(--text);
            padding: 12px 24px;
            border-radius: 12px;
            font-size: 13px;
            font-weight: 500;
            box-shadow: 0 8px 32px rgba(0,0,0,0.4);
            border: 1px solid var(--border);
            opacity: 0;
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
            z-index: 1000;
            pointer-events: none;
        }
        .toast.show {
            opacity: 1;
            transform: translateX(-50%) translateY(0);
        }
        
        /* Footer */
        .footer {
            text-align: center;
            font-size: 12px;
            color: var(--text-secondary);
            margin-top: 8px;
            opacity: 0.6;
        }
    </style>
</head>
<body>
    <div class="container">
        <!-- Header -->
        <div class="header">
            <h1>CCTV Dashboard</h1>
            <div class="status-badge offline" id="statusBadge">
                <span class="status-dot"></span>
                <span id="statusText">OFFLINE</span>
            </div>
        </div>
        
        <!-- Preview -->
        <div class="preview-card">
            <div class="preview-wrapper">
                <img id="cam-preview" src="/shot.jpg" alt="Live Preview" />
                <div class="preview-overlay">
                    <span class="chip" id="chipCodec">—</span>
                    <span class="chip" id="chipRes">—</span>
                </div>
            </div>
            <div class="controls">
                <button class="btn primary" id="btnStream" onclick="toggleStream()">
                    <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
                    <span id="btnStreamText">Start</span>
                </button>
                <button class="btn" onclick="switchCamera()">
                    <svg viewBox="0 0 24 24"><path d="M20 4h-3.17L15 2H9L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 13c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z"/></svg>
                    Flip Camera
                </button>
            </div>
        </div>
        
        <!-- Settings -->
        <div class="settings-card">
            <h3>Settings</h3>
            <div class="setting-row">
                <span class="setting-label">Codec</span>
                <div class="select-wrap">
                    <select id="codecSelect" onchange="changeCodec(this.value)">
                        <option value="H264">H.264</option>
                        <option value="H265">H.265</option>
                        <option value="VP9">VP9</option>
                        <option value="AV1">AV1</option>
                    </select>
                </div>
            </div>
            <div class="setting-row">
                <span class="setting-label">Resolution</span>
                <div class="select-wrap">
                    <select id="resSelect" onchange="changeResolution(this.value)">
                        <option value="640x480">480p</option>
                        <option value="1280x720">720p</option>
                        <option value="1920x1080">1080p</option>
                    </select>
                </div>
            </div>
        </div>
        
        <!-- Connection Info -->
        <div class="info-card">
            <h3>Connection</h3>
            <div class="url-row">
                <div>
                    <div class="url-label">RTSP Stream</div>
                    <div class="url-value" id="rtspUrl">rtsp://$ipAddress:8554/stream</div>
                </div>
                <button class="copy-btn" onclick="copyUrl('rtspUrl')" title="Copy">
                    <svg viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>
                </button>
            </div>
            <div class="url-row">
                <div>
                    <div class="url-label">Web Dashboard</div>
                    <div class="url-value">http://$ipAddress:8080</div>
                </div>
            </div>
        </div>
        
        <div class="footer">CCTV Server • $ipAddress</div>
    </div>
    
    <div class="toast" id="toast"></div>
    
    <script>
        // --- Preview auto-refresh ---
        const img = document.getElementById('cam-preview');
        setInterval(() => {
            const newImg = new Image();
            newImg.onload = () => { img.src = newImg.src; };
            newImg.src = '/shot.jpg?t=' + Date.now();
        }, 500);
        
        // --- Status polling ---
        function fetchStatus() {
            fetch('/status')
                .then(r => r.json())
                .then(data => {
                    const badge = document.getElementById('statusBadge');
                    const statusText = document.getElementById('statusText');
                    const btnStream = document.getElementById('btnStream');
                    const btnStreamText = document.getElementById('btnStreamText');
                    const chipCodec = document.getElementById('chipCodec');
                    const chipRes = document.getElementById('chipRes');
                    
                    if (data.streaming) {
                        badge.className = 'status-badge live';
                        statusText.textContent = 'LIVE';
                        btnStream.className = 'btn danger';
                        btnStreamText.textContent = 'Stop';
                    } else {
                        badge.className = 'status-badge offline';
                        statusText.textContent = 'OFFLINE';
                        btnStream.className = 'btn primary';
                        btnStreamText.textContent = 'Start';
                    }
                    
                    chipCodec.textContent = data.codec;
                    chipRes.textContent = data.resolution;
                    
                    // Sync dropdowns
                    document.getElementById('codecSelect').value = data.codec;
                    document.getElementById('resSelect').value = data.resolution;
                })
                .catch(() => {});
        }
        fetchStatus();
        setInterval(fetchStatus, 2000);
        
        // --- Actions ---
        function toggleStream() {
            fetch('/action/toggle-stream')
                .then(r => r.text())
                .then(t => { showToast(t === 'Started' ? 'Stream started' : 'Stream stopped'); fetchStatus(); });
        }
        
        function switchCamera() {
            fetch('/action/switch-camera')
                .then(() => showToast('Camera switched'));
        }
        
        function changeCodec(val) {
            fetch('/action/set-codec?codec=' + val)
                .then(() => { showToast('Codec: ' + val); fetchStatus(); });
        }
        
        function changeResolution(val) {
            const [w, h] = val.split('x');
            fetch('/action/set-resolution?w=' + w + '&h=' + h)
                .then(() => { showToast('Resolution: ' + val); fetchStatus(); });
        }
        
        function copyUrl(id) {
            const text = document.getElementById(id).textContent;
            navigator.clipboard.writeText(text).then(() => showToast('Copied to clipboard'));
        }
        
        // --- Toast ---
        let toastTimer;
        function showToast(msg) {
            const toast = document.getElementById('toast');
            toast.textContent = msg;
            toast.classList.add('show');
            clearTimeout(toastTimer);
            toastTimer = setTimeout(() => toast.classList.remove('show'), 2500);
        }
    </script>
</body>
</html>
        """
    }
}