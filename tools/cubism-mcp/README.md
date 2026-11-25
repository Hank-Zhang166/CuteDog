# Cubism Editor 本地 MCP

此服务使用 Live2D Cubism Editor 的外部应用连接功能。Codex 通过标准输入输出调用服务，服务连接本机编辑器。

## 安装

Windows 需要 Node.js 22 或更新版本及 Codex CLI。在本目录运行以下 PowerShell 命令。脚本按锁文件安装依赖，将运行文件复制到当前用户的本地应用目录，并把 `cutedog-cubism` 注册为 Codex MCP。

```powershell
.\Install.ps1
codex mcp get cutedog-cubism
```

在 Cubism 中打开「文件 → 外部应用程序连接设置」，启用默认端口 22033。首次调用 `cubism_connect` 后，勾选 **CuteDog Cubism MCP** 的「允许」。编辑器使用其他端口时，运行 `.\Install.ps1 -Port 端口号`。打开新的 Codex 聊天，即可发现已注册工具。

## 使用

| 工具 | 用途 |
| --- | --- |
| `cubism_connect` | 查看编辑器连接与授权状态 |
| `cubism_documents` | 获取模型、动画、物理文档和当前编辑模式 |
| `cubism_parameters` | 读取模型的参数 ID、范围、分组与编辑器提供的关键形状信息 |
| `cubism_get_values` | 读取模型当前的一组参数值 |
| `cubism_set_values` | 在已知范围内设置参数姿态 |
| `cubism_preview_motion` | 按指定帧率逐帧发送一组参数，结束时恢复原姿态 |
| `cubism_clear_values` | 清除编辑器中的外部参数覆盖 |
| `cubism_display_message` | 在编辑器日志面板显示信息 |

多数工具默认使用当前模型。动画视图中应先选中模型轨道。预览按给定数组的顺序同步眼睛、耳朵和身体等参数，每帧等待编辑器确认。要保存动作，可在 Cubism 动画视图中使用编辑器的插入关键帧功能。[Live2D 外部应用程序连接手册](https://docs.live2d.com/en/cubism-editor-manual/external-application-integration/)

## 验证

在本目录运行以下命令。最后一条会在当前模型上小幅设置一个现有参数、读取变化，再恢复原值，宜在测试模型中执行。

```powershell
npm ci --ignore-scripts
node smoke.mjs --wait
node smoke.mjs --pose-test
```

授权令牌保存在本机用户的 `%LOCALAPPDATA%\CuteDogCubismMCP`，由 Cubism 的「允许」设置控制。移除 Codex 注册可运行 `codex mcp remove cutedog-cubism`。操作名称和参数含义见 [Live2D 外部连接 API](https://docs.live2d.com/en/cubism-editor-manual/external-application-integration-api-list/)。
