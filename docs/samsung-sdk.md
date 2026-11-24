# Galaxy Watch 三星 SDK 接入决策

适用于现有 Kotlin 手表端。优先回答两个问题：Watch5 固件能否初始化 Galaxy Performance SDK，以及使用它能否改善录音。两项分别验收。当前默认后端为 Android 采集与编码，已有 `thermal_status`、电池快照和录音阶段计时可作基线。[项目适配入口](watch-backends.md)

## 已核实的能力

| 接口 | 官方能力与边界 | KoeMemo 接入位置 |
|---|---|---|
| Galaxy Performance SDK 1.0.0 | 以 JAR 接入，经 socket 与系统守护进程通信，需要 INTERNET 权限。支持短时资源控制请求，官网未明确列出 Watch5。类参考明确标注本版本不支持 SPerfListener。 | 作为可选的手表性能实验模块。实际温控观测继续读取 Android PowerManager。公开 API 没有提供分部件能量或音频帧。[编程指南](https://developer.samsung.com/galaxy-performance/programming-guide.html) · [监听器参考](https://developer.samsung.com/galaxy-performance/api-reference/com/samsung/sdk/sperf/SPerfListener.html) |
| Samsung Health Sensor SDK | 支持 Watch4 及后续型号。应用通过 HealthTrackingService 连接并枚举可用 tracker；根据 tracker 请求相应权限，分发走三星合作伙伴流程。 | 将来有佩戴或生理上下文需求时，通过独立健康功能接入。传感器监听和用户授权由该功能管理。[接入指南](https://developer.samsung.com/health/sensor/guide/getting-started.html) · [分发流程](https://developer.samsung.com/health/sensor/process.html) |
| Samsung Device Health Manager | 此前 Watch5 实机盘点发现 sdhms 服务；公开 SDK 能力与普通 APK 权限需要单独确认。 | 保留为 ADB 故障分析数据来源。此前同一固件通过 Android 公共 API 枚举到 0 个 PowerMonitor，仍应按设备探测。 |

## 第一阶段：验证 SDK 初始化

1. 参照 Data Layer 的 Gradle 模块边界，新增可选手表模块（建议路径 `apps/watch/wearos/vendor/samsung-performance`），在显式开启的实验构建中包含。JAR 和 INTERNET 声明由该模块持有。使用官网下载包并核对其中的使用与再分发条款，再决定依赖的交付方式；仓库和默认 CI 应能在没有 JAR 时构建。[官方下载](https://developer.samsung.com/galaxy-performance/download.html)
2. AppGraph 只注册探测工厂。打开实验功能后，由用户点击一次“探测三星性能 SDK”，在后台线程串行初始化，一次进程内复用结果。调用 `SPerf.initialize(applicationContext)` 并保存布尔结果；成功时记录 `SPerf.getVersionName()`。分别记录未随包提供、初始化返回 false、调用异常和成功。保留机型、系统构建号和耗时，避免每分钟或每次采集重新初始化。[SPerf API](https://developer.samsung.com/galaxy-performance/api-reference/com/samsung/sdk/sperf/SPerf.html)
3. 结果通过实验日志展示。若录音日志需要同一证据，已有 TelemetrySource 只读取缓存结果。录音线程不等待探测。验证普通构建、实验关闭、初始化失败时均能录音；Watch5 与非三星表分别覆盖成功或降级路径。初始化成功仅证明该设备可以建立 SDK 会话，后续资源请求需要独立验证。

## 第二阶段：仅在出现性能瓶颈时验证资源请求

先用阶段耗时与短时 Perfetto 区分编码等待、写盘阻塞和 CPU 压力，再决定是否试用 `PerformanceManager`。以短时、可关闭的独立控制接口接入，仅在已验证的机型和配置上开放。由会话持有请求，按设定超时自动结束，暂停、结束及异常清理时回收。把请求结果、持续时间和阶段写入实验日志；主动资源控制与被动采样分别开关。系统可限制或拒绝请求。[PerformanceManager](https://developer.samsung.com/galaxy-performance/api-reference/com/samsung/sdk/sperf/PerformanceManager.html) · [官方 FAQ](https://developer.samsung.com/galaxy-performance/faq.html)

同机、离电、固定音源与编码配置，交替运行通用基线和实验组，比较每小时电荷变化、长尾阶段耗时、断音及音频可解码性。短时压力改善和全天续航分别判断。仅当可重复验证收益后再考虑自动启用。若 Watch5 初始化失败，保留失败证据，继续使用公共 Android 路径。只有拿到实际输出 PCM 或编码帧的厂商接口时，才进入 PcmInput 或 CaptureBackend。
