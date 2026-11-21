package dev.watchmemory.transfer

import java.io.InputStream
import java.io.OutputStream

interface TransferChannel {
    val path: String
    val nodeId: String
    fun input(): InputStream
    fun output(): OutputStream
    fun close()
}

interface IncomingTransferHandler {
    fun serve(channel: TransferChannel)
}
