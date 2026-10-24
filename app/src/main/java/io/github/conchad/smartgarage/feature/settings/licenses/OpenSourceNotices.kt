package io.github.conchad.smartgarage.feature.settings.licenses

/** One component from OPEN_SOURCE_NOTICES.md: a "### " entry under a group heading (D-A65). */
data class NoticeComponent(
    val group: String,
    val name: String,
    val license: String,
    val copyright: String,
    val source: String?,
    val version: String?,
    /** Maven coordinates with versions, comma-separated, without the Markdown backticks. */
    val artifacts: String?,
    val note: String?,
)

data class OpenSourceNotices(
    val components: List<NoticeComponent>,
    /** Licence id (the "### " heading under "## License texts") → full text. */
    val licenseTexts: Map<String, String>,
) {
    /** Components by group, in file order. */
    val groups: List<Pair<String, List<NoticeComponent>>>
        get() = components.groupBy { it.group }.toList()

    fun licenseText(component: NoticeComponent): String? = licenseTexts[component.license]
}

/**
 * Reads the repo-root OPEN_SOURCE_NOTICES.md, which the build copies into the APK's assets as [ASSET] (D-A65), so
 * the in-app list and the file are the same text. The format is described in the file's header comment.
 */
object OpenSourceNoticesParser {
    const val ASSET = "open_source_notices.md"
    const val LICENSE_TEXTS = "License texts"

    fun parse(markdown: String): OpenSourceNotices {
        val components = mutableListOf<NoticeComponent>()
        val texts = linkedMapOf<String, String>()
        var group: String? = null
        var inLicenseTexts = false
        var inComment = false
        var heading: String? = null
        val fields = linkedMapOf<String, String>()
        var fence: StringBuilder? = null

        fun flush() {
            val name = heading ?: return
            if (!inLicenseTexts && group != null) {
                components += NoticeComponent(
                    group = group!!,
                    name = name,
                    license = fields["License"].orEmpty(),
                    copyright = fields["Copyright"].orEmpty(),
                    source = fields["Source"],
                    version = fields["Version"],
                    artifacts = fields["Artifacts"]?.replace("`", ""),
                    note = fields["Note"],
                )
            }
            heading = null
            fields.clear()
        }

        for (raw in markdown.lineSequence()) {
            val line = raw.trimEnd()
            fence?.let { block ->
                if (line == "```") {
                    heading?.let { if (it !in texts) texts[it] = block.toString().trimEnd('\n') }
                    fence = null
                } else {
                    block.append(raw).append('\n')
                }
                continue
            }
            if (inComment) {
                if ("-->" in line) inComment = false
                continue
            }
            when {
                line.startsWith("<!--") -> inComment = "-->" !in line
                line.startsWith("### ") -> {
                    flush()
                    heading = line.removePrefix("### ").trim()
                }
                line.startsWith("## ") -> {
                    flush()
                    val title = line.removePrefix("## ").trim()
                    inLicenseTexts = title == LICENSE_TEXTS
                    group = title.takeUnless { inLicenseTexts }
                }
                line.startsWith("```") && inLicenseTexts && heading != null -> fence = StringBuilder()
                line.startsWith("- ") && !inLicenseTexts && heading != null -> {
                    val colon = line.indexOf(": ")
                    if (colon > 2) fields[line.substring(2, colon).trim()] = line.substring(colon + 2).trim()
                }
            }
        }
        flush()
        return OpenSourceNotices(components, texts)
    }
}
