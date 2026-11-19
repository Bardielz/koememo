# 安卓手机端

在仓库根目录执行 `apps/watch/wearos/gradlew -p apps/watch/wearos :phone:assembleDebug :app:assembleDebug`。phone-debug.apk 安装到安卓手机，app-debug.apk 安装到配对手表，两端需要相同 applicationId、签名与可用的 Google Play services。使用同一次构建生成的两个 APK。本分支保留 0.1.6-dev 的录音诊断，两个 APK 版本均为 0.1.7-dev。

手机点击“从手表同步”。已关闭片段保存在手机应用私有目录。点击片段可播放或通过系统文件选择器导出。暂停或断线后再次点击同步，手机根据磁盘上的临时文件恢复。手表保留源文件。第一版由用户在手机前台发起，支持一台可达手表。

## 实机同步验证

若手机提示 `Wearable.API` 不可用，先在 Galaxy Wearable 中完成 Watch5 的配对设置，确认手表显示已连接，再回到 KoeMemo 点击“从手表同步”。国行 Wear OS 配套服务的包名为 `com.google.android.wearable.app.cn`，手机端在清单中声明了对该包的可见性。

使用 debug 包且两端已授权 ADB 时，等手机收到至少三段录音，在仓库根目录运行以下命令。脚本对照两端文件的 SHA-256，并检查手表保存的回执。可用 `--samples` 调整抽查数量。

```sh
python3 tools/sync/verify-device-transfer.py --phone "$PHONE_SERIAL" --watch "$WATCH_SERIAL"
```

## 按需和定时启动

手机请求可由 WearableListenerService 在手表后台接收。手机没有连接时下次手动重试。后续自动同步可用唯一 WorkManager 作业，由充电、非低电量条件约束；周期最短 15 分钟，Doze 可能延迟。需要在任务执行时检查对端可达性，普通网络连接条件不能代替配对节点检查。

定时录音建议采用预约时间 → 手表通知 → 用户点击打开录音界面 → 启动 microphone 前台服务。手机按需发起录音也采用相同的手表确认入口。Android 的后台启动豁免和麦克风的使用中权限是两项独立限制，精确闹钟本身不能保证无人操作时启动麦克风。定时任务和预约录音属于后续功能。

[后台接收事件](https://developer.android.com/training/wearables/data/events) · [周期调度](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) · [麦克风启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
