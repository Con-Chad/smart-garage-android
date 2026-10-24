# OCR accuracy fixtures

These 12 images and `ground_truth.json` are copied unmodified (the JSON filtered to the 12 images) from
ente/mobile_ocr, `example/assets/test_ocr/`, at commit 0c68f88f20607cfcb10286422860350ccb024acd
(https://github.com/ente-io/mobile_ocr). They are used only by the instrumented test
`OcrAccuracyTest` and are not shipped in any APK.

Copyright (c) 2025 Laurens Priem. Licensed under the MIT License; see `LICENSE` in this directory.

Upstream fixtures not copied:
- `heic_test.HEIC`: HEIC container; decoder coverage, not recognition accuracy.
- `ocr_not_working.jpg`: the largest file (3.1 MB); `ocr_working.jpg` already covers a 9.4 MP camera photo.
