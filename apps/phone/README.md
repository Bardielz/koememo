# 安卓手机端

在仓库根目录执行 `apps/watch/wearos/gradlew -p apps/watch/wearos :phone:assembleDebug :app:assembleDebug`。phone-debug.apk 安装到安卓手机，app-debug.apk 安装到配对手表，两端需要相同 applicationId、签名与可用的 Google Play services。使用同一次构建生成的两个 APK。本分支保留 0.1.6-dev 的录音诊断，两个 APK 版本均为 0.1.7-dev。

手机点击“从手表同步”。已关闭片段保存在手机应用私有目录。点击片段可播放或通过系统文件选择器导出。手机前台服务负责同步。返回桌面或重新打开页面可以继续当前任务。允许通知后也可从通知暂停。临时断线后，手机按 2、4、8 秒间隔重试，接着已有临时文件继续下载；主动暂停后需手动继续。手表保留源文件。第一版由用户在手机前台发起，支持一台可达手表。

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

## 双端同步性能统计

手机开始同步时会保存会话编号。传输期间进程被系统回收，前台服务重新创建后会沿用该编号续传。连接重试耗尽后保留任务，下次打开手机应用时再尝试。手动暂停会清除待恢复任务，已接收的数据保留。

连接已解锁的 debug 手机和配对手表，确保有待传录音且同步处于空闲状态，运行以下真机回归。脚本从界面开始同步，将手机应用切到后台并终止其进程，检查服务恢复和会话编号，然后暂停同步并验证重新打开后的状态。

```sh
python3 tools/sync/test-device-recovery.py --phone "$PHONE_SERIAL"
```

手机在「实验功能」内开启总开关和「同步性能与耗电统计」。手表沿用「设置 → 实验功能 → 性能与耗电统计」。两端各自控制，默认关闭，从下一次同步尝试生效。「查看同步统计」展示本机最近五次尝试，关闭采样后仍可查看历史。UID 用户态与内核态 CPU 来自本应用 UID 的系统累计快照差，统计窗口重置或字段缺失时省略。

开启采样后，两端在应用私有目录 `files/diagnostics/sync.jsonl` 写入本机数据。每次连接尝试记录开始、约每 60 秒的快照和结束结果。约 1 MiB 后轮换，保留上一份 `sync.previous.jsonl`。每条同步采样还以相同 JSON 写入 logcat 的 KoeMemoMetrics 标签，可用 `adb -s "$SERIAL" logcat -s KoeMemoMetrics:I` 实时查看。仅同步期间采样。新版两端用同一 session 关联，重试产生新的 attempt。旧版通道在手表上标为 legacy。传输中进程被终止可能只有开始或中间样本。

在仓库根目录执行以下命令导出 debug 包的日志。若发生轮换，还可按相同方式导出上一份，一起交给脚本。

```sh
adb -s "$PHONE_SERIAL" exec-out run-as dev.watchmemory cat files/diagnostics/sync.jsonl > phone-sync.jsonl
adb -s "$WATCH_SERIAL" exec-out run-as dev.watchmemory cat files/diagnostics/sync.jsonl > watch-sync.jsonl
python3 tools/sync/performance.py phone-sync.jsonl watch-sync.jsonl
```

- payload_bytes 为本次实际复制的音频字节，不含续传跳过的前缀、协议开销和无线链路重传；两端不可相加当作文件大小。平均吞吐包含发现、校验和退避等本次尝试开销。
- phase_wall_ms 含等待，phase_thread_cpu_ns 只计同步线程，process_cpu_ms 含本应用其他线程。手表同时录音时进程 CPU 含录音开销。手表录音本身的采集、编码和写盘统计仍由实验设置中的录音性能诊断控制。
- 电量百分比、电荷计数、电流、温度、电压及充电状态为整机读数，缺失字段表示设备未报告。汇总仅对有首尾且所有样本均显示拔电放电的尝试计算整机净下降；短任务可能低于电量计分辨率。采样间隔内短暂接电可能无法观察。电荷计数的单位和精度需要在具体设备验证。
- 对比同步策略时，固定录音编码、时长和屏幕状态，用同设备的“只录不传”作为基线；USB 给手机供电时仅验证采样链路，正式耗电实验用电池供电。

### 核对系统按应用估算的能耗

在录制或同步测试前后分别保存以下输出，按设备和当前用户的 UID 匹配 `Estimated power use (mAh)` 里的应用行。保留统计起始时间，只有同设备、同 UID、同一系统统计窗口的两份快照才适合做差。包名过滤仍可能包含其他 UID 的整机汇总。该命令为只读，使用它不需要重置全机统计。

```sh
adb -s "$SERIAL" shell cmd package list packages -U --user 0 dev.watchmemory
adb -s "$SERIAL" shell dumpsys batterystats dev.watchmemory
```

系统给 UID 的 CPU、audio 等分类为归因估算，可能包含录音和同步并行的消耗。框架代为执行的工作也可能计入其他 UID。用应用的阶段耗时、CPU 时间、字节和系统统计交叉分析。调试内存可用 `adb -s "$SERIAL" shell dumpsys meminfo dev.watchmemory` 获取进程 PSS。系统统计字段和硬件电量监测能力依设备而异。
