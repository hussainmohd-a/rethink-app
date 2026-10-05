/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.iab

import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.service.PersistentState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Identity tests for the device re-registration 401 loop:
 * - R1: when the purchase cid (recvCid) differs from the stored cid, the stored pair is
 *   discarded and a fresh DID is minted under recvCid (single /d/reg, blank did header).
 * - R2: when the mint fails, getDeviceId returns "" (never the stale stored DID bound
 *   to the old cid) and the store is untouched.
 * - R3: reconcileDidForCid mints under the new cid and persists the new pair.
 * - R4: refreshIdentity never sends the stale stored DID when the cid was freshly
 *   minted (blank did header on the first /d/reg).
 *
 * HTTP is faked by mocking [RetrofitManager.okHttpClient] to return a client whose
 * interceptor records request headers and answers with a canned script.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BillingBackendClientTest : KoinTest {

    private data class CapturedRequest(
        val path: String,
        val cidHeader: String?,
        val didHeader: String?,
        val testParameterPresent: Boolean
    )

    private val capturedRequests = mutableListOf<CapturedRequest>()

    /** Script: invoked for every request; returns the canned okhttp Response. */
    private var script: (Request) -> Response = { _ ->
        error("no script configured")
    }

    private val mockPersistentState: PersistentState = mockk(relaxed = true)
    private val mockIdentityStore: SecureIdentityStore = mockk(relaxed = true)

    /** In-memory backing for the mocked SecureIdentityStore. */
    private var storedCid: String? = null
    private var storedDid: String? = null
    private var testStoredCid: String? = null
    private var testStoredDid: String? = null
    private var testMode = false

    private lateinit var client: BillingBackendClient

    @Before
    fun setUp() {
        try { stopKoin() } catch (_: Exception) {}

        capturedRequests.clear()
        storedCid = null
        storedDid = null
        testStoredCid = null
        testStoredDid = null
        testMode = false

        every { mockPersistentState.appTestMode } answers { testMode }
        every { mockPersistentState.routeRethinkInRethink } returns false
        every { mockPersistentState.appVersion } returns 69

        coEvery { mockIdentityStore.get(any()) } coAnswers {
            when (firstArg<SecureIdentityStore.Env>()) {
                SecureIdentityStore.Env.PROD -> Pair(storedCid, storedDid)
                SecureIdentityStore.Env.TEST -> Pair(testStoredCid, testStoredDid)
            }
        }
        coEvery { mockIdentityStore.save(any(), any(), any()) } coAnswers {
            val env: SecureIdentityStore.Env = firstArg()
            val cid: String = secondArg()
            val did: String = thirdArg()
            if (cid.isNotBlank() && did.isNotBlank()) {
                if (env == SecureIdentityStore.Env.TEST) {
                    testStoredCid = cid
                    testStoredDid = did
                } else {
                    storedCid = cid
                    storedDid = did
                }
            }
        }
        every { mockIdentityStore.hasLegacyIdentity() } returns false

        val recordingClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                capturedRequests.add(
                    CapturedRequest(
                        path = request.url.encodedPath,
                        cidHeader = request.header("x-rethink-app-cid"),
                        didHeader = request.header("x-rethink-app-did"),
                        testParameterPresent = request.url.queryParameterNames.contains("test")
                    )
                )
                script(request)
            })
            .build()

        mockkObject(RetrofitManager)
        every { RetrofitManager.okHttpClient(any()) } returns recordingClient

        startKoin {
            modules(module {
                single { mockPersistentState }
                single { mockIdentityStore }
            })
        }

        client = BillingBackendClient(mockIdentityStore)
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    private fun okResponse(request: Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun unauthorizedResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("""{"error":"did verification failed"}""".toResponseBody("application/json".toMediaType()))
            .build()

    /**
     * R1: recvCid differs from stored cid; the stored did is never sent, a single
     * blank-did /d/reg mints the new did, and the store is replaced.
     */
    @Test
    fun `R1 getDeviceId recvCid mismatch mints new did without sending stored did`() = runTest {
        storedCid = "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        script = { request ->
            check(request.header("x-rethink-app-did") == null) { "stored did must not be sent" }
            okResponse(request, """{"did":"did-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}""")
        }

        val recvCid = "cid-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val result = client.getDeviceId(recvCid)

        check(result == "did-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") {
            "expected fresh-minted did under recvCid, got: ${if (result.isBlank()) "<blank>" else "stale-or-other (len=${result.length})"}"
        }
        check(storedCid == recvCid) { "store must be rebound to recvCid" }
        check(storedDid == result) { "store must persist the fresh did" }
        check(capturedRequests.map { it.path } == listOf("/d/reg")) { "exactly one /d/reg expected: ${capturedRequests.map { it.path }}" }
        check(capturedRequests.single().cidHeader == recvCid) { "/d/reg must carry recvCid" }
    }

    /**
     * R2: both re-bind and fresh mint 401 → getDeviceId returns "" and does not
     * rewrite the store. Current code returns the stale stored did (the bug).
     */
    @Test
    fun `R2 getDeviceId recvCid mismatch mint 401 returns blank not stale did`() = runTest {
        storedCid = "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        script = { request -> unauthorizedResponse(request) }

        val recvCid = "cid-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val result = client.getDeviceId(recvCid)

        check(result.isEmpty()) {
            "expected blank did on total re-registration failure, got len=${result.length} (stale did leak)"
        }
        check(storedCid == "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") { "store must be untouched" }
        check(capturedRequests.size == 1) { "mint must be attempted once, got ${capturedRequests.size}" }
    }

    /** R3: reconcileDidForCid under a mismatched cid mints a new did and persists the pair. */
    @Test
    fun `R3 reconcileDidForCid mismatch mints new did and persists`() = runTest {
        storedCid = "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        script = { request ->
            check(request.header("x-rethink-app-did") == null) { "stored did must not be sent" }
            okResponse(request, """{"did":"did-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}""")
        }

        val targetCid = "cid-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val result = client.reconcileDidForCid(targetCid)

        check(result.isSuccess) { "expected success after mint, got errorCode=${result.errorCode}" }
        check(result.deviceId == "did-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") { "expected fresh did" }
        check(storedCid == targetCid && storedDid == result.deviceId) { "store must persist the new pair" }
    }

    /**
     * R4: refreshIdentity with a blank stored cid must NOT send the stale stored
     * did on the first /d/reg (blank did header = legitimate first mint).
     */
    @Test
    fun `R4 refreshIdentity with blank cid does not send stale did on first reg`() = runTest {
        storedCid = null
        storedDid = "did-orphan-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/acc" -> okResponse(request, """{"cid":"cid-fresh-cccccccccccccccccccccccccccccccccccc"}""")
                "/d/reg" -> okResponse(request, """{"did":"did-fresh-dddddddddddddddddddddddddddddddddddd"}""")
                else     -> unauthorizedResponse(request)
            }
        }

        val result = client.refreshIdentity()

        check(result is RefreshIdentityResult.Success) { "expected success, got $result" }
        val regRequests = capturedRequests.filter { it.path == "/d/reg" }
        check(regRequests.isNotEmpty()) { "/d/reg must have been called" }
        check(regRequests.none { it.didHeader != null }) {
            "first /d/reg under a fresh cid must omit the did header (sent: ${regRequests.map { it.didHeader }})"
        }
    }

    /**
     * B1: with an empty store and a preferredCid (the purchase's authoritative cid),
     * /d/acc must carry the preferred cid header and the first /d/reg must omit the
     * did header; the adopted (preferredCid, new did) pair is persisted.
     */
    @Test
    fun `B1 refreshIdentity adopts preferredCid on acc and mints did under it`() = runTest {
        storedCid = null
        storedDid = null

        val preferredCid = "cid-purchase-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/acc" -> okResponse(request, """{"cid":"$preferredCid"}""")
                "/d/reg" -> okResponse(request, """{"did":"did-adopted-ffffffffffffffffffffffffffffffffff"}""")
                else     -> unauthorizedResponse(request)
            }
        }

        val result = client.refreshIdentity(preferredCid)

        check(result is RefreshIdentityResult.Success) { "expected success, got $result" }
        check((result as RefreshIdentityResult.Success).cid == preferredCid) {
            "resolved cid must be the adopted preferredCid, got ${result.cid}"
        }
        val accRequests = capturedRequests.filter { it.path == "/d/acc" }
        check(accRequests.isNotEmpty()) { "/d/acc must have been called" }
        check(accRequests.all { it.cidHeader == preferredCid }) {
            "/d/acc must carry the preferred cid header (sent: ${accRequests.map { it.cidHeader }})"
        }
        val regRequests = capturedRequests.filter { it.path == "/d/reg" }
        check(regRequests.isNotEmpty() && regRequests.none { it.didHeader != null }) {
            "first /d/reg under the adopted cid must omit the did header"
        }
        check(storedCid == preferredCid && storedDid == result.did) {
            "adopted pair must be persisted (stored=($storedCid, ${storedDid?.take(8)}))"
        }
    }

    @Test
    fun `B1 refreshIdentity does not adopt a rejected preferred cid in test mode`() = runTest {
        testMode = true
        storedCid = null
        storedDid = null
        val preferredCid = "cid-purchase-llllllllllllllllllllllllllllllllllll"

        script = { request -> unauthorizedResponse(request) }

        val result = client.refreshIdentity(preferredCid)

        check(result is RefreshIdentityResult.Unauthorized) { "expected Unauthorized, got $result" }
        check(capturedRequests.filter { it.path == "/d/acc" }.map { it.cidHeader } == listOf(preferredCid)) {
            "rejected preferred CID must not be adopted or replaced"
        }
        check(storedCid == null && storedDid == null && testStoredCid == null && testStoredDid == null) {
            "unconfirmed identity must not be stored in either environment"
        }
        check(capturedRequests.none { it.path == "/d/reg" }) { "DID registration must not run" }
    }

    @Test
    fun `B1 refreshIdentity ignores production purchase cid in test mode`() = runTest {
        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val testCid = "cid-test-cccccccccccccccccccccccccccccccccccc"
        val testDid = "did-test-dddddddddddddddddddddddddddddddddddd"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/acc" -> {
                    check(request.header("x-rethink-app-cid") == null) {
                        "test backend must not receive the production CID"
                    }
                    okResponse(request, """{"cid":"$testCid"}""")
                }
                "/d/reg" -> {
                    check(request.header("x-rethink-app-cid") == testCid) {
                        "DID must be registered under the test CID"
                    }
                    okResponse(request, """{"did":"$testDid"}""")
                }
                else -> unauthorizedResponse(request)
            }
        }

        val result = client.refreshIdentity(storedCid!!)

        check(result == RefreshIdentityResult.Success(testCid, testDid)) {
            "expected a test-environment identity, got $result"
        }
        check(storedCid == "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") {
            "test identity resolution must not overwrite production CID"
        }
        check(storedDid == "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") {
            "test identity resolution must not overwrite production DID"
        }
        check(testStoredCid == testCid && testStoredDid == testDid) {
            "test identity must be saved separately"
        }
    }

    @Test
    fun `identity methods resolve a cid from its stored environment`() = runTest {
        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val deviceId = client.getDeviceId(storedCid!!)
        val didResult = client.reconcileDidForCid(storedCid!!)

        check(deviceId == "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") {
            "production CID must use its production DID"
        }
        check(didResult.isSuccess && didResult.deviceId == deviceId) {
            "reconciliation must read the DID from the matching environment"
        }
        check(capturedRequests.isEmpty()) { "cross-environment CIDs must not be sent to /d/reg" }
        check(testStoredCid == null && testStoredDid == null) {
            "refusing a production CID must not write the test identity store"
        }
    }

    @Test
    fun `refreshIdentity refuses a test backend cid that matches production identity`() = runTest {
        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        script = { request ->
            if (request.url.encodedPath == "/d/acc") {
                okResponse(request, """{"cid":"$storedCid"}""")
            } else {
                error("DID registration must not run for a production CID in test mode")
            }
        }

        val result = client.refreshIdentity()

        check(result is RefreshIdentityResult.Failure) {
            "expected cross-environment CID to be rejected, got $result"
        }
        check(testStoredCid == null && testStoredDid == null) {
            "production CID/DID must not be written to the test identity store"
        }
        check(capturedRequests.none { it.path == "/d/reg" }) { "no DID request may use the production CID" }
    }

    @Test
    fun `server calls use the backend associated with the supplied cid`() = runTest {
        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        testStoredCid = "cid-test-cccccccccccccccccccccccccccccccccccc"
        testStoredDid = "did-test-dddddddddddddddddddddddddddddddddddd"
        script = { request -> okResponse(request, """{}""") }

        check(client.registerDevice(storedCid!!, storedDid!!).isSuccess) {
            "production CID should register through the production backend"
        }
        check(client.registerDevice(testStoredCid!!, testStoredDid!!).isSuccess) {
            "test CID should register through the test backend"
        }
        check(capturedRequests.map { it.testParameterPresent } == listOf(false, true)) {
            "backend routing must follow the CID's stored environment: ${capturedRequests.map { it.testParameterPresent }}"
        }
    }

    /** An initial blank-DID mint is attempted once without a re-bind retry. */
    @Test
    fun `A1 refreshIdentity does not retry a rejected initial did mint`() = runTest {
        storedCid = "cid-stored-jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj"
        storedDid = null

        script = { request ->
            when (request.url.encodedPath) {
                "/d/reg" -> unauthorizedResponse(request)
                else     -> okResponse(request, """{"cid":"$storedCid"}""")
            }
        }

        val first = client.refreshIdentity()
        check(first is RefreshIdentityResult.Unauthorized) { "expected Unauthorized, got $first" }
        check(storedCid == "cid-stored-jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj" && storedDid == null) {
            "store must be untouched on failure"
        }
        val firstPassRegs = capturedRequests.filter { it.path == "/d/reg" }
        check(firstPassRegs.size == 1) {
            "initial DID mint must be attempted once, got ${firstPassRegs.size}"
        }
        check(firstPassRegs.all { it.didHeader == null }) { "mint attempts must omit the did header" }

        capturedRequests.clear()
        val second = client.refreshIdentity()
        check(second is RefreshIdentityResult.Unauthorized) { "expected Unauthorized, got $second" }
        val secondPassRegs = capturedRequests.filter { it.path == "/d/reg" }
        check(secondPassRegs.size == 1) {
            "a repeated initial mint must still make only one /d/reg call"
        }
    }

    /** A mismatch in one environment must not touch the other environment's identity. */
    @Test
    fun `mismatch mint in test mode leaves production identity untouched`() = runTest {
        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        testStoredCid = "cid-test-old-rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr"
        testStoredDid = "did-test-old-ssssssssssssssssssssssssssssssssssss"
        script = { request -> okResponse(request, """{"did":"did-test-new-uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu"}""") }

        val newCid = "cid-test-new-tttttttttttttttttttttttttttttttttttt"
        check(client.getDeviceId(newCid) == "did-test-new-uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu")
        check(testStoredCid == newCid) { "test store must be replaced" }
        check(storedCid == "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" &&
            storedDid == "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") { "production store must be untouched" }
        check(capturedRequests.single().testParameterPresent) { "mint must use the test backend" }
    }
}
