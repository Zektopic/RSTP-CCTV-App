Object detection model
======================

detect.tflite is BUNDLED with this app, so person/animal detection works out of the box.

Bundled model
-------------
File:    detect.tflite
Model:   EfficientDet-Lite0 (float32), object detection, COCO classes
Source:  https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite
Docs:    https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector
Size:    ~13.8 MB
Runtime: MediaPipe Tasks (com.google.mediapipe:tasks-vision)

Licence
-------
Published by Google as part of the MediaPipe Solutions model set, which is distributed
under the Apache License 2.0:

    https://www.apache.org/licenses/LICENSE-2.0

Note: the model file was downloaded directly from the Google Cloud Storage bucket above,
which does not serve a LICENSE file alongside it. The Apache-2.0 attribution here follows
MediaPipe's project licensing. If you redistribute this app commercially, confirm the
current terms on the model card before relying on that.

This licence covers the model only. The app itself is MIT licensed (see LICENSE at the
repository root).

Replacing it
------------
Any TFLite object detection model that carries TFLite Metadata will work. Drop it in as
detect.tflite, replacing the bundled one.

Requirements:
- COCO-compatible labels, for person / cat / dog / bird / horse / sheep / cow events
- TFLite Metadata embedded (MediaPipe reads labels from it; a bare model will fail to load)

If the file is missing or unreadable, motion detection continues to work and person/animal
detection reports itself unavailable rather than crashing.
