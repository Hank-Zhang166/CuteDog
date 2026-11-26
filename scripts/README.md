# 可复用构建工具

构建使用 JDK 17、Android SDK 35、build-tools 35.0.0、NDK 28.2.13676358 和 CMake 3.22.1。Gradle Wrapper 固定为 8.11.1，下载的分发包经过 SHA-256 验证。

普通开发机（Python 3.11 或以上版本）：

```sh
python3 -m pip install -r scripts/artwork-requirements.txt
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest # 需要启动的设备或模拟器
```

在本项目的 Linux 云工作区：

```sh
python3 -m pip install -r scripts/artwork-requirements.txt
python3 scripts/setup-android.py /workspace/tooling
python3 scripts/cloud-gradle.py --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 scripts/package-apk.py
```

`setup-android.py` 从官方 HTTPS 下载固定版本 Temurin JDK 17、Android command-line tools 19.0，在解压前验证已核对的 SHA-256；随后使用 sdkmanager 安装官方 SDK 包。工具位于仓库外的 `/workspace/tooling`，不写入系统目录。脚本的 SDK 安装步骤接受所需开发工具的许可证。已安装工具会复用。

`cloud-gradle.py` 把云环境现有代理交给 Java，使用系统维护的 Java CA 信任库，不关闭 TLS 验证、不复制身份令牌、不记录密钥。它还为 Android 和 Gradle 指定可写目录。正常本地开发直接使用 `./gradlew` 即可。

`package-apk.py` 必须在实际构建成功后运行；它使用 `apksigner` 验签及 `aapt` 读取 APK 真实清单，生成 `artifacts/` 下的 APK、SHA-256、构建信息和验签记录。此目录默认忽略，不误提交临时构建数据。测试版使用调试证书，不能视为生产签名版本；不同机器的调试证书可能不同，跨证书安装需要先卸载并会删除本地数据。

GitHub Actions 包含单元测试、Lint、APK 构建，以及 Android 15（API 35）的设备测试。推送 `v*` 标签触发测试版 Release 发布。实际结果见对应工作流运行记录。

检查已发布的 APK 时，在 Actions 手动运行 `Published APK UI validation`，按[设备验收说明](../docs/final-apk-validation.md)填写下载地址、SHA-256 和源码提交。截图、测试报告和运行日志随该次运行的 Artifacts 提供。

当前小狗的整犬图序、方向与衔接证据由 Cubism 动作工具生成。重建步骤见[动作集成说明](../art/live2d/review2/integration/README.md)。

APK 内置 Qwen3-TTS 0.6B Base、音频编解码器、64 位 CPU 朗读引擎和审阅版小萨的 A 声线参考。CMake 首次构建按固定提交和 SHA-256 获取引擎源码。手机首次朗读会在本机准备内置模型，随后可直接合成新回复。[语音实现说明](../docs/offline-pet-speech.md)

默认的设备验证在单台干净的 64 位测试模拟器上运行。要单独复验语音，可在安装应用与测试 APK 后运行下列命令。动作预览版的测试包名为 `com.cutedog.app.motionpreview.test`。测试使用内置 A 声线合成两句不同的 24 kHz 音频，记录得到首句和次句 PCM 的耗时，并验证播放打断、听写、回环 HTTPS 和跨窗口记录。

```sh
adb shell am instrument -w \
  -e class com.cutedog.app.voice.PetSpeechDeviceTest,com.cutedog.app.voice.OfflineVoiceDeviceTest#mandarinAudioToHttpsAndSpokenReply \
  com.cutedog.app.test/androidx.test.runner.AndroidJUnitRunner
```
