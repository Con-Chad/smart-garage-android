# Open-source notices

Smart Garage Companion for Android is built on the open-source software listed below. Each entry gives the
licence, the copyright holders and where the source lives; the full licence texts follow at the end. The same
list is shown in the app under Settings > About > Open-source licenses, which reads this very file (it is copied
into the APK's assets at build time).

The library list covers the app's release runtime classpath
(`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`), grouped by project. Build-time tools
(Gradle, the Android Gradle plugin, KSP, the Kotlin compiler plugins, annotation processors) and test-only
libraries are not part of the app and are not listed.

<!--
Format (parsed by the app, D-A65): a "## " heading is a group; each "### " heading under it is a component
followed by "- Key: value" lines (License, Copyright, Source, Version, Artifacts, Note). "License" must name a
"### " heading under "## License texts", whose first ```text block is the licence text. Keep this file the
single source: OpenSourceNoticesTest checks it.
-->

## Smart Garage

### Smart Garage (upstream web app)

- License: MIT
- Copyright: Copyright (c) 2026 flizzy27
- Source: https://github.com/flizzy27/smart-garage
- Note: This app is a companion client for the Smart Garage server. Its API contract, the English and German wording of categories, coverage types and other labels, and several calculations (fuel economy, due status, cost totals) follow the upstream web app.

## On-device OCR

### ente/mobile_ocr

- License: MIT
- Copyright: Copyright (c) 2025 Laurens Priem
- Source: https://github.com/ente/mobile_ocr
- Version: commit 0c68f88f20607cfcb10286422860350ccb024acd
- Note: The OCR pipeline in the :ocr module (text detection, angle classification, recognition, CTC decoding, image handling) is a Kotlin port of this project's Android code, with changes noted in each source file header. The model download code was removed: the models are bundled.

### PaddleOCR PP-OCRv5 models

- License: Apache-2.0
- Copyright: Copyright (c) 2016 PaddlePaddle Authors. All Rights Reserved.
- Source: https://github.com/PaddlePaddle/PaddleOCR
- Version: PP-OCRv5 (det.onnx, rec.onnx, cls.onnx, ppocrv5_dict.txt)
- Note: Text detection, recognition and text-direction classification models and the recognition dictionary, trained and released by the PaddlePaddle authors in PaddleOCR. The ONNX files bundled in ocr/src/main/assets/ocr/ were downloaded from https://models.ente.io/PP-OCRv5/ (the ONNX conversions ente/mobile_ocr fetches at run time; its README states they are derived from PaddleOCR and follow its licence) and are byte-identical to them (sizes and SHA-256 digests checked against the values in ente/mobile_ocr, see ModelManager.kt). They are not modified by this app.

### OnnxOCR

- License: Apache-2.0
- Copyright: Copyright jingsongliujing and OnnxOCR contributors
- Source: https://github.com/jingsongliujing/OnnxOCR
- Note: The reference ONNX Runtime pipeline for the PaddleOCR models that ente/mobile_ocr (and so this port) mirrors. No OnnxOCR code is included; listed for attribution.

### ONNX Runtime

- License: MIT
- Copyright: Copyright (c) Microsoft Corporation
- Source: https://github.com/microsoft/onnxruntime
- Artifacts: `com.microsoft.onnxruntime:onnxruntime-android` 1.31.0
- Note: The Android package bundles third-party components under their own licences, listed in ONNX Runtime's ThirdPartyNotices: https://github.com/microsoft/onnxruntime/blob/v1.31.0/ThirdPartyNotices.txt

## Kotlin

### Kotlin Standard Library

- License: Apache-2.0
- Copyright: Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors
- Source: https://github.com/JetBrains/kotlin
- Artifacts: `org.jetbrains.kotlin:kotlin-stdlib-common` 2.4.21, `org.jetbrains.kotlin:kotlin-stdlib` 2.4.21

### kotlinx.coroutines

- License: Apache-2.0
- Copyright: Copyright 2016-2025 JetBrains s.r.o. and contributors
- Source: https://github.com/Kotlin/kotlinx.coroutines
- Artifacts: `org.jetbrains.kotlinx:kotlinx-coroutines-android` 1.11.0, `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` 1.11.0, `org.jetbrains.kotlinx:kotlinx-coroutines-core` 1.11.0, `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` 1.11.0

### kotlinx.serialization

- License: Apache-2.0
- Copyright: Copyright 2017-2025 JetBrains s.r.o. and contributors
- Source: https://github.com/Kotlin/kotlinx.serialization
- Artifacts: `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` 1.11.0, `org.jetbrains.kotlinx:kotlinx-serialization-core` 1.11.0, `org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm` 1.11.0, `org.jetbrains.kotlinx:kotlinx-serialization-json-io` 1.11.0, `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` 1.11.0, `org.jetbrains.kotlinx:kotlinx-serialization-json` 1.11.0

### kotlinx-io

- License: Apache-2.0
- Copyright: Copyright 2017-2025 JetBrains s.r.o. and contributors
- Source: https://github.com/Kotlin/kotlinx-io
- Artifacts: `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm` 0.9.1, `org.jetbrains.kotlinx:kotlinx-io-bytestring` 0.9.1, `org.jetbrains.kotlinx:kotlinx-io-core-jvm` 0.9.1, `org.jetbrains.kotlinx:kotlinx-io-core` 0.9.1

### kotlinx-datetime

- License: Apache-2.0
- Copyright: Copyright 2019-2025 JetBrains s.r.o. and contributors
- Source: https://github.com/Kotlin/kotlinx-datetime
- Artifacts: `org.jetbrains.kotlinx:kotlinx-datetime-jvm` 0.8.0-0.6.x-compat, `org.jetbrains.kotlinx:kotlinx-datetime` 0.8.0-0.6.x-compat

### JetBrains Java Annotations

- License: Apache-2.0
- Copyright: Copyright 2000-2021 JetBrains s.r.o.
- Source: https://github.com/JetBrains/java-annotations
- Artifacts: `org.jetbrains:annotations` 23.0.0

## AndroidX and Jetpack Compose

### AndroidX (Jetpack) libraries

- License: Apache-2.0
- Copyright: Copyright The Android Open Source Project
- Source: https://android.googlesource.com/platform/frameworks/support
- Artifacts: `androidx.activity:activity-compose` 1.13.0, `androidx.activity:activity-ktx` 1.13.0, `androidx.activity:activity` 1.13.0, `androidx.annotation:annotation-experimental` 1.5.0, `androidx.annotation:annotation-jvm` 1.10.0, `androidx.annotation:annotation` 1.10.0, `androidx.appcompat:appcompat-resources` 1.7.1, `androidx.arch.core:core-common` 2.2.0, `androidx.arch.core:core-runtime` 2.2.0, `androidx.autofill:autofill` 1.0.0, `androidx.collection:collection-jvm` 1.5.0, `androidx.collection:collection-ktx` 1.5.0, `androidx.collection:collection` 1.5.0, `androidx.concurrent:concurrent-futures-ktx` 1.1.0, `androidx.concurrent:concurrent-futures` 1.1.0, `androidx.core:core-ktx` 1.19.1, `androidx.core:core-viewtree` 1.0.0, `androidx.core:core` 1.19.1, `androidx.customview:customview-poolingcontainer` 1.0.0, `androidx.customview:customview` 1.0.0, `androidx.datastore:datastore-android` 1.2.1, `androidx.datastore:datastore-core-android` 1.2.1, `androidx.datastore:datastore-core-okio-jvm` 1.2.1, `androidx.datastore:datastore-core-okio` 1.2.1, `androidx.datastore:datastore-core` 1.2.1, `androidx.datastore:datastore-preferences-android` 1.2.1, `androidx.datastore:datastore-preferences-core-android` 1.2.1, `androidx.datastore:datastore-preferences-core` 1.2.1, `androidx.datastore:datastore-preferences-proto` 1.2.1, `androidx.datastore:datastore-preferences` 1.2.1, `androidx.datastore:datastore` 1.2.1, `androidx.emoji2:emoji2` 1.4.0, `androidx.exifinterface:exifinterface` 1.4.2, `androidx.fragment:fragment` 1.5.1, `androidx.graphics:graphics-path` 1.0.1, `androidx.hilt:hilt-lifecycle-viewmodel-compose` 1.4.0, `androidx.hilt:hilt-lifecycle-viewmodel` 1.4.0, `androidx.hilt:hilt-navigation-compose` 1.4.0, `androidx.interpolator:interpolator` 1.0.0, `androidx.lifecycle:lifecycle-common-java8` 2.11.0, `androidx.lifecycle:lifecycle-common-jvm` 2.11.0, `androidx.lifecycle:lifecycle-common` 2.11.0, `androidx.lifecycle:lifecycle-livedata-core-ktx` 2.11.0, `androidx.lifecycle:lifecycle-livedata-core` 2.11.0, `androidx.lifecycle:lifecycle-livedata` 2.11.0, `androidx.lifecycle:lifecycle-process` 2.11.0, `androidx.lifecycle:lifecycle-runtime-android` 2.11.0, `androidx.lifecycle:lifecycle-runtime-compose-android` 2.11.0, `androidx.lifecycle:lifecycle-runtime-compose` 2.11.0, `androidx.lifecycle:lifecycle-runtime-ktx-android` 2.11.0, `androidx.lifecycle:lifecycle-runtime-ktx` 2.11.0, `androidx.lifecycle:lifecycle-runtime` 2.11.0, `androidx.lifecycle:lifecycle-service` 2.11.0, `androidx.lifecycle:lifecycle-viewmodel-android` 2.11.0, `androidx.lifecycle:lifecycle-viewmodel-compose-android` 2.11.0, `androidx.lifecycle:lifecycle-viewmodel-compose` 2.11.0, `androidx.lifecycle:lifecycle-viewmodel-ktx` 2.11.0, `androidx.lifecycle:lifecycle-viewmodel-savedstate-android` 2.11.0, `androidx.lifecycle:lifecycle-viewmodel-savedstate` 2.11.0, `androidx.lifecycle:lifecycle-viewmodel` 2.11.0, `androidx.loader:loader` 1.0.0, `androidx.navigation:navigation-common-android` 2.10.2, `androidx.navigation:navigation-common` 2.10.2, `androidx.navigation:navigation-compose-android` 2.10.2, `androidx.navigation:navigation-compose` 2.10.2, `androidx.navigation:navigation-runtime-android` 2.10.2, `androidx.navigation:navigation-runtime` 2.10.2, `androidx.navigationevent:navigationevent-android` 1.1.2, `androidx.navigationevent:navigationevent-compose-android` 1.1.2, `androidx.navigationevent:navigationevent-compose` 1.1.2, `androidx.navigationevent:navigationevent` 1.1.2, `androidx.profileinstaller:profileinstaller` 1.4.1, `androidx.room:room-common-jvm` 2.8.5, `androidx.room:room-common` 2.8.5, `androidx.room:room-ktx` 2.8.5, `androidx.room:room-runtime-android` 2.8.5, `androidx.room:room-runtime` 2.8.5, `androidx.savedstate:savedstate-android` 1.5.0, `androidx.savedstate:savedstate-compose-android` 1.5.0, `androidx.savedstate:savedstate-compose` 1.5.0, `androidx.savedstate:savedstate-ktx` 1.5.0, `androidx.savedstate:savedstate` 1.5.0, `androidx.sqlite:sqlite-android` 2.6.2, `androidx.sqlite:sqlite-framework-android` 2.6.2, `androidx.sqlite:sqlite-framework` 2.6.2, `androidx.sqlite:sqlite` 2.6.2, `androidx.startup:startup-runtime` 1.2.0, `androidx.tracing:tracing-ktx` 1.2.0, `androidx.tracing:tracing` 1.2.0, `androidx.vectordrawable:vectordrawable-animated` 1.1.0, `androidx.vectordrawable:vectordrawable` 1.1.0, `androidx.versionedparcelable:versionedparcelable` 1.1.1, `androidx.viewpager:viewpager` 1.0.0, `androidx.window:window-core-android` 1.5.0, `androidx.window:window-core` 1.5.0, `androidx.window:window` 1.5.0, `androidx.work:work-runtime-ktx` 2.12.0, `androidx.work:work-runtime` 2.12.0

### Jetpack Compose

- License: Apache-2.0
- Copyright: Copyright The Android Open Source Project
- Source: https://android.googlesource.com/platform/frameworks/support
- Artifacts: `androidx.compose.animation:animation-android` 1.12.1, `androidx.compose.animation:animation-core-android` 1.12.1, `androidx.compose.animation:animation-core` 1.12.1, `androidx.compose.animation:animation` 1.12.1, `androidx.compose.foundation:foundation-android` 1.12.1, `androidx.compose.foundation:foundation-layout-android` 1.12.1, `androidx.compose.foundation:foundation-layout` 1.12.1, `androidx.compose.foundation:foundation` 1.12.1, `androidx.compose.material:material-icons-core-android` 1.7.8, `androidx.compose.material:material-icons-core` 1.7.8, `androidx.compose.material:material-icons-extended-android` 1.7.8, `androidx.compose.material:material-icons-extended` 1.7.8, `androidx.compose.material:material-ripple-android` 1.12.1, `androidx.compose.material:material-ripple` 1.12.1, `androidx.compose.material3:material3-android` 1.4.0, `androidx.compose.material3:material3` 1.4.0, `androidx.compose.runtime:runtime-android` 1.12.1, `androidx.compose.runtime:runtime-annotation-android` 1.12.1, `androidx.compose.runtime:runtime-annotation` 1.12.1, `androidx.compose.runtime:runtime-retain-android` 1.12.1, `androidx.compose.runtime:runtime-retain` 1.12.1, `androidx.compose.runtime:runtime-saveable-android` 1.12.1, `androidx.compose.runtime:runtime-saveable` 1.12.1, `androidx.compose.runtime:runtime` 1.12.1, `androidx.compose.ui:ui-android` 1.12.1, `androidx.compose.ui:ui-geometry-android` 1.12.1, `androidx.compose.ui:ui-geometry` 1.12.1, `androidx.compose.ui:ui-graphics-android` 1.12.1, `androidx.compose.ui:ui-graphics` 1.12.1, `androidx.compose.ui:ui-text-android` 1.12.1, `androidx.compose.ui:ui-text` 1.12.1, `androidx.compose.ui:ui-tooling-preview-android` 1.12.1, `androidx.compose.ui:ui-tooling-preview` 1.12.1, `androidx.compose.ui:ui-unit-android` 1.12.1, `androidx.compose.ui:ui-unit` 1.12.1, `androidx.compose.ui:ui-util-android` 1.12.1, `androidx.compose.ui:ui-util` 1.12.1, `androidx.compose.ui:ui` 1.12.1

### Compose Multiplatform

- License: Apache-2.0
- Copyright: Copyright 2020-2025 JetBrains s.r.o. and respective authors and developers; Copyright The Android Open Source Project
- Source: https://github.com/JetBrains/compose-multiplatform
- Artifacts: `org.jetbrains.androidx.lifecycle:lifecycle-common` 2.9.6, `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` 2.9.6, `org.jetbrains.androidx.lifecycle:lifecycle-runtime` 2.9.6, `org.jetbrains.androidx.savedstate:savedstate-compose` 1.3.6, `org.jetbrains.androidx.savedstate:savedstate` 1.3.6, `org.jetbrains.compose.animation:animation-android` 1.12.0, `org.jetbrains.compose.animation:animation-core-android` 1.12.0, `org.jetbrains.compose.animation:animation-core` 1.12.0, `org.jetbrains.compose.animation:animation` 1.12.0, `org.jetbrains.compose.foundation:foundation-android` 1.12.0, `org.jetbrains.compose.foundation:foundation-layout-android` 1.12.0, `org.jetbrains.compose.foundation:foundation-layout` 1.12.0, `org.jetbrains.compose.foundation:foundation` 1.12.0, `org.jetbrains.compose.runtime:runtime-android` 1.12.0, `org.jetbrains.compose.runtime:runtime-saveable-android` 1.12.0, `org.jetbrains.compose.runtime:runtime-saveable` 1.12.0, `org.jetbrains.compose.runtime:runtime` 1.12.0, `org.jetbrains.compose.ui:ui-android` 1.12.0, `org.jetbrains.compose.ui:ui-geometry-android` 1.12.0, `org.jetbrains.compose.ui:ui-geometry` 1.12.0, `org.jetbrains.compose.ui:ui-graphics-android` 1.12.0, `org.jetbrains.compose.ui:ui-graphics` 1.12.0, `org.jetbrains.compose.ui:ui-text-android` 1.12.0, `org.jetbrains.compose.ui:ui-text` 1.12.0, `org.jetbrains.compose.ui:ui-unit-android` 1.12.0, `org.jetbrains.compose.ui:ui-unit` 1.12.0, `org.jetbrains.compose.ui:ui-util-android` 1.12.0, `org.jetbrains.compose.ui:ui-util` 1.12.0, `org.jetbrains.compose.ui:ui` 1.12.0
- Note: Pulled in by Coil 3.

## Dependency injection

### Dagger and Hilt

- License: Apache-2.0
- Copyright: Copyright 2012 The Dagger Authors
- Source: https://github.com/google/dagger
- Artifacts: `com.google.dagger:dagger-lint-aar` 2.60.1, `com.google.dagger:dagger` 2.60.1, `com.google.dagger:hilt-android` 2.60.1, `com.google.dagger:hilt-core` 2.60.1

### JSR-330 javax.inject

- License: Apache-2.0
- Copyright: Copyright (C) 2009 The JSR-330 Expert Group
- Source: https://github.com/javax-inject/javax-inject
- Artifacts: `javax.inject:javax.inject` 1

### Jakarta Dependency Injection

- License: Apache-2.0
- Copyright: Copyright Contributors to the Eclipse Foundation; Copyright (C) 2009 The JSR-330 Expert Group
- Source: https://github.com/eclipse-ee4j/injection-api
- Artifacts: `jakarta.inject:jakarta.inject-api` 2.0.1
- Note: NOTICE: This content is produced and maintained by the Eclipse Jakarta Dependency Injection project (https://projects.eclipse.org/projects/cdi.batch). Jakarta Dependency Injection is a trademark of the Eclipse Foundation. All content is the property of the respective authors or their employers. Third-party content: none.

## Networking

### Ktor

- License: Apache-2.0
- Copyright: Copyright 2014-2025 JetBrains s.r.o. and contributors
- Source: https://github.com/ktorio/ktor
- Artifacts: `io.ktor:ktor-client-content-negotiation-jvm` 3.6.0, `io.ktor:ktor-client-content-negotiation` 3.6.0, `io.ktor:ktor-client-core-jvm` 3.6.0, `io.ktor:ktor-client-core` 3.6.0, `io.ktor:ktor-client-okhttp-jvm` 3.6.0, `io.ktor:ktor-client-okhttp` 3.6.0, `io.ktor:ktor-events-jvm` 3.6.0, `io.ktor:ktor-events` 3.6.0, `io.ktor:ktor-http-cio-jvm` 3.6.0, `io.ktor:ktor-http-cio` 3.6.0, `io.ktor:ktor-http-jvm` 3.6.0, `io.ktor:ktor-http` 3.6.0, `io.ktor:ktor-io-jvm` 3.6.0, `io.ktor:ktor-io` 3.6.0, `io.ktor:ktor-network-jvm` 3.6.0, `io.ktor:ktor-network` 3.6.0, `io.ktor:ktor-openapi-schema-jvm` 3.6.0, `io.ktor:ktor-openapi-schema` 3.6.0, `io.ktor:ktor-serialization-jvm` 3.6.0, `io.ktor:ktor-serialization-kotlinx-json-jvm` 3.6.0, `io.ktor:ktor-serialization-kotlinx-json` 3.6.0, `io.ktor:ktor-serialization-kotlinx-jvm` 3.6.0, `io.ktor:ktor-serialization-kotlinx` 3.6.0, `io.ktor:ktor-serialization` 3.6.0, `io.ktor:ktor-sse-jvm` 3.6.0, `io.ktor:ktor-sse` 3.6.0, `io.ktor:ktor-utils-jvm` 3.6.0, `io.ktor:ktor-utils` 3.6.0, `io.ktor:ktor-websocket-serialization-jvm` 3.6.0, `io.ktor:ktor-websocket-serialization` 3.6.0, `io.ktor:ktor-websockets-jvm` 3.6.0, `io.ktor:ktor-websockets` 3.6.0

### OkHttp

- License: Apache-2.0
- Copyright: Copyright 2019 Square, Inc.
- Source: https://github.com/square/okhttp
- Artifacts: `com.squareup.okhttp3:okhttp-android` 5.5.0, `com.squareup.okhttp3:okhttp` 5.5.0

### Okio

- License: Apache-2.0
- Copyright: Copyright 2013 Square, Inc.
- Source: https://github.com/square/okio
- Artifacts: `com.squareup.okio:okio-jvm` 3.18.2, `com.squareup.okio:okio` 3.18.2

### kaml

- License: Apache-2.0
- Copyright: Copyright 2018-2023 Charles Korn
- Source: https://github.com/charleskorn/kaml
- Artifacts: `com.charleskorn.kaml:kaml-jvm` 0.79.0, `com.charleskorn.kaml:kaml` 0.79.0
- Note: Pulled in by Ktor.

### snakeyaml-engine-kmp

- License: Apache-2.0
- Copyright: Copyright (c) 2018 SnakeYAML (Andrey Somov); Kotlin Multiplatform port Copyright Piotr Krzeminski and contributors
- Source: https://github.com/krzema12/snakeyaml-engine-kmp
- Artifacts: `it.krzeminski:snakeyaml-engine-kmp-jvm` 3.1.1, `it.krzeminski:snakeyaml-engine-kmp` 3.1.1
- Note: Pulled in by Ktor (through kaml).

### UrlEncoder

- License: Apache-2.0
- Copyright: Copyright 2001-2023 Geert Bevin, Erik C. Thauvin
- Source: https://github.com/ethauvin/urlencoder
- Artifacts: `net.thauvin.erik.urlencoder:urlencoder-lib-jvm` 1.6.0, `net.thauvin.erik.urlencoder:urlencoder-lib` 1.6.0
- Note: Pulled in by Ktor (through snakeyaml-engine-kmp).

### SLF4J API

- License: MIT
- Copyright: Copyright (c) 2004-2023 QOS.ch
- Source: https://www.slf4j.org
- Artifacts: `org.slf4j:slf4j-api` 2.0.19
- Note: Pulled in by Ktor.

## Images

### Coil

- License: Apache-2.0
- Copyright: Copyright 2025 Coil Contributors
- Source: https://github.com/coil-kt/coil
- Artifacts: `io.coil-kt.coil3:coil-android` 3.6.3, `io.coil-kt.coil3:coil-compose-android` 3.6.3, `io.coil-kt.coil3:coil-compose-core-android` 3.6.3, `io.coil-kt.coil3:coil-compose-core` 3.6.3, `io.coil-kt.coil3:coil-compose` 3.6.3, `io.coil-kt.coil3:coil-core-android` 3.6.3, `io.coil-kt.coil3:coil-core` 3.6.3, `io.coil-kt.coil3:coil` 3.6.3

### Accompanist Drawable Painter

- License: Apache-2.0
- Copyright: Copyright 2020 The Android Open Source Project
- Source: https://github.com/google/accompanist
- Artifacts: `com.google.accompanist:accompanist-drawablepainter` 0.37.3
- Note: Pulled in by Coil.

## Push notifications

### UnifiedPush Android connector

- License: Apache-2.0
- Copyright: Copyright UnifiedPush contributors
- Source: https://codeberg.org/UnifiedPush/android-connector
- Artifacts: `org.unifiedpush.android:connector` 3.3.5

### Tink

- License: Apache-2.0
- Copyright: Copyright Google LLC
- Source: https://github.com/tink-crypto/tink-java
- Artifacts: `com.google.crypto.tink:tink` 1.23.0
- Note: Pulled in by the UnifiedPush connector (Web Push message encryption).

### Gson

- License: Apache-2.0
- Copyright: Copyright 2008 Google Inc.
- Source: https://github.com/google/gson
- Artifacts: `com.google.code.gson:gson` 2.13.2
- Note: Pulled in by Tink.

### Protocol Buffers

- License: BSD-3-Clause
- Copyright: Copyright 2008 Google Inc. All rights reserved.
- Source: https://github.com/protocolbuffers/protobuf
- Artifacts: `androidx.datastore:datastore-preferences-external-protobuf` 1.2.1, `com.google.protobuf:protobuf-java` 4.33.6
- Note: protobuf-java is pulled in by Tink; AndroidX DataStore ships its own repackaged copy (datastore-preferences-external-protobuf).

## Annotations

### Error Prone annotations

- License: Apache-2.0
- Copyright: Copyright 2015 The Error Prone Authors
- Source: https://github.com/google/error-prone
- Artifacts: `com.google.errorprone:error_prone_annotations` 2.41.0

### Guava ListenableFuture

- License: Apache-2.0
- Copyright: Copyright (C) 2007 The Guava Authors
- Source: https://github.com/google/guava
- Artifacts: `com.google.guava:listenablefuture` 1.0

### JSR-305 annotations

- License: Apache-2.0
- Copyright: Copyright (c) 2007-2009 FindBugs project and JSR-305 expert group
- Source: https://github.com/findbugsproject/findbugs
- Artifacts: `com.google.code.findbugs:jsr305` 3.0.2

### JSpecify

- License: Apache-2.0
- Copyright: Copyright 2022 The JSpecify Authors
- Source: https://github.com/jspecify/jspecify
- Artifacts: `org.jspecify:jspecify` 1.0.0

## License texts

### Apache-2.0

Applies to every component marked Apache-2.0 above.

```text

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### MIT

Applies to every component marked MIT above, each with the copyright notice given in its entry
(for example "Copyright (c) 2025 Laurens Priem" for ente/mobile_ocr).

```text
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### BSD-3-Clause

Applies to Protocol Buffers.

```text
Copyright 2008 Google Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Code generated by the Protocol Buffer compiler is owned by the owner
of the input file used when generating it.  This code is not
standalone and requires a support library to be linked with it.  This
support library is itself covered by the above license.
```
