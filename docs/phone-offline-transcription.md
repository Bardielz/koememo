# 安卓手机端离线转写方案

单文件 SenseVoice INT8 转写、区间回听与进度保存已接入。自动队列、VAD 和多模型质量评测待实现。第一版让用户在手机录音列表选中一个已同步片段，点「本地转写」，看到带时间的文字，点一句即可回听。手表继续负责采集和保存。以每天 8～12 小时录音为容量目标，模型选型由一加手机上的真实腕部录音对照决定。

## 模型选择

| 用途 | 首轮选择 | 选择依据 |
| --- | --- | --- |
| 中文默认候选 | sherpa-onnx + SenseVoiceSmall INT8 | 有 Android 和离线文件示例，覆盖普通话、粤语及英语，适合先测中文夹英文。固定具体模型版本、词表和哈希。[官方示例](https://k2-fsa.github.io/sherpa/onnx/sense-voice/) |
| 质量对照 | whisper.cpp + Whisper base 多语言版 | 能在 Android CPU 上运行。用同一批 PCM 和相同的语音区间测远讲、夹英文和专名；若 base 达不到质量目标，再试 small。[Android 实现与量化支持](https://github.com/ggml-org/whisper.cpp) |
| 后续候选 | sherpa-onnx + 中文 Paraformer | 可复用同一套解码与推理适配层，适合补测特定口音。[模型清单](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/index.html) |

建议模型包与 APK 分开，首次由用户下载或导入，展示下载体积、版本和所需空间，校验成功后原子启用。预装好模型后，飞行模式下应能完成转写和回听。分发前按实际使用的权重、VAD 模型和运行库分别核对许可与署名。[SenseVoice 项目许可说明](https://github.com/FunAudioLLM/SenseVoice) · [Whisper 权重许可](https://github.com/openai/whisper#license)

## 从录音到可回听文本

1. **选择输入。** 手机同步模块按现有协议校验 SHA-256、保存正式文件并发送回执。转写读取已完成的 AAC/ADTS 或 Opus/Ogg，排除下载中的部分文件。手动模式可以处理已有录音；未来开启自动模式时，由独立队列发现新增及漏入队的文件。转写失败不影响同步回执。
2. **统一音频。** 流式解码，按解码器报告的采样率和声道转换成单声道 16 kHz PCM，覆盖现有 16、24、48 kHz 配置。记录每块音频在原文件中的位置，避免生成全天大小的临时 WAV。
3. **检测语音。** 用 VAD 找出语音区间，给句首句尾留余量；连续长语音按模型输入上限切窗，相邻窗保留重叠并去重。腕上远讲与轻声容易漏检，实验设置应允许关闭 VAD 做对照。纯静音可正常完成并显示「未检测到语音」。
4. **识别与回听。** Kotlin 应用层通过可替换的识别接口提交 PCM、语言配置和取消信号，取得文字及可用的时间信息。VAD 区间加分段内偏移生成原音频时间；只有模型输出支持时才展示更细粒度时间戳。首版按语音区间定位回听。约五分钟文件边界保留各自的原始位置，跨文件展示按同一次录音的顺序排列。
5. **保留扩展能力。** 后续可把说话人分段的时间区间与文本对齐，为总结提供可回听的来源。VAD 只判断哪里有人声；重叠说话的分离、说话人身份匹配各自需要独立验证。

## 队列、恢复与耗电

- 使用手机本地数据库。以音频 SHA-256、识别引擎与模型版本、配置指纹组成任务唯一键。配置包含语言、重采样、VAD 和切窗版本；同一录音重新同步时复用结果，不同录音位置通过来源记录关联。模型升级保留旧转写，按用户选择重算。
- 一个任务只由一个工作线程认领，每完成一个语音区间，在同一事务保存文本、原文件起止时间和下一处理位置。进程被终止后回收失效任务，从最近已提交区间继续。重复交付按任务和区间去重。手动暂停持久化，下次打开仍保持暂停；缺模型、源文件移除或空间不足时给出具体原因。
- 首版先提供用户可见的「转写、暂停、继续」。随后增加可选的充电批处理，按系统调度窗口分批执行，窗口结束即保存进度。一次只加载一个模型，连续处理时复用实例，空闲时释放。鉴于一加曾在亮屏后台冻结同步进程，验收须覆盖后台中断后的正确恢复；全天后台吞吐需要另测。
- 录音、文本与索引默认保存在应用私有目录，转写只走本机。沿用手机实验统计总开关，记录模型版本、原音频与人声时长、解码/VAD/推理耗时、峰值内存、温度与充电状态。性能日志排除音频内容和转写正文。

## 真机验收与选型

| 项目 | 方法与判定 |
| --- | --- |
| 格式与时间轴 | 两种编码 × 三种采样率分别验证，覆盖静音、短尾段、持续长句和跨文件的发言。点首、中、末位置回听能落到对应语音。 |
| 中文质量 | 准备 30～60 分钟人工校对的 Watch5 录音，含近讲、自然垂腕、衣物摩擦、多人交谈和中英混说。按场景报告中文字符错误率、轻声整句漏识别、专名错误、静音误出字。两模型用相同区间；另做一次 VAD 开关对照。 |
| 速度与耗电 | 在同一手机、相同样本和温度区间重复测量。分别报告每小时原录音的总处理时间、每小时人声的推理时间、人声占比、峰值内存。拔电测整机电荷变化并扣除等时长空闲基线，结果标为估算。充电长跑另测热降频与队列清空时间。 |
| 恢复与隐私 | 在已有模型、飞行模式下从本地录音完成转写；处理中切后台、熄屏、终止进程并重开。核对已提交文本无重复或缺失，手动暂停保持有效。另在连接手表时确认转写不阻塞同步回执。 |
| 全天容量 | 用实际一天 8～12 小时的录音分布跑积压，记录总人声时长、完成时间、电量和温升，再决定默认模型、并发及是否默认启用自动处理。 |

实施顺序为单文件手动转写与回听 → 持久化队列与断点恢复 → 双模型真机评测 → 可选充电批处理。首版完成条件是断网可用、文本能定位到原录音、进程中断后可继续；默认模型和耗电预算以实测确定。

## 手机双引擎选型（2026-11-26）

目标是用同一段手表录音，独立运行专用 ASR 和音频语言模型，保存两份可回听的结果。KoeMemo 当前实现了 SenseVoice INT8。下表其余引擎属于候选。

| 项目 | 手机上可以复用的部分 | 优先验证的问题 |
| --- | --- | --- |
| SenseVoiceSmall INT8 / sherpa-onnx | 当前 Kotlin 文件识别路径。已用同一句公开普通话在一加 PJD110 验证 AAC 和 Opus 各 16、24、48 kHz，以及区间保存后续跑。[官方模型与示例](https://k2-fsa.github.io/sherpa/onnx/sense-voice/) | 5.6 秒样本的六种输入均出字，AAC 和 Opus 的结果有一字差异。全天吞吐、远讲漏词和耗电仍要独立评测。 |
| Vosk / Kaldi | 小型中文模型 vosk-model-small-cn-0.22，官方列出 42 MB、Apache-2.0。Java 接口可增量喂 PCM、取最终结果，适合作为传统解码和小模型的基线。[模型目录](https://alphacephei.com/vosk/models) · [Android 示例](https://github.com/alphacep/vosk-android-demo) | 在低内存约束下对照中文字符错误率、专名、时间戳及每小时耗电。 |
| Whisper / whisper.cpp | Android JNI 样例可参考模型装载、PCM 输入与线程设置。多语 base 原版文件为 142 MiB，量化版权重另计。属于专用编码器／解码器模型。[Android 示例](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android) · [模型清单](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md) | 作为跨实现质量对照。中文须使用多语权重。关注静音误出字、远讲和推理耗时。 |
| Qwen3-ASR-0.6B INT8 / sherpa-onnx | 优先接入的小型音频 LLM。已有 Android 文件识别样例，sherpa-onnx 在 1.12.34 加入了 Java／Kotlin 配置。目前项目固定 1.12.20，需要先升级手机 AAR 并回归 SenseVoice。[模型卡](https://huggingface.co/Qwen/Qwen3-ASR-0.6B) · [框架变更](https://github.com/k2-fsa/sherpa-onnx/blob/master/CHANGELOG.md#11234) · [文件识别样例](https://github.com/k2-fsa/sherpa-onnx/blob/master/flutter/sherpa_onnx/example/example.md) | 固定语言、提示、分段和生成上限，评估是否改善夹英文和专名，同时统计无声误出字、重复和漏句。词级或字级回听需验证对齐器。[Qwen3-ForcedAligner](https://github.com/QwenLM/Qwen3-ASR) |
| Fun-ASR-Nano-2512 INT8 / sherpa-onnx | 约 0.8B，含语音编码适配器、LLM、embedding 和 tokenizer，有 ARM64 安卓示例。三个 INT8 ONNX 主文件合计约 994 MB，另需 tokenizer。[模型与 APK](https://k2-fsa.github.io/sherpa/onnx/funasr-nano/pretrained.html) · [模型包](https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30/tree/main) | 中文方言和口音对照。单独核对所选权重及转换包的分发许可；下载体积与峰值内存分别实测。 |

**最接近的应用实例是 Recly。** 它的安卓端读取录音，以 Silero VAD 划出语音区间，再交给 Qwen3-ASR。可参考其 Kotlin 引擎、工作任务和真机冒烟测试。应用代码标注 AGPL-3.0；KoeMemo 可独立实现相同的接口分工。[识别引擎](https://github.com/rokrokss/recly/blob/main/android/app/src/main/kotlin/app/recly/android/transcribe/QwenSpeechEngine.kt) · [后台任务](https://github.com/rokrokss/recly/blob/main/android/app/src/main/kotlin/app/recly/android/work/LocalTranscriptionWorker.kt) · [真机测试](https://github.com/rokrokss/recly/blob/main/android/app/src/androidTest/kotlin/app/recly/android/transcribe/LocalSpeechSmokeTest.kt)

**接入顺序。** 先将 LocalTranscription 中创建识别器和推理的部分抽成可替换接口；解码 AAC／Ogg、进度持久化和回听继续共用。第一轮并排提供 SenseVoice 和 Qwen3-ASR，按需导入模型，每次仅加载其中一个。每条结果以音频哈希、引擎和模型版本、参数与分窗版本区分，保留原文。两个引擎使用相同 PCM 和区间做配对比较。模型输出带有可靠时间信息时才展示更细的定位；LLM 听写、文本润色和说话人分段分别保存。

**验收顺序。** 先跑原有六种编码／采样率交叉用例和中断续跑，再用人工校对的腕部近讲、远讲、轻声、中英混说及纯背景录音做盲测。分别给出中文字符错误率、漏句和插入、峰值 PSS、实时因子（处理秒数除以音频秒数）和每小时录音的手机净耗电；单独记录冷启动与模型复用。按 12 小时积压、8 小时夜间处理的目标，整条流程平均实时因子须不高于 0.67，并需持续运行验证热降频。
