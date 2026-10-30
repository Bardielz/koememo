# KoeMemo

Kotlin 原生 Wear OS 本地录音应用，面向 Galaxy Watch5 等手表。支持熄屏录音、连续分段保存，以及音源、采样率和编码格式的实验对比。

当前版本 **0.1.4**，处于设备验证阶段。KoeMemo 是原「随声记 / WatchMemory」项目的新名称；保留应用 ID `dev.watchmemory`，以兼容原有应用数据和更新路径。

## 功能

- 手动启动麦克风前台服务，支持暂停、继续、结束、时间点标记和最新片段试听。
- 连续采集和编码，约每 5 分钟切一个文件，不因切段重启麦克风。
- AAC-LC / ADTS (`.aac`) 与 Opus / Ogg (`.ogg`)。
- 文件关闭后记录 SHA-256；每 30 秒 flush + fsync，停止和切段时同步。
- 记录电量、音量、系统静音、可用空间、温度状态及实际音频配置。
- 电量低于 9%、空间不足 256 MiB 或超过 30 秒无编码数据时停止并保存。
- 本地存储，不申请网络权限。尚无云同步、转写、摘要或手机配套应用。

## 实验设置

主界面下滑进入 **设置 → 实验功能**。总开关默认关闭；只能在暂停后修改，下次开始或继续录音时生效。

| 配置 | 选项 | 总开关关闭时 |
| --- | --- | --- |
| 音源 | MIC / VOICE_RECOGNITION / UNPROCESSED | MIC |
| 采样率 | 16 / 24 / 48 kHz | 16 kHz |
| 编码 | AAC-LC / Opus | AAC-LC |
| 目标码率 | AAC 32 / 48 kbps；Opus 24 / 32 kbps | 48 kbps |

`UNPROCESSED` 仅在系统明确声明支持时可选。不支持的采集或编码配置在启动时明确报错，不静默换成另一种编码。采样率是应用请求值，设备内部仍可能重采样，不能据此断言麦克风 ADC 的原生采样率。

每次只启用一种配置，不做三路同时录音。实验功能关闭后仍保留选择，便于再次开启。

## 构建

需要 **完整 JDK 17 或 21**、Android SDK Platform 35、Build Tools 35.0.0，以及用于安装的 Platform Tools。Android Studio 可选；不需要 Rust、NDK 或三星专用 SDK。

| 构建配置 | 版本 |
| --- | --- |
| Gradle Wrapper | 8.11.1（已配置分发包 SHA-256） |
| Android Gradle Plugin | 8.9.2 |
| Kotlin | 2.1.20 |
| minSdk / targetSdk | 30 / 35 |

```sh
git clone https://github.com/Bardielz/koememo.git
cd koememo
# 设置 JAVA_HOME 与 ANDROID_HOME，或用 Android Studio 配置 SDK。
apps/watch/wearos/gradlew -p apps/watch/wearos assembleDebug testDebugUnitTest :recording:domain:test :recording:application:test lintDebug
python3 -m unittest discover -s tools/audio -p 'test_*.py' -v
```

APK 输出：`apps/watch/wearos/app/build/outputs/apk/debug/app-debug.apk`。Python 工具仅使用标准库，建议 Python 3.10 或更新版本。首次构建需要访问 Google Maven、Maven Central 和 Gradle 分发服务。

`tools/build/build-local.sh` 是原开发工作区的可选构建辅助脚本，使用邻近的 `work/toolchain` 目录和 IntelliJ JBR，通过仅监听回环地址的 curl 缓存下载依赖。普通克隆环境请优先使用 Gradle Wrapper，不依赖这套本地目录布局。

## 安装与使用

1. 在手表开发者选项开启 ADB 调试和无线调试，电脑与手表连接同一 Wi-Fi。
2. 打开「配对新设备」，用当前 IP、配对端口及临时配对码配对；随后使用无线调试主页面的连接端口连接。
3. 暂停并保存当前录音，再安装 APK。

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb -s WATCH_IP:CONNECTION_PORT install -r apps/watch/wearos/app/build/outputs/apk/debug/app-debug.apk
```

配对端口与连接端口通常不同，且可能变化。打开 KoeMemo，允许麦克风与通知权限，点击开始。正式续航对比时关闭无线调试。

暂停会保存当前片段；继续会在同一会话中建立新一轮采集；结束关闭会话但保留文件。计时显示本轮连续运行时长，暂停后归零。

**更新必须使用相同签名。** 不同电脑生成的 debug 签名可能不同；如果提示签名冲突，不要直接卸载，先导出录音。仓库不包含签名密钥。卸载或清除应用数据会删除录音。

## 导出与校验

先暂停或结束，等待保存完成，再通过已授权的 ADB 导出到一个尚不存在的目录：

```sh
python3 tools/audio/export.py /path/to/new-export --serial WATCH_IP:CONNECTION_PORT
# adb 不在 PATH 时加 --adb /path/to/platform-tools/adb
python3 tools/audio/audit.py /path/to/new-export/files/recordings
```

导出使用 debug 应用的 `run-as` 权限，保留手表原件。输出包含音频、`events.jsonl`、原始归档和 `audit.json`。退出码 0 表示结构与已保存哈希检查无异常，2 表示存在错误或警告。

校验覆盖 ADTS、Ogg 页 CRC/序号/结束标记和 SHA-256；**结构校验不等于音质或录音连续性验证**，还需解码和试听。音频文件未进行应用层加密；导出后自行管理。

## 实现说明

```text
Activity → 麦克风前台服务 → AudioRecord → 系统 MediaCodec
                                           ├─ AAC → ADTS 分段
                                           └─ Opus → Ogg 分段
