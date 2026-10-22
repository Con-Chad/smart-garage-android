package io.github.conchad.smartgarage.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The hourly worker's skip rule (D-A13): a recent sync with nothing queued is left alone; everything else syncs. */
class SyncFreshnessTest {
    private val now = Instant.parse("2026-10-22T20:00:00Z")
    private val maxAge = Duration.ofMinutes(15)

    @Test
    fun `recent sync with empty outbox is skipped`() {
        assertThat(SyncEngine.needsSync(now.minusSeconds(5 * 60).toString(), queued = false, now, maxAge)).isFalse()
    }

    @Test
    fun `stale sync runs`() {
        assertThat(SyncEngine.needsSync(now.minusSeconds(15 * 60).toString(), queued = false, now, maxAge)).isTrue()
        assertThat(SyncEngine.needsSync(now.minusSeconds(3 * 3600).toString(), queued = false, now, maxAge)).isTrue()
    }

    @Test
    fun `queued writes always run`() {
        assertThat(SyncEngine.needsSync(now.minusSeconds(60).toString(), queued = true, now, maxAge)).isTrue()
    }

    @Test
    fun `never synced, unparseable or future timestamps run`() {
        assertThat(SyncEngine.needsSync(null, queued = false, now, maxAge)).isTrue()
        assertThat(SyncEngine.needsSync("not a time", queued = false, now, maxAge)).isTrue()
        assertThat(SyncEngine.needsSync(now.plusSeconds(600).toString(), queued = false, now, maxAge)).isTrue()
    }
}
