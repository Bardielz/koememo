# 安卓手机端

在仓库根目录执行 `apps/watch/wearos/gradlew -p apps/watch/wearos :phone:assembleDebug :app:assembleDebug`。phone-debug.apk 安装到安卓手机，app-debug.apk 安装到配对手表，两端需要相同 applicationId、签名与可用的 Google Play services。使用同一次构建生成的两个 APK。本分支保留 0.1.6-dev 的录音诊断，两个 APK 版本均为 0.1.7-dev。

手机点击“从手表同步”。已关闭片段保存在手机应用私有目录。点一次录音即可进入回听页。手机前台服务负责同步。返回桌面或重新打开页面可以继续当前任务。允许通知后也可从通知暂停。临时断线后，手机按 2、4、8 秒间隔重试，接着已有临时文件继续下载；主动暂停后需手动继续。手表保留源文件。第一版由用户在手机前台发起，支持一台可达手表。

录音库按开始日期排列，可输入日期（例如 `11-20`）或名称查找。打开后点「播放这次录音」，手机会接着播放已收到且可用的原音。总时长已知时可拖动整场进度条；这个时间只累计可回听的音频。旧录音缺少完整时间轴时，跨录制轮次的先后顺序可能无法还原。点「文字稿」查找原话，返回可继续从刚才的位置播放。

需要导出或单独处理文件时，从右上菜单进入「音频文件」。可按编号、时间或文件名筛选；有可靠映射时显示录制时间，旧录音显示存入时间。点文件右侧的更多按钮导出或查看本段文字稿。录音页可查看标记和运动与心率，右上菜单提供录制详情。上次手表清单记录了获取时间和当时已关闭的文件；分子是手机已收到且与该清单哈希匹配的数量。时间轴可以稍后到达。已匹配音频的时长来自编码时间戳，静音省略扣除恢复时补回的音频。采集轮次内的未覆盖时长按最终采样数减去省略区间与本地可定位音频计算；两次采集之间的暂停单独于此计算。缺少时间轴或最终采样数时显示未知。

首次从[官方模型目录](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/main)取得 `model.int8.onnx` 和 `tokens.txt`，放入同一文件夹后在应用内选择该目录。应用校验两个文件的 SHA-256，此后在手机 CPU 离线识别。点「开始／继续整场转写」处理任务执行时已经同步的片段，新到的片段可再次点按补齐。一次运行一个任务，复用同一模型。返回桌面、切换页面后可继续；系统可能延后或中断任务，允许执行时从已保存的约 15 秒窗口继续。手动暂停后由用户继续。安卓强行停止应用后需重新打开并手动继续。

已保存的窗口可跨文件搜索，点文字从该窗口起点回听。长按文字可校订或恢复识别原文；校订绑定来源文件和内容哈希。固定窗可能切断词语，暂未提供逐词时间戳。模型缺失时可以查看已有结果和播放录音。音频和识别文字存放在手机应用私有目录。

## 选择端侧转写模型

打开一场录音的「文字稿」，点当前模型名，选择模型和处理器。首次使用时，点「导入模型」选择含下表两个文件的目录。应用核对 SHA-256 后将权重保存到私有目录。同一模型的三种处理器共享权重。