```

- PCM 按约 128 ms 批量读取；编码器输入较小时拆分批次，停止时先送完已读数据，再送 EOS。
- AudioRecord 缓冲取一秒音频与系统最小缓冲两倍中的较大者；文件写入使用 32 KiB 缓冲。
- Opus 将约一秒音频包合为一页；分段复制至少 80 ms 历史包供解码器预热，通过 pre-skip 排除重复播放。
- 兼容 Android 包装的 Opus 配置头，将编码延迟换算为 Ogg 的 48 kHz 时间基准。
- Opus 试听使用连续包时间戳，避免提取器重复时间戳导致重复跳过预热音频；AAC 试听使用 MediaPlayer。
- 音量每约 500 ms 抽查一批 PCM；静音与无帧监测每 5 秒，电量与空间每 30 秒，遥测日志每 60 秒。

项目按客户端和平台组织：

```text
apps/
  watch/wearos/       # Wear OS Gradle 工程
    app/             # UI、Android 服务、音频与存储适配器
    recording/
      domain/        # 纯 Kotlin：会话、录音配置、安全规则
      application/   # 纯 Kotlin：用例及存储、控制、播放端口
  phone/             # 手机端预留，尚未实现
  desktop/           # PC 端预留，尚未实现
packages/contracts/  # 跨端协议预留，尚未实现
tools/              # 构建、音频校验与设备探针
docs/               # 架构与变更记录
```

依赖方向为 `app → application → domain`。Android SDK、MediaCodec、文件和 SharedPreferences 留在 app 适配层；`bootstrap/AppGraph` 负责组装。保留原应用 ID 和 Android 组件入口。详见 [架构说明](docs/architecture.md)。

## 验证状态与限制

重构前的 0.1.4 已完成构建、Lint、14 项 Kotlin 单元测试及 8 项 Python 测试。Galaxy Watch5（Wear OS 6 / One UI 8 Watch）上，使用合成正弦波验证了 AAC 与 Opus 共 12 组采样率/码率配置，包含一秒切段和解码；24 个 Opus 片段通过结构及 CRC 检查。测试工具为 `tools/probes/CodecProbe.java`，不打开麦克风。

这些测试不代表所有设备可用，也不证明新版麦克风录音、扬声器试听或全天续航均已验收。早期版本的长时运行包含充电时段，不能作为单次充电 24 小时续航证据。尚未完成当前各配置的耗电对照，不承诺 Opus 或更低码率一定更省电。

- 进程被终止或手表重启后需手动重新开始，不自动在后台启动麦克风。
- 异常退出可能损失最近同步周期内的尾部音频；发生调度阻塞时窗口可能更长。未正常结束的 Ogg 会被校验标记。
- 编码可能包含少量尾部填充，文件时长不等于精确采集时长。
- 电量和空间保护存在约 30 秒检查延迟；低音量提示不保证识别所有丢音。
- 当前是侧载测试应用，未作为商店版本发布；无自动清理录音功能。

建议先做 12 分钟切段与暂停测试，再做熄屏长录及同条件电量对比。请仅在具备录音授权的场景使用。

## 自动构建与发布

[GitHub Actions](https://github.com/Bardielz/koememo/actions) 在推送 main、提交 PR、推送 `v*` 标签或手动触发时执行：Python 测试 → APK 构建 → Kotlin 单元测试 → Android Lint。成功后可下载 `koememo-ci-debug`，测试报告单独保存，保留 14 天。

推送与 `versionName` 一致的标签（例如 `v0.1.4`）会在检查通过后创建预发布 Release 并附上 CI APK 与校验信息；已有同名附件不会覆盖。普通分支构建不会发布 Release。工作流不上传 GitHub Packages，因为此项目交付的是手表安装包，而非 Maven 依赖库。

**签名区别：** `ci-debug.apk` 使用本次运行生成的临时 debug 密钥，可能无法覆盖本机安装的版本，也不保证不同 CI 构建之间能直接更新。标为 `local-debug.apk` 的附件使用原开发机 debug 签名。遇到签名冲突先导出录音，不要直接卸载。密钥不进入仓库或工作流附件。

## 开发记录

见 [变更记录](docs/CHANGELOG.md)。Git 历史从当前 0.1.4 源码快照开始，按应用、工具、文档拆分导入；之前的版本演进以变更记录描述，不伪造历史提交。

参考：[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)、[Android 编码格式](https://developer.android.com/media/platform/supported-formats)、[麦克风前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types)。
