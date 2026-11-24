package dev.koememo.samsung

import android.content.Context
import com.samsung.sdk.sperf.SPerf

object SamsungPerformance {
    fun probe(context: Context): Pair<String, String?> =
        if (SPerf.initialize(context.applicationContext)) "initialized" to SPerf.getVersionName()
        else "returned_false" to null
}
