package io.github.conchad.smartgarage.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.network.ApiClient
import io.github.conchad.smartgarage.core.network.CfAccessCredentials
import io.github.conchad.smartgarage.core.network.HttpClientFactory
import io.github.conchad.smartgarage.core.security.SecretCipher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reversible stand-in for the Keystore cipher; marks values so tests can see they never stay plaintext. */
private class FakeCipher : SecretCipher {
    override fun encrypt(plaintext: String) = "enc:" + plaintext.reversed()
    override fun decrypt(blob: String) = blob.removePrefix("enc:").reversed().takeIf { blob.startsWith("enc:") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val loginOk = """{"token":"sgd_new","device":{"id":"dev1","name":"Pixel 9"},
        "user":{"id":"u1","username":"connor","displayName":"Connor","role":"ADMIN"},"apiVersion":1}"""
    private val meOk = """{"user":{"id":"u1","username":"connor","displayName":"Connor","role":"ADMIN"},
        "preferences":{"distanceUnit":"mi","volumeUnit":"gal","currency":"USD","timezone":"America/Chicago"},
        "server":{"version":"1.4.0","apiVersion":1}}"""

    private var meStatus = HttpStatusCode.OK
    private val calls = mutableListOf<String>()

    private fun TestScope.setup(loginStatus: HttpStatusCode = HttpStatusCode.Created): Pair<AuthRepository, ProfileStore> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
        ) { tmp.newFile("app.preferences_pb").also { it.delete() } }
        val store = ProfileStore(dataStore, FakeCipher())
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            when (path) {
                "/api/v1/auth/login" -> if (loginStatus == HttpStatusCode.Created) {
                    respond(loginOk, loginStatus, json)
                } else {
                    respond("""{"error":{"code":"invalid_credentials","message":"Invalid"}}""", loginStatus, json)
                }
                "/api/v1/me" -> if (meStatus == HttpStatusCode.OK) {
                    respond(meOk, meStatus, json)
                } else {
                    respond("""{"error":{"code":"unauthorized","message":"revoked"}}""", meStatus, json)
                }
                "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return AuthRepository(ApiClient(HttpClientFactory.create("test", engine)), store) to store
    }

    /**
     * Sign-out wraps the revoke call in a 5 s timeout. Under runTest that timeout runs on virtual time and can fire
     * before the mock engine (on a real thread) answers, so run it on a real dispatcher.
     */
    private suspend fun <T> realTime(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    private val draft = ServerDraft(
        profileId = null,
        name = "Tunnel",
        baseUrl = "https://garage.example.com",
        cfAccess = CfAccessCredentials("id.access", "s3cret"),
    )

    @Test
    fun `sign-in saves an active, signed-in profile with server preferences`() = runTest {
        val (auth, store) = setup()

        val result = auth.signIn(draft, " connor ", "pw", "Pixel 9")

        val id = (result as SignInResult.Success).profileId
        val state = store.current()
        assertThat(state.activeId).isEqualTo(id)
        assertThat(state.isSignedIn(id)).isTrue()
        val profile = state.active!!
        assertThat(profile.account?.deviceId).isEqualTo("dev1")
        assertThat(profile.preferences?.distanceUnit).isEqualTo("mi")
        assertThat(profile.serverVersion).isEqualTo("1.4.0")
        assertThat(store.connection(id)?.token).isEqualTo("sgd_new")
        assertThat(store.connection(id)?.cfAccess).isEqualTo(CfAccessCredentials("id.access", "s3cret"))
    }

    @Test
    fun `wrong password saves nothing`() = runTest {
        val (auth, store) = setup(loginStatus = HttpStatusCode.Unauthorized)

        assertThat(auth.signIn(draft, "connor", "bad", "Pixel")).isEqualTo(SignInResult.InvalidCredentials)
        assertThat(store.current().profiles).isEmpty()
    }

    @Test
    fun `a revoked token signs the profile out but keeps the server`() = runTest {
        val (auth, store) = setup()
        val id = (auth.signIn(draft, "connor", "pw", "Pixel") as SignInResult.Success).profileId

        meStatus = HttpStatusCode.Unauthorized
        auth.refresh(id)

        val state = store.current()
        assertThat(state.isSignedIn(id)).isFalse()
        assertThat(state.active?.lastUsername).isEqualTo("connor")
        assertThat(store.connection(id)?.token).isNull()
        assertThat(store.cfAccess(id)).isNotNull()
    }

    @Test
    fun `sign-out revokes the device on the server`() = runTest {
        val (auth, store) = setup()
        val id = (auth.signIn(draft, "connor", "pw", "Pixel") as SignInResult.Success).profileId

        realTime { auth.signOut(id) }

        assertThat(calls).contains("POST /api/v1/auth/logout")
        assertThat(store.current().isSignedIn(id)).isFalse()
    }

    @Test
    fun `removing the active profile activates the next one`() = runTest {
        val (auth, store) = setup()
        val first = (auth.signIn(draft, "connor", "pw", "Pixel") as SignInResult.Success).profileId
        val second = (auth.signIn(draft.copy(name = "LAN", baseUrl = "http://192.168.50.129:3000", cfAccess = null), "connor", "pw", "Pixel") as SignInResult.Success).profileId
        assertThat(store.current().activeId).isEqualTo(second)
        assertThat(store.cfAccess(second)).isNull()

        realTime { auth.removeProfile(second) }

        val state = store.current()
        assertThat(state.profiles.map { it.id }).containsExactly(first)
        assertThat(state.activeId).isEqualTo(first)
        assertThat(store.connection(second)).isNull()
    }
}
