package dev.watchmemory.surfaces

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.watchmemory.MainActivity

/** All system shortcuts open the recorder and leave microphone control to the user. */
internal fun openRecorder(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0, Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)
