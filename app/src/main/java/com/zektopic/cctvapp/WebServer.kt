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
    private val onCodecUpdate: (Boolean) -> Unit,
    private val isH265: () -> Boolean,
    private val onResolutionUpdate: (Int, Int) -> Unit
) : NanoHTTPD(8080) {

    override fun serve(session: IHTTPSession): Response {
        try {
            return processRequest(session)
        } catch (e: Exception) {
            // Suppress broken pipe / socket exceptions from client disconnects
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

        if (uri == "/action/toggle-codec") {
            val useH265 = session.parameters["h265"]?.get(0) == "true"
            onCodecUpdate(useH265)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Codec Updated")
        }
        
        if (uri == "/action/set-resolution") {
            val w = session.parameters["w"]?.get(0)?.toIntOrNull() ?: 640
            val h = session.parameters["h"]?.get(0)?.toIntOrNull() ?: 480
            onResolutionUpdate(w, h)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Resolution Updated")
        }

        // Status endpoint for UI to poll or check initial state
        if (uri == "/status") {
            val status = if (isStreaming()) "streaming" else "stopped"
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, status)
        }

        if (uri == "/" || uri == "/greet.html") {
            val streamChecked = if (isStreaming()) "checked" else ""
            val h265Checked = if (isH265()) "checked" else ""
            
            val html = """
                <!DOCTYPE html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                    <title>IP Webcam ($ipAddress)</title>
                    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap" rel="stylesheet">
                    <style>
                        body { font-family: 'Inter', sans-serif; background-color: #f5f5f7; color: #1d1d1f; margin: 0; padding: 0; display: flex; flex-direction: column; align-items: center; min-height: 100vh; }
                        .container { width: 100%; max-width: 640px; padding: 20px; box-sizing: border-box; }
                        h2 { font-weight: 600; text-align: center; margin-bottom: 24px; }
                        .preview-card { background: white; border-radius: 18px; box-shadow: 0 4px 24px rgba(0,0,0,0.08); overflow: hidden; margin-bottom: 24px; }
                        .preview-box { width: 100%; aspect-ratio: 4/3; background: #000; display: flex; align-items: center; justify-content: center; position: relative; }
                        #cam-preview { width: 100%; height: 100%; object-fit: contain; display: block; }
                        .controls { padding: 20px; display: flex; flex-wrap: wrap; gap: 20px; justify-content: space-around; align-items: center; }
                        
                        /* Toggle Switch CSS */
                        .switch-group { display: flex; align-items: center; gap: 12px; }
                        .switch-label { font-weight: 600; font-size: 14px; }
                        .switch { position: relative; display: inline-block; width: 52px; height: 32px; }
                        .switch input { opacity: 0; width: 0; height: 0; }
                        .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #e9e9ea; -webkit-transition: .4s; transition: .4s; border-radius: 34px; }
                        .slider:before { position: absolute; content: ""; height: 24px; width: 24px; left: 4px; bottom: 4px; background-color: white; -webkit-transition: .4s; transition: .4s; border-radius: 50%; box-shadow: 0 2px 4px rgba(0,0,0,0.2); }
                        input:checked + .slider { background-color: #34c759; }
                        input:focus + .slider { box-shadow: 0 0 1px #34c759; }
                        input:checked + .slider:before { -webkit-transform: translateX(20px); -ms-transform: translateX(20px); transform: translateX(20px); }
                        
                        /* Select CSS */
                        .select-group { display: flex; align-items: center; gap: 12px; }
                        select { -webkit-appearance: none; appearance: none; background-color: #f5f5f7; border: 1px solid #e9e9ea; padding: 8px 16px; border-radius: 8px; font-family: inherit; font-size: 14px; font-weight: 600; color: #1d1d1f; cursor: pointer; }
                        
                        .info-pane { font-size: 13px; color: #86868b; text-align: center; background: #fff; padding: 16px; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); width: 100%; }
                        code { background: #f5f5f7; padding: 4px 8px; border-radius: 6px; font-family: monospace; color: #1d1d1f; user-select: all; }
                    </style>
                  </head>
                  <body>
                      <div class="container">
                        <h2>IP Webcam</h2>
                        
                        <div class="preview-card">
                            <div class="preview-box">
                                <img id="cam-preview" src="/shot.jpg" alt="Stream Preview" />
                            </div>
                            <div class="controls">
                                <div class="switch-group">
                                    <span class="switch-label">Stream</span>
                                    <label class="switch">
                                      <input type="checkbox" id="streamToggle" $streamChecked onchange="toggleStream()">
                                      <span class="slider"></span>
                                    </label>
                                </div>
                                <div class="switch-group">
                                    <span class="switch-label">Front/Back</span>
                                    <label class="switch">
                                      <input type="checkbox" id="cameraToggle" onchange="switchCamera()">
                                      <span class="slider"></span>
                                    </label>
                                </div>
                                <div class="switch-group">
                                    <span class="switch-label">H.265</span>
                                    <label class="switch">
                                      <input type="checkbox" id="codecToggle" $h265Checked onchange="toggleCodec()">
                                      <span class="slider"></span>
                                    </label>
                                </div>
                                <div class="select-group">
                                    <select id="resSelect" onchange="changeResolution(this.value)">
                                        <option value="640x480">480p</option>
                                        <option value="1280x720">720p</option>
                                        <option value="1920x1080">1080p</option>
                                    </select>
                                </div>
                            </div>
                        </div>

                        <div class="info-pane">
                          <p><strong>RTSP URL (VLC):</strong><br><code>rtsp://$ipAddress:8554/stream</code></p>
                        </div>
                      </div>
                    
                    <script>
                        // MJPEG refresh
                        const img = document.getElementById('cam-preview');
                        setInterval(() => {
                            img.src = '/shot.jpg?t=' + new Date().getTime();
                        }, 500); // 2 FPS

                        function switchCamera() {
                            fetch('/action/switch-camera');
                        }
                        
                        function toggleStream() {
                            fetch('/action/toggle-stream');
                        }
                        
                        function toggleCodec() {
                            const isH265 = document.getElementById('codecToggle').checked;
                            fetch('/action/toggle-codec?h265=' + isH265);
                        }
                        
                        function changeResolution(val) {
                            const [w, h] = val.split('x');
                            fetch('/action/set-resolution?w=' + w + '&h=' + h);
                        }
                    </script>
                  </body>
                </html>
            """
            return newFixedLengthResponse(html)
        } 
        
        // Keep legacy for now if needed, or redirect
        if (uri.startsWith("/server/")) {
             val intent = Intent(context, CctvServerService::class.java)
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
}