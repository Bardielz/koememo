# 贡献与 Git 工作流

`master` 是集成主分支和发布基线；旧 `main` 仅保留历史，不再用于开发。

## 开始新任务

先确认工作区状态，妥善保留未提交改动，再从远端最新主分支创建任务分支：

```sh
git status
git fetch origin
git switch -c feature/<name> origin/master
```

特性用 `feature/`，修复用 `fix/`，维护用 `chore/`。同一任务的后续调整沿用已有分支。

## 提交与评审

按逻辑拆分提交，清楚说明改动目的。运行相关检查后推送分支，创建目标为 `master` 的 PR，说明行为变化、验证结果和已知限制。CI 通过且获得合并授权后再合并。不要直接向主分支提交，不强推共享分支，不为通过 CI 跳过失败检查。

```sh
git push -u origin feature/<name>
gh pr create --base master
```

## Wear OS 验证

在仓库根目录执行：

```sh
apps/watch/wearos/gradlew -p apps/watch/wearos assembleDebug testDebugUnitTest :recording:domain:test :recording:application:test lintDebug
python3 -m unittest discover -s tools/audio -p 'test_*.py' -v
```

仅文档或分支触发条件变更不必重复完整 Android 构建，但必须检查差异和引用。录音行为变化仍需与实机验收区分。

发布标签在已合并到 `master` 的提交上创建，并须与应用版本一致；发布需要单独授权。密钥、录音和本机配置不进入 Git。
