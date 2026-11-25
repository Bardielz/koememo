# RNNoise GPU 对照探针

以 Xiph [RNNoise v0.1](https://github.com/xiph/rnnoise/tree/v0.1) 的公开模型和 C 实现为基准。此目录用于 adb 离线实验。输入为文件，日常录音 APK 沿用原有音频管线。

CPU 对照逐帧调用上游 `rnnoise_process_frame`。GPU 路径在 CPU 做相同的特征提取、基音滤波与波形合成，将 42 维特征送入 OpenGL ES 3.1 compute shader，计算整个循环网络、22 个频带增益和 VAD 概率。每批最多 12 个 10 ms 帧，保留跨批次的三层 GRU 状态，跳过上游判定为静音的帧。权重一次上传；每批回读预测值。在线使用这样的批量大小会增加最多约 110 ms 的等待时间。

## 复现

需要 Android SDK platform 35、build-tools 35.0.0、platform-tools、JDK 17+、Python 3、zip，以及目标 ABI 的 NDK clang（环境变量 `ANDROID_CC`）。Watch5 也可用 Zig 的 armv7 静态 Linux 目标（`ZIG` 指向 zig）。输入需为有声的 48 kHz 单声道 PCM16LE，样本数为 480 的整数倍。CPU、GPU 与对照指标均使用同一个文件。

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
export ANDROID_SERIAL=watch-adb-serial
out=$(mktemp -d)
tools/probes/rnnoise-gpu/build.sh "$out"
tools/probes/rnnoise-gpu/run.sh "$out" /path/to/input.pcm
```

构建脚本按 SHA-256 校验上游源码归档，从该版本生成权重和着色器。运行脚本先逐样本对比 float 波形，同时校验每个有声帧的增益与 VAD；容差为波形对照信噪比至少 60 dB、预测绝对误差最多 0.001。然后交替顺序跑三轮计时。计时包括文件读写、频谱处理、GPU 上传与回读以及原型的进程间通信，从开始读音频到输出文件关闭。EGL 和模型初始化在计时前完成。该时间衡量离线处理吞吐，续航需要另做等时长、不充电的实时录音实验。

上游许可见 [LICENSE.rnnoise](LICENSE.rnnoise)。
