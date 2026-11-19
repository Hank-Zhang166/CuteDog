# 最终 APK 的独立设备验收入口

下列命令说明如何为指定 APK 运行设备验证。[本次设备记录](interaction-review.md)

在 GitHub Actions 打开 `Published APK UI validation`，选择 `Run workflow`。输入最终 APK 的 HTTPS 下载地址 `apk_url`、完整 SHA-256 `apk_sha256` 和构建该版本的 40 位源码提交 `source_commit`。如需验证覆盖升级，同时填写旧版 APK 地址 `baseline_url` 和对应的 `baseline_sha256`。

准备阶段流式下载核对完整字节 SHA、实测签名和 aapt 元数据，对照声明源码的 applicationId/versionCode/versionName/SDK；最终 APK 必须实际包含唯一 `assets/pet3d/manifest.json`。升级基线必须同包名、同签名且实际 versionCode 更低。元数据对应不能证明可复现构建，证据明确保留这个限制。

设备阶段仅用于显式可丢弃模拟器。实际安装 SHA 另行核对；升级检查创建真实 UI 事项，执行 install -r 后验证保留，不以签名相同代替实际升级。随后仍执行四页、事项生命周期、未配置聊天、悬浮交互以及严格 rig 生命周期 profile。运行结束后，在该次 Actions 运行的 Artifacts 中下载 `published-apk-ui-evidence-<run_id>`，查看截图、断言及原始诊断。

标准 instrumentation 后的 API35 profile 同样读取 `tested-apks.json`，核对所记录的真实 APK 文件 SHA/字节数，再检查该 APK ZIP。包含角色 manifest 就启用严格 rig 指标；真实安装 base.apk 必须与这份记录一致。缺帧、指标缺失或不连续不能以 legacy/partial 状态返回成功。缓存、暂停时零活动、实际绘制及性能观测标准未降低。模拟器短测不等于物理手机长期功耗、温度或内存泄漏验收。
