# 仓库更新

仓库更新是一款用 Kotlin、Jetpack Compose 和 Miuix 构建的 Android 应用，用于订阅公开 GitHub 仓库的 Release。首页集中显示各仓库的版本号、发布时间和未读更新，点开即可阅读 Markdown 更新说明。

- 粘贴仓库链接或输入 `owner/repo` 添加订阅。
- 支持手动刷新、可调的后台检查间隔和新版本通知。默认每 6 小时检查一次正式版。
- 可在设置中独立配置 HTTP 代理和 GitHub Token。留空时直连 GitHub 并匿名查询公开仓库。Token 在本机加密保存。

## 安装与构建

应用要求 Android 8.0 或以上。推送到 `main` 会自动构建签名 APK，也可在 [GitHub Actions](https://github.com/Mo-SeTian/RepoUpdates/actions/workflows/android-release.yml) 手动运行工作流。从成功运行的 Artifacts 下载 APK，文件名格式为 `仓库更新_版本号_realse_MD5前8位.apk`。

APK 打包完成后，工作流会通过 MessagePush 把产物和构建说明发到 `RepoUpdate` 频道。此步骤读取 Actions Secrets 中的 `MESSAGE_PUSH_URL` 和 `MESSAGE_PUSH_API_TOKEN`。

本地开发需要 JDK 17 和 Android SDK 36。运行 `./gradlew :app:assembleDebug` 构建调试版。

## 致谢

- [Release Tracker](https://github.com/MasoudFallahpour/ReleaseTracker) 提供了定期检查 GitHub Release 的实现参考。
- [OpenHub](https://github.com/nikakvo/OpenHub) 提供了更新流和 Release 详情的交互参考。
- [Miuix](https://github.com/compose-miuix-ui/miuix) 提供了界面组件与 HyperOS 风格。
