# Bundled OCR model provenance

Bill bundles the official PP-OCRv6 small ONNX detector and unified multilingual
recognizer. The recognizer dictionary includes Chinese, Latin/English, and Japanese
characters. No model is downloaded at runtime.

## Detection model

- Source: <https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_det_onnx_infer.tar>
- Download archive SHA-256: `d218f6fbf0f1c23d2161bd6ac7f5eaa6104fa89955c09290497e31008e2618e4`
- Bundled `inference.onnx` SHA-256: `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`

## Recognition model

- Source: <https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv6_small_rec_onnx_infer.tar>
- Download archive SHA-256: `d267ab077a44a0eedb1ea8f8c542d263f211de8e9d7a029bf9fcfff7e5a88fb1`
- Bundled `inference.onnx` SHA-256: `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`
- Bundled `inference.yml` SHA-256: `ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1`

PaddleOCR source and these official model artifacts are distributed under the
Apache License 2.0. See [PaddleOCR-LICENSE.txt](PaddleOCR-LICENSE.txt).
