# 架构

录音是当前唯一实现的业务域。采用三个 Gradle 模块，避免将每个类都拆为模块。

- `recording:domain`：会话状态、配置约束与停止保护规则，不依赖 Android。
- `recording:application`：开始、暂停、结束、恢复和设置用例；定义持久化、控制及播放端口，只依赖 domain。
- `app`：presentation 呈现状态；infrastructure 实现 Android 音频、文件、偏好和服务；bootstrap 组装依赖。

会话允许暂停后继续；结束会清除当前会话引用，不删除文件。Android 服务负责线程、唤醒锁和前台通知，用例负责会话及持久化状态。原组件类保留为兼容入口。录音日志格式、应用 ID 与偏好键保持兼容。

手机和桌面目录仅作扩展位置，不包含空壳应用或虚构的共享协议。未来协议确认后再添加到 packages/contracts。

## 参考与取舍

- [Now in Android](https://github.com/android/nowinandroid)：参考模块边界和平台依赖隔离，并非直接照搬其结构。
- [Android Showcase](https://github.com/igorwojda/android-showcase)：参考按功能组织和依赖方向。
- [Kotlin DDD 示例](https://github.com/ttulka/ddd-example-ecommerce-kotlin)：参考聚合及应用用例的职责分离，不引入服务端框架。

当前没有跨域事务、消息总线和云端同步需求，因此不引入相关抽象。编码器与 Android 生命周期仍需实机验收，模块测试不能代替录音质量及耗电测量。
