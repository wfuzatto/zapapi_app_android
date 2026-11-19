package br.com.prodatastelecom.zapapi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CachedMessagePage(val messages: List<ChatMessage>, val hasMore: Boolean)

/** Stores only messages that have actually been loaded by the operator. */
class LocalMessageCache(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "zapapi_messages.db", null, 3
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                account_key TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                server_message_id TEXT NOT NULL DEFAULT '',
                text TEXT NOT NULL,
                direction TEXT NOT NULL,
                message_time TEXT NOT NULL,
                status TEXT NOT NULL,
                type TEXT NOT NULL,
                has_media INTEGER NOT NULL,
                media_mime TEXT NOT NULL,
                media_filename TEXT NOT NULL,
                is_pending INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(account_key, conversation_id, message_id)
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX messages_page_idx ON messages(account_key, conversation_id, message_id)"
        )
        createConversationState(db)
        createConversationList(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createConversationState(db)
            migrateCachedMedia(db)
        }
        if (oldVersion < 3) createConversationList(db)
    }

    private fun createConversationState(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS conversation_state (
                account_key TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                has_more INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(account_key, conversation_id)
            )""".trimIndent()
        )
    }

    private fun createConversationList(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS cached_conversations (
                account_key TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                name TEXT NOT NULL,
                phone TEXT NOT NULL,
                preview TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                unread INTEGER NOT NULL,
                window_open INTEGER NOT NULL,
                has_photo INTEGER NOT NULL,
                photo_version TEXT NOT NULL,
                read_through_id INTEGER NOT NULL,
                PRIMARY KEY(account_key, conversation_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS cached_conversations_order_idx ON cached_conversations(account_key, position)")
    }

    @Synchronized
    fun conversations(accountKey: String): List<ChatSummary> {
        val result = mutableListOf<ChatSummary>()
        readableDatabase.query(
            "cached_conversations",
            arrayOf("conversation_id", "name", "phone", "preview", "updated_at", "unread", "window_open", "has_photo", "photo_version", "read_through_id"),
            "account_key=?", arrayOf(accountKey), null, null, "position ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += ChatSummary(
                id = cursor.getString(0), name = cursor.getString(1), phone = cursor.getString(2),
                preview = cursor.getString(3), time = cursor.getString(4), unread = cursor.getInt(5),
                windowOpen = cursor.getInt(6) != 0, hasPhoto = cursor.getInt(7) != 0,
                photoVersion = cursor.getString(8), readThroughId = cursor.getLong(9)
            )
        }
        return result
    }

    @Synchronized
    fun saveConversations(accountKey: String, conversations: List<ChatSummary>) {
        if (this.conversations(accountKey) == conversations) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("cached_conversations", "account_key=?", arrayOf(accountKey))
            conversations.forEachIndexed { index, chat ->
                db.insertWithOnConflict("cached_conversations", null, ContentValues().apply {
                    put("account_key", accountKey)
                    put("conversation_id", chat.id)
                    put("position", index)
                    put("name", chat.name)
                    put("phone", chat.phone)
                    put("preview", chat.preview)
                    put("updated_at", chat.time)
                    put("unread", chat.unread)
                    put("window_open", if (chat.windowOpen) 1 else 0)
                    put("has_photo", if (chat.hasPhoto) 1 else 0)
                    put("photo_version", chat.photoVersion)
                    put("read_through_id", chat.readThroughId)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun rekeyAccount(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("messages", "conversation_state", "cached_conversations").forEach { table ->
                db.execSQL("UPDATE OR IGNORE $table SET account_key=? WHERE account_key=?", arrayOf(newKey, oldKey))
                db.delete(table, "account_key=?", arrayOf(oldKey))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun migrateCachedMedia(db: SQLiteDatabase) {
        listOf("image", "video", "audio", "sticker", "document", "file").forEach { label ->
            val type = when (label) { "file" -> "document"; else -> label }
            db.execSQL(
                "UPDATE messages SET type=?, has_media=1 WHERE lower(type)='text' AND lower(trim(text)) LIKE ?",
                arrayOf(type, "[$label]%")
            )
        }
    }

    @Synchronized
    fun setHasMore(accountKey: String, conversationId: String, hasMore: Boolean) {
        writableDatabase.insertWithOnConflict("conversation_state", null, ContentValues().apply {
            put("account_key", accountKey)
            put("conversation_id", conversationId)
            put("has_more", if (hasMore) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun save(accountKey: String, conversationId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            messages.filter { it.id.toLongOrNull() != null && !it.isPending }.forEach { message ->
                val values = ContentValues().apply {
                    put("account_key", accountKey)
                    put("conversation_id", conversationId)
                    put("message_id", message.id)
                    put("server_message_id", "")
                    put("text", message.text)
                    put("direction", message.direction)
                    put("message_time", message.time)
                    put("status", message.status)
                    put("type", message.type)
                    put("has_media", if (message.hasMedia) 1 else 0)
                    put("media_mime", message.mediaMime)
                    put("media_filename", message.mediaFilename)
                    put("is_pending", 0)
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun savePending(accountKey: String, conversationId: String, message: ChatMessage) {
        if (!message.isPending) return
        val values = ContentValues().apply {
            put("account_key", accountKey)
            put("conversation_id", conversationId)
            put("message_id", message.id)
            put("server_message_id", message.serverMessageId)
            put("text", message.text)
            put("direction", message.direction)
            put("message_time", message.time)
            put("status", "pending")
            put("type", message.type)
            put("has_media", 0)
            put("media_mime", "")
            put("media_filename", "")
            put("is_pending", 1)
        }
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun attachPendingId(accountKey: String, conversationId: String, localId: String, serverId: String) {
        writableDatabase.update("messages", ContentValues().apply {
            put("server_message_id", serverId)
        }, "account_key=? AND conversation_id=? AND message_id=? AND is_pending=1",
            arrayOf(accountKey, conversationId, localId))
    }

    @Synchronized
    fun pending(accountKey: String, conversationId: String): List<ChatMessage> {
        val pending = mutableListOf<ChatMessage>()
        readableDatabase.query("messages", COLUMNS,
            "account_key=? AND conversation_id=? AND is_pending=1",
            arrayOf(accountKey, conversationId), null, null, "rowid ASC").use { cursor ->
            while (cursor.moveToNext()) pending += ChatMessage(
                id = cursor.getString(2),
                text = cursor.getString(3),
                direction = cursor.getString(4),
                time = cursor.getString(5),
                status = cursor.getString(6),
                type = cursor.getString(7),
                hasMedia = cursor.getInt(8) != 0,
                mediaMime = cursor.getString(9),
                mediaFilename = cursor.getString(10),
                isPending = true,
                serverMessageId = cursor.getString(11)
            )
        }
        return pending
    }

    @Synchronized
    fun removePending(accountKey: String, conversationId: String, localId: String) {
        writableDatabase.delete("messages",
            "account_key=? AND conversation_id=? AND message_id=? AND is_pending=1",
            arrayOf(accountKey, conversationId, localId))
    }

    @Synchronized
    fun latest(accountKey: String, conversationId: String, limit: Int = PAGE_SIZE): CachedMessagePage =
        queryPage(
            accountKey, conversationId,
            "account_key=? AND conversation_id=? AND is_pending=0",
            arrayOf(accountKey, conversationId), limit
        )

    @Synchronized
    fun before(
        accountKey: String,
        conversationId: String,
        beforeId: String,
        limit: Int = PAGE_SIZE
    ): CachedMessagePage = queryPage(
        accountKey, conversationId,
        "account_key=? AND conversation_id=? AND is_pending=0 AND CAST(message_id AS INTEGER) < ?",
        arrayOf(accountKey, conversationId, beforeId), limit
    )

    private fun queryPage(
        accountKey: String,
        conversationId: String,
        selection: String,
        args: Array<String>,
        limit: Int
    ): CachedMessagePage {
        val rows = mutableListOf<ChatMessage>()
        readableDatabase.query(
            "messages", COLUMNS, selection, args, null, null,
            "CAST(message_id AS INTEGER) DESC", (limit + 1).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += ChatMessage(
                    id = cursor.getString(2),
                    text = cursor.getString(3),
                    direction = cursor.getString(4),
                    time = cursor.getString(5),
                    status = cursor.getString(6),
                    type = cursor.getString(7),
                    hasMedia = cursor.getInt(8) != 0,
                    mediaMime = cursor.getString(9),
                    mediaFilename = cursor.getString(10)
                )
            }
        }
        val hasMore = rows.size > limit || hasMore(accountKey, conversationId)
        return CachedMessagePage(rows.take(limit).asReversed(), hasMore)
    }

    private fun hasMore(accountKey: String, conversationId: String): Boolean =
        readableDatabase.query(
            "conversation_state", arrayOf("has_more"),
            "account_key=? AND conversation_id=?", arrayOf(accountKey, conversationId),
            null, null, null, "1"
        ).use { cursor -> !cursor.moveToFirst() || cursor.getInt(0) != 0 }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("messages", null, null)
        writableDatabase.delete("conversation_state", null, null)
        writableDatabase.delete("cached_conversations", null, null)
    }

    companion object {
        const val PAGE_SIZE = 10
        private val COLUMNS = arrayOf(
            "account_key", "conversation_id", "message_id", "text", "direction",
        "message_time", "status", "type", "has_media", "media_mime", "media_filename",
        "server_message_id", "is_pending"
        )
    }
}
