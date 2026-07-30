# Third-party notices

This module combines three local runtime components:

- PaddleOCR Android SDK source and PP-OCRv6 model artifacts, Apache License 2.0.
  See [PaddleOCR-LICENSE.txt](PaddleOCR-LICENSE.txt).
- OpenCV Android 4.12.0, Apache License 2.0.
  See [PaddleOCR-LICENSE.txt](PaddleOCR-LICENSE.txt) for the license text.
  Maven AAR SHA-256: `f71846a313388d9da667a59be1e921669fcf792d1ba264b3898253ca789bc3f0`.
- Microsoft ONNX Runtime Android 1.24.3, MIT License.
  See [ONNXRuntime-LICENSE.txt](ONNXRuntime-LICENSE.txt).
  Maven AAR SHA-256: `67397e4a970e75617f765d2015ceaf911917e1d822276cfb5792744e8085cbce`.

Neither Android AAR declares network permissions, services, receivers, jobs, or alarms.
Bill also calls `OrtEnvironment.setTelemetry(false)` before creating any session.
