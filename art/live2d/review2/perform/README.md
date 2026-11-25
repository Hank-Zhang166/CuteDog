# 头颈、卷尾与日常姿态

`editor/HeadTail.cmo3` 是 Cubism 5.3.05 可编辑模型。打开后，`ParamHeadYaw` 控制头部角度，范围为 −12° 到 +16°。`HeadNeck` 使用原站姿的一张毛面纹理，绕颈部转动。眼睛、口鼻和耳朵各自保持局部形状，颈毛吸收转角，肩背与四足固定。`ParamTailAngle` 控制尾根转角，`ParamTailTip` 控制尾梢延迟。卷尾内侧取自已有素材 `art/puppet/pose-sources/right-parts-atlas.png`。本机编辑器组件已另存、重新载入 CMO3 并绘制站姿预览。

运行模型位于 `model/CuteDog.model3.json`，动作控制文件位于 `motions/`。Android 的渲染登记额外把静止区域恢复为参考 PNG 的精确 RGBA，以免未移动的透明绒毛边缘因纹理混合而改变。

## 本地重绘

使用 Python 3.14、NumPy、SciPy、OpenCV、Pillow、ModernGL、JDK 17 和已安装的 Cubism Editor。以下命令从本目录运行。生成目录应使用单独位置。

```powershell
$env:CUTEDOG_REVIEW_PERFORM_OUT = Join-Path $PWD 'build'
python performance.py
python native.py
python head_check.py
python postures.py
python postures.py lie lie_idle lie_to_stand side_lie side_lie_idle side_to_lie curl curl_idle fall_asleep sleep wake uncurl stretch belly_up scratch yawn
python rest_repair.py
```

当几何规格与登记摘要一致时，`native.py` 使用交付的 MOC3 重新采样。更改几何后，使用 `review_moc.rs` 重新生成模型。在 Ayatsuri2D/ayatsuri_2d 的提交 `080f72b63a37098ce0f4974699871230beeecb65` 下，将该文件放入 `crates/import/examples/`，运行 `cargo build -p ayatsuri-import --example review_moc`；将环境变量 `CUBISM_REVIEW_EXPORTER` 指向生成的可执行文件。`CUBISM_APP` 可覆盖本机 Cubism 的 app 目录。

## 新增姿态的画稿

`supplement.png` 是以同一只狗的 `Stand.png` 为参考，通过本轮内置图像生成工具补绘的六幅整身原画。绘制要求是保持原有面部比例、暖白绒毛、卷尾和左上光线，在统一视角下给出前肢伸展的游戏鞠躬、中途趴下、侧滚、仰卧、后爪挠耳、张口打哈欠。程序用每幅独立画稿的透明度提取轮廓，并按站姿画布的地线注册。

`rest-inbetweens.png` 以原有 `Rest.png` 和 `Sleep.png` 为参照，补绘俯卧时向镜头转头、半阖眼正面以及把头收向卷尾的三个中间姿态。前足和地线用于对齐，额外画稿用于保持同一张脸、鼻尖和口鼻轮廓。

五幅补绘睡姿用于侧卧、蜷身、入睡和醒来。 `rest_repair.py` 使用 `rest-inbetweens.png` 的三幅正面与斜前方画稿，以及 `lowering-roll-inbetweens.png` 上排两幅头颈降低的画稿，在原有 Lie、Rest 和 Sleep 之间生成连续动作。脚掌和尾巴具有独立的遮挡顺序，眼睛和鼻尖始终使用同一侧的解剖标记。运行后的 `rest-repair-qa.json` 记录六个动作的 242 个样本、鼻尖位置和轮廓连通情况。


`scratch_repair.py` 使用 `care-seated-atlas.png` 上排的三个姿态确定后腿的屈曲和接触位置。近侧后爪的脚趾和脚背始终取自同一块画面，头部始终取自原始 `Sit05.png`。挠耳共 83 个播放区间（24 fps），第 38、45、52 帧触到同侧耳根，随后放回坐姿。运行 `python postures.py scratch` 生成透明 WebP、每帧画布坐标 `scratch-tip-track.json` 和像素检查 `scratch-repair-qa.json`。检查覆盖从第一次触耳到终点的 46 帧，逐帧比较虹膜、鼻头和抬起的脚趾纹理。

打哈欠使用同一张九格参考图的下排三幅表情。可在 `editor/Yawn.cmo3` 中拖动 `ParamJawOpen` 查看下颌和眼睑。四种开合程度在鼻、眼、上下唇、舌边、下巴处逐点配准，肩以下沿用原坐姿。运行 `python postures.py yawn` 会从 `yawn-model/` 的已登记模型在本机 Cubism Core 中重绘 64 个播放区间和终帧。
