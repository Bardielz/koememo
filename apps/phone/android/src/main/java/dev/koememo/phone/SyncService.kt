package dev.koememo.phone

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import dev.koememo.sync.*
import dev.koememo.telemetry.SyncMetrics
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private class WatchUnavailable : IOException("手表暂不可达")

class SyncService : Service() {
    companion object {
        const val RESUME = "dev.koememo.phone.RESUME_PENDING"
        const val PENDING = "pending_session"
        const val PAUSE = "dev.koememo.phone.PAUSE_SYNC"
        @Volatile var running = false
            private set
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val stopped = CountDownLatch(1)
    @Volatile private var active: ChannelClient.Channel? = null
    private val prefs by lazy { getSharedPreferences("sync_state", MODE_PRIVATE) }
    private lateinit var session: String
    private var stage = "discover"
    private var segment = "-"
    private var bytes = 0L
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            prefs.edit().remove(PENDING).commit()
            if (!running) publish("已暂停，可继续同步")
            Log.i("KoeMemoSync", "side=phone reason=user_pause")
            stopTransfer()
            if (!running) stopSelf()
        } else if (!running && !finished) {
            val pending = prefs.getString(PENDING, null)
            if ((intent == null || intent.action == RESUME) && pending == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            session = pending ?: UUID.randomUUID().toString()
            running = true
            notifications.createNotificationChannel(NotificationChannel("transfer", "录音同步", NotificationManager.IMPORTANCE_LOW))
            startForeground(1, notification("正在查找手表"))
            if (!prefs.edit().putString(PENDING, session).commit()) {
                running = false
                publish("无法保存同步任务")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            publish(if (pending == null) "正在查找手表" else "正在恢复同步")
            Log.i("KoeMemoSync", "session=$session side=phone restored=${pending != null}")
            Thread(::transfer, "KoeMemoSync").start()
        }
        return if (running && stopped.count != 0L) START_STICKY else START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val pause = PendingIntent.getService(this, 1, Intent(this, SyncService::class.java).setAction(PAUSE), flags)
        return Notification.Builder(this, "transfer").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("KoeMemo").setContentText(message).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "暂停", pause).build()).setOnlyAlertOnce(true).setOngoing(true).build()
    }

    private fun publish(message: String) {
        getSharedPreferences("sync_state", MODE_PRIVATE).edit().putString("status", message)
            .putBoolean("active", running).apply()
        if (running) notifications.notify(1, notification(message))
    }

    private fun checkActive() = check(stopped.count != 0L) { "已暂停" }
    private fun stopTransfer() {
        stopped.countDown()
        active?.let { Wearable.getChannelClient(this).close(it) }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("KoeMemoSync", "session=$session reason=system_time_limit")
        stopTransfer()
        stopSelf()
    }
    override fun onDestroy() {
        finished = true
        stopTransfer()
        running = false
        super.onDestroy()
    }

    private fun transfer() {
        var message = "已暂停，可继续同步"
        var retainTask = false
        for (attempt in 0..3) {
            val metrics = if (PhoneExperiments.collectMetrics(this)) SyncMetrics(this, "phone", session) else null
            var outcome = "interrupted"
            try {
                checkActive()
                stage = "discover"
                segment = "-"
                bytes = 0
                val count = receive(session, metrics)
                outcome = "completed"
                message = "同步完成，已校验 ${count} 段"
                Log.i("KoeMemoSync", "session=$session side=phone reason=completed count=$count")
                break
            } catch (e: Exception) {
                val cause = if (e is ExecutionException) e.cause ?: e else e
                val retry = cause is WatchUnavailable || cause is ChannelIOException ||
                    cause is EOFException || cause is TimeoutException || cause is com.google.android.gms.common.api.ApiException
                val paused = stopped.count == 0L
                val reason = when {
                    paused -> "paused"
                    cause is ChannelIOException -> "channel_closed(code=${cause.closeReason},app=${cause.appSpecificErrorCode})"
                    else -> cause.message ?: cause.javaClass.simpleName
                }
                Log.w("KoeMemoSync", "session=$session side=phone reason=$reason stage=$stage segment=$segment bytes=$bytes attempt=$attempt", e)
                outcome = if (paused) "paused" else cause.javaClass.simpleName
                if (paused) break
                if (!retry || attempt == 3) {
                    retainTask = retry
                    message = if (cause is WatchUnavailable) "等待手表连接，可继续同步" else "同步失败，可重试：" + (cause.message ?: reason)
                    break
                }
                publish("连接中断，正在等待重试（${attempt+1}/3）")
                metrics?.enter("backoff")
                if (stopped.await(2L shl attempt, TimeUnit.SECONDS)) {
                    outcome = "paused"
                    break
                }
            } finally {
                metrics?.finish(outcome)
            }
        }
        if (stopped.count == 0L) {
            Log.i("KoeMemoSync", "session=$session side=phone reason=stopped stage=$stage segment=$segment bytes=$bytes")
        }
        main.post {
            if (!finished) {
                finished = true
                if (!retainTask) prefs.edit().remove(PENDING).commit()
                running = false
                publish(message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // Poll only asynchronous setup calls so Pause also works before a channel exists.
    private fun <T> awaitSetup(task: com.google.android.gms.tasks.Task<T>): T {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (true) {
            checkActive()
            try {
                return Tasks.await(task, 500, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw e
            }
        }
    }

    private fun receive(session: String, metrics: SyncMetrics?): Int {
        val progress = TransferProgress(android.os.SystemClock::elapsedRealtime)
        fun enter(phase: String) {
            progress.enter(phase)
            stage = phase
            metrics?.enter(phase)
        }
        enter("discover")
        val capabilities = awaitSetup(Wearable.getCapabilityClient(this).getAllCapabilities(CapabilityClient.FILTER_REACHABLE))
        val nodes = capabilities[CAPABILITY]?.nodes.orEmpty()
        if (nodes.isEmpty()) throw WatchUnavailable()
        val watch = nodes.singleOrNull() ?: error("请仅连接一台手表")
        val client = Wearable.getChannelClient(this)
        checkActive()
        val withSession = capabilities[SESSION_CAPABILITY]?.nodes.orEmpty().any { it.id == watch.id }
        val withTimeline = capabilities[TIMELINE_CAPABILITY]?.nodes.orEmpty().any { it.id == watch.id }
        val withReceipts = withTimeline || capabilities[RECEIPTS_CAPABILITY]?.nodes.orEmpty().any { it.id == watch.id }
        val path = when {
            withTimeline -> "$TIMELINE_CHANNEL/$session"
            withReceipts -> "$RECEIPTS_CHANNEL/$session"
            withSession -> "$CHANNEL/$session"
            else -> CHANNEL
        }
        enter("open")
        val opening = client.openChannel(watch.id, path)
        val channel = try {
            awaitSetup(opening)
        } catch (e: Exception) {
            opening.addOnSuccessListener { lateChannel -> client.close(lateChannel) }
            throw e
        }
        active = channel
        val watchdog = java.util.Timer("sync-channel-progress", true)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            checkActive()
            enter("streams")
            watchdog.schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (progress.expired(120_000L)) {
                        cancel()
                        timedOut.set(true)
                        Log.w("KoeMemoSync", "session=$session side=phone idle_timeout phase=${progress.stage}")
                        client.close(channel)
                    }
                }
            }, 10_000L, 10_000L)
            val input = DataInputStream(awaitSetup(client.getInputStream(channel)).buffered())
            val output = DataOutputStream(awaitSetup(client.getOutputStream(channel)).buffered())
            enter("catalog_header")
            val count = input.readInt(); require(count in 0..100000)
            enter("catalog_entries")
            val nodeDirectory = File(File(filesDir, "audio"), watch.id.toByteArray().joinToString("") { "%02x".format(it) })
            val entries = List(count) { index ->
                val item = Segment.read(input)
                val acknowledged = withReceipts && input.readBoolean()
                progress.advanced(0)
                PendingSegment(index, item, nodeDirectory, acknowledged)
            }.sortedBy { it.priority }
            Log.i("KoeMemoSync", "session=$session receipts=$withReceipts first=${entries.firstOrNull()?.item?.name}")
            var lastRequest = android.os.SystemClock.elapsedRealtime()
            var reused = 0
            entries.forEachIndexed { position, entry ->
                val item = entry.item
                checkActive()
                enter("verify_local")
                segment = item.name
                bytes = 0
                val target = entry.target
                val dir = requireNotNull(target.parentFile)
                check(dir.isDirectory || dir.mkdirs())
                val done = entry.verified()
                // A size-offset request also keeps the peer alive during a long local audit.
                if (done && entry.acknowledged && android.os.SystemClock.elapsedRealtime() - lastRequest < 30_000L) {
                    reused++
                    return@forEachIndexed
                }
                val part = entry.part
                if (part.length() > item.size) check(part.delete())
                val offset = if (done) item.size else part.length()
                bytes = offset
                if (position == 0) Log.i("KoeMemoSync", "session=$session first_request=${entry.index} offset=$offset")
                enter("receive")
                publish("正在同步 ${position+1}/$count")
                output.writeInt(entry.index); output.writeLong(offset); output.flush()
                if (!done) {
                    check(dir.usableSpace > item.size-offset) { "手机存储空间不足" }
                    FileOutputStream(part,true).use {
                        copyExactly(input,it,item.size-offset) { n ->
                            progress.advanced(n)
                            bytes += n
                            metrics?.copied(n)
                        }
                        enter("fsync")
                        it.fd.sync()
                    }
                    enter("verify_hash")
                    if (part.sha256() != item.hash) { check(part.delete()); error("校验失败，请重试") }
                    android.system.Os.rename(part.path,target.path)
                }
                enter("ack")
                output.writeUTF(item.hash); output.flush()
                metrics?.confirmed(offset, item.size)
                lastRequest = android.os.SystemClock.elapsedRealtime()
            }
            output.writeInt(-1); output.flush()
            if (withTimeline) {
                enter("timeline")
                val remaining = entries.mapTo(mutableSetOf()) { it.item.session }
                require(input.readInt() == remaining.size)
                repeat(remaining.size) {
                    checkActive()
                    val id = input.readUTF()
                    require(remaining.remove(id))
                    TimelineTransfer.receive(input, File(nodeDirectory, id)) {
                        checkActive()
                        progress.advanced(0)
                    }
                }
                output.writeBoolean(true)
                output.flush()
            }
            check(input.readBoolean()) { "手表未确认完成，请重试" }
            enter("completed")
            Log.i("KoeMemoSync", "session=$session reused_receipts=$reused count=$count")
            return count
        } catch (e: Exception) {
            if (timedOut.get()) throw TimeoutException("手机等待同步进展超时：$stage").apply { initCause(e) }
            throw e
        } finally {
            watchdog.cancel()
            client.close(channel)
            active = null
        }
    }
}
