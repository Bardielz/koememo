package dev.koememo.phone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One explicit phone task at a time. The scheduler owns retries, the checkpoint owns completed windows. */
class TranscriptionJob : JobService() {
    private val main = Handler(Looper.getMainLooper())
    private var execution: AtomicBoolean? = null
    private var executionId: String? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val scope = params.extras.getString(SCOPE) ?: return false
        val id = params.extras.getString("id") ?: return false
        if (prefs(this).getString("request", null) != id) return false
        execution?.set(true)
        val cancelled = AtomicBoolean()
        execution = cancelled
        executionId = id
        executor.execute {
            var recognizer: OfflineRecognizer? = null
            var current: File? = null
            fun publish(message: String?, file: File? = null) {
                main.post {
                    if (execution === cancelled && prefs(this).getString("request", null) == id) {
                        if (message != null) record(this, scope, message)
                        notifyChange(file)
                    }
                }
            }
            val message = try {
                val target = File(scope)
                val directory = if (target.isDirectory) target else requireNotNull(target.parentFile)
                val clips = AudioSession.load(directory, target.takeUnless { it == directory }).clips
                check(clips.isNotEmpty()) { "没有已同步的音频" }
                val failures = mutableListOf<String>()
                for ((index, clip) in clips.withIndex()) {
                    if (cancelled.get()) throw CancellationException()
                    current = clip.file
                    val transcript = LocalTranscription(this, clip)
                    val data = try {
                        check(!clip.hashMismatch) { "音频校验失败，请重新同步" }
                        transcript.snapshot()
                    } catch (e: Exception) {
                        failures.add("${clip.file.name} · ${e.localizedMessage}")
                        continue
                    }
                    if (data.optBoolean("complete")) continue
                    publish("正在转写 ${index + 1}/${clips.size} · ${clip.file.name}")
                    val engine = recognizer ?: LocalTranscription.recognizer(this).also { recognizer = it }
                    try { transcript.run(data, engine, cancelled::get) { publish(null, clip.file) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures.add("${clip.file.name} · ${e.localizedMessage}") }
                    publish(null, clip.file)
                }
                if (failures.isEmpty()) "已完成本次任务的 ${clips.size} 个片段"
                else "完成 ${clips.size - failures.size}/${clips.size} 段，首个失败片段：${failures.first()}"
            } catch (_: CancellationException) { null }
            catch (e: Exception) { "转写中断 · ${current?.name.orEmpty()} ${e.localizedMessage.orEmpty()}" }
            catch (_: LinkageError) { "设备无法加载识别运行库" }
            finally { recognizer?.release() }
            main.post {
                if (execution === cancelled) {
                    execution = null
                    executionId = null
                    if (prefs(this).getString("request", null) == id) {
                        prefs(this).edit().remove("request").apply()
                        if (message != null) record(this, scope, message)
                    }
                    jobFinished(params, false)
                    notifyChange(null)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getString("id") ?: return false
        if (executionId != id) return false
        execution?.set(true)
        execution = null
        executionId = null
        val scope = params.extras.getString(SCOPE) ?: return false
        // cancel() can race with onStopJob. Only retry the same, still requested task.
        val retry = prefs(this).getString("request", null) == id
        if (retry) record(this, scope, "系统暂缓，等待继续")
        notifyChange(null)
        return retry
    }

    companion object {
        private const val ID = 42017
        private const val SCOPE = "scope"
        private val executor = Executors.newSingleThreadExecutor()
        internal val listeners = mutableSetOf<(File?) -> Unit>()
        private fun notifyChange(file: File?) = listeners.toList().forEach { it(file) }
        private fun prefs(context: Context) = context.getSharedPreferences("transcription-job", Context.MODE_PRIVATE)
        private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
        fun active(context: Context): String? {
            val id = prefs(context).getString("request", null) ?: return null
            val extras = scheduler(context).getPendingJob(ID)?.extras ?: return null
            return extras.getString(SCOPE).takeIf { extras.getString("id") == id }
        }
        fun status(context: Context, scope: String) = prefs(context).getString("message:$scope", "").orEmpty()
        private fun record(context: Context, scope: String, message: String) {
            prefs(context).edit().putString("message:$scope", message).apply()
        }

        fun start(context: Context, target: File) {
            check(active(context) == null) { "已有转写任务，请等待完成或先暂停" }
            val id = java.util.UUID.randomUUID().toString()
            val extras = PersistableBundle().apply { putString(SCOPE, target.path); putString("id", id) }
            val job = JobInfo.Builder(ID, ComponentName(context, TranscriptionJob::class.java))
                .setExtras(extras).setPersisted(true).setMinimumLatency(0).build()
            check(prefs(context).edit().putString("request", id).commit()) { "无法保存任务" }
            if (scheduler(context).schedule(job) != JobScheduler.RESULT_SUCCESS) {
                prefs(context).edit().remove("request").apply()
                error("系统未接受转写任务")
            }
            record(context, target.path, "等待系统调度")
            notifyChange(null)
        }

        fun pause(context: Context, target: File) {
            if (active(context) != target.path) return
            prefs(context).edit().remove("request").apply()
            scheduler(context).cancel(ID)
            record(context, target.path, "已暂停，可继续转写")
            notifyChange(null)
        }
    }
}
