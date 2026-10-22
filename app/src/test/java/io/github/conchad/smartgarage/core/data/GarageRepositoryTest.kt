package io.github.conchad.smartgarage.core.data

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.cache.CachedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Test

/** The read side's decode pipeline (D-A22): decode once per change, share among every collector. */
@OptIn(ExperimentalCoroutinesApi::class)
class GarageRepositoryTest {

    @Serializable
    private data class Car(val id: String, val make: String)

    private fun row(id: String, json: String) = CachedRow("vehicles", id, id, "2026-10-01T00:00:00Z", json)

    @Test
    fun `rows that did not change are neither decoded nor emitted again`() = runTest(UnconfinedTestDispatcher()) {
        val rows = MutableSharedFlow<List<CachedRow>>()
        val cars = GarageRepository.decodeShared(rows, Car.serializer(), backgroundScope, Dispatchers.Unconfined)
        val emissions = mutableListOf<List<Car>>()
        backgroundScope.launch { cars.collect { emissions += it } }

        rows.emit(listOf(row("v1", """{"id":"v1","make":"Mazda"}""")))
        // Room re-runs the query whenever any row in the table changes; the same rows must not reach the screen twice.
        rows.emit(listOf(row("v1", """{"id":"v1","make":"Mazda"}""")))
        rows.emit(listOf(row("v1", """{"id":"v1","make":"Honda"}""")))

        assertThat(emissions.map { it.single().make }).containsExactly("Mazda", "Honda").inOrder()
    }

    @Test
    fun `every collector shares one subscription and gets the latest list at once`() = runTest(UnconfinedTestDispatcher()) {
        val rows = MutableSharedFlow<List<CachedRow>>()
        val cars = GarageRepository.decodeShared(rows, Car.serializer(), backgroundScope, Dispatchers.Unconfined)
        val first = mutableListOf<List<Car>>()
        val second = mutableListOf<List<Car>>()

        backgroundScope.launch { cars.collect { first += it } }
        rows.emit(listOf(row("v1", """{"id":"v1","make":"Mazda"}""")))
        backgroundScope.launch { cars.collect { second += it } }

        assertThat(rows.subscriptionCount.value).isEqualTo(1)
        // The late collector is served from the replay, without another query or decode.
        assertThat(second).isEqualTo(first)
        assertThat(second.single().single().make).isEqualTo("Mazda")
    }

    @Test
    fun `a row the app cannot decode is skipped rather than failing the list`() = runTest(UnconfinedTestDispatcher()) {
        val rows = MutableSharedFlow<List<CachedRow>>()
        val cars = GarageRepository.decodeShared(rows, Car.serializer(), backgroundScope, Dispatchers.Unconfined)
        val emissions = mutableListOf<List<Car>>()
        backgroundScope.launch { cars.collect { emissions += it } }

        rows.emit(listOf(row("v1", """{"id":"v1","make":"Mazda"}"""), row("v2", """{"id":"v2"""")))

        assertThat(emissions.single().map { it.id }).containsExactly("v1")
    }
}
