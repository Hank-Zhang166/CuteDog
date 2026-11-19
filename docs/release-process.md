# APK 发布输入与验证流程

构建产物通过独立的 Release 输入和 SHA-256 绑定发布，日常开发检出只保存源文件与应用必需资源。

## 接口与实际数据

- `python3 scripts/package-apk.py` 保留 Android 验证工作流的无参数入口：读取 `app/build/outputs/apk/debug/app-debug.apk`，把 APK 与证据放入忽略的 `artifacts/`。可用 `--apk`、`--output`、`--build-tools` 和 `--source-commit` 指定输入。
- `python3 scripts/prepare-release-input.py prepare ...` 检查已签名 APK，生成 `release-manifest.json` 和不超过 **40 MiB** 的 `parts/00000.part` 等分片。默认输出 `artifacts/release-input/`，默认发布模式 `draft`。
- `... verify --input DIR --tag TAG --source-commit SHA --output DIR` 在本地还原并验证输入。
- `... fetch-verify --tag TAG [--input-commit SHA] --output DIR` 获取指定标签与固定输入提交，提取数据后验证。输入提交的代码不会被检出或执行。
- `... self-test` 运行离线边界测试，不需要 Gradle、网络、签名私钥或凭据。

工具使用 Android Build Tools 35.0.0 的 `aapt dump badging` 与 `apksigner verify --verbose --print-certs`；位置可由 `--build-tools` 覆盖。默认查找 `$ANDROID_HOME`、`$ANDROID_SDK_ROOT`，最后使用本环境的 `/workspace/tooling/android-sdk`。Java 必须可用。

版本名、versionCode、applicationId、最低/目标 SDK、`debuggable` 与所有 signer 的证书 SHA-256 都来自实际 APK 工具输出。文件名中的 `debuggable` 或 `signed` 根据实际标志生成；`signed` 仅表示签名已通过检查，不声明证书具有生产发布身份。证书指纹并非私钥，可以公开；签名私钥、口令和 keystore 不属于任何发布输入。

`BUILD-INFO.json` 还记录 APK 字节数、SHA-256、源码提交和工作树是否存在未提交内容。普通 debug 打包允许脏工作树并明确标记；发布输入准备要求源码工作树干净，包括未跟踪文件。APK 本身不能证明它由某个 Git 提交构建：`sourceCommit` 是构建者声明，流程验证提交绑定及元数据一致性，并非可复现构建证明。应在指定提交的干净 checkout 构建、签名和验收同一个 APK，再准备输入。

## Manifest 契约

JSON schemaVersion 为 `1`，字段必须严格符合脚本定义；重复字段、未知字段、缺失字段、错误类型、路径穿越和无效 Git tag 都会被拒绝。

| 字段 | 约束 |
| --- | --- |
| `tag` | 精确为 `v` 加 APK 的实际 `versionName`。例如预发布 APK 版本应为 `0.2.0-preview.1`，tag 为 `v0.2.0-preview.1`。不能把版本 `0.1.0` 的旧 APK 声明成新版本。 |
| `sourceCommit` | 40 位小写完整提交 SHA；必须是标签最终指向的源码提交。 |
| `release.mode` | `draft`（默认）、`prerelease` 或明确选择的 `stable`。 |
| `release.allowDebuggable` | 默认 `false`；调试包须明确允许，且只能使用 draft/prerelease。 |
| `apk` | 规范文件名、字节数、整包 SHA-256、实测应用元数据及排序去重的 `signerCertificateSha256` 数组。 |
| `chunks` | 有序连续的固定分片路径、各自字节数与 SHA-256；单片 ≤40 MiB，合计必须等于 APK 大小。 |

APK 必须非空且小于 GitHub 单个 Release asset 的 2 GiB 上限。还原时逐片校验字节数与 SHA-256，再校验整包，并重新调用 Android 工具验证签名和全部元数据。包名、版本和 SDK 还须匹配标签源码 `app/build.gradle.kts` 中的字面量设置。将来若改为动态 Gradle 配置，验证器会停止，需先更新验证器，不能绕过检查。

## 质量验收后准备输入

下面的版本只是示例。先完成该版本源码提交，在干净 checkout 中构建并用既定本地证书签名，确认安装、升级、权限、动画和业务行为均通过验收。脚本不生成签名密钥，也不替换已有证书。

`0.2.0-preview.1` 本轮已明确采用：源码与资源 CI 通过、最终本地 APK 和签名校验后发布 prerelease，再以公开 URL、完整 APK SHA 和源码 SHA 启动最终 APK 覆盖升级/UI/严格角色帧验证。发布可提供固定测试下载，但不能提前宣称全部交付通过；设备检查失败则修复为新的预览版本，不覆盖旧标签或资产。此顺序无需额外的草稿发布协议。

```bash
source_sha=$(git rev-parse HEAD)
python3 scripts/prepare-release-input.py prepare \
  --apk /absolute/path/to/already-signed.apk \
  --tag v0.2.0 \
  --source-commit "$source_sha" \
  --output artifacts/release-input-0.2.0

python3 scripts/prepare-release-input.py verify \
  --input artifacts/release-input-0.2.0 \
  --tag v0.2.0 \
  --source-commit "$source_sha" \
  --output artifacts/release-verified-0.2.0
```

