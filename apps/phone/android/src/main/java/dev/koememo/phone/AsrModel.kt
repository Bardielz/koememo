package dev.koememo.phone

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

internal object AsrModel {
    const val ID = "sensevoice-20240717-int8"
    private val files = mapOf(
        "model.int8.onnx" to "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        "tokens.txt" to "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
    )
    fun directory(context: Context) = File(context.filesDir, ID)
    fun import(context: Context, tree: Uri) = importModel(context, tree, ID, files)
}

@Synchronized internal fun importModel(context: Context, tree: Uri, id: String, files: Map<String, String>) {
    val destination = File(context.filesDir, id)
    if (destination.isDirectory) return
    val stage = File(context.filesDir, "$id.import")
    stage.deleteRecursively()
    check(stage.mkdir())
    try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { rows ->
            while (rows.moveToNext()) {
                val name = rows.getString(1)
                val hash = files[name] ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(0))
                val digest = MessageDigest.getInstance("SHA-256")
                val source = requireNotNull(context.contentResolver.openInputStream(uri))
                DigestInputStream(source, digest).use { input -> File(stage, name).outputStream().use { input.copyTo(it) } }
                check(digest.digest().hex() == hash) { "$name 校验失败" }
            }
        }
        check(files.keys.all { File(stage, it).isFile }) { "请选择含 ${files.keys.joinToString("、")} 的目录" }
        check(stage.renameTo(destination)) { "无法保存模型" }
    } finally { stage.deleteRecursively() }
}
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
