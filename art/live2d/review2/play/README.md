# 喂食、玩球和近身互动

在 Cubism Editor 5.3 中打开 `rig/Interactions.cmo3`。该文档经过编辑器后台加载和另存验证，含 10 个 ArtMesh、90 个 Warp Deformer 和 12 个参数。`rig/CuteDog.model3.json` 是同一套表面与关键形状的运行时入口。每张整犬图保持 512×512 的原稿尺寸，伸舌使用原稿舌头单独制作的小贴图。

|动作|视频区间数|手机区间数|手机动作开头和结尾|
|---|---:|---:|---|
|玩球 play_ball|146|146|站立|
|接食 treat|120|120|站立|
|蹭脸 nuzzle|69|69|站立|
|舔爪 paw_lick，手机 ID 为 lick_paw|72|213|站立，先趴下再舔爪|
|整理胸肩毛 groom|81|125|站立，先坐下再转颈压肩梳毛|

全部动作以每秒 24 个区间采样，文件序号为 00000 到 N，两端均有完整画面。播放 `0…N-1`，用第 N 张验证或连接下一段。每段的 `scene.json` 记录 800×512 世界坐标、狗的 512×512 透明表面、接触点及道具。接触时使用原生鼻尖、眼睛和嘴角顶点配准张口、闭口与咬住的原画。球沿用同一张玩具纹理，球心在第 68–90 帧跟随头部，球的前端露出，上唇与下颌遮住后缘。饼干在第 56 帧入口，随后收进两唇之间，三次咀嚼逐渐减小。每帧的实际开口像素、上下唇遮挡面积和可见道具面积见场景数据中的 `mouthContact`。[口型参考](muzzle-reference.png) · [接触合成](mouth_contact.py)

手机帧保持狗的像素尺度，横向镜头跟随狗，纵向镜头固定以保留起跳。

### 复现

需要 Python 3.14、NumPy、SciPy、Pillow、ModernGL、OpenCV、imageio-ffmpeg、JDK 17 或以上，以及本机安装的 Cubism Editor 5.3。Cubism 的签名库从本机安装目录读取。下面从仓库根目录运行 PowerShell。

```powershell
$env:CUTEDOG_PLAY_OUTPUT = (Join-Path (Get-Location) 'build/interaction-review')
python art/live2d/review2/play/scenes.py
python art/live2d/review2/play/mobile.py
python art/live2d/review2/play/verify.py
```

横向追球读取 gait 作者输出的 `art/live2d/review2/gait/frames/chase`。舔爪进出读取 posture 作者输出的 `art/live2d/review2/perform/{lie,side_lie,side_to_lie,lie_to_stand}`。请先运行相应作者脚本生成这些中间帧，或用 `CUTEDOG_GAIT_FRAMES` 和 `CUTEDOG_POSTURE_FRAMES` 指向已有输出。`CUTEDOG_CUBISM_APP` 可指定 Cubism 安装目录内的 app 文件夹。`CUTEDOG_JAVAC` 可指定 JDK 编译器。其中 `scenes.py` 和 `mobile.py` 可在命令后传入一个或多个动作 ID，`verify.py` 检查五段完整输出。

`scenes/<id>/<id>.motion3.json` 和 `native/parameters.tsv` 保存原生曲线及 24fps 的参数采样。`ParamPose` 使用阶梯曲线切换九张注册过的全身原画。`ParamPitch`、`ParamReach`、`ParamSniff` 和 `ParamChew` 控制低头、探头、嗅闻和下颌；`ParamPaw`、`ParamTuck`、`ParamWeight` 控制举爪、收爪和准备落地；`ParamBlink`、`ParamEar`、`ParamTail` 和 `ParamLick` 控制眨眼、耳朵、尾巴和舔舐。`play_ball` 的追逐区间 31…61 采用 gait 模块的实际步态与该次 chase 的实测位移，具体数值记录在 scene.json 的 chaseSource.travelPx，属于场景合成轨道。

修改网格或关键形状时，可将本目录的 `dog_project.rs` 放入 Ayatsuri2D 的 `crates/import/examples`，在提交 `080f72b63a37098ce0f4974699871230beeecb65` 上用 `cargo build --example dog_project -p ayatsuri-import` 构建工具。将可执行文件路径写入 `CUTEDOG_MODEL_EXPORTER`，再运行 `python art/live2d/review2/play/interaction_rig.py`，即可从仓库的整犬原画重建运行时模型和未回存的编辑器草稿。随附的 `Interactions.cmo3` 是本次已经过 Cubism 编辑器重新保存的版本。

### 整理胸肩毛

在 Cubism Editor 5.3 打开 `groom-rig/Grooming.cmo3`，运行时使用 `groom-rig/CuteDog.model3.json`。这套坐姿模型包含 30 张完整身体视图、90 个局部变形器和 4 个参数。头部转到同侧肩膀后，嘴接触胸肩毛，向下后方梳理两次，再转回坐姿。`ParamPose` 选择全身角度，`ParamComb` 控制口鼻沿毛梳动，`ParamCoat` 控制受压毛束，`ParamBreath` 控制胸部呼吸。

原画来自仓库的 `Sit05`、`Turn02`、`Turn03` 与 `art/live2d/review2/perform/care-seated-atlas.png` 的第二排。运行 `python groom_author.py` 可重新注册原画并导出模型。之后运行 `python scenes.py groom` 与 `python mobile.py groom` 可单独重烘。舞台第 27–60 帧保持肩毛接触，下方从 y=338 起的像素在整个坐姿表演中固定。
