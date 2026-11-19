# Manifest v2 生产合同

相位退出桥让循环从指定帧平滑回到可进入下一动作的姿态。当前清单包含 49 条相位桥。下文约定路径、连续状态和帧摘要的校验格式。

生产 `FrameClipPlayer` 使用代码内 `ReleasedArtwork.REQUIRED_ACTIONS` 的 29 项本轮发行清单作为硬门槛，包含 walk↔trot↔run 全部获准变速桥及真实 play_ball/treat/hop；不接受 manifest 自报必需 ID。原 `ClipManifest.REQUIRED_ACTIONS` 的完整目标集合保留，普通测试或调用默认仍使用它。额外内部桥和普通片段也必须通过所有结构、姿态、可达性、位移与文件摘要规则。

## 字段

根对象仍包含 `version/width/height/initialClip/clips`；相位资源使用 `version: 2`。v2 的所有 clip 增加 `frameSha256` 数组，与 `frames` 一一对应，摘要为 APK 内实际编码文件字节的 SHA-256。普通 clip 可增加 `entryStateSha256` / `exitStateSha256`；凡作为桥的直接出口目标，必须声明真实 `entryStateSha256`。

内部桥额外声明：

```json
{
  "id": "stand_exit_006",
  "frames": ["pet3d/stand_exit_006/000.png", "...仅文档省略..."],
  "frameSha256": ["实际文件摘要", "..."],
  "fps": 24,
  "sampling": "interval_starts",
  "entryPose": "phase_stand_6",
  "exitPose": "stand_left",
  "loop": false,
  "interrupt": "never",
  "transitions": ["stand", "happy", "tilt", "wave"],
  "heading": "left",
  "exitHeading": "left",
  "canTravel": false,
  "travelUnits": "canvas_width_fraction",
  "projectedTravel": [0, 0, 0, 0, 0, 0, 0, 0],
  "entryStateSha256": "经验证的共享相位状态摘要",
  "exitStateSha256": "经验证的canonical状态摘要",
  "phaseEntry": {
    "sourceClip": "stand",
    "atFrame": 6,
    "sourceClipSha256": "按下方唯一算法计算",
    "sourceFrameSha256": "源运行帧6实际文件摘要，也等于桥帧0摘要",
    "entryStateSha256": "同桥entryStateSha256",
    "proof": {"path": "pet3d/phase-proof.json", "sha256": "报告实际文件摘要"}
  },
  "terminalProof": {
    "path": "pet3d/proofs/stand_exit_006.png",
    "sha256": "省略终点实际文件摘要，也等于各直接目标frame0摘要",
    "stateSha256": "同桥exitStateSha256及各直接目标entryStateSha256"
  }
}
```

帧数和索引不固定成 8 或 6，数量不固定成 30/36。第一批生产选择 stand 的 6..84、breathe 的 6..60、tail_wag 的 6..36（步长 6），共 30 条、各 8 个区间；这只是当前作者资源。接口要求相位 `0 < atFrame < source.frames.size`，桥与源帧率相同、同为 interval_starts，并且 `atFrame + bridge.frames.size < source.frames.size`，否则普通结束已经一样快或更快，不接受无收益桥。桥的 `entryPose` 精确为 `phase_<sourceClip>_<十进制atFrame>`，不对数字补零。

源必须是 stand/breathe/tail_wag 的真实循环或其正式方向变体；源与桥有稳定相同朝向、明确全零位移数组、canTravel=false。桥非循环、never、无安全出口和 variantOf。桥入口文件**直接复用源已编码文件字节到独立路径**；终点同样复制实际 canonical 编码文件，不能只保证解码 RGBA 一样却填写不同文件摘要。支路只能在严格未来锚点进入，出口目标必须在真实图中声明。

## 唯一内容摘要算法

`sourceClipSha256` 和 proof 内 `bridgeClipSha256` 使用同一 `clip-content-v2` 记录。记录**只含以下键**，所有缺省值先展开，再按下面的 canonical JSON 编码后计算 UTF-8 SHA-256：

```text
schema                  "cutedog-clip-content-v2"
width, height           根整数
id, fps                 clip真实ID、整数
sampling                缺省 "inclusive_endpoints"
entryPose, exitPose      原字符串
loop                    布尔
interrupt               "immediate" / "boundary" / "never"
transitions             原数组顺序（BFS顺序也是内容的一部分）
safeExitFrames          去重后按整数升序，缺省 []
variantOf               缺省 null
heading, exitHeading    "left" / "right" / null；exitHeading缺省沿用heading
frames                  原路径字符串数组
frameSha256             对应摘要字符串数组
travelUnits             缺省 null
projectedTravel         缺省 []，按数值正规化
canTravel               缺省 false
entryStateSha256        缺省 null
exitStateSha256         缺省 null
terminalProof           缺省 null，或仅含path/sha256/stateSha256三键的对象
```

**不含 phaseEntry、proof、独立 contentSha 字段或任意说明字段**，避免报告→桥→报告的循环摘要。生产器不能直接对原始 manifest 子对象的 `json.dumps` 输出做哈希。

