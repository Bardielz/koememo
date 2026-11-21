package dev.watchmemory

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.watchmemory.transfer.IncomingTransferHandler
import dev.watchmemory.transfer.TransferChannel

class AudioTransferService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        (application as IncomingTransferHandler).serve(object : TransferChannel {
            override val path = channel.path
            override val nodeId = channel.nodeId
            override fun input() = Tasks.await(client.getInputStream(channel))
            override fun output() = Tasks.await(client.getOutputStream(channel))
            override fun close() { client.close(channel) }
        })
    }
}
