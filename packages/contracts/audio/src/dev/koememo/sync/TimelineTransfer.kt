package dev.koememo.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The timeline extension follows all audio acknowledgements. A lost footer never replaces the last snapshot. */
object TimelineTransfer {
    fun write(output: DataOutputStream, lines: Sequence<String>, progress: () -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        for (line in lines) {
            require(line.isNotEmpty())
            output.writeUTF(line)
            digest.update((line + "\n").toByteArray(Charsets.UTF_8))
            progress()
        }
        output.writeUTF("")
        output.write(digest.digest())
    }

    fun receive(input: DataInputStream, dir: File, progress: () -> Unit) {
        val temp = File.createTempFile("timeline-", ".part", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { file ->
                val out = file.buffered()
                while (true) {
                    val line = input.readUTF()
                    if (line.isEmpty()) break
                    require(!line.contains('\n') && !line.contains('\r'))
                    val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                    out.write(bytes)
                    digest.update(bytes)
                    progress()
                }
                val expected = ByteArray(32).also(input::readFully)
                if (!MessageDigest.isEqual(digest.digest(), expected)) throw IOException("时间轴校验失败")
                out.flush()
                file.fd.sync()
            }
            Files.move(temp.toPath(), File(dir, TimelineJournal.FILE).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
}
