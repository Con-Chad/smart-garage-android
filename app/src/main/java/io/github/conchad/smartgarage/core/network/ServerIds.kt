package io.github.conchad.smartgarage.core.network

/**
 * What a server-supplied id may look like before the app uses it as a file name or a URL path segment (D-A28).
 *
 * Server ids are cuids and the app's own temp ids are `tmp-<uuid>`; both are plain `[A-Za-z0-9_-]`. Anything else
 * (`../x`, a slash, unicode, an empty or overlong string) is rejected at the trust boundary ([ServerIds.isValid] in
 * `ServerRows.toCachedRow`) and again in the file helpers ([ServerIds.require] in `PhotoStore`, `OutboxFiles`), so a
 * hostile server for one profile can never write outside that profile's own directories.
 */
object ServerIds {
    private val pattern = Regex("[A-Za-z0-9_-]{1,64}")

    fun isValid(id: String): Boolean = pattern.matches(id)

    /** [id] unchanged when it is valid; otherwise throws [InvalidServerIdException]. */
    fun require(id: String): String = if (isValid(id)) id else throw InvalidServerIdException(id.length)
}

/** A server-supplied id that can't be used as a file name or path segment. Carries only the length, never the id. */
class InvalidServerIdException(length: Int) : IllegalArgumentException("server sent an invalid id ($length chars)")
