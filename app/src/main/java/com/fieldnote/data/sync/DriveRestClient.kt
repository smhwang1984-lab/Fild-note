package com.fieldnote.data.sync

import java.io.IOException
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

class DriveRestClient(
    private val accessToken: String,
    private val client: OkHttpClient = OkHttpClient()
) {
    fun currentUserEmail(): String {
        val url = API.newBuilder().addPathSegments("drive/v3/about")
            .addQueryParameter("fields", "user(emailAddress)").build()
        return execute(Request.Builder().url(url).get().authorized())
            .getJSONObject("user").getString("emailAddress")
    }

    fun ensureFolder(name: String, parentId: String?): String {
        val parent = parentId ?: "root"
        val query = "name = '" + escape(name) + "' and mimeType = '" + FOLDER_MIME +
            "' and '" + parent + "' in parents and trashed = false"
        listFiles(query).firstOrNull()?.let { return it.id }
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parent))
        val url = API.newBuilder().addPathSegments("drive/v3/files")
            .addQueryParameter("fields", "id").build()
        return execute(Request.Builder().url(url).post(metadata.toString().jsonBody()).authorized())
            .getString("id")
    }

    fun listJsonFiles(parentId: String): List<RemoteFile> = listFiles(
        "'" + parentId + "' in parents and mimeType = 'application/json' and trashed = false"
    )

    fun downloadJson(fileId: String): JSONObject {
        val url = API.newBuilder().addPathSegments("drive/v3/files/" + fileId)
            .addQueryParameter("alt", "media").build()
        return execute(Request.Builder().url(url).get().authorized())
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
        val url = API.newBuilder().addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id")
            .build()
        return execute(Request.Builder().url(url).post(multipart(metadata, body)).authorized())
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
        val url = API.newBuilder().addPathSegments("upload/drive/v3/files/" + fileId)
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id")
            .build()
        execute(Request.Builder().url(url).patch(multipart(metadata, body)).authorized())
    }

    private fun listFiles(query: String): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        var pageToken: String? = null
        do {
            val builder = API.newBuilder().addPathSegments("drive/v3/files")
                .addQueryParameter("q", query)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files(id,name,appProperties)")
                .addQueryParameter("pageSize", "1000")
            pageToken?.let { builder.addQueryParameter("pageToken", it) }
            val response = execute(Request.Builder().url(builder.build()).get().authorized())
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

    private fun execute(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("Drive API " + response.code + ": " + text)
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun Request.Builder.authorized(): Request =
        header("Authorization", "Bearer " + accessToken).build()

    private fun String.jsonBody() =
        toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun escape(value: String) =
        value.replace("'", 92.toChar().toString() + "'")

    companion object {
        private val API = "https://www.googleapis.com/".toHttpUrl()
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
