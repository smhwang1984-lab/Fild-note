package com.fieldnote.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * A minimal folder/file abstraction over the sync target so [FolderSyncManager]'s diffing logic
 * can be unit-tested without the Android SAF framework (see `FakeFileStore` in the test sources).
 * [SafFileStore] is the real implementation, backed by [DocumentFile] over a user-picked tree Uri.
 */
interface SyncFolderHandle

interface SyncFileHandle {
    val name: String
}

interface SyncFileStore {
    /** Finds an existing subfolder named [name] under [parent], or creates one. */
    fun childFolder(parent: SyncFolderHandle, name: String): SyncFolderHandle

    fun listJsonFiles(folder: SyncFolderHandle): List<SyncFileHandle>

    fun readText(file: SyncFileHandle): String

    /**
     * Writes [content] to the file named [name] in [folder]: overwrites it if a file with that
     * name already exists, creates it otherwise. Folding create/update into one operation means a
     * retried upload can never leave a duplicate file behind.
     */
    fun upsertJson(folder: SyncFolderHandle, name: String, content: String): SyncFileHandle
}

private class SafFolderHandle(val document: DocumentFile) : SyncFolderHandle
private class SafFileHandle(val document: DocumentFile) : SyncFileHandle {
    override val name: String get() = document.name.orEmpty()
}

/**
 * SAF-backed [SyncFileStore]. All reads/writes go through [Context.getContentResolver] against a
 * `content://` tree Uri the user granted via `ActivityResultContracts.OpenDocumentTree()` --
 * no network calls are made from this process, they happen inside whichever app (e.g. Google
 * Drive) actually backs the chosen folder.
 */
class SafFileStore(private val context: Context) : SyncFileStore {
    fun rootFolder(treeUri: Uri): SyncFolderHandle {
        val document = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("동기화 폴더에 접근할 수 없습니다. 폴더를 다시 선택해 주세요.")
        return SafFolderHandle(document)
    }

    override fun childFolder(parent: SyncFolderHandle, name: String): SyncFolderHandle {
        val parentDoc = (parent as SafFolderHandle).document
        val existing = parentDoc.findFile(name)
        if (existing != null && existing.isDirectory) return SafFolderHandle(existing)
        val created = parentDoc.createDirectory(name)
            ?: parentDoc.findFile(name)?.takeIf { it.isDirectory }
            ?: throw IOException("'$name' 폴더를 만들 수 없습니다.")
        return SafFolderHandle(created)
    }

    override fun listJsonFiles(folder: SyncFolderHandle): List<SyncFileHandle> =
        (folder as SafFolderHandle).document.listFiles()
            .filter { it.isFile && it.name?.endsWith(".json") == true }
            .map { SafFileHandle(it) }

    override fun readText(file: SyncFileHandle): String {
        val uri = (file as SafFileHandle).document.uri
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IOException("파일을 읽을 수 없습니다: ${file.name}")
    }

    override fun upsertJson(folder: SyncFolderHandle, name: String, content: String): SyncFileHandle {
        val parentDoc = (folder as SafFolderHandle).document
        val target = parentDoc.findFile(name)
            ?: parentDoc.createFile("application/json", name)
            ?: throw IOException("파일을 만들 수 없습니다: $name")
        context.contentResolver.openOutputStream(target.uri, "wt")
            ?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("파일에 쓸 수 없습니다: $name")
        return SafFileHandle(target)
    }

    // -- Below: not part of the SyncFileStore contract (arbitrary/binary files, not the
    // note/todo JSON entities), used only by AppUpdateChecker.

    /**
     * Finds a file directly under [folder] (non-recursive) matching [name], ignoring case and
     * surrounding whitespace -- SAF providers (Drive included) preserve whatever the uploading
     * device typed, and a strict exact match silently found nothing for anything but a perfect
     * match (e.g. "Update.apk" or "update.apk " from a different OS/uploader).
     */
    fun findFile(folder: SyncFolderHandle, name: String): SyncFileHandle? {
        val parentDoc = (folder as SafFolderHandle).document
        // Try the fast exact-match path first (most providers optimize findFile()).
        parentDoc.findFile(name)?.takeIf { it.isFile }?.let { return SafFileHandle(it) }
        val match = parentDoc.listFiles().firstOrNull {
            it.isFile && it.name?.trim()?.equals(name, ignoreCase = true) == true
        }
        return match?.let { SafFileHandle(it) }
    }

    /** Copies [file]'s raw bytes to a local [destination] file (overwriting it if present). */
    fun copyToLocalFile(file: SyncFileHandle, destination: File) {
        val uri = (file as SafFileHandle).document.uri
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("파일을 읽을 수 없습니다: ${file.name}")
    }
}
