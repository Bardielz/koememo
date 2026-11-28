package dev.koememo.phone

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TranscriptionActionTest {
    @Test fun materialActionOpensModelPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val choice = AsrChoice.from(context.getSharedPreferences("asr-model", 0).getString("selected", null))
        assumeTrue("Exercise first-run model import", !choice.family.directory(context).exists() && TranscriptionJob.active(context) == null)
        val audio = File(context.filesDir, "audio").apply { mkdirs() }
        val session = Files.createTempDirectory(audio.toPath(), "action-test-").toFile()
        File(session, "fixture.aac").writeBytes(byteArrayOf(1))
        val picker = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        var screen: Activity? = null
        try {
            val activity = instrumentation.startActivitySync(Intent(context, TranscriptionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("path", session.path))
            screen = activity
            val deadline = SystemClock.elapsedRealtime() + 8000
            var clicked = false
            while (!clicked && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    val matches = arrayListOf<View>()
                    activity.window.decorView.findViewsWithText(matches, "导入 ${choice.family.label} 模型", View.FIND_VIEWS_WITH_TEXT)
                    clicked = matches.filterIsInstance<Button>().firstOrNull { it.isEnabled }?.performClick() == true
                }
                if (!clicked) SystemClock.sleep(50)
            }
            instrumentation.waitForIdleSync()
            assertEquals("The action must launch the model picker", 1, picker.hits)
        } finally {
            screen?.let { instrumentation.runOnMainSync { it.finishAndRemoveTask() } }
            instrumentation.removeMonitor(picker)
            session.deleteRecursively()
        }
    }
}
