# KoeMemo

KoeMemo 用于在手表上记录一天的对话，随后在安卓手机上按会话查找文字、回听原音。手表独立录音，手机负责同步、离线转写和校订。两端使用 Kotlin，Galaxy Watch5 是目前的主要验证手表。

当前开发版本 **0.1.7-dev**，处于设备验证阶段。KoeMemo 是原「随声记 / WatchMemory」项目的新名称；保留应用 ID `dev.watchmemory`，以兼容原有应用数据和更新路径。

## 功能

- 手动启动麦克风前台服务，支持暂停、继续、结束、时间点标记和最新片段试听。
- 录音时可从系统持续活动入口返回。也可在手表添加「KoeMemo 录音」卡片，或在兼容表盘的短文本、单色图标槽位选择 KoeMemo。两种快捷入口均打开录音主页，点「开始记录」后才启动麦克风。
- 连续采集和编码，约每 5 分钟切一个文件，不因切段重启麦克风。
- 手表设置中的「系统降噪」默认开启，使用当前录音会话的 Android 降噪引擎。设备不支持或启用失败时继续录音；选择 UNPROCESSED 时跳过降噪。
- AAC-LC / ADTS (`.aac`) 与 Opus / Ogg (`.ogg`)。
- 文件关闭后记录 SHA-256；每 30 秒 flush + fsync，停止和切段时同步。
- 记录电量、音量、系统静音、可用空间、温度状态及实际音频配置。
- 电量低于 9%、空间不足 256 MiB 或超过 30 秒无编码数据时停止并保存。
- 本地存储。配对安卓手机可通过 [手机端](apps/phone/README.md) 同步已保存片段并播放。重连优先续传未接收完整的分段，手机校验本地文件后可复用手表回执。手动暂停会清除待恢复任务。

手表主页、设置页和同步统计页支持旋转输入。可用 Galaxy Watch5 的触控表圈上下滚动，也可滑动屏幕。

在 Galaxy Watch 的「设置 → 辅助功能 → 互动和敏捷度 → 通用手势」启用并完成手势教学。打开 KoeMemo 后，按系统配置移动焦点并选中按钮。默认可捏合前移、双捏合后移、握拳选择；录音中选中「标记此刻」即可记录时间点。系统菜单名称和手势映射依固件及用户设置而异。

## 实验设置

主界面下滑进入 **设置 → 实验功能**。总开关默认关闭；只能在暂停后修改，下次开始或继续录音时生效。

| 配置 | 选项 | 总开关关闭时 |
| --- | --- | --- |
| 音源 | MIC / VOICE_RECOGNITION / UNPROCESSED | MIC |
| 采样率 | 16 / 24 / 48 kHz | 16 kHz |
| 编码 | AAC-LC / Opus | AAC-LC |
| 目标码率 | AAC 32 / 48 kbps；Opus 24 / 32 kbps | 48 kbps |

`UNPROCESSED` 仅在系统明确声明支持时可选。不支持的采集或编码配置在启动时明确报错，不静默换成另一种编码。采样率是应用请求值，设备内部仍可能重采样，不能据此断言麦克风 ADC 的原生采样率。

**录音后端**默认自动选择，也可在实验设置中指定已注册的后端，对照测试采集行为。

**三星性能 SDK 探测**可在录音空闲时手动运行。默认 APK 不包含三星 SDK，本地验证构建方式见[手表能力适配](docs/watch-backends.md)。

**佩戴触发对照**在页面可见期间监听可用的佩戴传感器。摘下再戴上会在标准传感器产生有效状态变化时记为一次候选事件，麦克风仍由用户手动启动。事件写入 `files/diagnostics/wear-signals.jsonl`。详见[手表能力适配](docs/watch-backends.md)。

每次只启用一种配置，不做三路同时录音。实验功能关闭后仍保留选择，便于再次开启。

手表端「设置 → 实验功能 → 静音时暂停写入」默认关闭。开启后，录音期间连续约 10 秒低于 −55 dBFS 会暂停向编码器送入音频，麦克风继续监听。短时音量达到约 −50 dBFS 时恢复，并补回最近 1 秒。轻声也可能被省略，需要完整留存时可关闭。播放时间会跳过被省略的静音，事件日志中的 `quiet_gate` 记录每次切换时的采集样本位置及补回样本数。点「暂停」会结束监听，须手动继续录音。

