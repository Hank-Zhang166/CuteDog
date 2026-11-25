# 设备性能与悬浮生命周期验证

`scripts/profile-device.py` 在已经安装 APK 的 Android 设备上执行真实观测。脚本不编译、不安装、不清空应用数据，不生成模拟测量。所有阶段与失败写入 `profile.json`，原始包级 `gfxinfo`、`meminfo`、宠物诊断快照与命令退出码一并保留。版本 2 的报告还包含实际界面截图、XML、前台 Activity、窗口、锁屏与失败现场证据。

本文件描述可执行方法；只有对应运行目录存在真实设备结果，才表示执行过。Python 语法检查或脚本存在不代表任何设备检查通过。

## 前置条件与运行

使用专用、可丢弃的测试模拟器。APK 必须已经安装；模拟器应完成启动，并具有可解锁的无凭据锁屏。脚本会通过正常界面启动/停止桌宠，临时修改通知权限、悬浮窗 app-op 与屏幕超时，执行息屏/唤醒，最后停止测试应用并恢复设置。脚本同时检查 emulator 序列号与 `ro.kernel.qemu=1`，不接受物理手机。

```bash
python3 scripts/profile-device.py \
  --serial emulator-5554 \
  --disposable \
  --duration 20 \
  --cycles 3 \
  --output /tmp/cutedog-profile-current
```

若安装的是可并存的动作预览版，请加上 `--package com.cutedog.app.motionpreview`。

最终完整角色验收应增加 `--require-rig-metrics`。每次使用新的输出目录，避免覆盖旧证据。`--expected-apk-sha256` 可绑定预期安装包；脚本始终读取并记录实际安装 `base.apk` 的 SHA-256，不把代码仓库提交自动当成 APK 版本。物理手机的功耗与长期性能验收需要独立的真机流程。

需要宿主 Python 3、adb，以及系统提供的 `dumpsys`/`uiautomator`。`run-as` 读取进程 CPU 计数仅适用于允许该操作的调试包；不可用时 CPU 字段为 `null`，绝不写成零占用。

## 实际检查顺序

1. 从安装包计算 SHA-256，记录 API、设备型号、ABI、是否模拟器、原始悬浮授权与屏幕超时。API33+ 另外保存 user0 的通知 grant 与完整 flags，明确建立通知已授权环境并读回确认；原始权限输出和操作前后状态保存在 `notification-permission/`。通知拒绝场景由此前独立的 instrumentation 阶段验证，不在这个性能流程中混用。
2. 核实屏幕可交互、锁屏已解除且 MainActivity 确实 resumed；真实点按「首页 → 事项 → 陪伴 → 小狗」，分别核对对应页面自身文字并保存 PNG 与 XML。不会打开模型密钥设置或发送消息。回到已核实的首页，预热后观测前台内存和 CPU，并从 Activity 诊断确认首页没有注册宠物渲染视图。
3. 从应用“小狗 → 召唤小狗”真正启动前台悬浮服务，返回 Launcher，观察悬浮运行。
4. 通过系统按键真正息屏；等待稳定后检查运行状态、tick、draw、实际帧变化、解码次数及解码队列均停止。之后唤醒/解锁，确认服务与真实动画恢复。
5. 通过应用“停止桌宠”与“召唤小狗”反复销毁/重建服务，记录每轮 PSS 与渲染缓存，检查服务和窗口确实清理。
6. 宿主实际将 `SYSTEM_ALERT_WINDOW` 改为拒绝，等待服务和 2038 悬浮窗口消失；恢复授权后由用户入口重新启动，再正常停止。
7. `finally` 在宿主恢复原 app-op、屏幕超时以及通知 grant/flags，重新读取严格比较确认恢复生效。通知快照不可解析时不修改该权限；遇到不能安全恢复的 flags 或读回不一致时报告失败。撤权可能杀死应用，整个流程不在 instrumentation 进程内执行。

脚本不直接调用非导出服务，也不以 `force-stop` 代替“点击停止”或“权限撤销自动清理”的断言。`force-stop` 只用于准备/最终清理，相关时点在命令记录中可核查。应用当前中文按钮变化时可通过 `--start-label` / `--stop-label` 指定，找不到控件会明确失败。

## 界面与失败现场

`scenes/page-home`、`page-reminders`、`page-companion`、`page-settings` 保存当前已安装 APK 的四页原始截图与 XML 索引。首页核对「桌宠设置与悬浮窗权限」，事项核对「把小事记好」，陪伴核对「记忆」和「清空聊天」，设置核对「你的小狗，你的节奏」，不能仅凭点击命令成功就判定打开了对应页面。主状态另保留 `overlay-active`、`screen-off`、`overlay-resumed` 与撤销悬浮授权后的截图和系统状态；截图采集安排在性能采样区间之外。息屏时截图可能仍是合成器缓存的最后画面，必须结合 power/keyguard 状态判断，不能把该 PNG 当作屏幕实际发光的证明。

每次 UIAutomator 请求使用本次运行独有的新远端文件，保存 dump 原始输出、XML 与摘要后删除远端临时文件。即使系统工具打印错误却返回退出码 0，也不能读入上一次遗留的树。`navigation/` 同时保留每次启动的 `am start` 原始输出和 resumed Activity 检查。命令退出码 0 不等于页面已出现，`wm dismiss-keyguard` 返回也不等于锁屏已经解除。

