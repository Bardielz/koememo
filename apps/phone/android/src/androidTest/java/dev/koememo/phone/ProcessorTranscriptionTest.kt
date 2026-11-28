package dev.koememo.phone

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Opt-in foreground device acceptance with a public 16 kHz PCM fixture and its reference text. */
@RunWith(Parameterized::class)
class ProcessorTranscriptionTest(private val engineKey: String) {
    companion object {
        private var activity: Activity? = null
        private var session: File? = null

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = AsrChoice.choices.filter { it.family != AsrFamily.SENSE }.map { it.key }

        @JvmStatic @BeforeClass fun openTestSession() {
            val reference = InstrumentationRegistry.getArguments().getString("reference")
            assumeTrue("Provide a public reference transcript and 16 kHz fixture", !reference.isNullOrBlank())
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            if (InstrumentationRegistry.getArguments().getString("foreground") != "true") return
            val root = File(context.filesDir, "audio").apply { mkdirs() }
            val dir = Files.createTempDirectory(root.toPath(), "asr-acceptance-").toFile()
            session = dir
            activity = instrumentation.startActivitySync(Intent(context, LibraryActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("segments", dir.path))
            instrumentation.runOnMainSync { activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        @JvmStatic @AfterClass fun closeTestSession() {
            activity?.let { screen -> InstrumentationRegistry.getInstrumentation().runOnMainSync { screen.finishAndRemoveTask() } }
            session?.delete()
        }
    }
    @Test fun transcribesOnRequestedProcessor() {
        val args = InstrumentationRegistry.getArguments()
        val filter = args.getString("engine")
        assumeTrue(filter == null || filter == engineKey)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val choice = AsrChoice.from(engineKey)
        val model = args.getString("model")
        assumeTrue(model == null || model == choice.family.modelId)
        val fixture = File(context.cacheDir, "asr-smoke.pcm")
        require(fixture.length() in 2..480000 && fixture.length() % 2 == 0L)
        val input = ByteBuffer.wrap(fixture.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(input.remaining()) { input.get() / 32768f }
        val from = SystemClock.elapsedRealtime()
        openRecognizer(context, choice).use { recognizer ->
            val loaded = SystemClock.elapsedRealtime()
            val result = recognizer.transcribe(samples, 16000)
            val elapsed = SystemClock.elapsedRealtime() - loaded
            Log.i("KoeMemoASR", "engine=$engineKey loadMs=${loaded - from} inferMs=$elapsed samples=${samples.size}")
            fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
            assertEquals(normalize(requireNotNull(args.getString("reference"))), normalize(result))
        }
    }
}
