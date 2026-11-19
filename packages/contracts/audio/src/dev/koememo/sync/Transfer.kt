package dev.koememo.sync

import java.io.*
import java.security.MessageDigest

const val CHANNEL = "/koememo/audio/v1"
const val CAPABILITY = "koememo_audio_v1"
data class Segment(val session: String, val name: String, val size: Long, val hash: String) {
    init {
        require(session.matches(Regex("[A-Za-z0-9-]{1,100}")))
        require(name.matches(Regex("[A-Za-z0-9-]{1,120}\\.(aac|ogg)")))
        require(size in 1..64L*1024*1024 && hash.matches(Regex("[0-9a-f]{64}")))
    }
    fun send(out: DataOutputStream) {
        out.writeUTF(session); out.writeUTF(name); out.writeLong(size); out.writeUTF(hash)
    }
    companion object {
        fun read(input: DataInputStream) = Segment(input.readUTF(), input.readUTF(), input.readLong(), input.readUTF())
    }
}
fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
    val buffer = ByteArray(32768)
    var remaining = length
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        if (count <= 0) throw EOFException("传输中断")
        output.write(buffer, 0, count)
        remaining -= count
    }
}
fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { stream ->
        val buf = ByteArray(32768)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
