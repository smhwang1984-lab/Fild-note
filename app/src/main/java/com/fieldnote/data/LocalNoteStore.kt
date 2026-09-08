package com.fieldnote.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class NoteRecord(
    val id: String,
    val title: String,
    val content: String,
    val updatedAt: Long,
    val revision: Long,
    val dirty: Boolean,
    val remoteFileId: String?,
    val lastSyncedRevision: Long,
    val lastSyncedUpdatedAt: Long
)

data class TodoRecord(
    val id: Long,
    val title: String,
    val dueDay: Int?,
    val completed: Boolean,
    val updatedAt: Long,
    val revision: Long,
    val dirty: Boolean,
    val remoteFileId: String?,
    val lastSyncedRevision: Long,
    val lastSyncedUpdatedAt: Long
)

class LocalNoteStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "field_note.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL,
                updated_at INTEGER NOT NULL, revision INTEGER NOT NULL, dirty INTEGER NOT NULL,
                remote_file_id TEXT, last_synced_revision INTEGER NOT NULL DEFAULT 0,
                last_synced_updated_at INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE todos (
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, due_day INTEGER, completed INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, revision INTEGER NOT NULL, dirty INTEGER NOT NULL,
                remote_file_id TEXT, last_synced_revision INTEGER NOT NULL DEFAULT 0,
                last_synced_updated_at INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE sync_conflicts (
                id INTEGER PRIMARY KEY AUTOINCREMENT, item_type TEXT NOT NULL, item_id TEXT NOT NULL,
                local_revision INTEGER NOT NULL, remote_revision INTEGER NOT NULL,
                local_updated_at INTEGER NOT NULL, remote_updated_at INTEGER NOT NULL,
                detected_at INTEGER NOT NULL, details TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun ensureSeedData(): NoteRecord {
        val db = writableDatabase
        if (scalarCount(db, "notes") == 0L) {
            val now = System.currentTimeMillis()
            db.insertOrThrow("notes", null, ContentValues().apply {
                put("id", DEFAULT_NOTE_ID)
                put("title", "Field Note")
                put("content", """{"pages":{"1":[]}}""")
                put("updated_at", now)
                put("revision", 1)
                put("dirty", 1)
            })
        }
        if (scalarCount(db, "todos") == 0L) {
            insertSeedTodo(db, 1L, "Prepare note object model", null, false)
            insertSeedTodo(db, 2L, "Define calendar-linked todo", 2, false)
            insertSeedTodo(db, 3L, "Separate todo data and note placement", null, true)
        }
        return listNotes().first()
    }

    @Synchronized
    fun listNotes(): List<NoteRecord> = readableDatabase.query(
        "notes", null, null, null, null, null, "updated_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toNote()) } }

    @Synchronized
    fun findNote(id: String): NoteRecord? = readableDatabase.query(
        "notes", null, "id = ?", arrayOf(id), null, null, null, "1"
    ).use { if (it.moveToFirst()) it.toNote() else null }

    @Synchronized
    fun saveNote(id: String, title: String, content: String): NoteRecord {
        val current = requireNotNull(findNote(id))
        if (current.title == title && current.content == content) return current
        writableDatabase.update("notes", ContentValues().apply {
            put("title", title)
            put("content", content)
            put("updated_at", System.currentTimeMillis())
            put("revision", current.revision + 1)
            put("dirty", 1)
        }, "id = ?", arrayOf(id))
        return requireNotNull(findNote(id))
    }

    @Synchronized
    fun dirtyNotes(): List<NoteRecord> = readableDatabase.query(
        "notes", null, "dirty = 1", null, null, null, "updated_at ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toNote()) } }

    @Synchronized
    fun upsertRemoteNote(note: NoteRecord) {
        writableDatabase.insertWithOnConflict(
            "notes", null, note.values(dirty = false), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun markNoteSynced(id: String, remoteFileId: String, revision: Long, updatedAt: Long) {
        writableDatabase.update("notes", ContentValues().apply {
            put("remote_file_id", remoteFileId)
            put("last_synced_revision", revision)
            put("last_synced_updated_at", updatedAt)
            put("dirty", 0)
        }, "id = ? AND revision = ?", arrayOf(id, revision.toString()))
    }

    @Synchronized
    fun listTodos(): List<TodoRecord> = readableDatabase.query(
        "todos", null, null, null, null, null, "id ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTodo()) } }

    @Synchronized
    fun findTodo(id: Long): TodoRecord? = readableDatabase.query(
        "todos", null, "id = ?", arrayOf(id.toString()), null, null, null, "1"
    ).use { if (it.moveToFirst()) it.toTodo() else null }

    @Synchronized
    fun saveTodo(id: Long, title: String, dueDay: Int?, completed: Boolean): TodoRecord {
        val current = findTodo(id)
        val values = ContentValues().apply {
            put("id", id)
            put("title", title)
            if (dueDay == null) putNull("due_day") else put("due_day", dueDay)
            put("completed", if (completed) 1 else 0)
            put("updated_at", System.currentTimeMillis())
            put("revision", (current?.revision ?: 0) + 1)
            put("dirty", 1)
            if (current != null) {
                put("remote_file_id", current.remoteFileId)
                put("last_synced_revision", current.lastSyncedRevision)
                put("last_synced_updated_at", current.lastSyncedUpdatedAt)
            }
        }
        writableDatabase.insertWithOnConflict("todos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return requireNotNull(findTodo(id))
    }

    @Synchronized
    fun dirtyTodos(): List<TodoRecord> = readableDatabase.query(
        "todos", null, "dirty = 1", null, null, null, "updated_at ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTodo()) } }

    @Synchronized
    fun upsertRemoteTodo(todo: TodoRecord) {
        writableDatabase.insertWithOnConflict(
            "todos", null, todo.values(dirty = false), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun markTodoSynced(id: Long, remoteFileId: String, revision: Long, updatedAt: Long) {
        writableDatabase.update("todos", ContentValues().apply {
            put("remote_file_id", remoteFileId)
            put("last_synced_revision", revision)
            put("last_synced_updated_at", updatedAt)
            put("dirty", 0)
        }, "id = ? AND revision = ?", arrayOf(id.toString(), revision.toString()))
    }

    @Synchronized
    fun recordConflict(
        type: String,
        itemId: String,
        localRevision: Long,
        remoteRevision: Long,
        localUpdatedAt: Long,
        remoteUpdatedAt: Long
    ) {
        val exists = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_conflicts WHERE item_type=? AND item_id=? AND " +
                "local_revision=? AND remote_revision=? AND resolved=0",
            arrayOf(type, itemId, localRevision.toString(), remoteRevision.toString())
        ).use { it.moveToFirst(); it.getLong(0) > 0 }
        if (exists) return
        Log.w(
            "FieldNoteSync",
            "Conflict type=" + type + " id=" + itemId +
                " localRevision=" + localRevision + " remoteRevision=" + remoteRevision
        )
        writableDatabase.insertOrThrow("sync_conflicts", null, ContentValues().apply {
            put("item_type", type)
            put("item_id", itemId)
            put("local_revision", localRevision)
            put("remote_revision", remoteRevision)
            put("local_updated_at", localUpdatedAt)
            put("remote_updated_at", remoteUpdatedAt)
            put("detected_at", System.currentTimeMillis())
            put("details", "Both local and Drive copies changed after the last successful sync.")
        })
    }

    @Synchronized
    fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM notes WHERE dirty=1) + " +
            "(SELECT COUNT(*) FROM todos WHERE dirty=1)", null
    ).use { it.moveToFirst(); it.getInt(0) }

    @Synchronized
    fun conflictCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM sync_conflicts WHERE resolved=0", null
    ).use { it.moveToFirst(); it.getInt(0) }

    private fun insertSeedTodo(
        db: SQLiteDatabase,
        id: Long,
        title: String,
        dueDay: Int?,
        completed: Boolean
    ) {
        db.insertOrThrow("todos", null, ContentValues().apply {
            put("id", id)
            put("title", title)
            if (dueDay == null) putNull("due_day") else put("due_day", dueDay)
            put("completed", if (completed) 1 else 0)
            put("updated_at", System.currentTimeMillis())
            put("revision", 1)
            put("dirty", 1)
        })
    }

    private fun scalarCount(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM " + table, null).use { it.moveToFirst(); it.getLong(0) }

    private fun Cursor.toNote() = NoteRecord(
        id = getString(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        content = getString(getColumnIndexOrThrow("content")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        revision = getLong(getColumnIndexOrThrow("revision")),
        dirty = getInt(getColumnIndexOrThrow("dirty")) != 0,
        remoteFileId = getString(getColumnIndexOrThrow("remote_file_id")),
        lastSyncedRevision = getLong(getColumnIndexOrThrow("last_synced_revision")),
        lastSyncedUpdatedAt = getLong(getColumnIndexOrThrow("last_synced_updated_at"))
    )

    private fun Cursor.toTodo() = TodoRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        dueDay = getColumnIndexOrThrow("due_day").let { if (isNull(it)) null else getInt(it) },
        completed = getInt(getColumnIndexOrThrow("completed")) != 0,
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        revision = getLong(getColumnIndexOrThrow("revision")),
        dirty = getInt(getColumnIndexOrThrow("dirty")) != 0,
        remoteFileId = getString(getColumnIndexOrThrow("remote_file_id")),
        lastSyncedRevision = getLong(getColumnIndexOrThrow("last_synced_revision")),
        lastSyncedUpdatedAt = getLong(getColumnIndexOrThrow("last_synced_updated_at"))
    )

    private fun NoteRecord.values(dirty: Boolean) = ContentValues().apply {
        put("id", id)
        put("title", title)
        put("content", content)
        put("updated_at", updatedAt)
        put("revision", revision)
        put("dirty", if (dirty) 1 else 0)
        put("remote_file_id", remoteFileId)
        put("last_synced_revision", if (dirty) lastSyncedRevision else revision)
        put("last_synced_updated_at", if (dirty) lastSyncedUpdatedAt else updatedAt)
    }

    private fun TodoRecord.values(dirty: Boolean) = ContentValues().apply {
        put("id", id)
        put("title", title)
        if (dueDay == null) putNull("due_day") else put("due_day", dueDay)
        put("completed", if (completed) 1 else 0)
        put("updated_at", updatedAt)
        put("revision", revision)
        put("dirty", if (dirty) 1 else 0)
        put("remote_file_id", remoteFileId)
        put("last_synced_revision", if (dirty) lastSyncedRevision else revision)
        put("last_synced_updated_at", if (dirty) lastSyncedUpdatedAt else updatedAt)
    }

    companion object {
        const val DEFAULT_NOTE_ID = "00000000-0000-4000-8000-000000000001"
        @Volatile private var instance: LocalNoteStore? = null
        fun get(context: Context): LocalNoteStore = instance ?: synchronized(this) {
            instance ?: LocalNoteStore(context).also { instance = it }
        }
    }
}
