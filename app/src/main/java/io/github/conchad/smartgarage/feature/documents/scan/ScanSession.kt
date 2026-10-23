package io.github.conchad.smartgarage.feature.documents.scan

/**
 * One photographed page (D-A59): the upright source photo in `cache/scans/`, its four corners (normalized, any
 * order) and how many quarter turns clockwise the flattened page is rotated.
 */
data class ScanPage(val path: String, val corners: List<Pt>, val turns: Int = 0)

/**
 * The scanner's state, kept by the document form's view model and written to its `SavedStateHandle` through
 * [encode], so a scan of several pages survives the process being killed behind the camera app.
 */
data class ScanSession(
    val pages: List<ScanPage> = emptyList(),
    val filter: ScanFilter = ScanFilter.Greyscale,
    val name: String = "",
    /** The page whose corners are being adjusted; null shows the page list. */
    val cropIndex: Int? = null,
    /** The page the next photo replaces ("Retake"); null adds a page. */
    val retakeIndex: Int? = null,
    /** A photo is being prepared. */
    val busy: Boolean = false,
    /** The file is being built. */
    val exporting: Boolean = false,
    /** The export failed or came out above the server's limit. */
    val failed: Boolean = false,
) {
    val canAddPage: Boolean get() = pages.size < ScanGeometry.MAX_PAGES
    val canFinish: Boolean get() = pages.isNotEmpty() && name.isNotBlank() && !busy && !exporting

    /** [page] added at the end, or in place of the page being retaken; its corners open next. */
    fun withPhoto(page: ScanPage): ScanSession {
        val at = retakeIndex?.takeIf { it in pages.indices }
        val list = if (at != null) pages.toMutableList().apply { set(at, page) } else pages + page
        return copy(pages = list, cropIndex = at ?: list.lastIndex, retakeIndex = null, busy = false, failed = false)
    }

    fun withCorners(index: Int, corners: List<Pt>): ScanSession =
        if (index !in pages.indices) this else copy(pages = pages.toMutableList().apply { set(index, get(index).copy(corners = corners.map(ScanGeometry::clamp))) })

    fun rotated(index: Int): ScanSession =
        if (index !in pages.indices) this else copy(pages = pages.toMutableList().apply { set(index, get(index).copy(turns = (get(index).turns + 1) % 4)) })

    /** The page at [index] moved by [delta] places (clamped); the order is the PDF's. */
    fun moved(index: Int, delta: Int): ScanSession {
        if (index !in pages.indices) return this
        val to = (index + delta).coerceIn(0, pages.lastIndex)
        if (to == index) return this
        val list = pages.toMutableList()
        list.add(to, list.removeAt(index))
        return copy(pages = list)
    }

    fun removed(index: Int): ScanSession =
        if (index !in pages.indices) this else copy(pages = pages.filterIndexed { i, _ -> i != index }, cropIndex = null)

    /** One line per field; page lines are `path|x,y,x,y,x,y,x,y|turns` (a path never holds `|` or a newline). */
    fun encode(): String = buildString {
        appendLine("v1")
        appendLine(filter.name)
        appendLine(cropIndex?.toString().orEmpty())
        appendLine(retakeIndex?.toString().orEmpty())
        appendLine(name.replace('\n', ' '))
        pages.forEach { p ->
            appendLine("${p.path}|${p.corners.joinToString(",") { "${it.x},${it.y}" }}|${p.turns}")
        }
    }

    companion object {
        /** The session [encode] wrote; null when [text] is missing or from another version. Not busy, not failed. */
        fun decode(text: String?): ScanSession? {
            val lines = text?.lines() ?: return null
            if (lines.size < 5 || lines[0] != "v1") return null
            val filter = ScanFilter.entries.firstOrNull { it.name == lines[1] } ?: return null
            val pages = lines.drop(5).filter { it.isNotEmpty() }.map { line ->
                val parts = line.split('|')
                if (parts.size != 3) return null
                val numbers = parts[1].split(',').map { it.toFloatOrNull() ?: return null }
                if (numbers.size != 8) return null
                ScanPage(parts[0], List(4) { Pt(numbers[2 * it], numbers[2 * it + 1]) }, parts[2].toIntOrNull() ?: 0)
            }
            return ScanSession(
                pages = pages,
                filter = filter,
                name = lines[4],
                cropIndex = lines[2].toIntOrNull()?.takeIf { it in pages.indices },
                retakeIndex = lines[3].toIntOrNull()?.takeIf { it in pages.indices },
            )
        }
    }
}
