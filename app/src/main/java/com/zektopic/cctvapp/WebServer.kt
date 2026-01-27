package com.zektopic.cctvapp

import android.content.Context
import android.content.Intent
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class WebServer(
    private val context: Context,
    private val ipAddress: String,
    private val imageProvider: () -> ByteArray?
) : NanoHTTPD(8080) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        
        if (uri == "/shot.jpg") {
            val imageBytes = imageProvider()
            return if (imageBytes != null) {
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(imageBytes), imageBytes.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Camera not ready")
            }
        }

        if (uri == "/" || uri == "/greet.html") {
            val html = """
                <!DOCTYPE html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                    <title>IP Webcam ($ipAddress)</title>
                    <link href="https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.7/css/bootstrap.min.css" rel="stylesheet"/>
                    <style>
                        body { padding-top: 50px; }
                        .preview-box { width: 100%; max-width: 640px; margin: 0 auto; background: #000; min-height: 480px; display: flex; align-items: center; justify-content: center; }
                        #cam-preview { width: 100%; height: auto; display: block; }
                    </style>
                  </head>
                  <body>
                    <div class="navbar navbar-inverse navbar-fixed-top">
                      <div class="container">
                        <div class="navbar-header">
                          <a class="navbar-brand" href="/" style="color: #9d9d9d;">IP Webcam ($ipAddress)</a>
                        </div>
                      </div>
                    </div>

                    <div class="jumbotron">
                      <div class="container">
                        <div class="row">
                            <h2 style="text-align: center;">Live Preview</h2>
                            <div class="col-xs-12">
                                <div class="preview-box">
                                    <img id="cam-preview" src="/shot.jpg" alt="Loading stream..." />
                                </div>
                            </div>
                            
                            <div class="col-xs-12" style="text-align: center; margin-top: 20px;">
                                <p><a href="/server/on" class="btn btn-lg btn-success">Start Server</a> <a href="/server/off" class="btn btn-lg btn-danger">Stop Server</a></p>
                            </div>
                            
                            <div class="col-xs-12" style="text-align: center; margin-top: 20px;">
                                <div id="rtsp_pane" class="container" style="padding: 1rem; margin: auto; border: 1px solid #ccc; background: #fff;">
                                  <h4>Connection URL</h4>
                                  <p><strong>RTSP (VLC):</strong> <code>rtsp://$ipAddress:8554/stream</code></p>
                                </div>
                            </div>
                        </div>
                      </div>
                    </div>
                    
                    <script>
                        // Simple MJPEG-like refresh loop
                        const img = document.getElementById('cam-preview');
                        setInterval(() => {
                            img.src = '/shot.jpg?t=' + new Date().getTime();
                        }, 200); // 5 FPS
                    </script>
                  </body>
                </html>
            """
            return newFixedLengthResponse(html)
        } else if (uri.startsWith("/server/")) {
            val intent = Intent(context, CctvServerService::class.java)
            val action = if (uri.endsWith("on")) {
                context.startService(intent)
                "started"
            } else {
                context.stopService(intent)
                "stopped"
            }
            // Redirect back to home
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "")
            response.addHeader("Location", "/")
            return response
        } else if (uri.startsWith("/codec/")) {
            val intent = Intent(context, CctvServerService::class.java)
            intent.putExtra("use_h265", uri.endsWith("h265"))
            context.startService(intent)
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "")
            response.addHeader("Location", "/")
            return response
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }
}