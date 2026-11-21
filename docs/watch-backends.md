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

三星性能及健康 SDK 的候选模块、探测顺序和真机验收条件见[接入调研](samsung-sdk.md)。