「用人声概率判断静音」可在实验设置开启静音暂停后选择，默认关闭。手表使用 Silero VAD v5.1 单线程 CPU 推理。检测支路把单声道输入按组平均到 8 kHz，以连续 32 ms 帧推理并跨读取批次保留上下文，保存的音频仍使用所选采样率。每个读取批次取最高人声分数，达到 0.35 时继续写入，连续约 10 秒低于此值后暂停；达到 0.60 时恢复并补回最近最多一秒。模型不可用时继续完整录音。阈值为实验初值，音乐、远讲和轻声可能被省略。
模型来自 [Silero VAD v5.1](https://github.com/snakers4/silero-vad/tree/v5.1)，MIT 许可随模型打包。ONNX 文件 SHA-256 为 `2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f`。

### 随录音记录运动和心率

在手表「设置 → 实验功能」开启「录音时记录运动和心率」，下次开始或继续录音时生效。手表通过 Android 标准传感器请求约 5 Hz 的三轴加速度（m/s²）和设备报告的心率。没有心率硬件或权限时，仍可录音并记录可用的运动数据。手动暂停、结束或服务销毁时注销监听。静音暂停写入期间仍记录传感器数据。

采样时间与音频帧共享 BOOTTIME 时钟。原始传感器数据保存在手表会话的 `events.jsonl`，可随手表日志导出。新版两端同步时，手表按已保存音频的 15 秒窗口计算摘要，并与播放时间轴一起发送给配对手机。导出后运行 `python3 tools/audio/align-sensors.py /path/to/events.jsonl` 可获取对应的文件名和片段内毫秒数。离腕或不可靠的心率保留质量状态，输出的 `bpm` 为空。旧录音缺少分段时间范围时只输出可确定的时间信息。[三星健康 SDK 接入说明](docs/samsung-sdk.md)

手机打开录音并点「运动与心率」即可浏览本次会话，按时间或腕部活动强度排列，点选后回听原音。播放器、可回听的标记和转写窗口共用同一摘要。活动强度为加速度矢量模长超出标准重力的部分取均值（ENMO，m/s²）。心率显示有效 BPM 范围、有效/收到的样本数，以及没有有效读数的播放秒格数。静音省略的样本不计入已保存音频，最后一窗按实际片段长度计算。

## 性能诊断

手表设置 → 实验功能 → 性能与耗电统计，默认关闭。暂停录音后可修改，下次录音或同步尝试生效。设置页可查看最近同步统计。
开启后每分钟汇总采集、编码交互、复制、封装写盘和同步的调用次数、耗时及当前线程 CPU 时间，并在停止时输出最终累计值。高频编解码调用采用抽样计时。录音 CPU 唤醒锁覆盖启动和保存阶段，操作可通过 `KoeMemoWakeLock` 日志追踪。正式续航测试应关闭诊断。
所有录音均以低频记录充电状态、电压、温度和电荷计数；电流保留系统原始值，不直接换算功率。详见 [诊断与对照测试](docs/performance-diagnostics.md)。

RNNoise 的 GPU 对照实验使用独立的文件探针，构建与复现步骤见 [探针说明](tools/probes/rnnoise-gpu/README.md)。

从全天腕录、断点同步到可回听文字稿的优先级和验收条件，见[产品重整方案](docs/product-roadmap.md)。

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
手表手动开始 → 麦克风前台服务 → AudioRecord（可用时启用系统降噪）
    → 单声道 PCM → 可选静音判定 → MediaCodec → AAC/ADTS 或 Opus/Ogg 分段
    → 手机按需同步已关闭的文件 → 校验 SHA-256 → 播放或导出
```

手机录音库使用 Material 3 Expressive，跟随系统浅色或深色主题。首页按会话归集已同步音频，可按开始日期查找。打开录音，点「播放这次录音」连续回听手机已收到的原音，点「文字稿」查找并回听原话。已知总时长时，可在同一根进度条上跨文件定位。会话菜单中的「录制详情」显示上次手表清单的收取进度、已匹配音频时长、静音省略和采集轮次内的缺口。旧录音仍可回听，缺少证据的指标显示未知。

导入 SenseVoice INT8 后可一键转写整场已同步录音。离开页面后由安卓系统调度继续处理，手动暂停后可从已保存的约 15 秒窗口继续。搜索文字可跨分段回听，长按校订会保留识别原文。需要单独转写或导出时，打开录音右上角的「音频文件」，点对应文件的更多按钮。[手机端说明](apps/phone/README.md)

在手机文字稿中点模型名，可选 SenseVoice、Fun-ASR-Nano、Qwen3-ASR-0.6B 或 Qwen3-ASR-1.7B。Fun-ASR 与两个 Qwen 模型均可在兼容的骁龙手机上选择 CPU、Adreno GPU 或 Hexagon NPU。每种模型与处理器组合分别保存转写进度和校订。[手机模型与处理器](apps/phone/README.md#选择端侧转写模型)

整场文字稿右上角的「发言人」可在手机离线区分本次录音中的不同声音。选择 Fun-ASR 时使用 FSMN-VAD 与 CAM++，选择 SenseVoice 或 Qwen 时使用 pyannote 与 ERes2Net。可以回听并手动命名，一个转写窗口里有多种声音时会一并标出。[模型导入与使用](apps/phone/README.md#区分发言人)

文字稿按发言人和停顿自然分段，可点原文回听，拖动刻度快速定位，并在文字稿菜单中选择导航放在左侧或右侧。开启手机实验功能后，可用同一段文字比较 Meeting Agent 和原版 Gemma 4 E2B 的纪要，也可分别用两代 EmbeddingGemma 构建离线文字索引。一加 12 的纪要与索引实验默认优先 Hexagon NPU，支持 CPU 对照及回退。[文字稿实验与模型导入](apps/phone/README.md#文字稿实验)


手表端另有独立运行的 [Watch5 卸载探针](tools/probes/watch-offload/README.md)和 [RNNoise CPU/GPU 对照](tools/probes/rnnoise-gpu/README.md)。

两端升级后，在手机打开会话，点「本次录音的标记」即可跨分段回听。落在已省略静音中的标记单独标出。能匹配音频哈希及时间轴时，文字稿显示录制时刻。旧录音仍可按片段播放和转写。

- PCM 按约 128 ms 批量读取；编码器输入较小时拆分批次，停止时先送完已读数据，再送 EOS。
- AudioRecord 缓冲取一秒音频与系统最小缓冲两倍中的较大者；文件写入使用 32 KiB 缓冲。
- 实验设置中的「PCM 直读编码器」允许支持直接读取的采集后端将 PCM 写入 MediaCodec 输入缓冲区。单次读取受编码器容量限制，可在录音日志中查看实际启用或回退情况。
- 同时开启「静音时暂停写入」和「PCM 直读编码器」时，录音使用普通 PCM 缓冲，以便在编码前判定音量并保留恢复时的前一秒。日志记录 `direct_pcm_fallback_reason=quiet_gate`。Ogg 缓冲复用仍可独立使用。
- 「复用 Ogg 缓冲（Opus）」会复用已写出的音频包和 Ogg 页缓冲，分段预热所需的包保留到使用结束。两个开关默认关闭，可分别启用，下次开始录音时生效。
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
  phone/             # 安卓手机接收、播放与导出
  desktop/           # PC 端预留，尚未实现
packages/contracts/  # 分段元数据与流式传输契约
tools/              # 构建、音频校验与设备探针
docs/               # 架构与变更记录
```

依赖方向为 `app → application → domain`。Android SDK、MediaCodec、文件和 SharedPreferences 留在 app 适配层；`bootstrap/AppGraph` 负责组装。保留原应用 ID 和 Android 组件入口。详见 [架构说明](docs/architecture.md) 和 [手表能力适配与无 Google 模块构建](docs/watch-backends.md)。

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

[GitHub Actions](https://github.com/Bardielz/koememo/actions) 在推送 master、提交 PR、推送 `v*` 标签或手动触发时执行：Python 测试 → APK 构建 → Kotlin 单元测试 → Android Lint。需要同时安装手表端与手机端时，下载同一次构建的 `koememo-paired-debug`。单独的手表测试包和校验文件在 `koememo-ci-debug` 中，测试报告在 `verification-reports` 中。

推送与 `versionName` 一致的标签（例如 `v0.1.4`）会在检查通过后创建预发布 Release 并附上 CI APK 与校验信息；已有同名附件不会覆盖。普通分支构建不会发布 Release。工作流不上传 GitHub Packages，因为此项目交付的是手表安装包，而非 Maven 依赖库。

**签名区别：** `ci-debug.apk` 使用本次运行生成的临时 debug 密钥，可能无法覆盖本机安装的版本，也不保证不同 CI 构建之间能直接更新。标为 `local-debug.apk` 的附件使用原开发机 debug 签名。遇到签名冲突先导出录音，不要直接卸载。密钥不进入仓库或工作流附件。

## 开发记录

见 [变更记录](docs/CHANGELOG.md)。Git 历史从当前 0.1.4 源码快照开始，按应用、工具、文档拆分导入；之前的版本演进以变更记录描述，不伪造历史提交。

参考：[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)、[Android 编码格式](https://developer.android.com/media/platform/supported-formats)、[麦克风前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types)。

## 开发工作流

所有后续特性从最新 `origin/master` 创建 `feature/<name>` 分支；修复使用 `fix/<name>`，维护使用 `chore/<name>`。通过 Git 提交、推送分支和 PR 合并到 `master`，不直接在主分支开发。具体步骤见 [贡献指南](CONTRIBUTING.md)。