任意阶段失败时，在最终清理、force-stop 或恢复输入之前，尽力保存：

- PNG、失败前最后一次 XML/工具输出及一次新的有界 XML 请求。
- `activity activities`、`window windows`、`window policy`、`power` 与应用 service 状态。
- 失败额外保存进程状态、`activity lastanr` 和有限的 `am_anr` / `am_crash` 系统事件。

每个探针分别记录超时或错误；截图或 XML 不可用不会覆盖原始失败。`scene.json` 区分观测到的锁屏、非交互状态、系统错误对话框、已 resumed Activity 和实际导航节点。历史 ANR 记录本身不能证明当前故障由 ANR 导致；报告保留 `root_cause=unknown`，等待关联现场、时间与进程证据。

系统 `uiautomator dump` CLI 只保证获取 active-window 的树，并非所有窗口。在应用 Activity 已 resumed、另有悬浮窗时，缺少导航节点可能属于这项可见性限制；脚本会保留该组合证据并失败，不能据此宣称应用主页面不存在，也不通过关闭服务、乱点坐标或 force-stop 绕过。`--compressed` 不是读取所有窗口的选项。

这些 PNG/XML 和系统现场可能包含测试应用文字及设备画面，仅限显式 `--disposable` 的专用测试环境；不要用于真实账号或私人内容设备。它们是宿主测试工件，不写入产品日志，也不应将旧 APK 的画面用作当前源码截图。

## 指标与证据边界

- **真实角色帧率**：使用 `PetView` 实际切换已解码帧的累计计数，比较同一 PID、同一 `viewId` 的前后差值和设备 uptime。该值区别于 View 重绘频率。
- **系统渲染**：`dumpsys gfxinfo ... framestats` 的总重绘差值、保留帧时长 p50/p95/p99。Android 只保留有限环形记录，因此分位数可能只覆盖区间最后一部分，不冒充整个采样期。
- **内存**：真实 PSS、可用时的 Java/native/graphics PSS 与 RSS，以及角色缓存字节数、条目数和复用计数。`null` 表示没有可用值，不能视为零。
- **CPU**：同一 PID 和进程启动标识的 `/proc/<pid>/stat` 用户态/内核态 tick 差值；有设备 `CLK_TCK` 才换算为单核占用比例。探针和 adb 调用本身会产生少量开销。
- **息屏停止**：稳定区间内实际 tick、draw、帧切换和 decodedFrames 增量必须为零，同时 `running=false`、`activeDecoder=false`、`pendingFrames=0`。API 缺失或不能确认同一 View 时标为 `unverified`。
- **重复启停**：输出每轮 PSS 和首末差值。短期 PSS 含缓存与 GC 时机，只能作为回归信号，不能证明“没有内存泄漏”。`leak_verdict` 因此明确为 `not_proven`。

诊断通过 `Activity.dump` / `Service.dump` 输出 `CUTEDOG_VIEW_METRICS_JSON:` 与 `CUTEDOG_METRICS_JSON:`，只含数值、布尔状态和动作 ID；不含用户文本、模型密钥或聊天。ADB 使用系统 DUMP 权限读取，无需在产品 UI 暴露调试数字。

旧图片图集版本会报告 `renderMode=legacy`。即便其 PSS 和服务行为可实测，完整骨骼帧推进/解码验收也必须标为 `not_run`。没有诊断 API 时同样不能以 `gfxinfo` 重绘次数替代真实宠物动画帧率。

**模拟器测量不是手机续航结论。** KVM/SwiftShader、宿主负载、虚拟 CPU/GPU 与物理手机差异显著。发布前仍需真实设备长时间运行、充电/省电模式、内存分析和热功耗测试。息屏/解锁不等同于测试带密码的安全锁屏；本脚本也未验证提醒 Doze 准点性、OEM 后台政策或真实重启。

目前不设未经产品定义的统一 FPS/PSS 数字预算。`passed` 仅表示已执行的结构性与生命周期检查通过，不能解释为所有设备达到商业级流畅度。性能是否达标应结合保留数据、指定机型预算和真机体验另行判断。

## 退出码和 CI 接入

- `0`：所执行的生命周期检查通过。仍须读取 `status`，没有角色诊断时可能为 `partial`。
- `1`：实际检查、前置条件或恢复操作失败，证据照常保留。
- `2`：要求完整角色指标，但实际为旧资源、缺少指标或无法确认计数连续性。

当前原生测试入口为 `scripts/run-device-tests.sh`，运行后 APK 保持安装、应用被停止、权限恢复安装后状态。API35 的 emulator-runner **同一个 `script` 会话内**可接入下列命令，确保原生测试失败时仍尝试性能观测，最后保留两个退出状态：

```bash
set +e
scripts/run-device-tests.sh --disposable --output "$RUNNER_TEMP/instrumentation-evidence"
instrumentation_status=$?
python3 scripts/profile-device.py --disposable --require-rig-metrics \
  --output "$RUNNER_TEMP/profile-evidence"
profile_status=$?
set -e
printf 'instrumentation=%s profile=%s\n' "$instrumentation_status" "$profile_status"
test "$instrumentation_status" -eq 0 && test "$profile_status" -eq 0
```

上传步骤使用 `if: always()`，包含整个 `profile-evidence/`。应将 SHA-256、来源提交、运行 ID 与测量目录一起保留；不要把旧预览 APK 的观测冒充当前完整骨骼构建。
