# Future Tasks

## Important long-term note

The current workaround improves installation/runtime compatibility on 16 KB page-size devices by using legacy JNI packaging.

However, the TensorFlow native binary currently bundled in this app is still 4 KB ELF-aligned.

For Google Play compliance and long-term stability, migrate to a 16 KB-aligned TensorFlow/LiteRT native dependency as soon as the upstream dependency provides compatible binaries.

## Maybe-to-do

- Replace TensorFlow Task Vision with a newer 16 KB-compatible detector stack.
- Or isolate object detection behind a fallback path so motion detection always remains functional when object JNI is incompatible.
