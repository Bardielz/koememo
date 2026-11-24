# Galaxy Performance SDK 1.0.0 官方示例导读

对照官方 SDK 包内的 `Samples/Performance/` 与独立 `Samples.zip` 的 `Performance/`，独立包的 49 个文件均可在 SDK 包中找到逐字节相同的副本。阅读一份即可。工程采用 Eclipse/ADT 布局，手写入口在 `src/`，编译产物在 `bin/`，生成的资源索引在 `gen/`。[三星下载页](https://developer.samsung.com/galaxy-performance/overview.html)

| 源文件 | 示例内容 | KoeMemo 的取舍 |
|---|---|---|
| `src/com/test/RunPerfSdk.java` | 直接引用 SDK，保存初始化结果，展示自定义参数和限时 CPU、总线组合请求。 | 探测采用直接调用，单独记录初始化布尔值。后续资源控制可参考此例，在独立对照实验中验证。 |
| `src/com/samsung/sdk/perfsdktestapp/MainActivity.java` | 交互式测试台，可输入等级和超时、组合预设、发起与停止请求；打印 SDK 版本。 | 可参考实验交互设计。版本号标识库版本，判断设备能否初始化需读取 initialize 的返回值。 |
| `src/com/test/Sample.java` | 尝试从系统框架路径加载 perfsdk.jar 并通过反射查找方法。 | 该路径依赖具体固件。本项目使用显式打包的官方库，便于复现并区分缺库和初始化失败。 |

SDK 包的 `Libs/perfsdk-v1.0.0.jar` 与示例自己的 `lib/perfsdk.jar` 文件内容有差异。KoeMemo 本地实验构建指定前者，避免从示例工程误取依赖。示例的 CPU/GPU/总线控制按钮会改变测试条件，探测初始化只需调用 SPerf.initialize，无需启动这些负载。