| 模型 | 本地文件 | 手机后端 |
| --- | --- | --- |
| SenseVoice | `model.int8.onnx`、`tokens.txt` | CPU |
| [Fun-ASR-Nano-2512](https://huggingface.co/FunAudioLLM/Fun-ASR-Nano-GGUF/tree/46e849502a867080d66d351b8dfb1018b607e509) | `funasr-encoder-f16.gguf`、`qwen3-0.6b-q8_0.gguf` | CPU、Adreno GPU、Hexagon NPU |
| [Qwen3-ASR-0.6B](https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/tree/b98bf4381df3b400b929dcaff0dc83b6dafe221b) | `mmproj-Qwen3-ASR-0.6B-Q8_0.gguf`、`Qwen3-ASR-0.6B-Q8_0.gguf`，合计约 1.02 GB | CPU、Adreno GPU、Hexagon NPU |
| [Qwen3-ASR-1.7B](https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/tree/3b2c29585c2180452ccb14d3b6b58127f352400d) | `mmproj-Qwen3-ASR-1.7B-Q8_0.gguf`、`Qwen3-ASR-1.7B-Q8_0.gguf` | CPU、Adreno GPU、Hexagon NPU |

两类 GGUF 模型使用随 APK 安装的 ARM64 Snapdragon 运行库。音频编码器在 CPU 上运行，所选处理器承担语言解码。Fun-ASR 的 Adreno 路径使用 14 个解码层，其余层在 CPU 上运行。一加 12 的对照中，全层 Adreno 卸载曾将清晰语音识别成重复标点。应用会检查所选加速器的层分配，初始化失败时显示错误，可手动改选 CPU。一次任务复用已加载的权重，每个 15 秒音频窗口独立解码。

模型和处理器在任务开始时固定。各组合各自保存文字、校订与进度，切回 SenseVoice 可继续使用原有结果。同一会话中，使用相同分人模型的处理器组合共用发言人编号和名称。

构建手机 APK 需要 Python 3、Git 和 Android NDK r27c（`sdkmanager 'ndk;27.2.12479018'`）。首次构建会获取固定版本的 [Fun-ASR 源码](https://github.com/QwenAudio/Fun-ASR/tree/5df2b86c73d19fb9afca24411e4a052d9113e619/runtime/llama.cpp) 和 [llama.cpp Snapdragon 运行库](https://github.com/ggml-org/llama.cpp/releases/tag/b12830)，校验后缓存在 Gradle 用户目录。模型权重另行导入。

## 区分发言人

打开一次录音 →「文字稿」，选定转写模型后点「发言人」。首次使用时，把对应的两个文件放在同一文件夹，点「导入发言人模型」。应用校验 SHA-256。

**Fun-ASR-Nano** 使用官方 FSMN-VAD 划出语音区间，CAM++ 按 1.5 秒窗口、0.75 秒步长提取声音特征。手机在本地做谱聚类，再把同一会话中匹配的声音关联到已有编号。发言人分析在 CPU 上运行，文字转写仍可选 CPU、GPU 或 NPU。按上游短音频规则，一批少于 20 个有效声纹窗口时会归为一人，短对话可能漏掉换人。[Fun-ASR 官方组合示例](https://github.com/QwenAudio/Fun-ASR#speaker-diarization) · [FunASR 聚类实现](https://github.com/modelscope/FunASR/blob/main/funasr/models/campplus/cluster_backend.py)

- 下载 [fsmn-vad.gguf](https://huggingface.co/FunAudioLLM/fsmn-vad-GGUF/blob/6840bae4c5c92ee8c04faaf4db23dd0105098d7f/fsmn-vad.gguf)。
- 下载 [3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx)。

**SenseVoice 和 Qwen** 共用以下模型及同一会话下的既有分人结果和手动名称。

- 从 `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` 解压 `model.int8.onnx`。[分段模型](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models)
- 下载 `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`。[声音特征模型](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models)

点「区分本次发言人」后，手机在本地分析已收到的原音。可暂停，返回此页后可从已保存的音频继续。分析和转写可以分别进行。点发言人旁的「回听」核对，点「命名」设置本次录音内的名字。返回文字稿即可看到标签。清空名字恢复匿名编号。

手机按最多 60 秒一批分析 16 kHz 单声道音频，在同一会话内匹配编号。Fun-ASR 使用聚类后的声音特征中心，pyannote 路线使用单人发言区间的声音特征。噪声、轮次过短、重叠或声学环境变化可能使一个人被拆为多人，或不同人被归到同组。文字按约 15 秒一窗显示，多人同窗会列出所有发言人。词级归属和真实腕录上的分人错误率仍待验证。

一加 12 的 Fun-ASR 自测将两位说话人各自的五条不同录音交替拼接。十条录音的中点均标到了对应的同一人，静音样本没有生成发言区间。另一次将同一段双人英语录音原样循环三遍的测试产生了九个编号，重复音频可能造成过度分人。[公开语音样本](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models)

## 文字稿阅读

整场文字稿按发言人和停顿分段。能定位到同一人且相邻的原文会连起来。换人、至少两秒的可观测停顿、时间不连续和较长的完整句会另起一段。多人同窗显示全部已知名字。尚无词级时间时，每条原文保留采集窗口的归属精度。点文字可回听对应窗口，长按可校订。导航短线表示段落位置，默认在左侧。可在文字稿右上菜单的「段落导航位置」切换左侧或右侧，选择对后续录音生效。可点击或拖动，滚动文字时当前刻度同步变化。搜索文字或发言人会更新可跳转的范围，读屏与方向键也可操作段落导航。

交互参考了 [BBC Transcript Editor](https://github.com/bbc/react-transcript-editor) 的时间定位编辑、[WhisperX](https://github.com/m-bain/whisperX/blob/main/whisperx/diarize.py) 的时间区间归属，以及 [AndroidFastScroll](https://github.com/zhanghai/AndroidFastScroll) 的拖动定位。手机端直接使用已有 Android 列表和会话时间轴。

## 文字稿实验

打开手机「实验功能」总开关，在整场文字稿的右上菜单进入「文字稿实验」。模型按需从本地目录导入，校验 SHA-256，使用随 APK 安装的 ARM64 CPU 运行库。实验处理打开页面时已保存的文字和人工校订。要纳入之后的转写或校订，重新进入页面。

| 用途 | 从指定版本下载的文件 |
| --- | --- |
| Meeting Agent 中文 | [根目录 Q4_0 GGUF 和同目录的 system_prompt.txt](https://huggingface.co/Luigi/gemma-4-E2B-meeting-agent-zh/tree/bc219ef2c009075285d58fa0296ad6c0588a6b3f)，均放入同一个导入目录 |
| Gemma 4 E2B 原版 | [gemma-4-E2B_q4_0-it.gguf](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/tree/675cff42a74c774d6cb76f76d8eacb49b48c9b93) |
| EmbeddingGemma 1 文字编码器 | [embeddinggemma-300M-Q8_0.gguf](https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF/tree/0f741b5a6585bd53aeb15cd1372c56f2a0f65e12) |
| EmbeddingGemma 2 文字编码器 | [embeddinggemma-2-Q8_0.gguf](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/tree/6ea3e3a309d12a4779cfda98b8c19800d2d5da71) |

**比较两种纪要。** 选择同一文件内的一组文字，手机依次加载两种模型，用相同的原话、时间、发言人提示和贪心解码提取要点。页面分别保存两份输出和耗时。引用时间与输入匹配时可点回原音。输出格式或引用无效时展示原始输出供核查。时间匹配只证明可以定位，内容和责任人仍需回听确认。

**建立检索索引。** 两代模型分别对整场已保存的文字窗口计算 768 维归一化向量，存入手机私有 SQLite。每条记录携带会话、转写引擎、模型指纹、文件名及音频哈希、窗口起止时间和有效文字哈希。同一模型重建时只计算有变化的窗口，完整写入后才替换旧索引。当前阅读页仍提供即时文字搜索。后续语义检索须用同一模型对查询加 `task: search result | query: ` 前缀编码，在该模型文档向量上按余弦相似度排序。二代本次接入文字编码，原音向量需要另行接入音频投影器。


## 录音上下文

同步后进入会话，点「运动与心率」，可以查看整个会话内可对齐的 15 秒窗口。点「按活动强度」会把手腕活动较强的窗口排在前面，点窗口回听。进度条拖动、会话标记和转写窗口都使用对应片段的播放偏移。每窗显示腕部活动强度、有效心率范围、有效与收到的心率样本数，以及缺少有效读数的秒格数。没有心率样本和有回调但全部无效分别显示。此功能需要两端支持上下文同步，且手表录音时已开启运动和心率实验。

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

重连时手机先处理带断点的分段，再接收缺失文件，最后核对本地完整文件。支持回执目录的手表会报告当前手机已确认的哈希。手机重新校验本地 SHA-256 后可以复用对应回执；文件缺失或校验失败时仍会接收。旧设备继续使用原通道逐段确认。界面中的序号表示本次处理顺序。
