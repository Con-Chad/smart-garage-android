package io.github.conchad.smartgarage.core.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The app-start re-registration rule (PUSH.md §2, D-A29): a registration older than a day is sent again. */
class PushStalenessTest {
    private val now = Instant.parse("2026-10-22T20:00:00Z")

    @Test
    fun `a registration from today is left alone`() {
        assertThat(PushRegistrar.needsReregister(now.minus(Duration.ofHours(23)).toString(), now)).isFalse()
        assertThat(PushRegistrar.needsReregister(now.minusSeconds(5).toString(), now)).isFalse()
    }

    @Test
    fun `a day or older is re-registered`() {
        assertThat(PushRegistrar.needsReregister(now.minus(Duration.ofDays(1)).toString(), now)).isTrue()
        assertThat(PushRegistrar.needsReregister(now.minus(Duration.ofDays(30)).toString(), now)).isTrue()
    }

    @Test
    fun `never registered, unparseable or future timestamps re-register`() {
        assertThat(PushRegistrar.needsReregister(null, now)).isTrue()
        assertThat(PushRegistrar.needsReregister("yesterday-ish", now)).isTrue()
        assertThat(PushRegistrar.needsReregister(now.plusSeconds(3600).toString(), now)).isTrue()
    }

    @Test
    fun `the window is configurable`() {
        assertThat(PushRegistrar.needsReregister(now.minusSeconds(120).toString(), now, Duration.ofMinutes(1))).isTrue()
    }
}