Canonical JSON：本记录的固定 ASCII 对象键按字典序升序；数组维持次序；无空白；null/true/false 使用小写；字符串采用 JSON 双引号，转义 `"`、`\\`、控制字符，非 ASCII 字符不改写成 `\uXXXX`，`/` 不转义；数值必须有限，用十进制普通表示、无指数、去掉小数末尾的 0 和多余小数点，负零输出 `0`。例如 0、0.0、-0.0 均编码为 `0`，1e-5 编码为 `0.00001`。Kotlin 使用 `BigDecimal(value.toString()).stripTrailingZeros().toPlainString()`；Python 使用 `format(Decimal(str(value)), 'f')` 后剥离小数末尾零（整数部分的零不能剥）。摘要为小写十六进制。

v2 的 ID、路径和 pose 字符串使用受限 ASCII 格式，因此跨语言不会出现排序/Unicode 转义差异。Android 的 `ClipIntegrity.contentSha256(width,height,clip)` 与 `canonicalJson(value)` 实现该算法。共同 golden 为 `docs/phase-v2-golden.json`，内容摘要 `f536fe19c22ebe8afaf80f3961c99200918fb6ac4862af41e0d15a8ab1e7aecd`；这是明确的合成序列化夹具，不是已渲染素材或 QA 证据。

## 状态摘要与报告

状态摘要标识**经离线验证的共享边界状态合同**，而不是运行时重新做物理求解。共享合同明确 `continuityOrder: 1`，保存具体骨/部件位置、变换、接触状态、局部眼睑/纹理控制、速度及单位；源、rig、atlas、渲染设置摘要另入报告输入集合。可用上述 canonical JSON 对该完整状态记录计算摘要。源码的临时时间、动作名字和本地路径不属于共享物理状态，不应导致两个相同边界因名称不同产生不同摘要。加速度不纳入必须相同的共享 C1 状态：源→桥入口 C2 控制及全骨导数残差另存离线报告，桥终点与目标入口的实际加速度差异也须如实记录，不宣称整条链 C2 或重写目标曲线。

`entryStateSha256` 是该 clip t=0 的共享状态；`exitStateSha256` 是被省略终点的共享状态。桥的入口合同继承源真实相位；出口合同是实际 canonical 状态。有限差分得到的近似导数不应伪装成逐位相等：离线报告必须保留两侧实际数值、采样方法、容差和残差，并确认它们满足同一合同后绑定共享摘要。哈希不能取代这种验证。

`phaseEntry.proof` 指向小型 UTF-8 JSON（单个上限 512KiB），字节摘要必须匹配；可由全部桥共享一个报告。根格式为：

```json
{
  "version": 1,
  "continuityOrder": 1,
  "inputs": {
    "motionSourceSha256": "作者动作源码摘要",
    "rigSha256": "rig实际文件摘要",
    "atlasSha256": "atlas实际文件摘要",
    "rendererSha256": "渲染源码或版本化配置摘要",
    "continuityReportSha256": "包含实际测量和审查记录的完整离线QA文件摘要"
  },
  "bindings": [
    {
      "bridge": "stand_exit_006",
      "sourceClip": "stand",
      "atFrame": 6,
      "sourceClipSha256": "按clip-content-v2算法计算",
      "bridgeClipSha256": "按clip-content-v2算法计算",
      "sourceFrameSha256": "与phaseEntry及桥帧0一致",
      "entryStateSha256": "同phaseEntry及桥entryState",
      "terminalFrameSha256": "同terminalProof",
      "exitStateSha256": "同terminalProof及桥exitState"
    }
  ]
}
```

以上所有摘要必须是真实 64 位小写 SHA-256；不能用 `passed:true` 替代输入集合及逐桥绑定。报告路径、桥 ID 和 source/index 唯一，任何错绑/缺失/重复都会拒绝。离线发布器负责对所有源/桥/目标图像、连续状态、接地/导数和实际 QA 完整复核；APK 必须从该已审核输入集合打包。运行时不宣称重新完成美术/物理验收。

## Android I/O 与失败行为

启动后台线程读取有界 manifest 和小报告，重算内容摘要并验证结构及报告字节摘要，不预读全部动画图片。每张图在后台第一次被请求解码时核验实际文件摘要；桥入口按需额外核验其绑定源帧与真实 terminalProof 文件。检查结果与具体路径/摘要关联。未读取或校验失败的像素不能进入 ready 缓存。

v2 报告/结构校验失败明确报资源错误；按需图像错绑/损坏同样报错，不把它当“桥暂时没下载”静默掩盖。只有合法但尚未解码的候选可错过当前锚点，继续原源下一帧并等待下次有效相位。已进入桥后缺帧沿用保持最后真实帧的策略。UI 线程只查询状态和提交精确帧，不开文件、不算全帧摘要。

本合同不会删除现有 256 clip 总上限，内部桥同样计数；内部桥不是普通可请求动作，也不能成为移动/start-stop/方向路径的捷径。
