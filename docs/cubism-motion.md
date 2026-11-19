# CuteDog Android · Live2D 动作版

本页说明完整库中基础模型的烘焙步骤。整合后续头部、步态和护理动作的操作见[完整动作库说明](../art/live2d/review2/integration/README.md)。

这是可独立构建的 Android 源码。角色使用 Live2D Cubism 模型和 24 fps 动作。烘焙工具逐帧求解模型，Android 的首页预览与桌宠悬浮窗共用透明 WebP 播放器。这个制作阶段生成 29 个基础片段、30 个内部衔接片段。

## 构建

安装 JDK 17、Android SDK Platform 35 与 Build-Tools 35.0.0。将 `JAVA_HOME`、`ANDROID_HOME` 指向本机工具链。在解压目录中运行以下 PowerShell 命令。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

普通调试版的应用 ID 是 `com.cutedog.app`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。覆盖已有安装时需要沿用其签名。

如需和旧版并存，使用下列构建命令。此配置生成 `com.cutedog.app.motionpreview`，显示名为「CuteDog 动作预览」，使用独立的应用数据空间。

```powershell
.\gradlew.bat -I tools/preview/motion-preview.gradle testDebugUnitTest assembleDebug
```

## 角色与动作源文件

| 位置 | 内容 |
| --- | --- |
| `app/src/main/assets/pet3d/` | 应用实际播放的 WebP、动作清单、相位衔接和来源记录 |
| `art/live2d/model/` | 用于此次烘焙的模型源文件、MOC3、模型描述、纹理和姿态索引 |
| `art/live2d/poses/` | 在统一画布上对齐的 46 个完整姿态绘图 |
| `art/live2d/motions/` | 以原生 Cubism 曲线表示的普通动作与衔接动作 |
| `tools/cubism/` | 动作生成、模型读取和帧烘焙源码 |
| `scripts/` | Android 资源组包、摘要计算及衔接验证 |

编辑模型时请在 Cubism Editor 中打开 `art/live2d/editor/CuteDog.cmo3`。该版本已修正根变形器引用与画布坐标，并在 Cubism Editor 5.3.05 验证过中立姿态。`art/live2d/model/CuteDog.cmo3` 保留此次烘焙的原始输入，供核对 APK 的来源摘要。重新烘焙应用播放帧使用同目录内的 `CuteDog.model3.json`、MOC3 和纹理。[打开编辑模型](../art/live2d/editor/CuteDog.cmo3)

## 从模型重新生成播放资源

已有的 Android 资源可直接构建 APK。修改模型或动作时，可以使用本机 Cubism Editor 的 Java Core 库和 Windows 原生库重新求解模型。烘焙需要 Python 3.11 或更新版本、NumPy、Pillow、SciPy、ModernGL、支持 OpenGL 3.3 的显卡驱动和 JDK 17。以下命令在项目根目录运行。

```powershell
py -m pip install numpy pillow scipy moderngl
$core = 'C:\Program Files\Live2D Cubism 5.3\app'
$jdk = Join-Path $env:JAVA_HOME bin
py tools/cubism/base/bake.py art/live2d/model/CuteDog.model3.json art/live2d/model/pose-index.json art/live2d/poses art/live2d/toy-props.png art/live2d/motions build/native-bake --core $core --jdk $jdk
py scripts/pack-cubism.py build/native-bake --model art/live2d/model/CuteDog.model3.json --motions art/live2d/motions --library art/live2d/motions/library.json --renderer tools/cubism/CoreBake.java --renderer tools/cubism/base/render.py --renderer tools/cubism/bake_library.py --authoring art/live2d/model/CuteDog.cmo3 --authoring tools/cubism/author_motion.py --authoring tools/cubism/author_rig.py --authoring art/live2d/toy-props.png --output build/verified-pet3d
```

组包工具把校验后的完整资源写入指定的新目录。将输出目录作为整合器的 `--base`，含终帧的采样目录作为 `--base-bake`。最终资源由整合步骤生成。应用会检查每个实际播放帧的摘要，以及衔接动作首帧、源动作锚点、结束姿态之间的对应关系。模型求解版本、动作曲线、纹理、烘焙来源与距离测量的摘要保存在 `native-provenance.json`。基础样本使用签入的 `tools/cubism/base/render.py`，转头等后续动作使用支持局部锐采样的 `tools/cubism/render.py`。
