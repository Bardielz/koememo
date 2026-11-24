# 手表能力适配

应用启动时由 WatchApplication 初始化 AppGraph。CaptureBackends 按注册顺序保留与设备匹配的工厂。工厂的机型判断和配置查询必须轻量，不得打开麦克风或连接厂商服务。默认注册 Android 音频后端，当前未集成厂商 SDK。

- 对仅替换麦克风的设备，实现 PcmInput，向 AndroidCaptureBackend 注入输入工厂。可分别注入 MediaCodec 编码器选择函数。为特定组合注册具有唯一 ID、机型和配置约束的 CaptureBackend，放在通用实现之前。
- 对能直接输出编码帧的设备，实现 CaptureBackend，使用请求的配置和停止信号，将格式和帧交给现有 RecordingWriter，遵守已有的帧计数、诊断和定期同步约定。后端负责释放硬件资源；调用方负责关闭 writer。
- 自动模式允许在输出开始前，对明确报告 BackendUnavailable 的实现回退；一旦配置输出或写帧即停止回退。普通运行错误进入原有异常保存路径。设置中的强制后端用于对照实验，失败时报告错误。日志记录尝试的后端和回退原因。
- 电池统计作为 TelemetrySource 注入；同一设备的来源由 DeviceTelemetry 汇总。新增字段采用带来源、范围和单位的唯一名称，保留现有字段兼容性。读取失败只跳过对应来源。
- Google Data Layer 在独立 Android Library 中实现 TransferChannel。清单监听入口随该模块打包。新传输适配器可通过 IncomingTransferHandler 复用现有片段目录、断点、哈希回执和超时逻辑。

在 apps/watch/wearos 下运行。默认构建维持原应用 ID 和 Google 同步方式。无 Google 模块的手表构建使用：

```sh
./gradlew -PwearDataLayer=false :app:assembleDebug
```

该开关作用于手表。两种构建使用同一输出路径。跨品牌支持需逐固件测试实际采集、熄屏运行和离电续航。

## 按需开启：佩戴触发

第一阶段在「设置 → 实验功能 → 佩戴触发对照」验证触发信号。只在页面可见且录音空闲时启动监听；离开后注销。标准低延迟佩戴传感器给出初始状态（0 摘下，1 佩戴），用连续有效的 0 → 1 产生候选启动事件。初始已佩戴、重复值和未知状态都不触发。三星低功耗佩戴传感器在同一时间轴记录原始值；其值义、延迟和收益需在实机配对验证。缺少标准来源时仍可采集三星对照数据。加速度、方向或高度变化本身不足以推断佩戴。

实机验收时保持对照页可见，分别在佩戴和摘下状态进入页面，再做多轮戴上、摘下、静置和放在充电座的对照。使用应用私有的 `files/diagnostics/wear-signals.jsonl` 对齐同一轮两个来源的传感器时间戳、到达时间和候选次数。以人工操作为基准统计漏报、重复和延迟。熄屏后台交付及离电开销需要另外验证。

后续接入录音时，采用单独的用户开关和已验证来源。在应用可见、已有麦克风授权并满足现有电量和空间条件时，候选事件可复用现有启动用例；后台候选优先提供通知让用户点按进入。手动暂停、录音中、缺权限和重复事件均应单独验收。摘下后是否暂停作为独立策略处理。

三星性能及健康 SDK 的候选模块、探测顺序和真机验收条件见[接入调研](samsung-sdk.md)。

## 三星性能 SDK 探测

在本地取得官方 Galaxy Performance SDK 后，在手表 Gradle 工程构建时传入 JAR 的绝对路径：

```sh
./gradlew :app:assembleDebug -PsamsungPerformanceJar=/absolute/path/to/sperf.jar
```

安装后进入「设置 → 实验功能 → 探测三星性能 SDK」。按钮在录音空闲时可用，后台调用一次初始化并缓存本进程结果。界面区分初始化成功、返回 false、调用异常与构建未包含 SDK。结果写入应用私有的 `files/diagnostics/samsung-performance.jsonl` 和 `KoeMemoSPerf` Logcat 标签。默认构建可独立运行。SDK 文件放在仓库外部。
