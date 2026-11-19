package dev.koememo.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import dev.koememo.telemetry.showSyncHistory

internal object PhoneExperiments {
    private fun prefs(context: Context) = context.getSharedPreferences("experiments", Context.MODE_PRIVATE)
    fun collectMetrics(context: Context) = prefs(context).let {
        it.getBoolean("enabled", false) && it.getBoolean("sync_metrics", false)
    }

    fun show(activity: Activity) {
        val settings = prefs(activity)
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val master = Switch(activity).apply {
            text = "开启实验功能"
            isChecked = settings.getBoolean("enabled", false)
        }
        val metrics = Switch(activity).apply {
            text = "同步性能与耗电统计"
            isChecked = settings.getBoolean("sync_metrics", false)
            isEnabled = master.isChecked
            setOnCheckedChangeListener { _, checked -> settings.edit().putBoolean("sync_metrics", checked).apply() }
        }
        master.setOnCheckedChangeListener { _, checked ->
            settings.edit().putBoolean("enabled", checked).apply()
            metrics.isEnabled = checked
        }
        column.addView(master)
        column.addView(metrics)
        column.addView(Button(activity).apply {
            text = "查看同步统计"
            setOnClickListener { showSyncHistory(activity) }
        })
        AlertDialog.Builder(activity).setTitle("实验功能").setMessage("设置从下次同步尝试生效。")
            .setView(column).setPositiveButton("完成", null).show()
    }
}
