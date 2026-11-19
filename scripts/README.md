# 可复用构建工具

本机需要完整的 JDK（不只是 Java 运行时）、Android SDK 35 和 build-tools 35.0.0。Gradle Wrapper 已包含并固定为 8.11.1，分发包强制 SHA-256 验证。

普通开发机：

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest # 需要启动的设备或模拟器
```

在本项目的 Linux 云工作区：

```sh
python3 scripts/setup-android.py /workspace/tooling
python3 scripts/cloud-gradle.py --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 scripts/package-apk.py
```

`setup-android.py` 从官方 HTTPS 下载固定版本 Temurin JDK 17、Android command-line tools 19.0，在解压前验证已核对的 SHA-256；随后使用 sdkmanager 安装官方 SDK 包。工具位于仓库外的 `/workspace/tooling`，不写入系统目录。脚本的 SDK 安装步骤接受所需开发工具的许可证。已安装工具会复用。

`cloud-gradle.py` 把云环境现有代理交给 Java，使用系统维护的 Java CA 信任库，不关闭 TLS 验证、不复制身份令牌、不记录密钥。它还为 Android 和 Gradle 指定可写目录。正常本地开发直接使用 `./gradlew` 即可。

`package-apk.py` 必须在实际构建成功后运行；它使用 `apksigner` 验签及 `aapt` 读取 APK 真实清单，生成 `artifacts/` 下的 APK、SHA-256、构建信息和验签记录。此目录默认忽略，不误提交临时构建数据。测试版使用调试证书，不能视为生产签名版本；不同机器的调试证书可能不同，跨证书安装需要先卸载并会删除本地数据。

GitHub Actions 包含单元测试、Lint、APK 构建，以及 API 26 / 35 的设备测试。推送 `v*` 标签触发测试版 Release 发布；工作流文件存在不代表远程运行已成功，需查看真实运行结果。

检查已发布的 APK 时，在 Actions 手动运行 `Published APK UI validation`，按[设备验收说明](../docs/final-apk-validation.md)填写下载地址、SHA-256 和源码提交。截图、测试报告和运行日志随该次运行的 Artifacts 提供。

当前小狗的整犬图序、方向与衔接证据由 Cubism 动作工具生成。重建步骤见[动作集成说明](../art/live2d/review2/integration/README.md)。
