package io.github.conchad.smartgarage.feature.settings

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.feature.settings.licenses.OpenSourceNoticesParser
import java.io.File
import org.junit.Test

/**
 * D-A65: the repo-root OPEN_SOURCE_NOTICES.md is what Settings → Open-source licenses shows (the build copies it into
 * the assets unchanged), so these checks run the app's own parser over the real file.
 */
class OpenSourceNoticesTest {

    private fun repoFile(name: String): File =
        listOf(File("../$name"), File(name)).first { it.isFile }

    private val notices by lazy { OpenSourceNoticesParser.parse(repoFile("OPEN_SOURCE_NOTICES.md").readText()) }

    @Test
    fun `every component has a licence, copyright and source, and its licence text is in the file`() {
        assertThat(notices.components.size).isAtLeast(30)
        notices.components.forEach { c ->
            assertThat(c.license).isNotEmpty()
            assertThat(c.copyright).isNotEmpty()
            assertThat(c.source).startsWith("https://")
            assertThat(notices.licenseText(c)).isNotNull()
        }
        assertThat(notices.licenseTexts.keys).containsExactly("Apache-2.0", "MIT", "BSD-3-Clause")
        assertThat(notices.licenseTexts.getValue("Apache-2.0")).contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION")
        assertThat(notices.licenseTexts.getValue("Apache-2.0")).contains("END OF TERMS AND CONDITIONS")
        assertThat(notices.licenseTexts.getValue("MIT")).contains("Permission is hereby granted, free of charge")
        assertThat(notices.licenseTexts.getValue("BSD-3-Clause")).contains("Copyright 2008 Google Inc.")
    }

    @Test
    fun `the OCR port, the models, ONNX Runtime and upstream Smart Garage are credited`() {
        val byName = notices.components.associateBy { it.name }
        byName.getValue("ente/mobile_ocr").let {
            assertThat(it.license).isEqualTo("MIT")
            assertThat(it.copyright).isEqualTo("Copyright (c) 2025 Laurens Priem")
            assertThat(it.version).contains("0c68f88f20607cfcb10286422860350ccb024acd")
        }
        byName.getValue("PaddleOCR PP-OCRv5 models").let {
            assertThat(it.license).isEqualTo("Apache-2.0")
            assertThat(it.copyright).contains("PaddlePaddle Authors")
            assertThat(it.note).contains("https://models.ente.io/PP-OCRv5/")
        }
        val onnxVersion = Regex("""(?m)^onnxruntime\s*=\s*"([^"]+)"""")
            .find(repoFile("gradle/libs.versions.toml").readText())!!.groupValues[1]
        byName.getValue("ONNX Runtime").let {
            assertThat(it.license).isEqualTo("MIT")
            assertThat(it.copyright).contains("Microsoft")
            assertThat(it.artifacts).isEqualTo("com.microsoft.onnxruntime:onnxruntime-android $onnxVersion")
            assertThat(it.note).contains("ThirdPartyNotices")
        }
        byName.getValue("Smart Garage (upstream web app)").let {
            assertThat(it.license).isEqualTo("MIT")
            assertThat(it.copyright).contains("flizzy27")
        }
    }

    @Test
    fun `every library the app ships from the version catalog is listed`() {
        val buildOnly = setOf(
            "junit", "truth", "kotlinx-coroutines-test", "ktor-client-mock", "androidx-test-runner",
            "androidx-test-ext-junit", "androidx-room-compiler", "hilt-compiler", "compose-bom", "compose-ui-tooling",
        )
        val catalog = repoFile("gradle/libs.versions.toml").readText()
            .substringAfter("[libraries]").substringBefore("[plugins]")
        val modules = Regex("""(?m)^([\w-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"""").findAll(catalog)
            .filter { it.groupValues[1] !in buildOnly }
            .map { it.groupValues[2] }
            .toList()
        assertThat(modules).isNotEmpty()
        val listed = notices.components.mapNotNull { it.artifacts }.joinToString(", ")
        modules.forEach { assertThat(listed).contains("$it ") }
    }

    @Test
    fun `parser keeps groups and ignores comments and prose`() {
        val parsed = OpenSourceNoticesParser.parse(
            """
            # Title
            <!--
            ### Not a component
            -->
            ## Group A
            ### One
            - License: MIT
            - Copyright: Copyright (c) 2025 Someone
            - Source: https://example.org/one
            - Artifacts: `a:b` 1.0, `a:c` 1.0
            ## License texts
            ### MIT
            Some prose.
            ```text
            MIT License

            Body
            ```
            """.trimIndent(),
        )
        assertThat(parsed.components).hasSize(1)
        val one = parsed.components.single()
        assertThat(one.group).isEqualTo("Group A")
        assertThat(one.artifacts).isEqualTo("a:b 1.0, a:c 1.0")
        assertThat(one.version).isNull()
        assertThat(parsed.licenseText(one)).isEqualTo("MIT License\n\nBody")
    }
}
