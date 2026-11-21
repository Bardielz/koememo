package dev.watchmemory.bootstrap

import android.app.Application
import dev.watchmemory.transfer.AudioTransferSession
import dev.watchmemory.transfer.IncomingTransferHandler
import dev.watchmemory.transfer.TransferChannel

class WatchApplication : Application(), IncomingTransferHandler {
    private lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
    }

    override fun serve(channel: TransferChannel) {
        AudioTransferSession(this, graph.settings.diagnosticsEnabled, graph.telemetry).serve(channel)
    }
}
