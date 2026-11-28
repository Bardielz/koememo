package dev.koememo.phone

import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.koememo.sync.sha256
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class FunSpeakerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val examples = File(context.cacheDir, "fun-speaker-test")
    private var screen: LibraryActivity? = null

    @Before fun openFixture() {
        assumeTrue(examples.isDirectory && SpeakerMethod.FUN.directory(context).isDirectory)
        screen = instrumentation.startActivitySync(Intent(context, LibraryActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as LibraryActivity
        instrumentation.runOnMainSync { screen?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @After fun closeFixture() {
        screen?.let { instrumentation.runOnMainSync { it.finishAndRemoveTask() } }
    }

    @Test fun publicTwoSpeakerFixture() {
        SpeakerMethod.forFamily(AsrFamily.FUN).open(context).use { engine ->
            fun analyze(name: String, profiles: SpeakerProfiles): List<SpeakerTurn> {
                val file = File(examples, "$name.aac")
                val turns = engine.analyze(SessionClip(file, file.sha256(), null, null, false), profiles) { false }
                android.util.Log.i("KoeMemoSpeakers", "$name: $turns")
                assertTrue(turns.all { it.start >= 0 && it.end > it.start })
                return turns
            }
            val profiles = SpeakerProfiles(mutableListOf())
            // Ten different utterances by two alternating speakers from sherpa's public recognition fixtures.
            val long = analyze("known", profiles)
            assertEquals(setOf(1, 2), long.map { it.id }.toSet())
            val midpoints = listOf(1150, 4900, 10080, 15540, 19830, 25860, 33270, 39960, 44950, 48700)
            val people = midpoints.map { ms -> long.single { ms >= it.start && ms < it.end }.id }
            assertTrue(people[0] != people[1])
            assertTrue(people.indices.all { people[it] == people[it % 2] })
            assertEquals(long, analyze("known", profiles))
            assertEquals(1, analyze("short", SpeakerProfiles(mutableListOf())).map { it.id }.distinct().size)
            assertTrue(analyze("quiet", profiles).isEmpty())
        }
    }
}
