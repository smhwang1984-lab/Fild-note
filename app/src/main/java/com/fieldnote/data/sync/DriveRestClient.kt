package com.fieldnote.data.sync

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class RemoteFile(
    val id: String,
    val name: String,
    val entityId: String?,
    val revision: Long,
    val updatedAt: Long
)

/** Thrown when the Drive API answers with a non-2xx status. [code] is the HTTP status. */
class DriveApiException(val code: Int, message: String) : IOException(message)

/**
 * @param accessToken short-lived OAuth access token to start with.
 * @param refreshToken invoked at most once per request when the API answers 401, to obtain a
 *   fresh access token (e.g. [GoogleDriveAuthorization.silentToken] called from a blocking
 *   context). Return null to give up and let the 401 propagate as [DriveApiException].
 */
class DriveRestClient(
    accessToken: String,
    private val refreshToken: (() -> String?)? = null,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = API
) {
    @Volatile
    private var token: String = accessToken

    fun currentUserEmail(): String {
        val url = baseUrl.newBuilder().addPathSegments("drive/v3/about")
            .addQueryParameter("fields", "user(emailAddress)").build()
        return execute(Request.Builder().url(url).get())
            .getJSONObject("user").getString("emailAddress")
    }

    /**
     * Finds (or creates) a folder tagged with [marker] in its `appProperties`. The lookup is by
     * marker only, not by parent, so it keeps working even if the user renames or moves the
     * folder in Drive, and it is what lets a second device discover the same folder instead of
     * creating a duplicate `MyNoteApp` tree. If more than one match exists (e.g. a race between
     * two devices bootstrapping at once) the oldest one wins and is treated as canonical.
     */
    fun ensureFolder(name: String, parentId: String?, marker: String): String {
        findFolderByMarker(marker).firstOrNull()?.let { return it }
        val parent = parentId ?: "root"
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parent))
            .put("appProperties", JSONObject().put(MARKER_KEY, marker))
        val url = baseUrl.newBuilder().addPathSegments("drive/v3/files")
            .addQueryParameter("fields", "id").build()
        return execute(Request.Builder().url(url).post(metadata.toString().jsonBody()))
            .getString("id")
    }

    /** Oldest-first ids of every non-trashed folder tagged with [marker]. */
    private fun findFolderByMarker(marker: String): List<String> {
        val query = "appProperties has { key='" + MARKER_KEY + "' and value='" + escape(marker) +
            "' } and mimeType = '" + FOLDER_MIME + "' and trashed = false"
        val ids = mutableListOf<Pair<String, String>>()
        var pageToken: String? = null
        do {
            val builder = baseUrl.newBuilder().addPathSegments("drive/v3/files")
                .addQueryParameter("q", query)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files(id,createdTime)")
                .addQueryParameter("orderBy", "createdTime")
                .addQueryParameter("pageSize", "100")
            pageToken?.let { builder.addQueryParameter("pageToken", it) }
            val response = execute(Request.Builder().url(builder.build()).get())
            val array = response.optJSONArray("files") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                ids += item.getString("id") to item.optString("createdTime")
            }
            pageToken = response.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        return ids.sortedBy { it.second }.map { it.first }
    }

    /**
     * True if [fileId] still exists and is not trashed. A genuine 404 (folder deleted or the
     * cached id belongs to a different, no-longer-authorized account) returns false so the
     * caller can re-resolve it; any other failure (offline, permission hiccup) is rethrown so a
     * temporary network problem never looks like "the folder is gone" and triggers a needless
     * re-create.
     */
    fun folderExists(fileId: String): Boolean {
        val url = baseUrl.newBuilder().addPathSegments("drive/v3/files/" + fileId)
            .addQueryParameter("fields", "id,trashed").build()
        return try {
            !execute(Request.Builder().url(url).get()).optBoolean("trashed", false)
        } catch (error: DriveApiException) {
            if (error.code == 404) false else throw error
        }
    }

    fun listJsonFiles(parentId: String): List<RemoteFile> = listFiles(
        "'" + parentId + "' in parents and mimeType = 'application/json' and trashed = false"
    )

    fun downloadJson(fileId: String): JSONObject {
        val url = baseUrl.newBuilder().addPathSegments("drive/v3/files/" + fileId)
            .addQueryParameter("alt", "media").build()
        return execute(Request.Builder().url(url).get())
    }

    fun createJson(
        parentId: String,
        name: String,
        entityType: String,
        entityId: String,
        revision: Long,
        updatedAt: Long,
        body: JSONObject
    ): String {
        val metadata = fileMetadata(name, parentId, entityType, entityId, revision, updatedAt)
        val url = baseUrl.newBuilder().addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id")
            .build()
        return execute(Request.Builder().url(url).post(multipart(metadata, body)))
            .getString("id")
    }

    fun updateJson(
        fileId: String,
        name: String,
        entityType: String,
        entityId: String,
        revision: Long,
        updatedAt: Long,
        body: JSONObject
    ) {
        val metadata = fileMetadata(name, null, entityType, entityId, revision, updatedAt)
        val url = baseUrl.newBuilder().addPathSegments("upload/drive/v3/files/" + fileId)
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id")
            .build()
        execute(Request.Builder().url(url).patch(multipart(metadata, body)))
    }

    private fun listFiles(query: String): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        var pageToken: String? = null
        do {
            val builder = baseUrl.newBuilder().addPathSegments("drive/v3/files")
                .addQueryParameter("q", query)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files(id,name,appProperties)")
                .addQueryParameter("pageSize", "1000")
            pageToken?.let { builder.addQueryParameter("pageToken", it) }
            val response = execute(Request.Builder().url(builder.build()).get())
            val array = response.optJSONArray("files") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val properties = item.optJSONObject("appProperties") ?: JSONObject()
                files += RemoteFile(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    entityId = properties.optString("entityId").takeIf(String::isNotBlank),
                    revision = properties.optString("revision", "0").toLongOrNull() ?: 0,
                    updatedAt = properties.optString("updatedAt", "0").toLongOrNull() ?: 0
                )
            }
            pageToken = response.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        return files
    }

    private fun fileMetadata(
        name: String,
        parentId: String?,
        entityType: String,
        entityId: String,
        revision: Long,
        updatedAt: Long
    ) = JSONObject()
        .put("name", name)
        .put("mimeType", "application/json")
        .apply { if (parentId != null) put("parents", JSONArray().put(parentId)) }
        .put(
            "appProperties",
            JSONObject()
                .put("entityType", entityType)
                .put("entityId", entityId)
                .put("revision", revision.toString())
                .put("updatedAt", updatedAt.toString())
        )

    private fun multipart(metadata: JSONObject, body: JSONObject) = MultipartBody.Builder()
        .setType("multipart/related".toMediaType())
        .addPart(metadata.toString().jsonBody())
        .addPart(body.toString().jsonBody())
        .build()

    /**
     * Sends [builder] with the current bearer token, retrying at most once on 401 (after asking
     * [refreshToken] for a new token) and up to [MAX_ATTEMPTS] times total on 429/5xx with a
     * short backoff. All request bodies used in this class are in-memory (JSON strings /
     * [MultipartBody] built from them), so rebuilding and resending the same [builder] on retry
     * is safe.
     */
    private fun execute(builder: Request.Builder): JSONObject {
        var usedRefresh = false
        var attempt = 0
        while (true) {
            attempt++
            val request = builder.header("Authorization", "Bearer " + token).build()
            val response = client.newCall(request).execute()
            val text = response.body?.string().orEmpty()
            val code = response.code
            val successful = response.isSuccessful
            response.close()
            if (successful) {
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
            if (code == 401 && !usedRefresh && refreshToken != null) {
                val newToken = refreshToken.invoke()
                if (newToken != null) {
                    token = newToken
                    usedRefresh = true
                    continue
                }
            }
            if ((code == 429 || code >= 500) && attempt < MAX_ATTEMPTS) {
                Thread.sleep(BACKOFF_MILLIS * attempt)
                continue
            }
            throw DriveApiException(code, "Drive API " + code + ": " + text)
        }
    }

    private fun String.jsonBody() =
        toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun escape(value: String) =
        value.replace("'", 92.toChar().toString() + "'")

    companion object {
        private val API = "https://www.googleapis.com/".toHttpUrl()
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val MARKER_KEY = "fieldnoteFolder"
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MILLIS = 500L
    }
}
