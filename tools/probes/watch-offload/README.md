# 手表协处理器实验

此独立 Android APK 用公开 API 对照 KoeMemo 的三个工作负载。探针使用合成 PCM 和静音 ADTS，启动后自动结束，结果写入 logcat。包名为 `dev.koememo.offloadprobe`，无麦克风权限。

从仓库根目录运行，使用 JDK 17 或 21、Android SDK 35 和已授权的手表 ADB 序列号。

```sh
apps/watch/wearos/gradlew -p tools/probes/watch-offload assembleDebug lintDebug
adb -s "$WATCH_SERIAL" install -r tools/probes/watch-offload/build/outputs/apk/debug/watch-offload-probe-debug.apk
adb -s "$WATCH_SERIAL" shell am start -W -n dev.koememo.offloadprobe/.Probe
adb -s "$WATCH_SERIAL" logcat -s KoeMemoOffload:I
```

- **音量计算**：按项目 128 ms 的单声道 PCM 块，对照 CPU 的平方和与 GPU 的 OpenGL ES 3.1 分组归约。GPU 计时含上传、分派、同步读回和末级 CPU 求和。误差以 dB 表示。每档预热 10 次，再取 50 次的中位数。比较性能应使用同一非调试构建并固定编译模式；连续微基准与真实录音每约 500 ms 执行一次的功耗特性不同。
- **编码与回听**：枚举公开的 AAC-LC 和 Opus 编码器硬件标志，按采样率申请压缩播放卸载轨道。16 kHz AAC 另执行一次有时间上限的静音 ADTS 写入，报告播放头和时间戳。成功写入表示系统接收数据，判断实际播放进度还要看到帧位置增长。
- **佩戴状态**：前台订阅标准离腕传感器，收到回调时记录当前值、唤醒属性和 FIFO 容量；退出时注销。首次状态回调仅用于验证交付。佩戴转换、熄屏交付与整机耗电需要分别测量。
