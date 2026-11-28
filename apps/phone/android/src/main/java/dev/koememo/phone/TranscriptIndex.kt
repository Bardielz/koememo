package dev.koememo.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Clip-relative offsets remain directly playable even when the watch omitted silence. */
internal class TranscriptIndex(context: Context) : SQLiteOpenHelper(context, "transcript-search-v1.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE document(session TEXT, engine TEXT, model TEXT, file TEXT, sha TEXT,
            start_ms INTEGER, end_ms INTEGER, text_sha TEXT, vector BLOB NOT NULL,
            PRIMARY KEY(session, engine, model, file, start_ms))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = error("不支持的索引版本")
    fun build(session: String, engine: String, model: TranscriptModel, lines: List<TranscriptLine>,
              encode: (String) -> ByteArray, progress: (Int) -> Unit) {
        require(model.embedding)
        val db = writableDatabase
        val scope = arrayOf(session, engine, model.sha)
        // A cancelled refresh rolls back, so an existing index is always a complete snapshot.
        db.beginTransaction()
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS seen(file TEXT, start_ms INTEGER, PRIMARY KEY(file,start_ms))")
            db.execSQL("DELETE FROM seen")
            lines.forEachIndexed { index, line ->
                if (line.text.isNotBlank()) {
                    val key = scope + arrayOf(line.clip.file.name, line.start.toString())
                    val digest = stableDigest(line.text)
                    val fresh = db.rawQuery("""SELECT 1 FROM document WHERE session=? AND engine=? AND model=? AND file=?
                        AND start_ms=? AND sha=? AND end_ms=? AND text_sha=?""", key + arrayOf(line.clip.hash, line.end.toString(), digest)).use { it.moveToFirst() }
                    if (!fresh) {
                        val vector = encode("title: none | text: " + line.text)
                        embeddingVector(vector)
                        db.insertOrThrowReplacing(ContentValues().apply {
                            put("session", session); put("engine", engine); put("model", model.sha)
                            put("file", line.clip.file.name); put("sha", line.clip.hash)
                            put("start_ms", line.start); put("end_ms", line.end); put("text_sha", digest); put("vector", vector)
                        })
                    }
                    db.execSQL("INSERT INTO seen VALUES(?,?)", arrayOf(line.clip.file.name, line.start))
                }
                progress(index + 1)
            }
            db.execSQL("""DELETE FROM document WHERE session=? AND engine=? AND model=?
                AND NOT EXISTS(SELECT 1 FROM seen WHERE seen.file=document.file AND seen.start_ms=document.start_ms)""", scope)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun SQLiteDatabase.insertOrThrowReplacing(values: ContentValues) {
        check(insertWithOnConflict("document", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "无法写入索引" }
    }
}
