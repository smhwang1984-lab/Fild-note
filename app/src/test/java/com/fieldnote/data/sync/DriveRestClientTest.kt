package com.fieldnote.data.sync

import java.net.HttpURLConnection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriveRestClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(refreshToken: (() -> String?)? = null) = DriveRestClient(
        accessToken = "initial-token",
        refreshToken = refreshToken,
        baseUrl = server.url("/")
    )

    @Test
    fun `ensureFolder finds an existing folder by marker instead of creating a new one`() {
        server.enqueue(
            filesResponse(
                """{"id":"folder-1","createdTime":"2026-01-01T00:00:00.000Z"}"""
            )
        )

        val id = client().ensureFolder("Notes", parentId = "root-id", marker = "notes")

        assertEquals("folder-1", id)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("q="))
        val decodedQuery = java.net.URLDecoder.decode(request.path, "UTF-8")
        assertTrue(decodedQuery.contains("fieldnoteFolder"))
        assertTrue(decodedQuery.contains("value='notes'"))
    }

    @Test
    fun `ensureFolder creates a marked folder when no match exists`() {
        server.enqueue(filesResponse("")) // empty search result
        server.enqueue(MockResponse().setBody("""{"id":"new-folder"}"""))

        val id = client().ensureFolder("Notes", parentId = "root-id", marker = "notes")

        assertEquals("new-folder", id)
        server.takeRequest() // the search
        val createRequest = server.takeRequest()
        assertEquals("POST", createRequest.method)
        val body = JSONObject(createRequest.body.readUtf8())
        assertEquals("notes", body.getJSONObject("appProperties").getString("fieldnoteFolder"))
        assertEquals("root-id", body.getJSONArray("parents").getString(0))
    }

    @Test
    fun `ensureFolder resolves to the oldest match when duplicates exist`() {
        // Drive returns matches ordered by createdTime (orderBy=createdTime asc), so the first
        // entry in the array is already the oldest; the client must not just take any entry.
        server.enqueue(
            filesResponse(
                """{"id":"oldest","createdTime":"2026-01-01T00:00:00.000Z"},""" +
                    """{"id":"newer","createdTime":"2026-02-01T00:00:00.000Z"}"""
            )
        )

        val id = client().ensureFolder("Notes", parentId = "root-id", marker = "notes")

        assertEquals("oldest", id)
    }

    @Test
    fun `folderExists returns false on 404 without throwing`() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND).setBody("""{"error":"not found"}"""))

        assertEquals(false, client().folderExists("missing-id"))
    }

    @Test
    fun `folderExists propagates a server error instead of treating it as missing`() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAVAILABLE))
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAVAILABLE))
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAVAILABLE))

        try {
            client().folderExists("some-id")
            org.junit.Assert.fail("expected DriveApiException")
        } catch (error: DriveApiException) {
            assertEquals(503, error.code)
        }
    }

    @Test
    fun `a 401 triggers exactly one silent token refresh and retry`() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED))
        server.enqueue(MockResponse().setBody("""{"user":{"emailAddress":"user@example.com"}}"""))

        var refreshCalls = 0
        val email = client(refreshToken = {
            refreshCalls++
            "fresh-token"
        }).currentUserEmail()

        assertEquals("user@example.com", email)
        assertEquals(1, refreshCalls)
        assertEquals(2, server.requestCount)
        server.takeRequest() // first, 401 with the initial token
        val retried = server.takeRequest()
        assertEquals("Bearer fresh-token", retried.getHeader("Authorization"))
    }

    @Test
    fun `a 429 is retried with backoff and eventually succeeds`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("""{"user":{"emailAddress":"user@example.com"}}"""))

        val email = client().currentUserEmail()

        assertEquals("user@example.com", email)
        assertEquals(2, server.requestCount)
    }

    private fun filesResponse(filesJson: String): MockResponse {
        val body = if (filesJson.isBlank()) """{"files":[]}""" else """{"files":[$filesJson]}"""
        return MockResponse().setBody(body)
    }
}
