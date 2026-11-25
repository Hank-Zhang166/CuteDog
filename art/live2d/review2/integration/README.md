# 512px 整犬动作整合

仓库 `app/src/main/assets/pet3d` 保存 24 Hz 无损 WebP 原图和 v2 清单。Android 构建逐帧核对原图摘要，合并完全相同或像素级水平对称的图像，再将独立 WebP 编入 APK。追球的双向步态保留原像素，其他帧使用 Q96，全部帧保留 512 × 512 分辨率与逐像素透明度。只修改了业务交互时，无须重新烘焙。基础版的 Cubism 工程仍含当时制作的 29 条动作，当前完整库另包含[头部、尾巴与姿态](../perform/)、[双向实测步态](../gait/)、[情绪与注意](../emotions/)及[道具与接触互动](../play/)。

## 帧和路线

每个普通片段有 N 个实际播放区间。作者交付 N+1 张图，生产器检查最后一张和所有后继片段的首张逐像素相同，最后一张仅用于证明。状态图分别记录站立、坐姿、左/右行走和慢跑、卧姿、侧躺、蜷缩和睡眠。跳跃起跳段可接到对应空中相位的落地段。后退保留真实朝向，物理移动方向由声明的 `backwardTravel` 反向；预约空间、解码完成后提交的每一步和真实落足距离使用同一方向。

最终清单含 4,150 张播放帧。81 个普通、方向及姿态片段之间有 2,033 条首末像素相同的普通边，另有 49 条可提前离开静止循环的相位恢复段。16 组卧坐、伸展等姿态的采样描述保存在[姿态轨道](../perform/posture-tracks/)。来源报告对文本按 Git 中的 LF 字节计算摘要，模型、纹理、播放帧和 Android 实际读取的相位报告按原始字节校验。

挠耳使用同侧后爪三次触到耳根，再放回原坐姿，全程 83 个区间。打哈欠以 64 个区间逐渐张嘴、闭合，脚和臀部留在原处。[挠耳轨迹](../perform/scratch-tip-track.json) · [哈欠可编辑模型](../perform/editor/Yawn.cmo3)

整理毛发先坐下，低头把嘴送到肩毛，沿毛向梳理两次，再坐直起身，手机版共 125 个区间。日常移动提供走路和慢跑。玩球共 146 个区间，其中第 62–98 帧用上、下唇遮挡玩具。其余 109 帧、独立 30 区间左右追逐、全程 24 Hz 节奏和位移均有回归检查。[理毛可编辑模型](../play/groom-rig/Grooming.cmo3)

道具由 800×512 的作者场景重新取景到 512×512 的移动画幅。球的来去和勺柄可以穿过画面边界，狗和道具接触保持在画内。“玩球”的追逐由取景跟随处理，因此手机悬浮窗在该动作中保持原位。作者轨道保存实际世界坐标。

静止循环可在已验证的帧相位进入更短的恢复段。左右站立保留原生姿态与首导数，右侧对其进行同一水平反射。11 条尾部恢复段从本轮 HeadTail 模型的 Cubism 控制值和锚点左导数出发，通过三次 Hermite 曲线回到零角、零速度，并使用同一 MOC3 和注册校正重新渲染。清单、每帧文件、源相位、终点及曲线来源分别有 SHA-256 绑定。`PackagedArtworkTest` 会通过生产解析器和报告校验器逐帧读取实际文件。

挥爪的第 9–39 帧使用完整坐姿的真实抬爪原画，抬起的脚掌、另一条支撑腿、眼和鼻分别配准。原地跳使用站姿和定长腿骨，从压低、离地到着地缓冲共 26 个区间。卧倒、侧卧、挠耳和翻身等片段沿实轮廓追踪透明毛尖，清除退到毛外的旧绘图细线。[抬爪与原地跳](../perform/foreground_actions.py) · [接触与吃零食](../play/README.md) · [透明毛边](foreground_matte.py)

## 复现

1. 按[基础模型烘焙说明](../../../../docs/cubism-motion.md)从当前检出目录生成两份输入。将含终帧的 `build/native-bake` 作为 `BASE_BAKE`，将校验后的 `build/verified-pet3d` 作为 `BASE`。用于还原基础模型的路由字段保存在 [base-graph.json](../../motions/base-graph.json)。
2. 在同一工作目录 `REVIEW` 下运行相应作者源，得到 `gait/index.json` 和 `gait/frames`、`emotions/trajectories.json` 和 `emotions/frames`、`perform/<姿态>/motion.json`、`perform/native/<动作>/frames` 以及 `play/scenes/<动作>/mobile`。各子目录 README 列出输入依赖与本机 Cubism Core 用法。
3. 使用 JDK 编译 `tools/cubism/CoreBake.java`，类路径加入已安装 Cubism Editor 的 `app/lib/Live2DCubismCore.jar`。`CORE_CLASSES` 指向编译输出，`CUBISM_APP` 指向 Editor 的 `app`。复制已登记过的原生 MOC3、纹理与动作曲线到该次 head/tail 输出即可重新采样。
4. 生成尾巴恢复，再用下列命令写入一个**新的空目标路径**。其中 `STAGE/pet3d` 即 Android 资源目录。生产器逐一校验基础采样字节、方向步态源、共同端点、内部桥入口和终点、所有播放帧摘要、所有普通/内部边及清单体积。

```sh
python art/live2d/review2/integration/native_tail_exits.py REVIEW/perform/native EXITS --core CUBISM_APP --classes CORE_CLASSES
python art/live2d/review2/integration/build_library.py --review REVIEW --base BASE --base-bake BASE_BAKE --head REVIEW/perform/native --exits EXITS --output STAGE
```

Python 需要 NumPy、SciPy、Pillow、OpenCV 和 ModernGL。Android 构建的编码依赖见 `scripts/artwork-requirements.txt`，安装包支持离线随机读取任意帧。模拟器覆盖范围见[设备验收](../../../../docs/interaction-review.md)。

要检验安装包，运行 `python scripts/measure-artwork.py app/build/outputs/apk/debug/app-debug.apk`。它逐帧核对原图与安装字节摘要、尺寸、透明度、两向追球像素，以及所有动作的最低前景 PSNR。
