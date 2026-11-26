# CuteDog

CuteDog 是一只 Android 桌面萨摩耶。可以轻抚、喂零食、投球、提醒待办，也可以按住麦克风聊天。当前源码为 **0.2.1-preview.6**，支持 Android 8.0 及以上，使用豆包实时语音播放回答。

[已发布的 APK 和动作素材](https://github.com/Hank-Zhang166/CuteDog/releases/latest) · [观看 56 项动作与左右追球 MP4](https://github.com/Hank-Zhang166/CuteDog/releases/download/v0.2.1-preview.4/CuteDog-actions.mp4) · [可编辑的 Live2D 工程](https://github.com/Hank-Zhang166/CuteDog/releases/download/v0.2.1-preview.4/CuteDog-Live2D-project.zip)

![萨摩耶站姿](art/live2d/poses/Stand.png)

小狗住在桌面的悬浮窗中，日常走路或慢跑，追球有独立动作。共保留 56 项动作、4,150 张播放帧，以 24 fps 播放。[动作制作与校验](art/live2d/review2/integration/README.md)

## 使用

- 在「小狗」页授权悬浮窗并召唤桌宠。轻点头、腿或尾巴，小狗会用相应部位回应。按住再拖可以移动小狗。双击后，小狗旁边的浮动面板可以连续选择喂零食、玩球和聊天，轻点面板外收起。
- 双击菜单和聊天输入栏都有麦克风。按住说普通话，松手发送，滑出按钮取消。首次授予麦克风权限后要再按一次。你的语音会转成文字，和小狗的回复一起出现在「陪伴」和桌面聊天窗共用的历史中。
- 在「豆包实时语音」中填写语音 API Key。搜索使用独立的豆包搜索 Custom API Key。语音首次使用需确认将录音实时发给豆包。[配置和交互说明](docs/interaction-review.md)
- 在「事项」页创建待办并设置到期时间。小狗身旁只显示到点的待办提醒，直接勾选即可完成。通知权限与精确闹钟授权影响送达方式。[提醒与重复规则](docs/reminders.md)
- 「陪伴」可配置支持 OpenAI 兼容接口的 HTTPS 服务和自己的密钥。聊天需要联网，待办、桌宠和每日短句可离线使用。[模型服务与记忆设置](docs/ai-design.md)

## 构建

安装 JDK 17、Android SDK Platform 35、Build-Tools 35.0.0 和 Python 3.11 或以上版本。在 Windows 项目根目录运行以下命令。

```powershell
python -m pip install -r scripts/artwork-requirements.txt
.\gradlew.bat -I tools/preview/motion-preview.gradle testDebugUnitTest lintDebug assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。该配置的包名是 `com.cutedog.app.motionpreview`。覆盖升级需使用同一签名。默认 Gradle 配置的包名是 `com.cutedog.app`。[本版设备检查](docs/interaction-review.md)

## 制作资料

[原始设计参考](art/samoyed/) · [Cubism 编辑模型](art/live2d/editor/CuteDog.cmo3) · [Cubism MCP](tools/cubism-mcp/README.md) · [Android 播放资源](app/src/main/assets/pet3d/) · [原始需求](docs/原始需求.md)