输出目录必须事先不存在，以免混入旧文件。在源码 checkout 内只能输出到忽略的 `artifacts/` 下，也允许输出到仓库外的新目录。debuggable APK 需在 `prepare` 增加 `--allow-debuggable`；选 `--mode prerelease` 会公开预发布版本，选 `--mode stable` 会公开正式版本。未显式指定模式时只创建草稿。任何模式都不会自动设置 GitHub 的 Latest 标记。

## 持久保存与明确触发

本环境访问 `api.github.com` 受限时，可以用可用的 Git transport 推送数据分支，最终由 GitHub Actions 使用自身的仓库 token 创建 Release。每个分片均低于 GitHub 单文件 100 MiB 的 Git 限制；不需要把大 APK 放入 main。

1. 只把生成目录中的 `release-manifest.json` 和 `parts/` 复制到独立仓库或 orphan 分支 `codex/release-input-0.2.0`，提交并推送到 `Hank-Zhang166/CuteDog`。输入分支只存公开的已签名 APK 数据，不存 keystore、口令或其他工作区文件。建议使用独立临时仓库，不切换正在开发的源码工作树。
2. 记录该输入提交的完整 SHA。不要把可移动的分支名作为工作流输入，也不要修改历史输入提交或旧 tag。
3. 选择下面两种触发方式之一。推送输入分支本身不触发 release workflow。

**方式 A：新的 annotated tag。** 在已经验收的源码提交上创建带唯一绑定行的 annotated tag，随后推送该新标签：

```text
CuteDog 0.2.0

Release-Input-Commit: 0123456789abcdef0123456789abcdef01234567
```

绑定行中的 SHA 必须换成真实输入提交。`Release-Input-Commit:` 行必须恰好一条，格式严格如上。流程将 tag 的源码提交与 manifest 的 `sourceCommit` 比较。这个设计避免“先将 manifest 加进源码提交，然后源码 SHA 又发生变化”的循环依赖。含新验证器和工作流的源码提交才能使用新的 tag 自动发布路径。

**方式 B：明确的 workflow dispatch。** 在已包含新流程的分支上运行 `Publish verified APK input`，填写已存在的 `tag` 和完整 `input_commit`。即使是 lightweight tag，也会强制匹配该 tag 的源码 SHA。若 annotated tag 已有绑定行，dispatch 不能提供不同的输入提交。不要重新运行旧工作流定义来发布新版本。

新工作流首先在 `contents: read` 的任务中验证输入，保留校验后的 APK 及证据。单独的发布任务才具有 `contents: write`：下载本次运行的验证产物，再检查整包 SHA-256，并通过 GitHub 的精确 tag ref API 核对 tag object SHA 未改变，然后创建 Release。模式只能来自经过验证的固定 manifest，没有隐式升级成正式版的路径。

发布附件包括 APK、`SHA256SUMS.txt`、实际工具输出、`BUILD-INFO.json/.txt`、`RELEASE-INFO.json` 和 `release-manifest.json`。`RELEASE-INFO.json` 记录源码提交、输入提交、tag object 和模式，便于追溯。分片保留在独立输入分支，APK 则成为 GitHub Release 的完整可下载资产，不依赖短期 Actions artifact 留存。

上传后，发布任务用 `gh release download` 从该 Release 重新下载 APK，并流式核对文件名、字节数、整包 SHA-256 和 tag。成功后新增 `release-download-verification.json` 附件，绑定本次 source/input/tag object/run/attempt；即使校验失败，也尽量保留生成的校验记录为 Actions artifact。此步骤验证实际已上传的字节，不重复签名，也不会把授权下载当作公开 URL 已验证。对外提供最终下载前，还须从公开 Release URL 本地重新下载并比对实际字节与签名。

已存在的 Release 会使流程停止，不会 `--clobber`、修改旧版本或覆盖旧资产。上传或回验失败时可能留下附件不完整或尚未完成回验的 Release，其 draft/prerelease/stable 模式仍是此前明确选择的模式。须先检查已创建的 Release 与附件，再决定如何处理，不能宣称整个交付已通过，也不能盲目重跑来覆盖已有资产。流程不会自动删除或修补现有 Release。

## 已完成的本地验证与限制

- 15 项离线测试通过，包含精确 40 MiB 与多 1 字节切分、损坏/缺失/软链接分片、整包校验失败、既有输出保留、严格 JSON/路径/大小/类型、tag 绑定冲突、调试包策略、源码元数据/脏工作树、证书不匹配及签名工具失败。
- 临时本地 Git 仓库测试拒绝 executable、symlink、缺失或大小错误的输入 blob。
- 使用历史真实 APK `CuteDog-0.1.0-debug.apk` 完成 Android 工具验证、准备分片、还原验证，以及仅使用本地 Git remote 的 annotated tag 与 explicit dispatch 两条完整获取路径。还原整包 SHA-256 为 `ff0d78720bd4303830badaa70c60d3da669d5fe6c6056ca6750880bb1ec59cdb`，与历史原文件一致；实测证书指纹为 `6486e0229ab66f784b98793e21bdd0e3efd5d36a93b28114fdd17c36c3f2f2f9`。此处只是运输与工具验证样本，不是新版本发布输入。
- 这些检查未运行 Gradle、未向 GitHub 推送或创建 Release、未生成/读取私有签名材料。当前新版本的质量验收及 GitHub Actions 上的首次完整运行仍须在后续实际发布前完成。本地 fixture 的源码仓库只含版本配置，不构成真实 APK 的源码出处证明。
